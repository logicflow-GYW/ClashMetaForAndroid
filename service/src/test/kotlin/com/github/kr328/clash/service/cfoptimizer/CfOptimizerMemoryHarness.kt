package com.github.kr328.clash.service.cfoptimizer

import com.github.kr328.clash.service.cfoptimizer.memory.CfMemoryScoring
import com.github.kr328.clash.service.cfoptimizer.memory.MemoryRecord
import java.util.TimeZone

/**
 * CF 记忆库 — 可复现纯 Kotlin 回归装置（与 [CfOptimizerEngineHarness] 同款：仓库 service 模块
 * 无 JUnit 测试设施，本文件用 `main()` 跑全部断言，**期望值全部写死字面量**，
 * 不从被测常量推导 —— 否则改常量时期望值会跟着变，变异会伪装成 PASS）。
 *
 * 夹具与 `cf-memory-parity/fixture.csv` 同源（43 次操作），期望值取自**原版 `cf_memory.py`
 * 在同一夹具、同一 NOW、同一 +08:00 时区下的实测输出**（两侧输出逐字节相同，见 README
 * 「公式口径的可复核性」）。覆盖：EMA、时段桶（含只保留最近 10 条）、新鲜度衰减、
 * 过期与冷却、TCP 命中阈值边界、淘汰三条规则。
 *
 * 沙箱复现命令：
 *   /opt/kotlinc/bin/kotlinc <CfMemoryScoring.kt> <本文件> -include-runtime -d mem.jar
 *   java -jar mem.jar
 */
private val ZONE: TimeZone = TimeZone.getTimeZone("GMT+08:00")
private const val NOW: Long = 1_790_663_400L

private var failures = 0
private var total = 0

private fun check(name: String, cond: Boolean) {
    total++

    if (!cond) {
        failures++
        println("FAIL: $name")
    }
}

private fun near(a: Double, b: Double, eps: Double = 1e-9) = kotlin.math.abs(a - b) <= eps

/** 夹具的一次操作：`key, cc, passed, score, ttfb, mbps, rangeOk, stage, daysAgo`。 */
private data class Op(
    val key: String,
    val cc: String,
    val passed: Boolean,
    val score: Double,
    val ttfb: Double,
    val mbps: Double,
    val rangeOk: Boolean,
    val stage: String,
    val daysAgo: Double,
)

private fun full(key: String, cc: String, score: Double, ttfb: Double, mbps: Double, daysAgo: Double) =
    Op(key, cc, true, score, ttfb, mbps, true, CfMemoryScoring.STAGE_FULL, daysAgo)

private fun fixture(): List<Op> {
    val ops = mutableListOf<Op>()

    // 同一时段（bucket 2）连续 5 次成功 —— EMA 与时段桶累计
    listOf(60.0, 70.0, 80.0, 90.0, 100.0).forEachIndexed { index, score ->
        ops += full("1.1.1.1:443", "US", score, 120.0 + index, 5.0 + index, 0.02)
    }

    // 12 次成功 —— 桶内只保留最近 10 条
    repeat(12) { index ->
        ops += full("2.2.2.2:443", "DE", 10.0 * (index + 1), 100.0, 3.0, 0.05)
    }

    // 12 天前成功 —— 新鲜度衰减 + decay 下限 0.3
    ops += full("3.3.3.3:443", "JP", 50.0, 200.0, 4.0, 12.0)

    // 20 天前成功 —— 超过 STALE_DAYS，移出优先候选
    ops += full("4.4.4.4:443", "SG", 90.0, 150.0, 8.0, 20.0)

    // 连续失败 3 次 —— fail_streak 达阈值
    listOf(0.10, 0.09, 0.08).forEach { ops += Op("5.5.5.5:443", "KR", false, 0.0, 0.0, 0.0, false, CfMemoryScoring.STAGE_FAIL, it) }

    // 纯 TCP 命中 2 次 / 3 次 —— 阈值边界两侧
    listOf(0.10, 0.09).forEach { ops += Op("6.6.6.6:443", "HK", false, 0.0, 0.0, 0.0, false, CfMemoryScoring.STAGE_TCP, it) }
    listOf(0.10, 0.09, 0.08).forEach { ops += Op("7.7.7.7:443", "TW", false, 0.0, 0.0, 0.0, false, CfMemoryScoring.STAGE_TCP, it) }

    // 失败两次后成功 —— fail_streak 归零
    ops += Op("8.8.8.8:443", "BR", false, 0.0, 0.0, 0.0, false, CfMemoryScoring.STAGE_FAIL, 5.0)
    ops += Op("8.8.8.8:443", "BR", false, 0.0, 0.0, 0.0, false, CfMemoryScoring.STAGE_FAIL, 4.9)
    ops += full("8.8.8.8:443", "BR", 70.0, 180.0, 6.0, 0.05)

    // 只失败一次 —— passes==0 且 tcp_hits==0
    ops += Op("9.9.9.9:443", "IN", false, 0.0, 0.0, 0.0, false, CfMemoryScoring.STAGE_FAIL, 0.10)

    // ttfb 阶段也算通过 + 另一次落在不同时段桶（无时段加成）
    ops += Op("10.10.10.10:443", "AU", true, 55.0, 210.0, 2.0, true, "ttfb", 0.03)
    ops += full("10.10.10.10:443", "AU", 65.0, 190.0, 2.5, 3.3)

    // 连续失败 6 次且 8 天未见 —— 淘汰规则二
    listOf(9.0, 8.9, 8.8, 8.7, 8.6, 8.5).forEach { ops += Op("12.12.12.12:443", "FR", false, 0.0, 0.0, 0.0, false, CfMemoryScoring.STAGE_FAIL, it) }

    // 只有 TCP 命中且 8 天未见 —— 淘汰规则三
    listOf(8.2, 8.1, 8.0).forEach { ops += Op("13.13.13.13:443", "CA", false, 0.0, 0.0, 0.0, false, CfMemoryScoring.STAGE_TCP, it) }

    // 20 天未见且从无成功 —— 淘汰规则一
    ops += Op("14.14.14.14:443", "NL", false, 0.0, 0.0, 0.0, false, CfMemoryScoring.STAGE_FAIL, 20.0)

    return ops
}

