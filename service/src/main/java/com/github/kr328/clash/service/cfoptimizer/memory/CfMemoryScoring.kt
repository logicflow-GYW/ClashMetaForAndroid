package com.github.kr328.clash.service.cfoptimizer.memory

import java.util.Calendar
import java.util.TimeZone

/**
 * 记忆库记录 —— 字段与原版 `cf_memory.py` 的 JSON schema **逐字段对齐**
 * （snake_case 键名见 [CfMemoryCodec]），因此导出的 `memory.json` 能被原版脚本直接读，
 * 原版脚本养出来的记忆库也能直接导入。
 *
 * [stageScores] 是「时段桶 → 最近若干次得分」：原版用它给同一 IP 在**当前时段**的历史表现
 * 加权（最多 +20%），这是「跑得越久越准」的来源，也是本模块存在的理由之一。
 */
data class MemoryRecord(
    val cc: String,
    val firstSeen: Long,
    val runs: Int,
    val passes: Int,
    val failStreak: Int,
    val lastSeen: Long,
    val lastScore: Double,
    val lastTtfbMs: Double,
    val lastMbps: Double,
    val lastRangeOk: Boolean,
    val avgScore: Double,
    val stageScores: Map<String, List<Double>>,
    val tcpHits: Int,
) {
    companion object {
        /** 新建记录的初值（与原版 `record_result` 里的 `db.get(key, {...})` 默认值一致）。 */
        fun empty(cc: String, nowSeconds: Long): MemoryRecord = MemoryRecord(
            cc = cc,
            firstSeen = nowSeconds,
            runs = 0,
            passes = 0,
            failStreak = 0,
            lastSeen = 0L,
            lastScore = 0.0,
            lastTtfbMs = 0.0,
            lastMbps = 0.0,
            lastRangeOk = false,
            avgScore = 0.0,
            stageScores = emptyMap(),
            tcpHits = 0,
        )
    }
}

/** 优先复测候选（置信度降序）。 */
data class PriorityCandidate(
    val address: String,
    val port: Int,
    val cc: String,
    val confidence: Double,
)

/**
 * 记忆库纯逻辑层：记录、置信度、优先级、淘汰。
 *
 * 无 Android 依赖 —— 可以在沙箱 JVM 里和原版 `cf_memory.py` 用**同一组输入**跑出
 * **同一组数值**，逐项对照（见 `CfOptimizerMemoryHarness`）。公式口径原样照搬，
 * 不做"优化"：任何偏离都会让两边的记忆库不再等价。
 */
object CfMemoryScoring {
    /**
     * 运行参数（超期/衰减/失败阈值/容量/分桶）由 [CfMemoryTuning] 传入，默认值 = 原版常量。
     * 可调项在设置页，取值范围与理由见 `CfOptimizerTuning`；这里不再存一份常量 ——
     * 两处各写一套迟早会漂移。
     */

    /** 每个时段桶最多保留的历史得分条数（与原版 `bucket_scores[-10:]` 一致）。 */
    const val BUCKET_SCORES_KEEP = 10

    /** 记录阶段：全量探测通过 / TCP 可连 / trace 命中 / 纯失败。 */
    const val STAGE_FULL = "full"
    const val STAGE_TCP = "tcp"
    const val STAGE_TRACE = "trace"
    const val STAGE_FAIL = "fail"

    private const val SECONDS_PER_DAY = 86_400.0

    /** 当前时间落在哪个时段桶（原版用 `time.localtime()`，即本地时区）。 */
    fun hourBucket(
        nowSeconds: Long,
        zone: TimeZone = TimeZone.getDefault(),
        tuning: CfMemoryTuning = CfMemoryTuning(),
    ): Int {
        val calendar = Calendar.getInstance(zone)

        calendar.timeInMillis = nowSeconds * 1000L

        return calendar.get(Calendar.HOUR_OF_DAY) / (24 / tuning.hourBuckets)
    }

    /**
     * 记录一次结果，返回更新后的记录（原版 `record_result` 的等价实现）。
     *
     * - [STAGE_FULL] + passed：runs/passes 递增、fail_streak 归零、EMA 更新 avg_score、
     *   当前时段桶追加得分（保留最近 [BUCKET_SCORES_KEEP] 条）
     * - [STAGE_TCP] / [STAGE_TRACE]：只记 tcp_hits 并把 fail_streak 归零
     *   （"能连上但没过门槛"与"完全连不上"要分开统计，否则置信度会被误伤）
     * - [STAGE_FAIL]：runs 递增、fail_streak 递增
     */
    fun recordResult(
        record: MemoryRecord?,
        cc: String,
        nowSeconds: Long,
        passed: Boolean,
        score: Double = 0.0,
        ttfbMs: Double = 0.0,
        mbps: Double = 0.0,
        rangeOk: Boolean = false,
        stage: String = STAGE_FULL,
        zone: TimeZone = TimeZone.getDefault(),
        tuning: CfMemoryTuning = CfMemoryTuning(),
    ): MemoryRecord {
        var rec = record ?: MemoryRecord.empty(cc, nowSeconds)

        if (cc.isNotEmpty()) {
            rec = rec.copy(cc = cc)
        }

        rec = rec.copy(lastSeen = nowSeconds)

        when {
            (stage == STAGE_FULL || stage == "ttfb") && passed -> {
                val bucket = hourBucket(nowSeconds, zone, tuning).toString()
                val bucketScores = (rec.stageScores[bucket] ?: emptyList()) + score

                rec = rec.copy(
                    runs = rec.runs + 1,
                    passes = rec.passes + 1,
                    failStreak = 0,
                    lastScore = score,
                    lastTtfbMs = ttfbMs,
                    lastMbps = mbps,
                    lastRangeOk = rangeOk,
                    avgScore = rec.avgScore * 0.6 + score * 0.4,
                    stageScores = rec.stageScores + (bucket to bucketScores.takeLast(BUCKET_SCORES_KEEP)),
                )
            }

            stage == STAGE_TCP || stage == STAGE_TRACE -> {
                rec = rec.copy(tcpHits = rec.tcpHits + 1, failStreak = 0)
            }

            stage == STAGE_FAIL -> {
                rec = rec.copy(runs = rec.runs + 1, failStreak = rec.failStreak + 1)
            }
        }

        return rec
    }

