package com.github.kr328.clash.service.cfoptimizer.worker

import android.content.Context
import android.net.Network
import com.github.kr328.clash.service.cfoptimizer.net.PhysicalNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL

data class CfWorkerSettings(val baseUrl: String, val password: String)

sealed interface WorkerUploadResult {
    data object Success : WorkerUploadResult

    data class Failure(val reason: WorkerFailureReason) : WorkerUploadResult
}

enum class WorkerFailureReason { InvalidUrl, LoginRejected, UploadRejected, NetworkError, InvalidResponse }

/**
 * Test seam for the Worker HTTP protocol. Implementations receive explicit URL, method,
 * headers and body, and return status/headers/body. No other channel is allowed.
 */
interface WorkerHttpTransport {
    suspend fun execute(request: WorkerHttpRequest): WorkerHttpResponse
}

class WorkerHttpRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
)

class WorkerHttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray?,
) {
    fun headerValues(name: String): List<String> =
        headers.entries.filter { it.key.equals(name, ignoreCase = true) }.flatMap { it.value }
}

/**
 * Client for the already deployed Cloudflare Worker shared-list protocol:
 * POST /login (form `password`) -> expect JSON `success: true` -> reuse the session
 * Cookie only for the same-origin POST /admin/ADD.txt with a UTF-8 text/plain body.
 *
 * Mirrors the verified Python protocol (cf_optimizer.py::_push_worker_entry).
 * This client never touches Android storage, never reads persisted credentials and
 * never accepts a Cloudflare API token. It never logs: failure reporting is limited
 * to [WorkerFailureReason] values; thrown exceptions carry no request, response,
 * cookie, password or list content.
 */
class CfWorkerClient(
    private val settings: CfWorkerSettings,
    private val transport: WorkerHttpTransport,
) {
    suspend fun upload(entries: List<String>): WorkerUploadResult {
        // Fail closed: an empty list must never overwrite the shared remote list.
        if (entries.isEmpty()) {
            return WorkerUploadResult.Failure(WorkerFailureReason.UploadRejected)
        }

        val base = normalizeBaseUrl(settings.baseUrl)
            ?: return WorkerUploadResult.Failure(WorkerFailureReason.InvalidUrl)

        val cookie = try {
            login(base)
        } catch (e: WorkerProtocolException) {
            return WorkerUploadResult.Failure(e.reason)
        } catch (e: IOException) {
            return WorkerUploadResult.Failure(WorkerFailureReason.NetworkError)
        }

        return try {
            pushEntries(base, cookie, entries)
        } catch (e: IOException) {
            WorkerUploadResult.Failure(WorkerFailureReason.NetworkError)
        }
    }

    /**
     * POST /login with a form-encoded `password` field. Returns the cookie extracted
     * from the login response only, or null if the response carried none.
     */
    private suspend fun login(base: String): String? {
        val form = "password=" + URLEncoder.encode(settings.password, Charsets.UTF_8.name())
        val response = transport.execute(
            WorkerHttpRequest(
                url = "$base/login",
                method = "POST",
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                body = form.toByteArray(Charsets.UTF_8),
            )
        )

        // Redirects are never followed by design: any 3xx from login is a rejection,
        // so the password can never be replayed onto another host.
        if (response.statusCode !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
            throw WorkerProtocolException(WorkerFailureReason.LoginRejected)
        }

        when (parseSuccess(response.body)) {
            true -> return response.headerValues("Set-Cookie")
                .map { it.substringBefore(';').trim() }
                .filter { it.isNotEmpty() }
                .takeIf { it.isNotEmpty() }
                ?.joinToString("; ")
            false -> throw WorkerProtocolException(WorkerFailureReason.LoginRejected)
            null -> throw WorkerProtocolException(WorkerFailureReason.InvalidResponse)
        }
    }

    /**
     * POST /admin/ADD.txt to the same origin as the login call, attaching the login
     * session cookie (if any) and nothing else.
     */
    private suspend fun pushEntries(
        base: String,
        cookie: String?,
        entries: List<String>,
    ): WorkerUploadResult {
        // Mirrors the Python protocol: entries joined with LF and a trailing newline.
        val body = entries.joinToString("\n") + "\n"
        val headers = buildMap {
            put("Content-Type", "text/plain;charset=utf-8")
            if (cookie != null) {
                put("Cookie", cookie)
            }
        }

        val response = transport.execute(
            WorkerHttpRequest(
                url = "$base/admin/ADD.txt",
                method = "POST",
                headers = headers,
                body = body.toByteArray(Charsets.UTF_8),
            )
        )

        if (response.statusCode !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
            return WorkerUploadResult.Failure(WorkerFailureReason.UploadRejected)
        }

        // A 2xx HTML error page is not a successful overwrite. Accept the
        // deployed protocol's JSON success response, or a non-empty text/plain
        // acknowledgement; reject JSON {success:false}, empty bodies, and HTML.
        when (parseSuccess(response.body)) {
            true -> return WorkerUploadResult.Success
            false -> return WorkerUploadResult.Failure(WorkerFailureReason.UploadRejected)
            // Not JSON (HTML error page, plain text, empty): falls through to the
            // content-type check below, which only accepts a non-empty text/plain ack.
            null -> { /* fall through */ }
        }

        val contentType = response.headerValues("Content-Type")
            .firstOrNull()
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        val responseBody = response.body?.decodeToString()?.trim()
        return if (contentType == "text/plain" && !responseBody.isNullOrEmpty()) {
            WorkerUploadResult.Success
        } else {
            WorkerUploadResult.Failure(WorkerFailureReason.InvalidResponse)
        }
    }

    private fun parseSuccess(body: ByteArray?): Boolean? {
        if (body == null) {
            return null
        }
        return try {
            Json.parseToJsonElement(body.decodeToString())
                .jsonObject["success"]
                ?.jsonPrimitive
                ?.booleanOrNull
        } catch (ignored: Exception) {
            null
        }
    }

    private class WorkerProtocolException(val reason: WorkerFailureReason) :
        RuntimeException(reason.name)

    companion object {
        private const val HTTP_SUCCESS_MIN = 200
        private const val HTTP_SUCCESS_MAX = 299

        /**
         * Only canonical HTTPS origins are accepted. Rejects non-HTTPS schemes,
         * userinfo, query, fragment, CR/LF injection, malformed hosts and any port
         * other than the implicit or explicit 443.
         */
        fun normalizeBaseUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.contains('\r') || trimmed.contains('\n')) {
                return null
            }
            if (trimmed.contains(' ') || trimmed.contains('\t')) {
                return null
            }

            return try {
                val uri = URI(trimmed)
                if (uri.scheme != "https") return null
                if (uri.userInfo != null) return null
                if (uri.query != null || uri.fragment != null) return null

                val host = uri.host
                if (host.isNullOrEmpty()) return null

                val port = uri.port
                if (port != -1 && port != 443) return null

                val path = uri.rawPath ?: ""
                if (!path.isEmpty() && !path.startsWith('/')) return null
                val normalizedPath = path.trimEnd('/')

                buildString {
                    append("https://")
                    append(host)
                    if (port == 443) {
                        append(":443")
                    }
                    append(normalizedPath)
                }
            } catch (ignored: Exception) {
                null
            }
        }
    }
}

