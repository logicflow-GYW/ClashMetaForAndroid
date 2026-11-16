package com.github.kr328.clash.service.cfoptimizer.source

import com.github.kr328.clash.service.cfoptimizer.CandidateIp
import kotlin.random.Random

/**
 * CF 官方网段盲扫 — 原版 `generate_random_ips()` 的手机端等价实现。
 *
 * 为什么必须有这一段（真机数据）：公开 .txt 源是**所有人共用**的同一批地址，实测 96 个源并集只有
 * 约 1400 个唯一 IP:port，随机抽 12 个源只剩 ~250 条候选；而原版每轮在这个静态池之外再从官方
 * 网段盲扫 3000 条（`RANDOM_SCAN_COUNT=15000`，静态源充足时取 20%），这也是它一轮能凑到 6700
 * 条候选、探到 454 个合格节点的主要来源。只吃公开源的后果是：永远在别人已经公开过的地址里
 * 挑，挑到的就是那批被反复测、被大量用户共用的节点。
 *
 * 权重口径与原版 `get_cidr_weights()` 一致：网段按地址数加权；该网段在记忆库里有 >5 条记录时，
 * 再乘 `0.2 + 0.8×通过率 + 时段加成(≤0.3)` —— 历史上出过好节点的网段被多扫一点。
 *
 * 纯逻辑（无 Android / 无 IO / 无网络）：候选生成由协调器调用，实际连通性仍由探针验证。
 */
object CfCidrScan {
    /** Cloudflare 官方 IPv4 网段（与原版 `cf_config.CF_CIDR_LIST` 逐条一致）。 */
    val CF_IPV4_CIDRS: List<String> = listOf(
        "173.245.48.0/20",
        "103.21.244.0/22",
        "103.22.200.0/22",
        "103.31.4.0/22",
        "141.101.64.0/18",
        "108.162.192.0/18",
        "190.93.240.0/20",
        "188.114.96.0/20",
        "197.234.240.0/22",
        "198.41.128.0/17",
        "162.158.0.0/15",
        "104.16.0.0/13",
        "104.24.0.0/14",
        "172.64.0.0/13",
        "131.0.72.0/22",
    )

    /** 盲扫命中后只测 HTTPS 默认端口（原版 `generate_random_ips()` 固定返回 443）。 */
    const val SCAN_PORT: Int = 443

    /**
     * 生成盲扫候选。[count] ≤ 0 返回空；[weights] 与 [CF_IPV4_CIDRS] 等长且全为正时按权重抽，
     * 否则按地址数加权（原版 `else [n.num_addresses for n in nets]`）。
     *
     * 结果去重（原版用 set 去重，尝试上限 count×5）且**保序稳定**（LinkedHashSet 语义）。
     */
    fun generate(
        count: Int,
        weights: List<Double>? = null,
        random: Random = Random.Default,
        cidrs: List<String> = CF_IPV4_CIDRS,
    ): List<CandidateIp> {
        if (count <= 0) return emptyList()

        val ranges = cidrs.mapNotNull(::parseCidr)
        if (ranges.isEmpty()) return emptyList()

        val effective = weights
            ?.takeIf { it.size == ranges.size && it.all { w -> w.isFinite() && w > 0.0 } }
            ?: ranges.map { it.size.toDouble() }

        val cumulative = DoubleArray(effective.size)
        var total = 0.0
        for (i in effective.indices) {
            total += effective[i]
            cumulative[i] = total
        }
        if (total <= 0.0 || !total.isFinite()) return emptyList()

        val out = LinkedHashSet<CandidateIp>()
        val attempts = count * 5
        var tried = 0

        while (out.size < count && tried < attempts) {
            tried++

            val pick = pickWeighted(cumulative, total, random.nextDouble())
            val range = ranges[pick]

            // 偏移 1..size-2：跳过网络地址与广播地址（原版 `randint(1, max(1, total-2))`）。
            val span = range.size - 2
            if (span <= 0) continue

            val offset = 1L + random.nextLong(span)
            out += CandidateIp(longToAddress(range.start + offset), SCAN_PORT, source = SOURCE)
        }

        return out.toList()
    }

