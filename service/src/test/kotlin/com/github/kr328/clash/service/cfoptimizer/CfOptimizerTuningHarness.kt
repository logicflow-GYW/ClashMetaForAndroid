package com.github.kr328.clash.service.cfoptimizer

/**
 * CF 优选调参对象 — 可复现纯 Kotlin 测试 harness（同 CfOptimizerEngineHarness：
 * 无 JUnit，用 `main()` 跑全部断言）。
 *
 * **期望值全部写死字面量，不从被测常量推导** —— 默认值/边界被改动时这里应当打红，
 * 而不是跟着一起漂移（2026-09-26 的教训：期望值从被测常量推导 = 变异伪装成 PASS）。
 *
 * 沙箱复现命令：
 *   /opt/kotlinc/bin/kotlinc <CfOptimizerTuning.kt> <OptimizerModels.kt> <本文件> -d out
 *   java -cp out:<kotlin-stdlib.jar> com.github.kr328.clash.service.cfoptimizer.CfOptimizerTuningHarnessKt
 */
import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerTuning
import com.github.kr328.clash.service.cfoptimizer.memory.CfMemoryTuning

// 助手声明为 file-private：与 CfOptimizerMemoryHarness 同款，避免同包多个装置顶层符号打架。
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

fun runCfOptimizerTuningHarness() {
    // ---- 1. 默认值（字面量；等于移植时的原版/实测值）----
    check("sourcesPerRun 默认 12", CfOptimizerTuning.sourcesPerRun(null) == 12)
    check("perSourceSample 默认 1000", CfOptimizerTuning.perSourceSample(null) == 1000)
    check("maxCandidates 默认 1200", CfOptimizerTuning.maxCandidates(null) == 1200)
    check("minUploadEntries 默认 6", CfOptimizerTuning.minUploadEntries(null) == 6)
    check("maxPerRegion 默认 4", CfOptimizerTuning.maxPerRegion(null) == 4)
    check("tcpConcurrency 默认 500", CfOptimizerTuning.tcpConcurrency(null) == 500)
    check("tcpTimeoutMs 默认 900", CfOptimizerTuning.tcpTimeoutMs(null) == 900)
    check("probeConcurrency 默认 50", CfOptimizerTuning.probeConcurrency(null) == 50)
    check("traceConcurrency 默认 100", CfOptimizerTuning.traceConcurrency(null) == 100)
    check("ttfbSamples 默认 3", CfOptimizerTuning.ttfbSamples(null) == 3)
    check("downloadPoolLimit 默认 60", CfOptimizerTuning.downloadPoolLimit(null) == 60)
    check("downloadConcurrency 默认 8", CfOptimizerTuning.downloadConcurrency(null) == 8)
    check("downloadSizeMb 默认 6", CfOptimizerTuning.downloadSizeMb(null) == 6)
    check("downloadTimeoutMs 默认 5000", CfOptimizerTuning.downloadTimeoutMs(null) == 5000)
    check("downloadEarlyStopMbps 默认 80.0", CfOptimizerTuning.downloadEarlyStopMbps(null) == 80.0)
    check("minDownloadMbps 默认 1.0", CfOptimizerTuning.minDownloadMbps(null) == 1.0)
    check("scoreBwRefMbps 默认 50.0", CfOptimizerTuning.scoreBwRefMbps(null) == 50.0)
    check("maxTtfbMs 默认 800", CfOptimizerTuning.maxTtfbMs(null) == 800)
    check("maxJitterMs 默认 200", CfOptimizerTuning.maxJitterMs(null) == 200)
    check("minScore 默认 30.0", CfOptimizerTuning.minScore(null) == 30.0)
    check("maxEntries 默认 12", CfOptimizerTuning.maxEntries(null) == 12)
    check("rawPoolFactor 默认 2", CfOptimizerTuning.rawPoolFactor(null) == 2)
    check("dedupPrefixV4 默认 24", CfOptimizerTuning.dedupPrefixV4(null) == 24)
    check("memoryStaleDays 默认 14.0", CfOptimizerTuning.memoryStaleDays(null) == 14.0)
    check("memoryDecayDays 默认 7.0", CfOptimizerTuning.memoryDecayDays(null) == 7.0)
    check("memoryMaxFailStreak 默认 3", CfOptimizerTuning.memoryMaxFailStreak(null) == 3)
    check("memoryMaxSize 默认 5000", CfOptimizerTuning.memoryMaxSize(null) == 5000)
    check("memoryHourBuckets 默认 4", CfOptimizerTuning.memoryHourBuckets(null) == 4)
    check("memoryPoolLimit 默认 100", CfOptimizerTuning.memoryPoolLimit(null) == 100)
    check("minIntervalHours 默认 6", CfOptimizerTuning.minIntervalHours(null) == 6)

    // ---- 2. 空串/空白/非法值 → 回落默认（设置页允许清空，清空 = 恢复默认）----
    check("空串 → 默认", CfOptimizerTuning.tcpConcurrency("") == 500)
    check("空白串 → 默认", CfOptimizerTuning.tcpConcurrency("   ") == 500)
    check("非法串 → 默认", CfOptimizerTuning.maxCandidates("abc") == 1200)
    check("带空格的合法数字可用", CfOptimizerTuning.maxCandidates(" 900 ") == 900)
    check("小数填进整数项 → 默认（不四舍五入）", CfOptimizerTuning.ttfbSamples("3.5") == 3)
    check("NaN → 默认", CfOptimizerTuning.minScore("NaN") == 30.0)
    check("Infinity → 默认", CfOptimizerTuning.downloadEarlyStopMbps("Infinity") == 80.0)
    check("非法串 → 默认（带宽参考值）", CfOptimizerTuning.scoreBwRefMbps("abc") == 50.0)
    check("非法串 → 默认（地区解析并发）", CfOptimizerTuning.traceConcurrency("abc") == 100)

    // ---- 3. 范围内照用、越界夹取（不是"推荐区间"）----
    check("tcpConcurrency 500 照用", CfOptimizerTuning.tcpConcurrency("500") == 500)
    check("tcpConcurrency 5 → 夹到 10", CfOptimizerTuning.tcpConcurrency("5") == 10)
    check("tcpConcurrency 99999 → 夹到 2000", CfOptimizerTuning.tcpConcurrency("99999") == 2000)
    check("tcpTimeoutMs 100 → 夹到 200", CfOptimizerTuning.tcpTimeoutMs("100") == 200)
    check("ttfbSamples 9 → 夹到 5", CfOptimizerTuning.ttfbSamples("9") == 5)
    check("dedupPrefixV4 4 → 夹到 8", CfOptimizerTuning.dedupPrefixV4("4") == 8)
    check("dedupPrefixV4 64 → 夹到 32", CfOptimizerTuning.dedupPrefixV4("64") == 32)
    check("rawPoolFactor 0 → 夹到 1", CfOptimizerTuning.rawPoolFactor("0") == 1)
    check("rawPoolFactor 50 → 夹到 10", CfOptimizerTuning.rawPoolFactor("50") == 10)
    check("memoryPoolLimit 0 合法（= 不复测历史）", CfOptimizerTuning.memoryPoolLimit("0") == 0)
    check("memoryPoolLimit -3 → 夹到 0", CfOptimizerTuning.memoryPoolLimit("-3") == 0)
    check("minIntervalHours 0 合法（= 不节流）", CfOptimizerTuning.minIntervalHours("0") == 0)
    check("minIntervalHours -1 → 夹到 0", CfOptimizerTuning.minIntervalHours("-1") == 0)
    check("minIntervalHours 1000 → 夹到 168", CfOptimizerTuning.minIntervalHours("1000") == 168)
    check("minIntervalHours 非法 → 默认", CfOptimizerTuning.minIntervalHours("abc") == 6)
    check("maxPerRegion 0 合法（= 不限地区）", CfOptimizerTuning.maxPerRegion("0") == 0)
    check("maxPerRegion 999 → 夹到 50", CfOptimizerTuning.maxPerRegion("999") == 50)
    check("downloadEarlyStopMbps 0.1 → 夹到 1.0", CfOptimizerTuning.downloadEarlyStopMbps("0.1") == 1.0)
    check("minScore 200 → 夹到 100.0", CfOptimizerTuning.minScore("200") == 100.0)
    check("minScore -5 → 夹到 0.0", CfOptimizerTuning.minScore("-5") == 0.0)
    check("traceConcurrency 0 → 夹到 1", CfOptimizerTuning.traceConcurrency("0") == 1)
    check("traceConcurrency 9999 → 夹到 500", CfOptimizerTuning.traceConcurrency("9999") == 500)
    check("minDownloadMbps 0 合法（= 不启用下限）", CfOptimizerTuning.minDownloadMbps("0") == 0.0)
    check("minDownloadMbps 200 → 夹到 100.0", CfOptimizerTuning.minDownloadMbps("200") == 100.0)
    check("scoreBwRefMbps 0 → 夹到 1.0", CfOptimizerTuning.scoreBwRefMbps("0") == 1.0)
    check("scoreBwRefMbps 5000 → 夹到 1000.0", CfOptimizerTuning.scoreBwRefMbps("5000") == 1000.0)

    // ---- 4. 门槛分随下载测速开关折半（满分 60 时门槛不能照抄 0–100 量纲）----
    check("开测速：30 分门槛 = 30.0", CfOptimizerTuning.effectiveMinScore("30", true) == 30.0)
    check("关测速：30 分门槛 → 15.0", CfOptimizerTuning.effectiveMinScore("30", false) == 15.0)
    check("关测速：100 分门槛 → 50.0", CfOptimizerTuning.effectiveMinScore("100", false) == 50.0)
    check("关测速：默认门槛 → 15.0", CfOptimizerTuning.effectiveMinScore(null, false) == 15.0)

    // ---- 4b. 带宽下限随下载测速开关失效（关测速时全池 downloadMbps 都是 0，
    //          门槛照用会把名单清空 —— 与门槛分折半同一个理由）----
    check("开测速：默认下限 = 1.0", CfOptimizerTuning.effectiveMinDownloadMbps(null, true) == 1.0)
    check("开测速：用户填 5 照用", CfOptimizerTuning.effectiveMinDownloadMbps("5", true) == 5.0)
    check("关测速：默认下限 → 0.0", CfOptimizerTuning.effectiveMinDownloadMbps(null, false) == 0.0)
    check("关测速：用户填 5 → 0.0（否则名单全空）",
        CfOptimizerTuning.effectiveMinDownloadMbps("5", false) == 0.0)

    // ---- 5. 评分权重（原版 0.6 / 0.4；两项都为 0 不能把用户关在门外）----
    val wDefault = CfOptimizerTuning.scoreWeights(null, null)
    check("权重默认 0.6 / 0.4", wDefault.ttfb == 0.6 && wDefault.bw == 0.4)
    val wZero = CfOptimizerTuning.scoreWeights("0", "0")
    check("两项都填 0 → 回落 0.6 / 0.4", wZero.ttfb == 0.6 && wZero.bw == 0.4)
    val wCustom = CfOptimizerTuning.scoreWeights("0.8", "0.2")
    check("自定义 0.8 / 0.2 照用", wCustom.ttfb == 0.8 && wCustom.bw == 0.2)
    check("权重和 = 1 时归一化不变",
        wCustom.normalized.ttfb == 0.8 && wCustom.normalized.bw == 0.2)
    // 单项先夹取到 [0,1]，再按和归一化：3 / 1 → 1.0 / 1.0 → 0.5 / 0.5（不是 0.75 / 0.25）
    val wClamped = CfOptimizerTuning.scoreWeights("3", "1")
    check("3 / 1 夹取后归一化 → 0.5 / 0.5",
        wClamped.normalized.ttfb == 0.5 && wClamped.normalized.bw == 0.5)
    // 真正需要归一化的用例：两项都在范围内但和 ≠ 1
    val wSum12 = CfOptimizerTuning.scoreWeights("0.8", "0.4")
    check("0.8 / 0.4 归一化 → 2/3 与 1/3",
        near(wSum12.normalized.ttfb, 2.0 / 3.0) && near(wSum12.normalized.bw, 1.0 / 3.0))
    check("越界权重被夹取（2 → 1.0）", CfOptimizerTuning.scoreWeights("2", "1").ttfb == 1.0)
    check("非法权重 → 默认", CfOptimizerTuning.scoreWeights("x", "y").ttfb == 0.6)

    // ---- 6. 记忆库参数快照：默认值与解析结果一致 ----
    val tuningDefault = CfMemoryTuning()
    check("记忆快照 staleDays 默认 14.0", tuningDefault.staleDays == 14.0)
    check("记忆快照 decayDays 默认 7.0", tuningDefault.decayDays == 7.0)
    check("记忆快照 maxFailStreak 默认 3", tuningDefault.maxFailStreak == 3)
    check("记忆快照 maxSize 默认 5000", tuningDefault.maxSize == 5000)
    check("记忆快照 hourBuckets 默认 4", tuningDefault.hourBuckets == 4)
    check("衰减起点 < 超期天数（否则衰减规则永不触发）",
        CfOptimizerTuning.MEMORY_DECAY_DAYS_DEFAULT < CfOptimizerTuning.MEMORY_STALE_DAYS_DEFAULT)

    println("TOTAL=$total FAILURES=$failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
