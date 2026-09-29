package com.github.kr328.clash.service.cfoptimizer

import com.github.kr328.clash.service.cfoptimizer.quality.CfQualityGate

/**
 * 劣化判定回归装置 —— 期望值全部写死字面量（不从被测常量推导：常量改了这里就该打红）。
 *
 * 沙箱运行：
 *   /opt/kotlinc/bin/kotlinc service/src/main/java/com/github/kr328/clash/service/cfoptimizer/quality/CfQualityGate.kt \
 *     service/src/test/java/com/github/kr328/clash/service/cfoptimizer/CfQualityGateHarness.kt \
 *     -include-runtime -d /tmp/qualitygate.jar
 *   java -jar /tmp/qualitygate.jar
 */
private var checks = 0
private var failures = 0

private fun check(name: String, pass: Boolean, detail: String = "") {
    checks++
    if (!pass) {
        failures++
        println("FAIL: $name $detail")
    }
}

private fun main() {
    // 固定"现在"（epoch ms，写死不从时钟取）
    val now = 1_790_663_400_000L
    val hour = 3_600_000L

    // 1. 样本太少：空 / 3 个节点
    check("empty → too_few_proxies",
        CfQualityGate.shouldRun(emptyList(), 0, now) == CfQualityGate.Verdict(false, "too_few_proxies"))
    check("3 nodes → too_few_proxies",
        CfQualityGate.shouldRun(listOf(100, 200, 300), 0, now) == CfQualityGate.Verdict(false, "too_few_proxies"))

    // 2. 健康且从未跑过：10 个节点全部正常延迟
    check("healthy, never run → 不触发",
        CfQualityGate.shouldRun(List(10) { 100 + it * 20 }, 0, now) == CfQualityGate.Verdict(false, null))

    // 3. 新装 profile 还没测过：全部 0 → data_not_ready（不是劣化）
    check("all untested → data_not_ready",
        CfQualityGate.shouldRun(List(10) { 0 }, 0, now) == CfQualityGate.Verdict(false, "data_not_ready"))
    check("8 零 + 2 真实（2/10 < 3/10）→ data_not_ready",
        CfQualityGate.shouldRun(List(8) { 0 } + listOf(120, 150), 0, now) == CfQualityGate.Verdict(false, "data_not_ready"))

    // 4. 失联过半：6 零 + 4 真实（4/10 恰好达数据就绪线；6*2=12 > 10）
    check("6/10 失联 → failed_majority",
        CfQualityGate.shouldRun(List(6) { 0 } + listOf(120, 150, 180, 200), 0, now) == CfQualityGate.Verdict(true, "failed_majority"))

    // 5. 失联恰好一半：5 零 + 5 真实（5*2=10 > 10 不成立）→ 不触发
    check("5/10 失联（正好一半）→ 不触发",
        CfQualityGate.shouldRun(List(5) { 0 } + listOf(120, 150, 180, 200, 220), 0, now) == CfQualityGate.Verdict(false, null))

    // 6. 慢节点过半：全部 >800ms
    check("10/10 慢 → slow_majority",
        CfQualityGate.shouldRun(List(10) { 900 }, 0, now) == CfQualityGate.Verdict(true, "slow_majority"))

    // 7. 混合但都不过半：4 零 + 3 慢 + 3 正常
    check("混合不过半 → 不触发",
        CfQualityGate.shouldRun(List(4) { 0 } + List(3) { 900 } + List(3) { 150 }, 0, now) == CfQualityGate.Verdict(false, null))

    // 8. 节流：健康但 2 小时前刚跑过
    check("2h 前跑过 → throttled",
        CfQualityGate.shouldRun(List(10) { 150 }, now - 2 * hour, now) == CfQualityGate.Verdict(false, "throttled"))

    // 9. 恰好 6 小时：节流放行（< 6h 才拦）+ 劣化数据 → 触发
    check("恰好 6h + 失联过半 → 触发",
        CfQualityGate.shouldRun(List(6) { 0 } + listOf(120, 150, 180, 200), now - 6 * hour, now) == CfQualityGate.Verdict(true, "failed_majority"))

    // 10. 恰好 6 小时 + 健康 → 不触发
    check("恰好 6h + 健康 → 不触发",
        CfQualityGate.shouldRun(List(10) { 150 }, now - 6 * hour, now) == CfQualityGate.Verdict(false, null))

    println("TOTAL=$checks FAILURES=$failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