private fun replay(): LinkedHashMap<String, MemoryRecord> {
    val db = LinkedHashMap<String, MemoryRecord>()

    fixture().forEach { op ->
        db[op.key] = CfMemoryScoring.recordResult(
            record = db[op.key],
            cc = op.cc,
            nowSeconds = NOW - Math.round(op.daysAgo * 86_400.0),
            passed = op.passed,
            score = op.score,
            ttfbMs = op.ttfb,
            mbps = op.mbps,
            rangeOk = op.rangeOk,
            stage = op.stage,
            zone = ZONE,
        )
    }

    return db
}

fun main() {
    val db = replay()

    // ---- 1. 记录字段（期望值 = 原版 cf_memory.py 在同一夹具下的实测输出）----
    check("record count = 13", db.size == 13)

    val first = db.getValue("1.1.1.1:443")
    check("1.1.1.1 runs=5", first.runs == 5)
    check("1.1.1.1 passes=5", first.passes == 5)
    check("1.1.1.1 fail_streak=0", first.failStreak == 0)
    check("1.1.1.1 avg_score=82.2784 (EMA)", near(first.avgScore, 82.2784))
    check("1.1.1.1 bucket2=[60,70,80,90,100]", first.stageScores["2"] == listOf(60.0, 70.0, 80.0, 90.0, 100.0))
    check("1.1.1.1 last_seen=t0+0.02d", first.lastSeen == NOW - Math.round(0.02 * 86_400.0))

    val second = db.getValue("2.2.2.2:443")
    check("2.2.2.2 runs=12", second.runs == 12)
    check("2.2.2.2 avg_score=105.0326517", near(second.avgScore, 105.0326517, 1e-6))
    check("2.2.2.2 bucket 只留最近 10 条", second.stageScores["2"]?.size == 10)
    check("2.2.2.2 bucket 首尾 = 30..120",
        second.stageScores["2"]?.first() == 30.0 && second.stageScores["2"]?.last() == 120.0)

    val stale = db.getValue("3.3.3.3:443")
    check("3.3.3.3 avg_score=20（单次成功 EMA）", near(stale.avgScore, 20.0))

    val old = db.getValue("4.4.4.4:443")
    check("4.4.4.4 avg_score=36", near(old.avgScore, 36.0))

    check("5.5.5.5 fail_streak=3", db.getValue("5.5.5.5:443").failStreak == 3)
    check("6.6.6.6 tcp_hits=2（阈值下）", db.getValue("6.6.6.6:443").tcpHits == 2)
    check("7.7.7.7 tcp_hits=3（阈值上）", db.getValue("7.7.7.7:443").tcpHits == 3)

    val recovered = db.getValue("8.8.8.8:443")
    check("8.8.8.8 runs=3 passes=1", recovered.runs == 3 && recovered.passes == 1)
    check("8.8.8.8 fail_streak 归零", recovered.failStreak == 0)
    check("8.8.8.8 avg_score=28", near(recovered.avgScore, 28.0))

    val crossBucket = db.getValue("10.10.10.10:443")
    check("10.10.10.10 avg_score=39.2", near(crossBucket.avgScore, 39.2))
    check("10.10.10.10 两个时段桶 1:[65] 2:[55]",
        crossBucket.stageScores["1"] == listOf(65.0) && crossBucket.stageScores["2"] == listOf(55.0))

    check("12.12.12.12 fail_streak=6", db.getValue("12.12.12.12:443").failStreak == 6)
    check("13.13.13.13 tcp_hits=3", db.getValue("13.13.13.13:443").tcpHits == 3)

    // ---- 2. 优先候选（顺序也必须是这个顺序）----
    val expected = listOf(
        Triple("2.2.2.2", "DE", 120.3561654),
        Triple("1.1.1.1", "US", 95.30659694),
        Triple("10.10.10.10", "AU", 33.2556),
        Triple("8.8.8.8", "BR", 10.602),
        Triple("3.3.3.3", "JP", 0.9428571429),
        Triple("7.7.7.7", "TW", 0.0),
        Triple("13.13.13.13", "CA", 0.0),
    )

    val priorities = CfMemoryScoring.priorityCandidates(db, NOW, 100, ZONE)

    check("priority count = 7", priorities.size == expected.size)

    expected.forEachIndexed { index, (address, cc, confidence) ->
        val actual = priorities.getOrNull(index)

        check("priority[$index] = $address/$cc",
            actual != null && actual.address == address && actual.cc == cc && near(actual.confidence, confidence, 1e-6))
    }

    check("5.5.5.5 被移出（fail_streak 达阈值）", priorities.none { it.address == "5.5.5.5" })
    check("6.6.6.6 被移出（tcp_hits<3 且从无成功）", priorities.none { it.address == "6.6.6.6" })
    check("4.4.4.4 被移出（超过 STALE_DAYS）", priorities.none { it.address == "4.4.4.4" })
    check("9.9.9.9 被移出（只失败一次）", priorities.none { it.address == "9.9.9.9" })

    // ---- 3. 淘汰（三条规则）----
    val mutable = LinkedHashMap(db)
    val pruned = CfMemoryScoring.pruneStale(mutable, NOW)

    check("prune count = 3", pruned == 3)
    check("被清除的是 12/13/14",
        setOf("12.12.12.12:443", "13.13.13.13:443", "14.14.14.14:443").all { it !in mutable })
    check("存活 10 条", mutable.size == 10)
    check("存活集合 = 原版实测集合",
        mutable.keys.sorted() == listOf(
            "1.1.1.1:443", "10.10.10.10:443", "2.2.2.2:443", "3.3.3.3:443", "4.4.4.4:443",
            "5.5.5.5:443", "6.6.6.6:443", "7.7.7.7:443", "8.8.8.8:443", "9.9.9.9:443",
        ))

    // ---- 4. 超上限淘汰（按 avg_score × freshness × (passes>0)）----
    val overflow = LinkedHashMap<String, MemoryRecord>()

    // 容量字面量 5000（默认值由 CfOptimizerTuningHarness 断言），不从常量推导。
    repeat(5000 + 3) { index ->
        overflow["10.0.$index:443"] = MemoryRecord(
            cc = "US", firstSeen = NOW, runs = 1, passes = if (index == 0) 0 else 1, failStreak = 0,
            lastSeen = NOW, lastScore = index.toDouble(), lastTtfbMs = 100.0, lastMbps = 1.0,
            lastRangeOk = true, avgScore = index.toDouble(), stageScores = emptyMap(), tcpHits = 0,
        )
    }

    check("超上限淘汰 3 条", CfMemoryScoring.evictOverflow(overflow, NOW) == 3)
    check("淘汰后正好到上限", overflow.size == 5000)
    check("无成功记录者优先被淘汰", "10.0.0:443" !in overflow)

    println("================================")
    println("CF 记忆库回归装置：$total 项断言，$failures 项失败")
    println("（期望值取自原版 cf_memory.py 在同一夹具下的实测输出）")
    println("================================")

    if (failures > 0) {
        throw AssertionError("$failures / $total 项断言失败")
    }
}
