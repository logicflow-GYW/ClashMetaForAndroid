package com.github.kr328.clash.service.cfoptimizer.quality

/**
 * 劣化判定 —— 纯逻辑，无 Android 依赖（沙箱 JVM 可测）。
 *
 * 思路：Clash 核心的 url-test 组本来就在每 interval 跑一轮健康检查 —— 那是已经在跑的
 * 真实质量信号。把它当**证据**不当**闹钟**：多数节点失联或变慢才触发一轮优选（自动补货），
 * 而不是定时偷跑。
 *
 * 数据口径：节点 delay 来自核心（0 = 失联或尚未测过）。全部节点的 delay 反映的就是
 * 优选推上去的 IP 池的当前质量 —— 劣化 = 该补货了。
 */
object CfQualityGate {
    /** 慢节点阈值（ms）：超过算"变慢"。TTFB 早停线下的一档，原版同机实测的 80% 量级。 */
    const val SLOW_MS: Int = 800

    /** 劣化占比：失联或慢节点**严格超过**一半才判劣化（正好一半不触发，防抖）。 */
    const val DEGRADE_RATIO_HALF: Int = 2

    /** 数据就绪线：有真实延迟（>0）的节点至少占三成 —— 全是 0 多半是还没测过，不是劣化。 */
    const val TESTED_TENTHS: Int = 3

    /** 最少样本：节点太少时占比不可信。 */
    const val MIN_SAMPLES: Int = 4

    private const val HOUR_MS: Long = 3_600_000L

    data class Verdict(
        val shouldRun: Boolean,
        val reason: String?,
    )

    /**
     * [delays] = 当前 profile 全部节点的延迟（去重后），0 = 失联/未测；
     * [lastRunAt] = 上次优选完成时间（epoch ms，0 = 从未跑过）；
     * [minIntervalHours] = 节流时长（小时，由调用方传入 —— 生产从设置读，
     * 默认值的单一来源是 CfOptimizerTuning.MIN_INTERVAL_HOURS_DEFAULT；
     * **0（或任何非正值）= 不节流**）。
     */
    fun shouldRun(
        delays: List<Int>,
        lastRunAt: Long,
        now: Long,
        minIntervalHours: Long,
    ): Verdict {
        if (delays.size < MIN_SAMPLES) return Verdict(false, "too_few_proxies")

        if (minIntervalHours > 0 && now - lastRunAt < minIntervalHours * HOUR_MS) {
            return Verdict(false, "throttled")
        }

        val tested = delays.count { it > 0 }
        if (tested * 10 < delays.size * TESTED_TENTHS) return Verdict(false, "data_not_ready")

        val size = delays.size
        val failed = size - tested
        if (failed * DEGRADE_RATIO_HALF > size) return Verdict(true, "failed_majority")

        val slow = delays.count { it > SLOW_MS }
        if (slow * DEGRADE_RATIO_HALF > size) return Verdict(true, "slow_majority")

        return Verdict(false, null)
    }
}
