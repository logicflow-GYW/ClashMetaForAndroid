package com.github.kr328.clash.service.cfoptimizer.worker

import android.content.Context
import android.net.Network
import com.github.kr328.clash.service.cfoptimizer.net.PhysicalNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    data class Failure(val reason: WorkerFailureReason, val attempts: Int = 1) : WorkerUploadResult
}

enum class WorkerFailureReason { InvalidUrl, LoginRejected, UploadRejected, NetworkError, InvalidResponse, ServerError }

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
 *
 * 上传是整条优选链路里唯一没有容错的环节（拉源/探测各自有多网络与超时兜底），
 * 而它失败一次就作废一整轮几分钟的探测结果 —— 因此这里内置两层韧性：
 * 1. [upload] 对**瞬时类失败**（网络 IO、服务端 5xx）做指数退避重试；
 *    密码错误（LoginRejected）与 4xx 拒绝（UploadRejected）是确定性失败，重试无意义，直接返回。
 * 2. 网络出口故障转移在 [UrlConnectionWorkerHttpTransport]（Wi-Fi → 蜂窝逐个尝试）。
 * 重试是安全的：ADD.txt 是整体覆写语义，重复 POST 同一份名单不会产生追加/重复行。
 */
class CfWorkerClient(
    private val settings: CfWorkerSettings,
    private val transport: WorkerHttpTransport,
    /** 一次 upload() 内的最大尝试次数（含第一次）；≥1。 */
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    /** 重试间隔（毫秒），按尝试序号取用；可注入以避免测试真实睡眠。 */
    private val backoffMillis: List<Long> = DEFAULT_BACKOFF_MILLIS,
) {
    suspend fun upload(entries: List<String>): WorkerUploadResult {
        // Fail closed: an empty list must never overwrite the shared remote list.
        if (entries.isEmpty()) {
            return WorkerUploadResult.Failure(WorkerFailureReason.UploadRejected)
        }

        val base = normalizeBaseUrl(settings.baseUrl)
            ?: return WorkerUploadResult.Failure(WorkerFailureReason.InvalidUrl)

        val attempts = maxAttempts.coerceAtLeast(1)
        var lastFailure: WorkerUploadResult.Failure? = null

        repeat(attempts) { attempt ->
            val result = attemptUpload(base, entries)

            // subject 提为局部 val：when 分支内才能对 Failure smart cast
            // （无 subject 的 when 不跨分支传播 smart cast —— 直接写 result.reason 编译不过）。
            val failure = result as? WorkerUploadResult.Failure

            when {
                result is WorkerUploadResult.Success -> return result
                failure == null || !failure.reason.isRetryable() -> return result
                else -> {
                    lastFailure = failure
                    // 还有下一次机会才退避等待；最后一次直接返回失败。
                    if (attempt < attempts - 1) {
                        delay(backoffMillis.getOrElse(attempt) { backoffMillis.last() })
                    }
                }
            }
        }

        return lastFailure!!
            .copy(attempts = attempts)
    }

    /** 单次完整尝试：login → push。任一步瞬时失败都按该失败原因参与重试判定。 */
    private suspend fun attemptUpload(base: String, entries: List<String>): WorkerUploadResult {
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
            // 5xx 是服务端瞬时故障（Cloudflare 边缘抖动），与"密码错误"不同，值得重试。
            throw WorkerProtocolException(
                if (response.statusCode >= 500) WorkerFailureReason.ServerError
                else WorkerFailureReason.LoginRejected
            )
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
            // 5xx = 服务端瞬时故障（重试可能成功）；4xx/3xx = 明确拒绝（重试无意义）。
            return WorkerUploadResult.Failure(
                if (response.statusCode >= 500) WorkerFailureReason.ServerError
                else WorkerFailureReason.UploadRejected
            )
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

        /** 默认 3 次尝试，间隔 1s / 3s（总上传窗口 ≈ 45s，仍远低于重跑一轮探测的分钟级成本）。 */
        val DEFAULT_BACKOFF_MILLIS: List<Long> = listOf(1_000L, 3_000L)
        const val DEFAULT_MAX_ATTEMPTS: Int = 3

        /** 瞬时失败（值得换网络出口再试）：网络 IO 与服务端 5xx。 */
        fun WorkerFailureReason.isRetryable(): Boolean =
            this == WorkerFailureReason.NetworkError || this == WorkerFailureReason.ServerError

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
 *
 * **多物理网络故障转移**：provider 返回候选网络列表（Wi-Fi 优先，其次蜂窝，全部排除 VPN）。
 * 单个网络上的连接/写入/读取抛 IOException 时，自动换下一个候选网络重发同一请求 ——
 * 真机实测的失败形状是"Wi-Fi 掉线瞬间整轮作废"或"单网络被运营商 QoS"，而手机上
 * 往往同时有两条出口。HTTP 层错误（4xx/5xx 响应）**不触发**故障转移 —— 那是服务端
 * 的答复，换网络没有意义（由客户端层判定是否重试）。
 */
class UrlConnectionWorkerHttpTransport(
    private val networksProvider: () -> List<Network>,
) : WorkerHttpTransport {
    /** 便捷构造：按需取当前全部可用物理网络（service 侧使用）。 */
    constructor(context: Context) : this({ PhysicalNetwork.candidates(context) })

    /** 兼容旧签名：固定单网络出口。 */
    constructor(network: Network) : this({ listOf(network) })

    override suspend fun execute(request: WorkerHttpRequest): WorkerHttpResponse =
        withContext(Dispatchers.IO) {
            val networks = networksProvider()
            if (networks.isEmpty()) {
                throw TransportException(IOException("no validated physical network"))
            }

            var lastError: TransportException? = null

            for (network in networks) {
                try {
                    return@withContext executeOn(network, request)
                } catch (e: IOException) {
                    // 记录并换下一个网络出口；HTTP 响应（含 4xx/5xx）不算 IO 失败，不会走到这。
                    lastError = TransportException(e)
                }
            }

            throw lastError!!
        }

    private fun executeOn(network: Network, request: WorkerHttpRequest): WorkerHttpResponse {
        val url = URL(request.url)
        val connection =
            (PhysicalNetwork.openConnection(network, url) as HttpURLConnection).apply {
                requestMethod = request.method
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
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

            return WorkerHttpResponse(statusCode, headers, body)
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
        /** 连接建立 15s；响应读取放宽到 30s —— 上传失败大多死在"连上了但响应慢"的弱网。 */
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val MAX_BODY_BYTES = 64 * 1024
        const val BUFFER_SIZE_BYTES = 8 * 1024
    }
}
