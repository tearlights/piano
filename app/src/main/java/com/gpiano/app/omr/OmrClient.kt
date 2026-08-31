package com.gpiano.app.omr

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject

data class RemoteOmrJob(
    val id: String,
    val status: String,
    val stage: String,
    val errorCode: String?,
    val errorMessage: String?,
    val diagnosticsJson: String?,
)

class OmrServiceException(
    message: String,
    val code: String,
    val retryable: Boolean,
) : Exception(message)

interface OmrClient {
    fun createJob(settings: OmrSettings, input: File, mimeType: String, fileName: String): RemoteOmrJob
    fun getJob(settings: OmrSettings, remoteJobId: String): RemoteOmrJob
    fun downloadMusicXml(settings: OmrSettings, remoteJobId: String): String
    fun health(settings: OmrSettings): String
}

class AudiverisOmrClient : OmrClient {
    override fun createJob(settings: OmrSettings, input: File, mimeType: String, fileName: String): RemoteOmrJob {
        require(input.isFile && input.length() in 1..MAX_UPLOAD_BYTES) { "单页图片不存在或超过 25 MB" }
        val connection = open(settings, "/v1/jobs", "POST").apply {
            setRequestProperty("Content-Type", mimeType)
            setRequestProperty("X-File-Name", URLEncoder.encode(fileName, Charsets.UTF_8.name()))
            setFixedLengthStreamingMode(input.length())
            doOutput = true
        }
        connection.outputStream.buffered().use { output -> input.inputStream().buffered().use { it.copyTo(output) } }
        return parseJob(readJson(connection))
    }

    override fun getJob(settings: OmrSettings, remoteJobId: String): RemoteOmrJob =
        parseJob(readJson(open(settings, "/v1/jobs/${safeId(remoteJobId)}", "GET")))

    override fun downloadMusicXml(settings: OmrSettings, remoteJobId: String): String {
        val connection = open(settings, "/v1/jobs/${safeId(remoteJobId)}/musicxml", "GET")
        ensureSuccess(connection)
        val bytes = connection.inputStream.buffered().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() + read > MAX_XML_BYTES) {
                    throw OmrServiceException("OMR 返回的 MusicXML 过大", "result_too_large", retryable = false)
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        return bytes.toString(Charsets.UTF_8)
    }

    override fun health(settings: OmrSettings): String = readJson(open(settings, "/health", "GET")).getString("status")

    private fun open(settings: OmrSettings, path: String, method: String): HttpURLConnection {
        val base = URI(settings.endpoint)
        val url = base.resolve(path).toURL()
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Authorization", "Bearer ${settings.token}")
            setRequestProperty("Accept", "application/json, application/vnd.recordare.musicxml+xml")
        }
    }

    internal fun readJson(connection: HttpURLConnection): JSONObject {
        ensureSuccess(connection)
        val text = try {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readTextLimited(MAX_JSON_CHARS) }
        } catch (_: ResponseTooLargeException) {
            throw OmrServiceException("OMR 服务响应过大", "response_too_large", retryable = false)
        }
        return JSONObject(text)
    }

    internal fun ensureSuccess(connection: HttpURLConnection) {
        val status = connection.responseCode
        if (status in 200..299) return
        val body = try {
            connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readTextLimited(MAX_JSON_CHARS) }.orEmpty()
        } catch (_: ResponseTooLargeException) {
            throw OmrServiceException("OMR 服务错误响应过大", "response_too_large", retryable = false)
        }
        val json = runCatching { JSONObject(body) }.getOrNull()
        val code = json?.optString("code")?.takeIf(String::isNotBlank) ?: "http_$status"
        val message = json?.optString("message")?.takeIf(String::isNotBlank) ?: "OMR 服务返回错误（$status）"
        throw OmrServiceException(message, code, status == 408 || status == 429 || status >= 500)
    }

    internal fun parseJob(json: JSONObject): RemoteOmrJob = RemoteOmrJob(
        id = json.getString("jobId"),
        status = json.getString("status"),
        stage = json.optString("stage", json.getString("status")),
        errorCode = json.nullableString("errorCode"),
        errorMessage = json.nullableString("errorMessage"),
        diagnosticsJson = boundOmrDiagnostics(json.optJSONObject("diagnostics")?.toString()),
    )

    private fun safeId(id: String): String {
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "OMR 任务标识无效" }
        return id
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 60_000
        const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024
        const val MAX_XML_BYTES = 20 * 1024 * 1024
        const val MAX_JSON_CHARS = 64 * 1024
    }
}

internal fun boundOmrDiagnostics(value: String?): String? = value?.take(4 * 1024)

private class ResponseTooLargeException : Exception()

private fun java.io.Reader.readTextLimited(limit: Int): String {
    val output = StringBuilder()
    val buffer = CharArray(2048)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (output.length + read > limit) throw ResponseTooLargeException()
        output.append(buffer, 0, read)
    }
    return output.toString()
}

private fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key).takeIf(String::isNotBlank)

