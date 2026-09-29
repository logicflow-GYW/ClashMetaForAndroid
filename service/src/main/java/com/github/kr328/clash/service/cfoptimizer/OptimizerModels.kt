package com.github.kr328.clash.service.cfoptimizer

/**
 * CF 候选优选 — 纯逻辑数据模型（无 Android 依赖、无 IO、无网络）。
 *
 * 契约来源：/var/minis/shared/clash-cf-autopilot-2026/INTERFACES.md（A 部分）。
 * 本文件为会话 A 独占；B/C/D 只消费这里的类型与 [CfOptimizerEngine.rank]。
 */

/** 一个候选入口 IP（address 为字面 IP，port 必须在 CF 端口白名单内）。 */
data class CandidateIp(val address: String, val port: Int, val source: String = "")

/** Android Probe（会话 D）提供的实测指标；A 不创建套接字、不实现网络测量。 */
data class ProbeMetrics(
    val ttfbMs: Long,
    val jitterMs: Long,
    val downloadMbps: Double,
    val successfulSamples: Int,
    val region: String = "ZZ",
)

/** 优选门槛与限额。默认值来自原 Python 脚本（SMART_PUSH_MAX_TOTAL=12 / 门槛 30 / TTFB 800ms / jitter 200ms）。 */
data class OptimizerLimits(
    val maxEntries: Int = 12,
    val maxTtfbMs: Long = 800,
    val maxJitterMs: Long = 200,
    val minDownloadMbps: Double = 0.0,
    val minScore: Double = 30.0,
    /**
     * 每个地区最多保留条数；0 = 不启用地区配额（默认，行为与契约基线一致）。
     * 接口偏差说明：INTERFACES.md 的 OptimizerLimits 未定义地区多样性限额，但任务书
     * （session-task-A-engine.md）要求实现“地区多样性限额”。为不改变默认行为、不破坏
     * 主集成会话的现有调用，以默认关闭的可选字段落地，并已在任务回报中声明此偏差。
     */
    val maxPerRegion: Int = 0,
)

/** 一条优选结果。由 [CfOptimizerEngine.rank] 产出。 */
data class OptimizedEntry(
    val address: String,
    val port: Int,
    val region: String,
    val ttfbMs: Long,
    val jitterMs: Long,
    val downloadMbps: Double,
    val score: Double,
) {
    /**
     * 输出 Worker ADD.txt 行，格式 `IP:port#REGION-[networkTag]`；
     * IPv6 地址规范为 `[IPv6]:port#REGION-[networkTag]`，IPv4 无方括号。
     *
     * @throws IllegalArgumentException networkTag 为空或含注入字符（仅允许字母/数字/`-`/`_`/`.`）。
     */
    fun toWorkerLine(networkTag: String): String {
        val tag = networkTag.trim()
        require(tag.isNotEmpty() && tag.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) {
            "invalid network tag"
        }
        val reg = region.trim().uppercase()
        val safeRegion = if (reg.length == 2 && reg.all { it in 'A'..'Z' }) reg else "ZZ"
        val host = if (address.contains(':')) "[$address]" else address
        return "$host:$port#$safeRegion-$tag"
    }
}
