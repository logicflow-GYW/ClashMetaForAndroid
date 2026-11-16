package com.github.kr328.clash.service.cfoptimizer

/**
 * 名单合并 — 把上一轮已推送、且**本轮仍 TCP 可达**的节点补进本轮结果。
 *
 * 为什么需要它（原版对照）：原版每轮把 `entry_ip_<网络>.txt` 按 14 天 TTL 合并后再推 Worker，
 * 所以某轮网络变差、只测出一个合格节点时，订阅里仍然保留着前几天的好节点（日志实测：
 * 11-11 16:10 那轮只推了 1 条，但 15:31/16:31 的名单仍在别的网络文件里）。本 App 每轮整体
 * 覆写 Worker，一轮抖动的代价直接落到用户身上 —— 26 轮记录里 10-29 / 10-30 都出现过
 * `ranked=16/18 但上传失败`、10-31 `ranked=1`，用户拿到的就是"突然变差"。
 *
 * **与原版的刻意偏差**：原版合并的是"盲合并"（从不重新验证那 14 天的旧文件）；这里只补
 * **本轮 TCP 实测可达**的旧节点 —— 死 IP 不进订阅，代价是 merged 列表比原版短，收益是
 * 不会把已经死掉的地址当"兜底"推上去。
 */
object CfEntryMerge {
    /** IPv6 去重取前 3 段（48 位），与原脚本 `IPV6_DEDUP_PREFIX` 一致。 */
    const val IPV6_DEDUP_HEXTETS: Int = 3

    /**
     * 去重键：IPv4 取前 `ceil(prefix/8)` 段，IPv6 取前 [IPV6_DEDUP_HEXTETS] 段。
     * 地址解析不出来时返回 null（调用方保留该条 —— 不因 tag 格式抖动丢结果）。
     */
    fun prefixKey(address: String, prefixLength: Int): String? {
        val octetsKept = (prefixLength.coerceIn(8, 32) + 7) / 8

        return if (address.contains(':')) {
            address.split(':').take(IPV6_DEDUP_HEXTETS).joinToString(":")
        } else {
            val octets = address.split('.')
            if (octets.size == 4) octets.take(octetsKept).joinToString(".") else null
        }
    }

    /**
     * 把 [previous] 里不与 [current] 冲突的节点追加到末尾，最多 [maxExtra] 条。
     *
     * 冲突判据两条：`address:port` 完全相同，或落在同一个去重前缀（原版 `cidr_seen` 语义）。
     * [maxExtra] ≤ 0 时原样返回 [current]。
     */
    fun merge(
        current: List<OptimizedEntry>,
        previous: List<OptimizedEntry>,
        maxExtra: Int,
        prefixLength: Int,
    ): List<OptimizedEntry> {
        if (maxExtra <= 0 || previous.isEmpty()) return current

        val keys = HashSet<String>()
        val prefixes = HashSet<String>()

        for (entry in current) {
            keys += "${entry.address}:${entry.port}"
            prefixKey(entry.address, prefixLength)?.let { prefixes += it }
        }

        val merged = ArrayList<OptimizedEntry>(current.size + maxExtra)

        merged += current

        for (entry in previous) {
            if (merged.size - current.size >= maxExtra) break

            val key = "${entry.address}:${entry.port}"
            if (key in keys) continue

            val prefix = prefixKey(entry.address, prefixLength)
            if (prefix != null && prefix in prefixes) continue

            keys += key
            prefix?.let { prefixes += it }
            merged += entry
        }

        return merged
    }
}
