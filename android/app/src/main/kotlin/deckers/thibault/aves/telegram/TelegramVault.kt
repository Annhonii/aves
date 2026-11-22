/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package deckers.thibault.aves.telegram

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The encrypted photo vault living in a Telegram channel. Owns the (encrypted) index, uploads files as
 * chunked AES-GCM blobs, streams them back decrypted with read-ahead, and keeps an encrypted manifest
 * pinned in the channel so the index can be restored after a reinstall (passphrase + bot + channel).
 *
 * Telegram only ever sees: random-looking `.bin` documents, their approximate sizes and upload times.
 * Names, albums, types, dates and image content exist only inside encrypted blobs / the encrypted index.
 */
internal class TelegramVault(
    private val client: TelegramBotClient,
    private val chatId: String,
    private val master: ByteArray,
    private val indexFile: File,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val manifestDelayMs: Long = DEFAULT_MANIFEST_DELAY_MS,
    private val log: (String) -> Unit = {}
) {

    private val lock = Any()
    private val flushLock = ReentrantLock()
    private var index = TelegramIndex()
    private var opened = false
    private var dirty = false
    private var flushScheduled = false
    @Volatile
    private var manifestFailures = 0

    private val ioPool: ExecutorService = Executors.newCachedThreadPool(daemonFactory("tg-io"))
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(daemonFactory("tg-sched"))

    // ---- opening / restoring --------------------------------------------------------------------

    /**
     * Loads the on-device index, or restores it from the channel's pinned manifest, or starts empty for
     * a brand-new channel. A wrong passphrase raises [TelegramCryptoException]; the manifest is never
     * overwritten after a failed restore.
     */
    fun open() {
        synchronized(lock) {
            if (opened) return
            val local = indexFile.takeIf { it.isFile }?.readBytes()
            index = if (local != null) {
                TelegramIndex.fromJsonBytes(TelegramCrypto.openContainer(master, local))
            } else {
                restoreFromChannel()?.also { persistLocked(it) } ?: TelegramIndex()
            }
            opened = true
        }
        ioPool.execute { runCatching { retryPendingDeletes() } }
    }

    private fun restoreFromChannel(): TelegramIndex? {
        val chat = client.getChat(chatId)
        val fileId = chat.pinnedFileId
        if (fileId == null || chat.pinnedFileName != MANIFEST_NAME) return null
        val restored = TelegramIndex.fromJsonBytes(TelegramCrypto.openContainer(master, client.downloadFile(fileId)))
        restored.manifestMessageId = chat.pinnedMessageId
        log("restored index from channel manifest (${restored.size} items)")
        return restored
    }

    private fun requireOpen() = check(opened) { "Telegram vault is not open" }

    // ---- index queries --------------------------------------------------------------------------

    fun entries(): List<TgEntry> = synchronized(lock) { requireOpen(); index.all() }

    fun find(path: String): TgEntry? = synchronized(lock) { requireOpen(); index[path] }

    // ---- upload ---------------------------------------------------------------------------------

    /**
     * Encrypts and uploads [input] under [path]. All-or-nothing: if anything fails, chunks that already
     * reached Telegram are deleted again (best effort) and nothing is added to the index.
     * [declaredSize] <= 0 means unknown.
     */
    fun upload(
        path: String,
        input: InputStream,
        declaredSize: Long,
        takenMs: Long,
        thumbnail: ByteArray?,
        preview: ByteArray?
    ): TgEntry {
        synchronized(lock) { requireOpen() }
        val fileId = TelegramCrypto.randomBytes(TelegramCrypto.FILE_ID_BYTES)
        val crypto = TelegramCrypto.FileCrypto(master, fileId)
        val digest = MessageDigest.getInstance("SHA-1")
        val sent = ArrayList<TgBlobRef>()
        var companionRef: TgBlobRef? = null
        var total = 0L
        try {
            val source = java.io.PushbackInputStream(input, 1)
            var chunkIndex = 0L
            while (true) {
                val chunk = readChunk(source)
                if (chunk.isEmpty()) break
                // A short chunk means EOF was hit while filling it; a full one needs a 1-byte probe.
                val isLast = chunk.size < chunkSize || source.read().let { b ->
                    if (b < 0) true else { source.unread(b); false }
                }
                digest.update(chunk)
                total += chunk.size
                sent += sendBlob(crypto.encrypt(TelegramCrypto.KIND_DATA, chunkIndex, isLast, chunk))
                chunkIndex++
                if (isLast) break
            }
            if (declaredSize > 0 && total != declaredSize) {
                throw IOException("Upload size mismatch: read $total of $declaredSize bytes")
            }
            val thumbBytes = thumbnail ?: ByteArray(0)
            val previewBytes = preview ?: ByteArray(0)
            if (thumbBytes.isNotEmpty() || previewBytes.isNotEmpty()) {
                companionRef = sendBlob(
                    crypto.encrypt(TelegramCrypto.KIND_COMPANION, 0L, true, thumbBytes + previewBytes)
                )
            }
            val entry = TgEntry(
                path = path,
                size = total,
                takenMs = takenMs,
                addedMs = System.currentTimeMillis(),
                sha1 = TelegramCrypto.toHex(digest.digest()),
                fileKey = TelegramCrypto.toHex(fileId),
                chunkSize = chunkSize,
                chunks = sent.toList(),
                companion = companionRef,
                thumbLen = thumbBytes.size,
                previewLen = previewBytes.size
            )
            val replaced = synchronized(lock) {
                index.put(entry).also { persistLocked(index) }
            }
            replaced?.let { deleteMessages(it.allMessageIds()) }
            markDirty()
            return entry
        } catch (t: Throwable) {
            val orphans = sent.map { it.messageId } + listOfNotNull(companionRef?.messageId)
            if (orphans.isNotEmpty()) deleteMessages(orphans)
            throw t
        }
    }

    private fun readChunk(input: InputStream): ByteArray {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Upload cancelled")
        val buffer = ByteArray(chunkSize)
        var filled = 0
        while (filled < chunkSize) {
            val read = input.read(buffer, filled, chunkSize - filled)
            if (read < 0) break
            filled += read
        }
        return if (filled == chunkSize) buffer else buffer.copyOf(filled)
    }

    private fun sendBlob(blob: ByteArray): TgBlobRef {
        val name = TelegramCrypto.toHex(TelegramCrypto.randomBytes(6)) + ".bin"
        val sent = client.sendDocument(chatId, name, blob)
        if (sent.fileSize >= 0 && sent.fileSize != blob.size.toLong()) {
            runCatching { client.deleteMessage(chatId, sent.messageId) }
            throw IOException("Telegram stored ${sent.fileSize} bytes instead of ${blob.size}")
        }
        return TgBlobRef(sent.messageId, sent.fileId, blob.size)
    }

    // ---- reading --------------------------------------------------------------------------------

    fun openRead(path: String, offset: Long): InputStream {
        val entry = find(path) ?: throw FileNotFoundException(path)
        if (offset < 0 || offset > entry.size) throw IOException("Offset out of range")
        return ChunkedStream(entry, offset)
    }

    /** Decrypted (thumbnail, preview) JPEGs stored at upload time, or null if this file has none. */
    fun readCompanion(path: String): Pair<ByteArray, ByteArray>? {
        val entry = find(path) ?: return null
        val ref = entry.companion ?: return null
        val crypto = TelegramCrypto.FileCrypto(master, TelegramCrypto.fromHex(entry.fileKey))
        val plain = crypto.decrypt(TelegramCrypto.KIND_COMPANION, 0L, true, client.downloadFile(ref.fileId))
        if (plain.size != entry.thumbLen + entry.previewLen) throw TelegramCryptoException("Preview blob has an unexpected size")
        return plain.copyOfRange(0, entry.thumbLen) to plain.copyOfRange(entry.thumbLen, plain.size)
    }

    private val cache = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var cacheBytes = 0L
    private val inflight = ConcurrentHashMap<String, FutureTask<ByteArray>>()

    private fun cachePut(key: String, data: ByteArray) {
        synchronized(cache) {
            cache.put(key, data)?.let { cacheBytes -= it.size }
            cacheBytes += data.size
            val iterator = cache.entries.iterator()
            while (cacheBytes > CHUNK_CACHE_BYTES && cache.size > 1 && iterator.hasNext()) {
                val eldest = iterator.next()
                if (eldest.key == key) continue
                cacheBytes -= eldest.value.size
                iterator.remove()
            }
        }
    }

    private fun cacheGet(key: String): ByteArray? = synchronized(cache) { cache[key] }

    private fun chunkFuture(entry: TgEntry, chunkIndex: Int): Future<ByteArray> {
        val key = "${entry.fileKey}:$chunkIndex"
        cacheGet(key)?.let { hit -> return FutureTask(Callable { hit }).also { it.run() } }
        val task = object : FutureTask<ByteArray>(Callable { fetchChunk(entry, chunkIndex, key) }) {
            override fun done() {
                inflight.remove(key, this)
            }
        }
        val existing = inflight.putIfAbsent(key, task)
        if (existing != null) return existing
        ioPool.execute(task)
        return task
    }

    private fun fetchChunk(entry: TgEntry, chunkIndex: Int, key: String): ByteArray {
        val ref = entry.chunks[chunkIndex]
        val last = chunkIndex == entry.chunks.lastIndex
        val crypto = TelegramCrypto.FileCrypto(master, TelegramCrypto.fromHex(entry.fileKey))
        val plain = crypto.decrypt(TelegramCrypto.KIND_DATA, chunkIndex.toLong(), last, client.downloadFile(ref.fileId))
        val expected = if (last) entry.size - chunkIndex.toLong() * entry.chunkSize else entry.chunkSize.toLong()
        if (plain.size.toLong() != expected) throw TelegramCryptoException("Chunk $chunkIndex has an unexpected size")
        cachePut(key, plain)
        return plain
    }

    private fun awaitChunk(entry: TgEntry, chunkIndex: Int): ByteArray {
        try {
            return chunkFuture(entry, chunkIndex).get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted while downloading from Telegram")
        } catch (e: ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause?.message ?: "Download failed", e.cause)
        }
    }

    private inner class ChunkedStream(private val entry: TgEntry, start: Long) : InputStream() {
        private var position = start
        private var current: ByteArray? = null
        private var currentIndex = -1
        private val one = ByteArray(1)

        override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (position >= entry.size) return -1
            val chunkIndex = (position / entry.chunkSize).toInt()
            if (chunkIndex != currentIndex) {
                current = awaitChunk(entry, chunkIndex)
                currentIndex = chunkIndex
                // Read-ahead: while this chunk is consumed, the next ones are already downloading.
                for (ahead in 1..READ_AHEAD) {
                    if (chunkIndex + ahead < entry.chunks.size) chunkFuture(entry, chunkIndex + ahead)
                }
            }
            val data = current!!
            val within = (position - chunkIndex.toLong() * entry.chunkSize).toInt()
            val n = minOf(len, data.size - within)
            System.arraycopy(data, within, b, off, n)
            position += n
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = n.coerceIn(0L, entry.size - position)
            position += skipped
            return skipped
        }

        override fun available(): Int {
            val data = current ?: return 0
            if (currentIndex != (position / entry.chunkSize).toInt()) return 0
            return (data.size - (position - currentIndex.toLong() * entry.chunkSize).toInt()).coerceAtLeast(0)
        }
    }

    // ---- delete ---------------------------------------------------------------------------------

    class DeleteOutcome(val existed: Boolean, val deleted: Int, val refused: Int, val deferred: Int)

    /**
     * Removes [path] from the vault. The entry always leaves the index (so it disappears from the app);
     * Telegram is then asked to delete the encrypted messages. Telegram only guarantees deletion of
     * messages younger than 48 h, so refusals are reported rather than hidden.
     */
    fun delete(path: String): DeleteOutcome {
        val entry = synchronized(lock) {
            requireOpen()
            index.remove(path)?.also { persistLocked(index) }
        } ?: return DeleteOutcome(existed = false, deleted = 0, refused = 0, deferred = 0)
        val outcome = deleteMessages(entry.allMessageIds())
        markDirty()
        return DeleteOutcome(true, outcome.first, outcome.second, outcome.third)
    }

    /** Returns (deleted, refused, deferred). Deferred ones are persisted and retried later. */
    private fun deleteMessages(messageIds: List<Long>): Triple<Int, Int, Int> {
        var deleted = 0
        var refused = 0
        val deferred = ArrayList<Long>()
        for (id in messageIds) {
            try {
                client.deleteMessage(chatId, id)
                deleted++
            } catch (e: TelegramApiException) {
                if (e.errorCode == 400 && "not found" in e.description.lowercase()) {
                    deleted++ // already gone
                } else if (e.errorCode in 400..499 && e.errorCode != 429) {
                    refused++ // e.g. older than 48 h: Telegram will not delete it
                    log("telegram refused to delete message: ${e.description}")
                } else {
                    deferred += id
                }
            } catch (e: IOException) {
                deferred += id
            }
        }
        if (deferred.isNotEmpty()) {
            synchronized(lock) {
                index.pendingDeletes.addAll(deferred)
                persistLocked(index)
            }
        }
        return Triple(deleted, refused, deferred.size)
    }

    private fun retryPendingDeletes() {
        val pending = synchronized(lock) {
            val copy = index.pendingDeletes.toList()
            index.pendingDeletes.clear()
            copy
        }
        if (pending.isEmpty()) return
        deleteMessages(pending) // re-queues whatever still fails transiently
    }

    // ---- local persistence & manifest -----------------------------------------------------------

    private fun persistLocked(source: TelegramIndex) {
        val sealed = TelegramCrypto.sealContainer(master, source.toJsonBytes())
        indexFile.parentFile?.mkdirs()
        val tmp = File(indexFile.path + ".tmp")
        tmp.writeBytes(sealed)
        // renameTo replaces the target atomically on the same filesystem (POSIX rename); java.nio.file needs API 26.
        if (!tmp.renameTo(indexFile)) {
            indexFile.delete()
            if (!tmp.renameTo(indexFile)) throw IOException("Could not save the index")
        }
    }

    private fun markDirty() {
        val delay: Long
        synchronized(lock) {
            dirty = true
            if (flushScheduled) return
            flushScheduled = true
            delay = (manifestDelayMs shl manifestFailures.coerceAtMost(5)).coerceAtMost(MAX_MANIFEST_DELAY_MS)
        }
        scheduler.schedule({
            synchronized(lock) { flushScheduled = false }
            try {
                flushManifest()
                manifestFailures = 0
            } catch (e: Exception) {
                manifestFailures++
                log("manifest upload failed: ${e.message}")
                markDirty()
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    /** Uploads the encrypted index to the channel now if anything changed since the last upload. */
    fun flushManifest() {
        flushLock.withLock {
            val sealed = synchronized(lock) {
                if (!dirty || !opened) return
                dirty = false
                TelegramCrypto.sealContainer(master, index.toJsonBytes())
            }
            try {
                pushManifest(sealed)
                retryPendingDeletes()
            } catch (t: Throwable) {
                synchronized(lock) { dirty = true }
                throw t
            }
        }
    }

    private fun pushManifest(sealed: ByteArray) {
        val existing = synchronized(lock) { index.manifestMessageId }
        if (existing != null) {
            try {
                client.editMessageDocument(chatId, existing, MANIFEST_NAME, sealed)
                return
            } catch (e: TelegramApiException) {
                if (e.errorCode == 429 || e.errorCode >= 500) throw e
                log("manifest edit refused (${e.description}); sending a fresh manifest")
            }
        }
        val sent = client.sendDocument(chatId, MANIFEST_NAME, sealed)
        runCatching { client.pinChatMessage(chatId, sent.messageId) }
            .onFailure { log("could not pin manifest (index restore after reinstall will not work): ${it.message}") }
        synchronized(lock) {
            index.manifestMessageId = sent.messageId
            persistLocked(index)
        }
        existing?.let { runCatching { client.deleteMessage(chatId, it) } }
    }

    /** Asynchronous flush hook for connection close; never blocks the caller. */
    fun requestFlush() {
        ioPool.execute {
            try {
                flushManifest()
            } catch (e: Exception) {
                log("manifest flush failed: ${e.message}")
            }
        }
    }

    fun shutdown() {
        runCatching { flushManifest() }
        scheduler.shutdownNow()
        ioPool.shutdown()
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 8 * 1024 * 1024
        const val DEFAULT_MANIFEST_DELAY_MS = 90_000L
        const val MANIFEST_NAME = "m.bin"
        private const val MAX_MANIFEST_DELAY_MS = 30 * 60_000L
        private const val READ_AHEAD = 2
        private const val CHUNK_CACHE_BYTES = 48L * 1024 * 1024

        private fun daemonFactory(prefix: String): java.util.concurrent.ThreadFactory {
            val counter = AtomicInteger()
            return java.util.concurrent.ThreadFactory { r ->
                Thread(r, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }
}
