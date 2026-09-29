package com.github.kr328.clash.service.cfoptimizer.memory

import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerTuning

/**
 * 记忆库运行参数快照（原版 `cf_memory.py` 顶部常量的一一对应物）。
 *
 * 默认值 = 原版常量，单一来源在 [CfOptimizerTuning]（设置页的可调项委托到那里）。
 * 以"快照"形式传入而不是让评分函数去读设置：一轮内参数必须自洽 ——
 * 中途改设置不能让同一个库出现两套淘汰规则。
 */
data class CfMemoryTuning(
    /** 超过此天数未见且无历史成功 → 清除（原版 `STALE_DAYS`）。 */
    val staleDays: Double = CfOptimizerTuning.MEMORY_STALE_DAYS_DEFAULT,
    /** 超过此天数未见 → avg_score 开始衰减（原版 `DECAY_DAYS`）。 */
    val decayDays: Double = CfOptimizerTuning.MEMORY_DECAY_DAYS_DEFAULT,
    /** 连续失败超过此次数 → 移出优先候选（原版 `MAX_FAIL_STREAK`）。 */
    val maxFailStreak: Int = CfOptimizerTuning.MEMORY_MAX_FAIL_STREAK_DEFAULT,
    /** 库容量，超出按置信度淘汰（原版 `MEMORY_MAX_SIZE`）。 */
    val maxSize: Int = CfOptimizerTuning.MEMORY_MAX_SIZE_DEFAULT,
    /** 时段分桶数（原版 `HOUR_BUCKETS`，4 = 每 6 小时一段）。 */
    val hourBuckets: Int = CfOptimizerTuning.MEMORY_HOUR_BUCKETS_DEFAULT,
)
