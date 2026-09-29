package com.github.kr328.clash.service.cfoptimizer.source

import com.github.kr328.clash.service.cfoptimizer.CandidateIp
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.random.Random

/**
 * CF 候选源 — 从固定 HTTPS 来源拉取候选入口 IP。
 *
 * 候选来源刻意**不开放用户自填**（v1 契约，INTERFACES.md C/D 部分）：固定常量 URL
 * 意味着没有用户输入 → 没有 SSRF 面；host 校验退化为常量比对。
 * 来源仅信任格式，不信任成员正确性：所有候选还必须落在 Cloudflare 官方 IPv4 网段内。
 */
object CfCandidateSource {
    /**
     * 固定候选来源，来自原 Python 脚本已验证配置（cf_config.URL_SOURCES，2026-09-15 用户提供）。
     */
    const val SOURCE_URL: String = "https://zip.cm.edu.kg/all.txt"

    /**
     * 响应字节上限。来源发布 ~15k 行（数百 KiB）；8 MiB 上限防止异常/敌意 origin 拖垮内存。
     * ponytail: 响应截断到 8 MiB | 天花板: 来源增长超过 8 MiB 会被截断丢尾 | 升级触发: 候选数 < 50 或日志出现截断
     */
    private const val MAX_RESPONSE_BYTES: Int = 8 * 1024 * 1024

    /**
     * 每轮探测的候选硬上限（Python 用 3000 sample_limit；Android MVP 探测是有界子集，
     * 物理网络探测是瓶颈，不是候选池）。
     */
    const val MAX_CANDIDATES: Int = 100

    /** 连接/读取超时（毫秒）。公网静态文本源，5s 足够；不静默重试，失败即失败。 */
    private const val CONNECT_TIMEOUT_MS: Int = 5_000
    private const val READ_TIMEOUT_MS: Int = 10_000

    /** Cloudflare 官方 IPv4 网段（与 cf_config.CF_CIDR_LIST 一致，静态常量 + 附理由）。 */
    private val CF_V4_PREFIXES: List<Pair<Long, Int>> = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
        "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
        "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
        "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
    ).mapNotNull { parseCidr(it) }

    /** Cloudflare 支持的入口端口（与引擎白名单一致，双保险）。 */
    private val ALLOWED_PORTS: Set<Int> =
        setOf(80, 8080, 8880, 2052, 2082, 2086, 2095, 443, 2053, 2083, 2087, 2096, 8443)

    /**
     * 拉取候选。返回打乱后截断的候选列表；失败抛 [IOException]，由调用方决定终止路径。
     */
    fun fetch(): List<CandidateIp> {
        val connection = URL(SOURCE_URL).openConnection() as HttpURLConnection

        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false

        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("candidate source HTTP ${connection.responseCode}")
            }

            val stream = connection.inputStream
            val buffer = ByteArray(MAX_RESPONSE_BYTES)
            var offset = 0

            while (offset < buffer.size) {
                val read = stream.read(buffer, offset, buffer.size - offset)
                if (read < 0) break
                offset += read
            }

            val text = String(buffer, 0, offset, Charsets.UTF_8)

            val parsed = text.lineSequence()
                .map { it.substringBefore('#').trim() }
                .mapNotNull(::parseLine)
                .distinctBy { it.address to it.port }
                .filter { isInCfRange(it.address) }
                .toList()

            return parsed.shuffled(Random(System.nanoTime())).take(MAX_CANDIDATES)
        } finally {
            connection.disconnect()
        }
    }

    /** 行格式 `IP:port`（忽略 `#` 后缀与空白）；拒绝一切非字面 IPv4。 */
    private fun parseLine(line: String): CandidateIp? {
        val match =Regex("""^(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})$""").find(line) ?: return null

        val address = match.groupValues[1]
        val port = match.groupValues[2].toIntOrNull() ?: return null

        if (port !in ALLOWED_PORTS) return null
        if (!isPublicLiteralIpv4(address)) return null

        return CandidateIp(address = address, port = port, source = "remote")
    }

    /** 字面公网 IPv4（不触发 DNS；拒绝 private/loopback/multicast/reserved/link-local/unspecified）。 */
    private fun isPublicLiteralIpv4(address: String): Boolean {
        val addr = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return false

        if (addr !is java.net.Inet4Address) return false

        return !(addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isMulticastAddress ||
                addr.isAnyLocalAddress || addr.isLinkLocalAddress)
    }

    private fun isInCfRange(address: String): Boolean {
        val addr = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return false
        val value = toLong(addr.address) ?: return false

        return CF_V4_PREFIXES.any { (network, prefix) ->
            val mask = if (prefix == 0) 0L else (-1L shl (32 - prefix))
            (value and mask) == (network and mask)
        }
    }

    private fun parseCidr(cidr: String): Pair<Long, Int>? {
        val parts = cidr.split("/")
        if (parts.size != 2) return null

        val addr = runCatching { InetAddress.getByName(parts[0]) }.getOrNull() ?: return null
        val prefix = parts[1].toIntOrNull() ?: return null

        return (toLong(addr.address) ?: return null) to prefix
    }

    private fun toLong(bytes: ByteArray): Long? {
        if (bytes.size != 4) return null

        return ((bytes[0].toLong() and 0xFF) shl 24) or
                ((bytes[1].toLong() and 0xFF) shl 16) or
                ((bytes[2].toLong() and 0xFF) shl 8) or
                (bytes[3].toLong() and 0xFF)
    }
}