/**
 * Default transport built on HttpURLConnection, compatible with Android API 21.
 * Redirects are disabled globally and per connection: the client never follows a
 * cross-host (or any) redirect, so credentials and cookies can never leak to
 * another origin. Response bodies are capped.
 */
class UrlConnectionWorkerHttpTransport(private val networkProvider: () -> Network?) : WorkerHttpTransport {
    /** 便捷构造：按需取当前主物理网络（service 侧使用）。 */
    constructor(context: Context) : this({ PhysicalNetwork.pick(context) })

    override suspend fun execute(request: WorkerHttpRequest): WorkerHttpResponse =
        withContext(Dispatchers.IO) {
            val url = URL(request.url)
            val network = networkProvider() ?: throw IOException("no validated physical network")
            val connection =
                (PhysicalNetwork.openConnection(network, url) as HttpURLConnection).apply {
                    requestMethod = request.method
                    instanceFollowRedirects = false
                    connectTimeout = TIMEOUT_MILLIS
                    readTimeout = TIMEOUT_MILLIS
                    useCaches = false
                    request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
                }

            try {
                request.body?.let { bytes ->
                    connection.outputStream.use { stream -> stream.write(bytes) }
                }

                val statusCode = connection.responseCode
                val headers = connection.headerFields
                    ?.filterKeys { it != null }
                    ?.mapKeys { it.key as String }
                    ?: emptyMap()

                val body: ByteArray? = try {
                    connection.inputStream?.use { readCapped(it) }
                } catch (ignored: IOException) {
                    try {
                        connection.errorStream?.use { readCapped(it) }
                    } catch (ignored: IOException) {
                        null
                    }
                }

                WorkerHttpResponse(statusCode, headers, body)
            } catch (e: IOException) {
                throw TransportException(e)
            } finally {
                connection.disconnect()
            }
        }

    private fun readCapped(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE_BYTES)
        while (output.size() < MAX_BODY_BYTES) {
            val limit = minOf(buffer.size, MAX_BODY_BYTES - output.size())
            val read = stream.read(buffer, 0, limit)
            if (read < 0) {
                break
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    /** Wrapper so transport failures stay detachable from unrelated I/O errors. */
    private class TransportException(cause: IOException) : IOException(cause)

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
        const val MAX_BODY_BYTES = 64 * 1024
        const val BUFFER_SIZE_BYTES = 8 * 1024
    }
}
