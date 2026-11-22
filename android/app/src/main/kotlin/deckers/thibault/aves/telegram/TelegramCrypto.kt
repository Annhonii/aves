/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package deckers.thibault.aves.telegram

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Thrown when a blob fails authentication: wrong passphrase, tampering, or a blob from another file. */
internal class TelegramCryptoException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * All cryptography for the Telegram private cloud. Nothing here touches the network.
 *
 * Scheme (AES-256-GCM everywhere, HKDF-SHA256 for key separation):
 *  - master key  = PBKDF2-HMAC-SHA256(passphrase, salt = SHA-256("refra-tg-v1|" + chatId))
 *  - file key    = HKDF(master, salt = random 16-byte fileId, info = "file")  -> unique key per photo/video
 *  - index key   = HKDF(master, salt = "refra-tg-v1", info = "index")
 *  - blob layout = fileId(16) || AES-GCM(payload) where payload = len(4) || data || zero padding
 *    (padded to a multiple of [PAD_BLOCK] so Telegram cannot read exact file sizes).
 *  - nonce       = kind(4, big endian) || chunkIndex(8, big endian); unique because the file key is unique.
 *  - AAD         = kind || fileId || chunkIndex || isLast, so chunks cannot be reordered, swapped between
 *    files, or truncated without the tag check failing.
 *
 * A blob has no magic bytes or header text: to Telegram (and anyone with channel access) it is random data.
 */
internal object TelegramCrypto {

    const val KEY_BYTES = 32
    const val FILE_ID_BYTES = 16
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
    const val TAG_BYTES = TAG_BITS / 8
    const val PAD_BLOCK = 64 * 1024
    private const val LEN_PREFIX = 4

    const val KIND_DATA = 0
    const val KIND_COMPANION = 1

    /** Slow on purpose (offline brute-force resistance); run once per account connect. */
    const val DEFAULT_PBKDF2_ITERATIONS = 310_000