    /**
     * 记忆库派生的网段权重（原版 `get_cidr_weights()`）。
     *
     * 输入是**已经压平的历史**（地址、通过次数、总次数、当前时段是否有得分），刻意不接收
     * `MemoryRecord` —— 让这段逻辑不依赖记忆库的存储细节，沙箱里能直接构造夹具验证。
     */
    fun weights(history: List<History>, cidrs: List<String> = CF_IPV4_CIDRS): List<Double> {
        val ranges = cidrs.map { parseCidr(it) }
        val stats = Array(cidrs.size) { intArrayOf(0, 0, 0) } // total / passes / timePasses

        for (item in history) {
            val address = addressToLong(item.address) ?: continue

            val index = ranges.indexOfFirst { it != null && address >= it.start && address <= it.end }
            if (index < 0) continue

            stats[index][0] += 1
            if (item.passes > 0) stats[index][1] += 1
            if (item.hasCurrentBucketScore) stats[index][2] += 1
        }

        return cidrs.mapIndexed { index, cidr ->
            val range = ranges[index] ?: return@mapIndexed 1.0
            val base = range.size.toDouble()
            val (total, passes, timePasses) = stats[index]

            if (total > 5) {
                val passRate = passes.toDouble() / total
                val timeBonus = minOf(0.3, timePasses.toDouble() / total * 0.5)
                base * (0.2 + 0.8 * passRate + timeBonus)
            } else {
                base
            }
        }
    }

    /** 一条记忆库历史，供 [weights] 使用（与 `MemoryRecord` 的字段同名，避免记忆包反向依赖本文件）。 */
    data class History(
        val address: String,
        val passes: Int,
        val runs: Int,
        val hasCurrentBucketScore: Boolean,
    )

    /** 解析后的网段（含起止地址，闭区间）。 */
    data class CidrRange(val start: Long, val end: Long) {
        val size: Long get() = end - start + 1
    }

    /** `a.b.c.d/prefix` → [CidrRange]；格式非法返回 null（不抛异常，源文件里的脏数据不该中断整轮）。 */
    fun parseCidr(cidr: String): CidrRange? {
        val parts = cidr.trim().split('/')
        if (parts.size != 2) return null

        val base = addressToLong(parts[0]) ?: return null
        val prefix = parts[1].toIntOrNull() ?: return null
        if (prefix !in 0..32) return null

        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val start = base and mask
        val end = start or (0xFFFFFFFFL xor mask)

        return CidrRange(start, end)
    }

    /** 点分十进制 → 32 位无符号整数（Long 承载）；非法/前导零/段数不符返回 null。 */
    fun addressToLong(address: String): Long? {
        val parts = address.trim().split('.')
        if (parts.size != 4) return null

        var value = 0L
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
            if (part.length > 1 && part[0] == '0') return null

            val octet = part.toInt()
            if (octet !in 0..255) return null

            value = (value shl 8) or octet.toLong()
        }

        return value
    }

    /** 32 位无符号整数 → 点分十进制。 */
    fun longToAddress(value: Long): String =
        "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"

    /** 按累计权重二分选一个下标（权重已保证 > 0）。 */
    private fun pickWeighted(cumulative: DoubleArray, total: Double, roll: Double): Int {
        val target = roll * total

        var low = 0
        var high = cumulative.size - 1

        while (low < high) {
            val mid = (low + high) / 2
            if (cumulative[mid] < target) low = mid + 1 else high = mid
        }

        return low
    }

    /** 候选来源标记（与源列表候选区分开，日志与记忆写回都能看出它从哪来）。 */
    const val SOURCE: String = "scan"
}
