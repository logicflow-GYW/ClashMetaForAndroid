package com.github.kr328.clash.service.cfoptimizer.source

import com.github.kr328.clash.service.cfoptimizer.CandidateIp
import com.github.kr328.clash.service.cfoptimizer.CfOptimizerEngine

/**
 * 源文本行 / Worker 行 → 候选的解析（纯逻辑，无 Android 依赖，沙箱 JVM 可直接测）。
 *
 * 支持的形态（对齐原脚本 `Cloudflare优选助手_v4.6.py` 的 `fetch_urls_classified` 与
 * `load_file_entries`）：
 *  - `a.b.c.d:port`（IPv4 显式端口，最常见）
 *  - `[v6]:port`（IPv6 方括号形式）
 *  - 裸 IPv6 `2403:18c0:4:5fd::1`（无端口 → 默认 443，原脚本 `append((ip6, 443))` 同口径）
 *
 * 地址校验统一走 [CfOptimizerEngine.normalizeLiteralAddress]（单一来源）：IPv4 拒绝
 * private/loopback/link-local/CGNAT/保留段，IPv6 只接受全球单播 2000::/3。
 * 之前这里只认 IPv4，而引擎、探测、Worker 行格式（`[v6]:port#CC`）都已支持 IPv6 ——
 * 结果是「能测、能写、能推」的 v6 通道没有任何入口喂数据（原版 v6 实测 HKG 延迟低 37%、
 * 吞吐高 23%，这条路径一直是死的）。
 */
object CfSourceLineParser {
    /** 裸 IPv6 行没有端口时的默认端口（与原脚本一致）。 */
    const val DEFAULT_IPV6_PORT: Int = 443

    private val BRACKET_V6 = Regex("""^\[([0-9A-Fa-f:.]+)]:(\d{1,5})$""")
    private val V4_WITH_PORT = Regex("""^(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})$""")

    /**
     * 解析源文本的一行（`#` 地区后缀由调用方先剥离）。
     *
     * 返回 null = 不是本轮考虑的候选（格式不认 / 端口不在 CF 白名单 / 地址不是公网字面量）。
     */
    fun parseCandidateLine(raw: String, source: String = "remote"): CandidateIp? {
        val line = raw.trim()
        if (line.isEmpty()) return null

        BRACKET_V6.matchEntire(line)?.let { match ->
            return candidate(match.groupValues[1], match.groupValues[2], source)
        }

        // 裸 IPv6：两条以上冒号（`a:b::c`）。单冒号是 IPv4:port，零冒号是裸 IPv4（不支持）。
        if (line.count { it == ':' } >= 2) {
            return candidate(line, DEFAULT_IPV6_PORT.toString(), source)
        }

        V4_WITH_PORT.matchEntire(line)?.let { match ->
            return candidate(match.groupValues[1], match.groupValues[2], source)
        }

        return null
    }

    /**
     * 解析 Worker 行 `IP:port#tag` / `[v6]:port#tag`（tag 可缺省）→ (address, port)。
     * 用于把 last-known-good 名单还原成候选（上一轮推上去的节点这轮继续复测）。
     */
    fun parseWorkerLine(raw: String): Pair<String, Int>? {
        val entry = raw.substringBefore('#').trim()
        if (entry.isEmpty()) return null

        BRACKET_V6.matchEntire(entry)?.let { match ->
            val port = match.groupValues[2].toIntOrNull() ?: return null
            val address = CfOptimizerEngine.normalizeLiteralAddress(match.groupValues[1]) ?: return null
            return address to port
        }

        V4_WITH_PORT.matchEntire(entry)?.let { match ->
            val port = match.groupValues[2].toIntOrNull() ?: return null
            val address = CfOptimizerEngine.normalizeLiteralAddress(match.groupValues[1]) ?: return null
            return address to port
        }

        return null
    }

    private fun candidate(addressRaw: String, portRaw: String, source: String): CandidateIp? {
        val port = portRaw.toIntOrNull() ?: return null
        if (port !in CfOptimizerEngine.ALLOWED_PORTS) return null

        val address = CfOptimizerEngine.normalizeLiteralAddress(addressRaw) ?: return null

        return CandidateIp(address = address, port = port, source = source)
    }
}