    private val random = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    fun toHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            chars[i * 2] = digits[v ushr 4]
            chars[i * 2 + 1] = digits[v and 0x0f]
        }
        return String(chars)
    }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Invalid hex length" }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex digit" }
            ((hi shl 4) or lo).toByte()
        }
    }

    fun deriveMasterKey(
        passphrase: String,
        chatId: String,
        iterations: Int = DEFAULT_PBKDF2_ITERATIONS
    ): ByteArray {
        require(passphrase.isNotEmpty()) { "Encryption passphrase is required" }
        val salt = MessageDigest.getInstance("SHA-256").digest("refra-tg-v1|$chatId".toByteArray(Charsets.UTF_8))
        return pbkdf2(passphrase.toByteArray(Charsets.UTF_8), salt, iterations)
    }

    /**
     * PBKDF2-HMAC-SHA256 (RFC 8018), single 32-byte block. Implemented on [Mac] because the platform
     * `PBKDF2WithHmacSHA256` factory only exists from API 26 and this app supports older devices.
     */
    internal fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations > 0) { "Invalid iteration count" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val result = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (j in result.indices) result[j] = (result[j].toInt() xor u[j].toInt()).toByte()
        }
        return result
    }

    /** RFC 5869 HKDF with HMAC-SHA256. An empty [salt] is treated as 32 zero bytes, per the RFC. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32) { "Invalid HKDF length" }
        val extractKey = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmac(extractKey, ikm)
        val out = ByteArrayOutputStream(length)
        var previous = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            out.write(previous)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun indexKey(master: ByteArray): ByteArray =
        hkdf(master, "refra-tg-v1".toByteArray(), "index".toByteArray(), KEY_BYTES)

    private fun nonce(kind: Int, index: Long): ByteArray =
        ByteBuffer.allocate(NONCE_BYTES).putInt(kind).putLong(index).array()

    private fun aad(kind: Int, fileId: ByteArray, index: Long, isLast: Boolean): ByteArray =
        ByteBuffer.allocate(4 + FILE_ID_BYTES + 8 + 1)
            .putInt(kind).put(fileId).putLong(index).put(if (isLast) 1 else 0).array()

    /** Size of the blob produced for [dataLength] plaintext bytes; used to size/verify uploads. */
    fun blobSize(dataLength: Int): Int {
        val payload = LEN_PREFIX + dataLength
        val padded = ((payload + PAD_BLOCK - 1) / PAD_BLOCK) * PAD_BLOCK
        return FILE_ID_BYTES + padded + TAG_BYTES
    }

    /** Encrypts/decrypts the blobs of one file under its own derived key. */
    class FileCrypto(master: ByteArray, val fileId: ByteArray) {
        init {
            require(fileId.size == FILE_ID_BYTES) { "Invalid file id" }
        }

        private val key = SecretKeySpec(hkdf(master, fileId, "file".toByteArray(), KEY_BYTES), "AES")

        fun encrypt(kind: Int, index: Long, isLast: Boolean, data: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray {
            val payloadLen = LEN_PREFIX + length
            val padded = ((payloadLen + PAD_BLOCK - 1) / PAD_BLOCK) * PAD_BLOCK
            val plain = ByteArray(padded)
            ByteBuffer.wrap(plain).putInt(length)
            System.arraycopy(data, offset, plain, LEN_PREFIX, length)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(kind, index)))
            cipher.updateAAD(aad(kind, fileId, index, isLast))
            val body = cipher.doFinal(plain)
            val blob = ByteArray(FILE_ID_BYTES + body.size)
            System.arraycopy(fileId, 0, blob, 0, FILE_ID_BYTES)
            System.arraycopy(body, 0, blob, FILE_ID_BYTES, body.size)
            return blob
        }

        fun decrypt(kind: Int, index: Long, isLast: Boolean, blob: ByteArray): ByteArray {
            if (blob.size < FILE_ID_BYTES + LEN_PREFIX + TAG_BYTES) {
                throw TelegramCryptoException("Encrypted blob is truncated")
            }
            if (!MessageDigest.isEqual(blob.copyOfRange(0, FILE_ID_BYTES), fileId)) {
                throw TelegramCryptoException("Encrypted blob belongs to a different file")
            }
            val plain = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(kind, index)))
                cipher.updateAAD(aad(kind, fileId, index, isLast))
                cipher.doFinal(blob, FILE_ID_BYTES, blob.size - FILE_ID_BYTES)
            } catch (e: GeneralSecurityException) {
                throw TelegramCryptoException("Encrypted blob failed authentication (wrong passphrase or tampered data)", e)
            }
            if (plain.size < LEN_PREFIX) throw TelegramCryptoException("Encrypted blob is malformed")
            val length = ByteBuffer.wrap(plain).int
            if (length < 0 || length > plain.size - LEN_PREFIX) throw TelegramCryptoException("Encrypted blob length is invalid")
            return plain.copyOfRange(LEN_PREFIX, LEN_PREFIX + length)
        }
    }

    /**
     * Index/manifest container: gzip -> AES-256-GCM under the index key. Layout: nonce(12) || ciphertext+tag.
     * Used both for the on-device index file and for the manifest document stored in the channel.
     */
    fun sealContainer(master: ByteArray, plain: ByteArray): ByteArray {
        val gz = ByteArrayOutputStream().also { bos -> GZIPOutputStream(bos).use { it.write(plain) } }.toByteArray()
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(indexKey(master), "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(CONTAINER_AAD)
        val body = cipher.doFinal(gz)
        return nonce + body
    }

    fun openContainer(master: ByteArray, sealed: ByteArray): ByteArray {
        if (sealed.size < NONCE_BYTES + TAG_BYTES) throw TelegramCryptoException("Index container is truncated")
        val gz = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(indexKey(master), "AES"),
                GCMParameterSpec(TAG_BITS, sealed.copyOfRange(0, NONCE_BYTES))
            )
            cipher.updateAAD(CONTAINER_AAD)
            cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
        } catch (e: GeneralSecurityException) {
            throw TelegramCryptoException("Index failed authentication (wrong passphrase or corrupted data)", e)
        }
        return GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }
    }

    private val CONTAINER_AAD = "refra-tg-v1|container".toByteArray()
}
