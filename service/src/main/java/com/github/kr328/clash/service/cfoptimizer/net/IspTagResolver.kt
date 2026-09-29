package com.github.kr328.clash.service.cfoptimizer.net

import android.content.Context
import android.net.Network
import android.util.Log
import org.json.JSONObject
import java.net.URL

/**
 * 取「公网出口的 ISP」标签 —— 原版 `get_current_net_name()` 的 App 等价物。
 *
 * 依次尝试，任一步成功即返回：
 * ① 自己的 Worker `/whoami`（返回 `request.cf.asOrganization`）：HTTPS、零第三方、无限频，
 *    代价是要在 Worker 里加一条只回「请求者自己是谁」的路由（不必鉴权：它不暴露任何别的东西）；
 * ② `ipwho.is`（HTTPS，第三方）；
 * ③ `ip-api.com`（**明文 HTTP**，原版用的就是它 —— 免费档不支持 HTTPS；泄露的信息仅
 *    「这台机器在查自己的 ISP」，与原版同等代价，故保留为最后一条）；
 * ④ 全失败 → 回落 [PhysicalNetwork.transportTag]（`Cellular` / `WiFi`），离线也能跑。
 *
 * 所有请求都绑物理网络（[PhysicalNetwork.openConnection]）。不绑的后果：开着代理时请求会
 * 回流进本应用 TUN，拿回来的是**代理出口**的 ISP，而优选结果只对用户真实所在运营商有意义。
 */
object IspTagResolver {
    private const val LOG_TAG = "CfOptimizer"
    private const val TIMEOUT_MS = 3_000
    private const val MAX_BODY_CHARS = 4_096

    /** 返回网络标签；任何一步失败都只降级、不抛异常（一轮优选不能因为查标签而失败）。 */
    fun resolve(context: Context, network: Network?, workerBaseUrl: String): String {
        val sources = listOfNotNull(
            workerBaseUrl.takeIf { it.isNotBlank() }?.let { "worker" to workerWhoamiUrl(it) },
            "ipwho.is" to "https://ipwho.is/",
            "ip-api" to "http://ip-api.com/json/?lang=zh-CN&fields=isp",
        )

        for ((name, url) in sources) {
            val body = runCatching { fetch(network, url) }.getOrNull()
            val tag = IspTag.parse(extractIsp(body))

            if (tag != null) {
                Log.i(LOG_TAG, "network tag=$tag source=$name")
                return tag
            }
        }

        val fallback = PhysicalNetwork.transportTag(context, network)
        Log.i(LOG_TAG, "network tag=$fallback source=transport-fallback (所有 ISP 查询都失败)")
        return fallback
    }

    /** `https://host/base` → `https://host/base/whoami`（容忍末尾斜杠与已有的子路径）。 */
    private fun workerWhoamiUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/whoami"

    /** 只读前 [MAX_BODY_CHARS] 个字符：响应可能是 HTML 错误页，别把整个 body 拖进来。 */
    private fun fetch(network: Network?, url: String): String? {
        val connection = PhysicalNetwork.openConnection(network, URL(url))

        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json, text/plain")

        connection.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(512)
            val builder = StringBuilder()

            while (builder.length < MAX_BODY_CHARS) {
                val read = reader.read(buffer)
                if (read <= 0) break
                builder.append(buffer, 0, read)
            }

            return builder.toString()
        }
    }

    /**
     * 从响应体里挖出 ISP 名，三种口径：
     * - `{"isp": "..."}`（Worker 与 ip-api）；
     * - `{"org": "..."}` / `{"organization": "..."}`（不同服务的字段名）；
     * - `{"connection": {"isp": "..."}}`（ipwho.is）。
     * 非 JSON（Worker 直接回纯文本）时取第一行。
     */
    private fun extractIsp(body: String?): String? {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return null

        if (!text.startsWith("{")) {
            return text.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }

        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null

        return json.optString("isp").takeIf { it.isNotBlank() }
            ?: json.optString("org").takeIf { it.isNotBlank() }
            ?: json.optString("organization").takeIf { it.isNotBlank() }
            ?: json.optJSONObject("connection")?.optString("isp")?.takeIf { it.isNotBlank() }
    }
}
