package com.github.kr328.clash.service.cfoptimizer

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * CF 候选优选引擎 — 纯 Kotlin 逻辑：候选校验、指标过滤、评分、稳定排序、去重与限额。
 *
 * 无网络请求、无 socket、无 IO、无 Android 依赖（仅 java.net.InetAddress 做字面解析）。
 * 指标由会话 D 的 Android Probe 提供；候选来源由会话 D 装配。
 *
 * 评分基线沿用原 Python 脚本 compute_score（原函数输出 0–1，此处 API 固定 0–100）：
 *   score = (0.6 * max(0, 1 - ttfbMs/800) + 0.4 * min(1, downloadMbps/150)) * 100
 *
 * 第一期只支持全球单播 IPv4 + 全球单播 IPv6 字面地址；不实现 IPv6 以外的范围之外的地址族，
 * 非 IPv4/IPv6 字面量一律拒绝（不是静默吞掉）。不通过反推地理位置填写地区：地区缺失用 "ZZ"。
 */
object CfOptimizerEngine {
    /** Cloudflare 官方支持的入口端口白名单（HTTP 组 + HTTPS 组）。 */
    val ALLOWED_PORTS: Set<Int> =
        setOf(80, 8080, 8880, 2052, 2082, 2086, 2095, 443, 2053, 2083, 2087, 2096, 8443)

    /** 评分公式中固定的 TTFB 参考值（毫秒），来自原脚本，不受 [OptimizerLimits.maxTtfbMs] 影响。 */
    const val SCORE_TTFB_REF_MS: Double = 800.0

    /** 评分公式中固定的带宽参考值（Mbps，原脚本下载测速参考）。 */
    const val SCORE_BW_REF_MBPS: Double = 150.0

    /** 地区缺失时的占位值（不从 IP 反推地理位置）。 */
    const val REGION_FALLBACK: String = "ZZ"

    /**
     * 便宜层（原始候选池）相对昂贵层（TTFB 探测上限）的倍数。
     *
     * 依据（2026-09-29 改为实测值）：原版真机日志给出这批源（bestcf / zip.cm 精选列表，不是随机 CIDR）
     * 的 **TCP 存活率 71.4%** —— 6397 个候选存活 4566 个。原注释里「存活率约 5–15%」的假设是错的，
     * 据此定的 10 倍会这样失效：原始池 3000 → 约 2100 存活，而昂贵层只探 300 →
     * **86% 的存活节点连测都没测就被随机丢掉**，白付 TCP 那一段的钱。
     *
     * 取 2 倍：`maxCandidates=600` → 原始池 1200 → 约 850 存活 → 探 600（覆盖约 70%），
     * 覆盖率与耗时同时优于旧的 10 倍。只有存活率极低（随机 CIDR 盲扫，约 1–5%）时才需要调大。
     */
    const val RAW_POOL_FACTOR: Int = 2

    /**
     * 便宜层的绝对上限。池子再大，TCP 预筛也是一次一次建连，不会更快，只多占内存与时间；
     * 20000 相当于把昂贵层上限拉到 2000 时的原始池，超出部分按随机顺序丢弃。
     */
    const val RAW_POOL_CEILING: Int = 20_000

    /**
     * 由昂贵层上限推导便宜层上限（两层配额，见 [RAW_POOL_FACTOR]）。
     * 传入值非法（≤0）时按 1 处理 —— 只放行、不放大用户填错的参数。
     */
    fun rawPoolLimit(maxCandidates: Int): Int =
        (maxCandidates.coerceAtLeast(1) * RAW_POOL_FACTOR).coerceAtMost(RAW_POOL_CEILING)

    /**
     * 对候选做校验、指标过滤、评分与排序，返回最多 [OptimizerLimits.maxEntries] 条结果。
     *
     * - 坏 IP（非字面量/private/loopback/link-local/multicast/reserved/unspecified）、
     *   端口不在白名单、缺指标、坏指标（负值/NaN/infinity/无成功样本）一律丢弃；
     * - 超过 maxTtfbMs / maxJitterMs、低于 minDownloadMbps / minScore 的样本丢弃；
     * - 相同 address:port 去重，保留得分更高的一条；
     * - 排序：score 降序，平局依次按 ttfb 升序、jitter 升序、带宽降序、address/port 升序（全序确定）；
     * - 全部失败返回空列表（不编造结果）。
     */
    fun rank(
        candidates: List<CandidateIp>,
        metrics: Map<CandidateIp, ProbeMetrics>,
        limits: OptimizerLimits = OptimizerLimits(),
    ): List<OptimizedEntry> {
        if (candidates.isEmpty()) return emptyList()
        if (limits.maxEntries <= 0) return emptyList()

        // 指标索引：候选自身的精确键（含 source）优先；缺失时回退到同 addr:port 的任一指标。
        // 同 addr:port 不同 source 是同一条目，由下方 best-by-score 去重，不在此处收敛。
        val byExact = HashMap<CandidateIp, ProbeMetrics>()
        val byAddrPort = HashMap<String, MutableList<ProbeMetrics>>()
        for ((k, v) in metrics) {
            if (!isSaneMetrics(v)) continue
            byExact[k] = v
            val key = keyOf(k) ?: continue
            byAddrPort.getOrPut(key) { ArrayList() }.add(v)
        }

        val best = HashMap<String, OptimizedEntry>()
        for (c in candidates) {
            val key = keyOf(c) ?: continue
            val address = validateAddress(c.address) ?: continue
            if (c.port !in ALLOWED_PORTS) continue
            val m = byExact[c] ?: byAddrPort[key]?.firstOrNull() ?: continue
            if (!isSaneMetrics(m)) continue

            if (m.ttfbMs > limits.maxTtfbMs) continue
            if (m.jitterMs > limits.maxJitterMs) continue
            if (m.downloadMbps < limits.minDownloadMbps) continue
            val score = scoreOf(m)
            if (score < limits.minScore) continue
            val entry = OptimizedEntry(
                address = address,
                port = c.port,
                region = normalizeRegion(m.region),
                ttfbMs = m.ttfbMs,
                jitterMs = m.jitterMs,
                downloadMbps = m.downloadMbps,
                score = score,
            )
            val prev = best[key]
            if (prev == null || score > prev.score) best[key] = entry
        }

        val sorted = best.values.sortedWith(
            compareByDescending<OptimizedEntry> { it.score }
                .thenBy { it.ttfbMs }
                .thenBy { it.jitterMs }
                .thenByDescending { it.downloadMbps }
                .thenBy { it.address }
                .thenBy { it.port },
        )

        val capped = if (limits.maxPerRegion > 0) applyRegionCap(sorted, limits.maxPerRegion) else sorted
        return capped.take(limits.maxEntries)
    }

