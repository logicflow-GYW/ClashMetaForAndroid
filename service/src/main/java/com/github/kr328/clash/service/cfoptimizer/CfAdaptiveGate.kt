package com.github.kr328.clash.service.cfoptimizer

/**
 * 门槛自适应 — 原版"节点不足就放宽"的等价实现（但**收敛**，不会越放越松到无限）。
 *
 * 为什么需要它（真机数据）：原版脚本在 11-11 的四轮里，合格节点数从 454 掉到 33、再掉到 1 ——
 * 它靠"严格集不够就放宽 TTFB 上限 / 抖动上限 / 门槛分 / 直接全局兜底"每次都还能交出一份名单；
 * 而本 App 的 26 轮记录里有 2 轮（10-26 13:29 / 10-31 20:26）因为 `ranked < 6` 直接放弃上传，
 * Worker 上留着的还是上一轮的旧 IP —— 用户体感就是"它好像不动了"。
 *
 * 设计约束（三条，全部可测）：
 *  1. **单调**：每一级的门槛都不比上一级更严（TTFB/抖动/门槛分/带宽地板逐级放松）；
 *  2. **收敛**：最多 [MAX_TIER] 级，最后一级是"只要能测通就要"，不会无中生有；
 *  3. **不改别的维度**：地区配额、权重、单条上限沿用用户设置 —— 放宽只针对"够不够格"，
 *     不针对"怎么排序"，否则同一轮会出现两套排序口径。
 */
object CfAdaptiveGate {
    /** 放宽级数（含 0 = 用户原始设置）。 */
    const val MAX_TIER: Int = 3

    /** 一级放宽后的 TTFB 上限下限（毫秒）；原版 `TTFB_MAX_CEIL = 2.5s`。 */
    const val RELAX_TTFB_MS: Long = 1_000
    const val RELAX_TTFB_MS_2: Long = 1_400
    const val RELAX_TTFB_MS_3: Long = 2_500

    /** 抖动上限的放宽档（原版 `TTFB_JITTER_MAX = 0.25s`；再上是"只要能测通"）。 */
    const val RELAX_JITTER_MS: Long = 250
    const val RELAX_JITTER_MS_2: Long = 350
    const val RELAX_JITTER_MS_3: Long = 2_000

    data class Step(val tier: Int, val limits: OptimizerLimits)

    /**
     * 由用户设置推导出的放宽阶梯：`[tier0=原样, tier1, tier2, tier3=只要能测通]`。
     *
     * 只放宽不收紧：若用户已经设了比某级更松的值，该级直接沿用用户值（不会把 1200ms 收回到 1000ms）。
     */
    fun plan(base: OptimizerLimits): List<Step> {
        val relaxed = listOf(
            Step(0, base),
            Step(
                1,
                base.copy(
                    maxTtfbMs = maxOf(base.maxTtfbMs, RELAX_TTFB_MS),
                    maxJitterMs = maxOf(base.maxJitterMs, RELAX_JITTER_MS),
                    minScore = base.minScore * 0.8,
                    minDownloadMbps = base.minDownloadMbps * 0.5,
                ),
            ),
            Step(
                2,
                base.copy(
                    maxTtfbMs = maxOf(base.maxTtfbMs, RELAX_TTFB_MS_2),
                    maxJitterMs = maxOf(base.maxJitterMs, RELAX_JITTER_MS_2),
                    minScore = base.minScore * 0.6,
                    minDownloadMbps = base.minDownloadMbps * 0.5,
                ),
            ),
            Step(
                3,
                base.copy(
                    maxTtfbMs = maxOf(base.maxTtfbMs, RELAX_TTFB_MS_3),
                    maxJitterMs = maxOf(base.maxJitterMs, RELAX_JITTER_MS_3),
                    minScore = 0.0,
                    minDownloadMbps = 0.0,
                    maxPerRegion = base.maxPerRegion,
                ),
            ),
        )

        // 去掉与上一级完全相同的步（用户本来就设得很松时，避免空跑一遍完整 rank）。
        val out = ArrayList<Step>(relaxed.size)
        var last: OptimizerLimits? = null
        for (step in relaxed) {
            if (last != null && step.limits == last) continue
            out += step
            last = step.limits
        }

        return out
    }

    /** 达到上传门槛了吗（原版 `SMART_PUSH_MIN_NODES`）。 */
    fun satisfied(ranked: Int, minUploadEntries: Int): Boolean = ranked >= minUploadEntries.coerceAtLeast(1)
}
