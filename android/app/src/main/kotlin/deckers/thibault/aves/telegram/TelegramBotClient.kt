/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package deckers.thibault.aves.telegram

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** A Bot API failure. [errorCode] is Telegram's `error_code` (or the HTTP status when the body is not JSON). */
internal class TelegramApiException(
    val errorCode: Int,
    val description: String,
    val retryAfterSeconds: Int? = null
) : IOException("Telegram error $errorCode: $description")

internal data class TgBotInfo(val id: Long, val username: String?)

internal data class TgSentDocument(val messageId: Long, val fileId: String, val fileSize: Long)

internal data class TgChatInfo(
    val id: Long,
    val type: String,
    val title: String?,
    val pinnedMessageId: Long?,
    val pinnedFileId: String?,
    val pinnedFileName: String?
)

/**
 * Minimal Telegram Bot API client (only what the private cloud needs), built on [HttpURLConnection]
 * so it has no third-party dependency. Handles 429 `retry_after`, 5xx and transient network errors.
 *
 * The bot token is part of every request URL, so it is scrubbed from any exception message that leaves
 * this class.
 */
internal class TelegramBotClient(
    private val token: String,
    private val baseUrl: String = "https://api.telegram.org",
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 120_000,
    private val maxRateLimitRetries: Int = 8,
    private val maxTransientRetries: Int = 3,
    /** Test seam: replaced so tests do not actually wait. */
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {

    init {
        require(token.isNotBlank()) { "Bot token is required" }
    }

    fun getMe(): TgBotInfo {
        val result = call("getMe").jsonObject
        return TgBotInfo(
            id = result["id"].asLong() ?: throw TelegramApiException(0, "getMe returned no id"),
            username = result["username"].asString()
        )
    }

    fun getChat(chatId: String): TgChatInfo {
        val result = call("getChat", mapOf("chat_id" to chatId)).jsonObject
        val pinned = (result["pinned_message"] as? JsonObject)
        val pinnedDoc = pinned?.get("document") as? JsonObject
        return TgChatInfo(
            id = result["id"].asLong() ?: 0L,
            type = result["type"].asString().orEmpty(),
            title = result["title"].asString(),
            pinnedMessageId = pinned?.get("message_id").asLong(),
            pinnedFileId = pinnedDoc?.get("file_id").asString(),
            pinnedFileName = pinnedDoc?.get("file_name").asString()
        )
    }

    /**
     * Uploads [bytes] as a document named [fileName]. `protect_content` blocks forwarding/saving inside
     * Telegram clients and `disable_content_type_detection` stops Telegram from sniffing the payload.
     */
    fun sendDocument(chatId: String, fileName: String, bytes: ByteArray): TgSentDocument {
        val result = call(
            "sendDocument",
            fields = mapOf(
                "chat_id" to chatId,
                "protect_content" to "true",
                "disable_notification" to "true",
                "disable_content_type_detection" to "true"
            ),
            file = FilePart("document", fileName, bytes)
        )
        return parseSentDocument(result)
    }

    /** Replaces the document of an existing message in place (no 48 h limit applies to bot-owned edits). */
    fun editMessageDocument(chatId: String, messageId: Long, fileName: String, bytes: ByteArray): TgSentDocument {
        val result = call(
            "editMessageMedia",
            fields = mapOf(
                "chat_id" to chatId,
                "message_id" to messageId.toString(),
                "media" to """{"type":"document","media":"attach://payload"}"""
            ),
            file = FilePart("payload", fileName, bytes)
        )
        return parseSentDocument(result)
    }

    fun pinChatMessage(chatId: String, messageId: Long) {
        call(
            "pinChatMessage",
            mapOf("chat_id" to chatId, "message_id" to messageId.toString(), "disable_notification" to "true")
        )
    }

    /** True when Telegram confirmed the deletion. Throws [TelegramApiException] otherwise. */
    fun deleteMessage(chatId: String, messageId: Long): Boolean {
        val result = call("deleteMessage", mapOf("chat_id" to chatId, "message_id" to messageId.toString()))
        return (result as? JsonPrimitive)?.booleanOrNull ?: true
    }

    /** Downloads a file by id (Bot API limit: 20 MB). The link is requested fresh each time. */
    fun downloadFile(fileId: String): ByteArray {
        val info = call("getFile", mapOf("file_id" to fileId)).jsonObject
        val path = info["file_path"].asString()
            ?: throw TelegramApiException(404, "File path unavailable")
        val declared = info["file_size"].asLong()
        val bytes = withRetries { httpGetBytes("$baseUrl/file/bot$token/$path") }
        if (declared != null && declared >= 0 && bytes.size.toLong() != declared) {
            throw IOException("Telegram download was cut short (${bytes.size} of $declared bytes)")
        }
        return bytes
    }

    // ---- plumbing -------------------------------------------------------------------------------

    private class FilePart(val field: String, val fileName: String, val bytes: ByteArray)

    private fun call(
        method: String,
        fields: Map<String, String> = emptyMap(),
        file: FilePart? = null
    ): JsonElement = withRetries { callOnce(method, fields, file) }

    private fun <T> withRetries(block: () -> T): T {
        var rateAttempts = 0
        var transientAttempts = 0
        while (true) {
            try {
                return block()
            } catch (e: TelegramApiException) {
                when {
                    e.errorCode == 429 && rateAttempts < maxRateLimitRetries -> {
                        rateAttempts++
                        pause(((e.retryAfterSeconds ?: 5).coerceIn(1, 120)) * 1000L + 250L)
                    }
                    e.errorCode >= 500 && transientAttempts < maxTransientRetries -> {
                        transientAttempts++
                        pause(1_000L * transientAttempts)
                    }
                    else -> throw e
                }
            } catch (e: InterruptedIOException) {
                // Includes SocketTimeoutException's parent: only a genuine interrupt must not be retried.
                if (Thread.currentThread().isInterrupted || e.javaClass == InterruptedIOException::class.java) throw e
                if (transientAttempts >= maxTransientRetries) throw redacted(e)
                transientAttempts++
                pause(1_000L * transientAttempts)
            } catch (e: IOException) {
                if (transientAttempts >= maxTransientRetries) throw redacted(e)
                transientAttempts++
                pause(1_000L * transientAttempts)
            }
        }
    }

    private fun pause(millis: Long) {
        try {
            sleeper(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted while waiting for Telegram")
        }
    }

    private fun callOnce(method: String, fields: Map<String, String>, file: FilePart?): JsonElement {
        val boundary = "----refra" + UUID.randomUUID().toString().replace("-", "")
        val head = ByteArrayOutputStream()
        fun text(s: String) = head.write(s.toByteArray(Charsets.UTF_8))
        for ((name, value) in fields) {
            text("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
        }
        val fileHeader = file?.let {
            "--$boundary\r\nContent-Disposition: form-data; name=\"${it.field}\"; filename=\"${it.fileName}\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
        }
        fileHeader?.let(::text)
        val tail = (if (file != null) "\r\n" else "") + "--$boundary--\r\n"
        val headBytes = head.toByteArray()
        val tailBytes = tail.toByteArray(Charsets.UTF_8)
        val total = headBytes.size.toLong() + (file?.bytes?.size?.toLong() ?: 0L) + tailBytes.size

        val conn = open("$baseUrl/bot$token/$method")
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setFixedLengthStreamingMode(total)
            conn.outputStream.use { out ->
                out.write(headBytes)
                if (file != null) out.write(file.bytes)
                out.write(tailBytes)
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
            return parseEnvelope(code, body)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGetBytes(url: String): ByteArray {
        val conn = open(url)
        try {
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = conn.errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
                throw parseEnvelopeError(code, body)
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = java.net.URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", "ReFra-TelegramCloud")
        return conn
    }

    private fun parseEnvelope(httpCode: Int, body: String): JsonElement {
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw TelegramApiException(httpCode, "Unexpected response (HTTP $httpCode)")
        if (json["ok"].asBoolean() == true) {
            return json["result"] ?: JsonPrimitive(true)
        }
        throw envelopeError(httpCode, json)
    }

    private fun parseEnvelopeError(httpCode: Int, body: String): TelegramApiException {
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return TelegramApiException(httpCode, "HTTP $httpCode")
        return envelopeError(httpCode, json)
    }

    private fun envelopeError(httpCode: Int, json: JsonObject): TelegramApiException {
        val code = json["error_code"].asInt() ?: httpCode
        val description = json["description"].asString() ?: "HTTP $httpCode"
        val retryAfter = (json["parameters"] as? JsonObject)?.get("retry_after").asInt()
        return TelegramApiException(code, description.replace(token, "***"), retryAfter)
    }

    private fun parseSentDocument(result: JsonElement): TgSentDocument {
        val message = result as? JsonObject
            ?: throw TelegramApiException(0, "Telegram did not return the message")
        val document = message["document"] as? JsonObject
            ?: throw TelegramApiException(0, "Telegram did not return the uploaded document")
        return TgSentDocument(
            messageId = message["message_id"].asLong() ?: throw TelegramApiException(0, "Missing message_id"),
            fileId = document["file_id"].asString() ?: throw TelegramApiException(0, "Missing file_id"),
            fileSize = document["file_size"].asLong() ?: -1L
        )
    }

    private fun redacted(e: IOException): IOException {
        val message = (e.message ?: e.javaClass.simpleName).replace(token, "***")
        return IOException(message)
    }

    private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.asLong(): Long? = (this as? JsonPrimitive)?.longOrNull
    private fun JsonElement?.asInt(): Int? = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.asBoolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
}