    /** 按原脚本公式计算 0–100 分。 */
    fun scoreOf(m: ProbeMetrics): Double {
        val ttfbPart = 0.6 * maxOf(0.0, 1.0 - m.ttfbMs / SCORE_TTFB_REF_MS)
        val bwPart = 0.4 * minOf(1.0, m.downloadMbps / SCORE_BW_REF_MBPS)
        return (ttfbPart + bwPart) * 100.0
    }

    /** 地区码规范化：两位大写 A–Z；缺失/非法用 "ZZ"。 */
    fun normalizeRegion(raw: String): String {
        val s = raw.trim().uppercase()
        return if (s.length == 2 && s.all { it in 'A'..'Z' }) s else REGION_FALLBACK
    }

    private fun keyOf(c: CandidateIp): String? {
        val addr = c.address.trim()
        if (addr.isEmpty() || c.port !in 1..65535) return null
        return "$addr:${c.port}"
    }

    private fun isSaneMetrics(m: ProbeMetrics): Boolean {
        if (m.ttfbMs < 0 || m.jitterMs < 0) return false
        if (!m.downloadMbps.isFinite() || m.downloadMbps < 0) return false
        if (m.successfulSamples <= 0) return false
        return true
    }

    /** 地区配额：按已排序顺序保留每地区最多 [cap] 条。 */
    private fun applyRegionCap(sorted: List<OptimizedEntry>, cap: Int): List<OptimizedEntry> {
        val counts = HashMap<String, Int>()
        val out = ArrayList<OptimizedEntry>(sorted.size)
        for (e in sorted) {
            val n = counts.getOrDefault(e.region, 0)
            if (n < cap) {
                counts[e.region] = n + 1
                out.add(e)
            }
        }
        return out
    }

    /**
     * 字面 IP 校验（严格拒绝 hostname —— 仅当输入形如 IPv4 点分十进制或 IPv6 冒号十六进制
     * 字面量时才调用 [InetAddress.getByName]，杜绝 DNS 解析入口），返回规范化地址或 null。
     */
    private fun validateAddress(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.length > 47) return null
        // 拒绝注入分隔符与任何空白
        if (s.any { it.isWhitespace() || it == '/' || it == '\\' || it == '#' || it == '%' }) return null

        // IPv6：允许方括号包裹
        var v6 = s
        if (v6.startsWith("[") && v6.endsWith("]")) v6 = v6.substring(1, v6.length - 1)
        if (v6.contains(':')) {
            if (v6.none { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }) return null
            if (!v6.contains("::") && v6.split(':').size < 8) return null // 完整形式至少 8 段
            val addr = tryLiteral(v6) ?: return null
            if (addr !is Inet6Address) return null
            return if (isGlobalV6(addr)) addr.hostAddress else null
        }

        // IPv4：严格点分十进制，拒绝前导零（InetAddress 会把前导零当八进制解析）
        val parts = s.split('.')
        if (parts.size != 4) return null
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || !p.all { it.isDigit() }) return null
            if (p.length > 1 && p[0] == '0') return null
            if (p.toInt() !in 0..255) return null
        }
        val addr = tryLiteral(s) ?: return null
        if (addr !is Inet4Address) return null
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
            addr.isAnyLocalAddress || addr.isMulticastAddress
        ) return null
        val o0 = parts[0].toInt()
        val o1 = parts[1].toInt()
        // 0/8 this-network、100.64/10 CGNAT 共享地址、240/4 及 255.255.255.255 保留段
        if (o0 == 0 || o0 in 240..255) return null
        if (o0 == 100 && o1 in 64..127) return null
        return addr.hostAddress
    }

    /** 仅在输入已是合法字面量形态时调用，避免 hostname 触发 DNS。 */
    private fun tryLiteral(literal: String): InetAddress? = try {
        InetAddress.getByName(literal)
    } catch (_: Exception) {
        null
    }

    /** IPv6 只接受全球单播 2000::/3；拒绝 loopback/link-local/site-local/ULA/multicast/unspecified/v4-mapped。 */
    private fun isGlobalV6(addr: Inet6Address): Boolean {
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
            addr.isAnyLocalAddress || addr.isMulticastAddress
        ) return false
        val b0 = addr.address[0].toInt() and 0xFF
        if (b0 == 0xFC || b0 == 0xFD) return false // ULA fc00::/7
        return (b0 and 0xE0) == 0x20 // 2000::/3 全球单播
    }
}
