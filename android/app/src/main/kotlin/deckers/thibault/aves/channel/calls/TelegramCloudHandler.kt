package deckers.thibault.aves.channel.calls

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import deckers.thibault.aves.channel.calls.Coresult.Companion.safe
import deckers.thibault.aves.telegram.TelegramApiException
import deckers.thibault.aves.telegram.TelegramBotClient
import deckers.thibault.aves.telegram.TelegramCrypto
import deckers.thibault.aves.telegram.TelegramCryptoException
import deckers.thibault.aves.telegram.TelegramVault
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Private encrypted cloud on a Telegram channel. Everything stored in Telegram is AES-256-GCM encrypted
 * under a key derived from a passphrase that never leaves the device (see TelegramCrypto).
 * Credentials are kept in EncryptedSharedPreferences; the decrypted index never touches disk unsealed.
 */
class TelegramCloudHandler(private val context: Context) : MethodCallHandler {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "status" -> ioScope.launch { safe(call, result, ::status) }
            "connect" -> ioScope.launch { safe(call, result, ::connect) }
            "disconnect" -> ioScope.launch { safe(call, result, ::disconnect) }
            "list" -> ioScope.launch { safe(call, result, ::list) }
            "thumbnail" -> ioScope.launch { safe(call, result, ::thumbnail) }
            "upload" -> ioScope.launch { safe(call, result, ::upload) }
            "download" -> ioScope.launch { safe(call, result, ::download) }
            "restore" -> ioScope.launch { safe(call, result, ::restore) }
            "delete" -> ioScope.launch { safe(call, result, ::delete) }
            else -> result.notImplemented()
        }
    }

    // ---- credentials & vault lifecycle ----------------------------------------------------------

    private fun store(): SharedPreferences {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun readableError(e: Exception): String = when (e) {
        is TelegramApiException -> when (e.errorCode) {
            401 -> "Invalid bot token"
            400, 403 -> "Telegram refused the request: ${e.description}. Add the bot as an administrator of the channel and check the channel id."
            else -> "Telegram error ${e.errorCode}: ${e.description}"
        }
        is TelegramCryptoException -> "Wrong encryption passphrase for this channel"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun normalizeChat(raw: String): String = raw.trim().removePrefix("telegram://").trim('/')

    /** Opens (and caches) the vault for the given credentials. Throws a readable [IOException] on failure. */
    private fun openVault(chatId: String, token: String, passphrase: String): TelegramVault {
        synchronized(LOCK) {
            val key = fingerprint("$token|$chatId|$passphrase")
            val existing = vault
            if (existing != null && vaultKey == key) return existing
            existing?.shutdown()
            vault = null
            vaultKey = null
            if (chatId.isEmpty()) throw IOException("Channel id is required (e.g. -1001234567890)")
            if (token.isEmpty()) throw IOException("Bot token is required")
            if (passphrase.length < MIN_PASSPHRASE) throw IOException("Passphrase must be at least $MIN_PASSPHRASE characters")
            val client = TelegramBotClient(token)
            try {
                client.getMe()
                client.getChat(chatId)
                val master = TelegramCrypto.deriveMasterKey(passphrase, chatId)
                val opened = TelegramVault(
                    client = client,
                    chatId = chatId,
                    master = master,
                    indexFile = File(File(context.filesDir, "telegram_cloud"), fingerprint("$token|$chatId").take(24) + ".idx")
                )
                try {
                    opened.open()
                } catch (e: Exception) {
                    opened.shutdown()
                    throw e
                }
                vault = opened
                vaultKey = key
                return opened
            } catch (e: IOException) {
                throw IOException(readableError(e), e)
            }
        }
    }

    private fun currentVault(): TelegramVault {
        synchronized(LOCK) { vault?.let { return it } }
        val prefs = store()
        val chatId = prefs.getString(KEY_CHAT, null)
        val token = prefs.getString(KEY_TOKEN, null)
        val passphrase = prefs.getString(KEY_PASSPHRASE, null)
        if (chatId == null || token == null || passphrase == null) throw IOException("Telegram cloud is not set up")
        return openVault(chatId, token, passphrase)
    }

    private fun fingerprint(s: String): String =
        TelegramCrypto.toHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))

    private fun toMap(e: deckers.thibault.aves.telegram.TgEntry): Map<String, Any?> = mapOf(
        "path" to e.path,
        "size" to e.size,
        "takenMs" to e.takenMs,
        "addedMs" to e.addedMs,
        "hasPreview" to (e.companion != null),
    )

    // ---- channel methods ------------------------------------------------------------------------

    private fun status(call: MethodCall, result: MethodChannel.Result) {
        val prefs = store()
        val configured = prefs.getString(KEY_TOKEN, null) != null
        result.success(mapOf("configured" to configured, "chatId" to prefs.getString(KEY_CHAT, null)))
    }

    private fun connect(call: MethodCall, result: MethodChannel.Result) {
        val chatId = normalizeChat(call.argument<String>("chatId").orEmpty())
        val token = call.argument<String>("token").orEmpty().trim()
        val passphrase = call.argument<String>("passphrase").orEmpty()
        try {
            val opened = openVault(chatId, token, passphrase)
            store().edit {
                putString(KEY_CHAT, chatId)
                putString(KEY_TOKEN, token)
                putString(KEY_PASSPHRASE, passphrase)
            }
            result.success(mapOf("count" to opened.entries().size))
        } catch (e: Exception) {
            result.error("telegram-connect", readableError(e), null)
        }
    }

    private fun disconnect(call: MethodCall, result: MethodChannel.Result) {
        synchronized(LOCK) {
            vault?.let { runCatching { it.flushManifest() }; it.shutdown() }
            vault = null
            vaultKey = null
        }
        store().edit { clear() }
        File(context.filesDir, "telegram_cloud").deleteRecursively()
        File(context.cacheDir, VIEW_CACHE).deleteRecursively()
        result.success(true)
    }

    private fun list(call: MethodCall, result: MethodChannel.Result) {
        try {
            result.success(currentVault().entries().sortedByDescending { it.lastModified }.map(::toMap))
        } catch (e: Exception) {
            result.error("telegram-list", readableError(e), null)
        }
    }

    private fun thumbnail(call: MethodCall, result: MethodChannel.Result) {
        val path = call.argument<String>("path")
        val preview = call.argument<Boolean>("preview") ?: false
        if (path == null) {
            result.error("telegram-thumbnail-args", "missing arguments", null)
            return
        }
        try {
            val pair = currentVault().readCompanion(path)
            val bytes = pair?.let { if (preview) it.second else it.first }
            result.success(bytes?.takeIf { it.isNotEmpty() })
        } catch (e: Exception) {
            result.error("telegram-thumbnail", readableError(e), null)
        }
    }

    private fun upload(call: MethodCall, result: MethodChannel.Result) {
        val uriString = call.argument<String>("uri")
        val path = call.argument<String>("path")
        val mimeType = call.argument<String>("mimeType").orEmpty()
        val takenMs = call.argument<Number>("takenMs")?.toLong() ?: 0L
        if (uriString == null || path == null) {
            result.error("telegram-upload-args", "missing arguments", null)
            return
        }
        val uri = Uri.parse(uriString)
        try {
            val vault = currentVault()
            val cleanPath = path.replace('\\', '/').trim('/')
            val size = sizeOf(uri)
            val existing = vault.find(cleanPath)
            if (existing != null && size > 0 && existing.size == size) {
                result.success(mapOf("uploaded" to false, "skipped" to true))
                return
            }
            val previews = deckers.thibault.aves.telegram.TelegramPreviews.render(context, uri, mimeType.startsWith("video/"))
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read $uriString")
            input.use {
                vault.upload(cleanPath, it, size, takenMs, previews?.first, previews?.second)
            }
            result.success(mapOf("uploaded" to true, "skipped" to false))
        } catch (e: Exception) {
            result.error("telegram-upload", readableError(e), null)
        }
    }

    private fun sizeOf(uri: Uri): Long {
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { if (it.length >= 0) return it.length }
        } catch (_: Exception) {
        }
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0)
            }
        } catch (_: Exception) {
        }
        return -1L
    }

    /** Decrypts the file into the app cache (once) and returns its path, for viewing. */
    private fun download(call: MethodCall, result: MethodChannel.Result) {
        val path = call.argument<String>("path")
        if (path == null) {
            result.error("telegram-download-args", "missing arguments", null)
            return
        }
        try {
            result.success(decryptToCache(path).path)
        } catch (e: Exception) {
            result.error("telegram-download", readableError(e), null)
        }
    }

    private fun decryptToCache(path: String): File {
        val vault = currentVault()
        val entry = vault.find(path) ?: throw IOException("Not found in the cloud")
        val dir = File(context.cacheDir, VIEW_CACHE).apply { mkdirs() }
        val target = File(dir, fingerprint(path).take(16) + "_" + path.substringAfterLast('/'))
        if (target.exists() && target.length() == entry.size) return target
        val tmp = File(target.path + ".part")
        try {
            vault.openRead(path, 0).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() != entry.size) throw IOException("Decrypted size mismatch")
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not store the decrypted file")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return target
    }

    /** Saves a decrypted copy into the device gallery (Pictures/Movies › Telegram Cloud). */
    private fun restore(call: MethodCall, result: MethodChannel.Result) {
        val path = call.argument<String>("path")
        if (path == null) {
            result.error("telegram-restore-args", "missing arguments", null)
            return
        }
        try {
            val source = decryptToCache(path)
            val name = path.substringAfterLast('/')
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val isVideo = mime.startsWith("video/")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/" + SAVE_DIR
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(collection, values) ?: throw IOException("Could not create the gallery entry")
                try {
                    resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                        ?: throw IOException("Could not write the gallery entry")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                result.success(uri.toString())
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES),
                    SAVE_DIR
                ).apply { mkdirs() }
                var out = File(dir, name)
                var n = 1
                while (out.exists()) out = File(dir, "${name.substringBeforeLast('.')}_${n++}.${ext}")
                source.copyTo(out)
                MediaScannerConnection.scanFile(context, arrayOf(out.path), arrayOf(mime), null)
                result.success(out.path)
            }
        } catch (e: Exception) {
            result.error("telegram-restore", readableError(e), null)
        }
    }

    private fun delete(call: MethodCall, result: MethodChannel.Result) {
        val path = call.argument<String>("path")
        if (path == null) {
            result.error("telegram-delete-args", "missing arguments", null)
            return
        }
        try {
            val outcome = currentVault().delete(path)
            File(context.cacheDir, VIEW_CACHE).listFiles()
                ?.filter { it.name.startsWith(fingerprint(path).take(16) + "_") }
                ?.forEach { it.delete() }
            result.success(
                mapOf(
                    "existed" to outcome.existed,
                    "deleted" to outcome.deleted,
                    // messages Telegram refused to delete (older than 48 h): removed from the app, still in the channel
                    "refused" to outcome.refused,
                    "deferred" to outcome.deferred,
                )
            )
        } catch (e: Exception) {
            result.error("telegram-delete", readableError(e), null)
        }
    }

    companion object {
        const val CHANNEL = "deckers.thibault/aves/telegram_cloud"
        private const val PREFS = "telegram_cloud_secret_prefs"
        private const val KEY_CHAT = "chat"
        private const val KEY_TOKEN = "token"
        private const val KEY_PASSPHRASE = "passphrase"
        private const val VIEW_CACHE = "telegram_cloud_view"
        private const val SAVE_DIR = "Telegram Cloud"
        private const val MIN_PASSPHRASE = 12

        private val LOCK = Any()
        private var vault: TelegramVault? = null
        private var vaultKey: String? = null
    }
}