    /** 单条记录的置信度（原版 `get_priority_candidates` 内联公式的等价实现）。 */
    fun confidenceOf(
        record: MemoryRecord,
        nowSeconds: Long,
        bucket: Int,
        tuning: CfMemoryTuning = CfMemoryTuning(),
    ): Double {
        val daysAgo = (nowSeconds - record.lastSeen) / SECONDS_PER_DAY

        val passRate = record.passes.toDouble() / maxOf(record.runs, 1).toDouble()
        val freshness = maxOf(0.0, 1.0 - daysAgo / tuning.staleDays)
        val decayFactor =
            if (daysAgo > tuning.decayDays) {
                maxOf(0.3, 1.0 - (daysAgo - tuning.decayDays) / (tuning.staleDays - tuning.decayDays))
            } else {
                1.0
            }

        var confidence = record.avgScore * decayFactor * passRate * freshness

        val bucketScores = record.stageScores[bucket.toString()].orEmpty()
        if (bucketScores.isNotEmpty()) {
            val timeBonus = (bucketScores.sum() / bucketScores.size) / 100.0 * 0.2

            confidence *= (1.0 + timeBonus)
        }

        return confidence
    }

    /**
     * 优先复测候选（置信度降序取前 [topN]），原版 `get_priority_candidates` 的等价实现。
     *
     * 三道过滤：太久没见（> [CfMemoryTuning.staleDays]）、连续失败（≥ [CfMemoryTuning.maxFailStreak]）、
     * 从没成功且 TCP 命中不足 3 次。
     */
    fun priorityCandidates(
        db: Map<String, MemoryRecord>,
        nowSeconds: Long,
        topN: Int,
        zone: TimeZone = TimeZone.getDefault(),
        tuning: CfMemoryTuning = CfMemoryTuning(),
    ): List<PriorityCandidate> {
        val bucket = hourBucket(nowSeconds, zone, tuning)

        return db.mapNotNull { (key, record) ->
            val daysAgo = (nowSeconds - record.lastSeen) / SECONDS_PER_DAY

            if (daysAgo > tuning.staleDays || record.failStreak >= tuning.maxFailStreak) return@mapNotNull null
            if (record.passes == 0 && record.tcpHits < 3) return@mapNotNull null

            val split = key.lastIndexOf(':')
            if (split <= 0) return@mapNotNull null

            val address = key.substring(0, split)
            val port = key.substring(split + 1).toIntOrNull() ?: return@mapNotNull null

            PriorityCandidate(address, port, record.cc, confidenceOf(record, nowSeconds, bucket, tuning))
        }
            .sortedByDescending { it.confidence }
            .take(topN)
    }

    /**
     * 清除过期/低质量条目，返回删除条数（原版 `prune_stale` 的等价实现）。
     *
     * 三条规则：`staleDays` 未见且从无成功 / 连续失败 ≥2×`maxFailStreak` 次且 7 天未见 /
     * 只有 TCP 命中且 `staleDays`/2 未见。
     */
    fun pruneStale(
        db: MutableMap<String, MemoryRecord>,
        nowSeconds: Long,
        tuning: CfMemoryTuning = CfMemoryTuning(),
    ): Int {
        val doomed = db.filter { (_, record) ->
            val daysAgo = (nowSeconds - record.lastSeen) / SECONDS_PER_DAY
            val tcpOnly = record.passes == 0 && record.tcpHits > 0

            when {
                daysAgo > tuning.staleDays && record.passes == 0 -> true
                record.failStreak >= tuning.maxFailStreak * 2 && daysAgo > 7 -> true
                tcpOnly && daysAgo > tuning.staleDays / 2 -> true
                else -> false
            }
        }.keys

        doomed.forEach { db.remove(it) }

        return doomed.size
    }

    /**
     * 超上限时按置信度淘汰，返回淘汰条数（原版 `save_memory` 内联逻辑的等价实现）。
     * 排序键与原版一致：`avg_score × freshness × (passes > 0)`。
     */
    fun evictOverflow(
        db: MutableMap<String, MemoryRecord>,
        nowSeconds: Long,
        tuning: CfMemoryTuning = CfMemoryTuning(),
    ): Int {
        if (db.size <= tuning.maxSize) return 0

        val ranked = db.entries.sortedByDescending { (_, record) ->
            val daysAgo = (nowSeconds - record.lastSeen) / SECONDS_PER_DAY
            val freshness = maxOf(0.0, 1.0 - daysAgo / tuning.staleDays)

            record.avgScore * freshness * (if (record.passes > 0) 1.0 else 0.0)
        }

        val keep = ranked.take(tuning.maxSize).map { it.key }.toSet()
        val evicted = db.keys.filter { it !in keep }

        evicted.forEach { db.remove(it) }

        return evicted.size
    }
}
