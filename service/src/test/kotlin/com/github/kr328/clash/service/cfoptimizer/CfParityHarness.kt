package com.github.kr328.clash.service.cfoptimizer

import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerTuning
import com.github.kr328.clash.service.cfoptimizer.source.CfCidrScan
import kotlin.random.Random

/**
 * 对齐蓝本（2026-11-16 批）新增能力 — 纯逻辑回归装置。
 *
 * 覆盖四块新逻辑：官方网段盲扫 [CfCidrScan]、门槛自适应 [CfAdaptiveGate]、名单合并 [CfEntryMerge]、
 * 记忆融合排序 [CfOptimizerEngine.fusedScoreOf]。期望值全部写死字面量，不从被测常量推导
 * （否则改常量时期望值跟着变，变异会伪装成 PASS）。
 *
 * 沙箱复现：
 *   kotlinc -no-stdlib -cp kotlin-stdlib.jar <4 个 main 文件> <本文件> -d out
 *   java -cp out:kotlin-stdlib.jar CfParityHarnessKt
 */
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

private fun entry(address: String, port: Int, score: Double, ttfb: Long = 300, mbps: Double = 10.0) =
    OptimizedEntry(address, port, "US", ttfb, 0, mbps, score)

fun runCfParityHarness() {
    // ---- 1. 网段解析 ----
    check("addressToLong 1.2.3.4 = 16909060", CfCidrScan.addressToLong("1.2.3.4") == 16_909_060L)
    check("addressToLong 拒绝前导零", CfCidrScan.addressToLong("01.2.3.4") == null)
    check("addressToLong 拒绝五段", CfCidrScan.addressToLong("1.2.3.4.5") == null)
    check("longToAddress 往返", CfCidrScan.longToAddress(16_909_060L) == "1.2.3.4")

    val net = CfCidrScan.parseCidr("104.16.0.0/13")
    check("parseCidr start", net?.start == 1_745_879_040L)
    check("parseCidr end", net?.end == 1_746_403_327L)
    check("parseCidr size = 524288", net?.size == 524_288L)
    check("parseCidr 非法前缀返回 null", CfCidrScan.parseCidr("104.16.0.0/33") == null)
    check("parseCidr 缺斜杠返回 null", CfCidrScan.parseCidr("104.16.0.0") == null)

    // ---- 2. 盲扫生成 ----
    val generated = CfCidrScan.generate(200, random = Random(7))
    check("generate 数量 = 200", generated.size == 200)
    check("generate 全部唯一", generated.distinctBy { it.address to it.port }.size == 200)
    check("generate 端口固定 443", generated.all { it.port == 443 })
    check("generate 来源标记 scan", generated.all { it.source == "scan" })
    check("generate 全部落在官方网段内", generated.all { candidate ->
        val value = CfCidrScan.addressToLong(candidate.address)!!
        CfCidrScan.CF_IPV4_CIDRS.any { cidr ->
            val range = CfCidrScan.parseCidr(cidr)!!
            value in range.start..range.end
        }
    })
    check("generate 不产生网络/广播地址", generated.none { candidate ->
        val value = CfCidrScan.addressToLong(candidate.address)!!
        CfCidrScan.CF_IPV4_CIDRS.any { cidr ->
            val range = CfCidrScan.parseCidr(cidr)!!
            value == range.start || value == range.end
        }
    })
    check("generate(0) 为空", CfCidrScan.generate(0).isEmpty())
    check("generate 同种子可复现",
        CfCidrScan.generate(20, random = Random(11)).map { it.address } ==
            CfCidrScan.generate(20, random = Random(11)).map { it.address })

    // ---- 3. 网段权重 ----
    // 15 个网段、无历史 → 权重 = 各自地址数（/13 = 524288 那一项在 index 11）
    val baseWeights = CfCidrScan.weights(emptyList())
    check("权重数量与网段数一致", baseWeights.size == CfCidrScan.CF_IPV4_CIDRS.size)
    check("无历史时 /13 权重 = 524288", near(baseWeights[11], 524_288.0))

    // 同一网段 6 条历史、3 条通过 → 104.16/13 权重 = 524288 × (0.2 + 0.8×0.5) = 314572.8
    val history = (0 until 6).map { index ->
        CfCidrScan.History(
            address = "104.16.0.${index + 1}",
            passes = if (index < 3) 1 else 0,
            runs = 2,
            hasCurrentBucketScore = false,
        )
    }
    val weighted = CfCidrScan.weights(history)
    check("6 条历史 3 条通过 → 权重 ×0.6", near(weighted[11], 314_572.8))
    check("其它网段权重不变", near(weighted[0], 4096.0))

    // 记录不足 5 条时不调整（原版 `total > 5` 才生效）
    val few = CfCidrScan.weights(
        listOf(CfCidrScan.History("104.16.0.1", 0, 1, false)),
    )
    check("历史 ≤5 条不改权重", near(few[11], 524_288.0))

    // ---- 4. 门槛自适应 ----
    val base = OptimizerLimits(
        maxEntries = 12,
        maxTtfbMs = 800,
        maxJitterMs = 200,
        minScore = 30.0,
        minDownloadMbps = 2.0,
        maxPerRegion = 4,
    )
    val plan = CfAdaptiveGate.plan(base)

    check("自适应阶梯 = 4 级", plan.size == 4)
    check("第 0 级就是用户设置", plan[0].limits == base)
    check("阶梯按级递增", plan.map { it.tier } == listOf(0, 1, 2, 3))
    check("TTFB 上限单调不减", plan.map { it.limits.maxTtfbMs }.zipWithNext().all { (a, b) -> b >= a })
    check("抖动上限单调不减", plan.map { it.limits.maxJitterMs }.zipWithNext().all { (a, b) -> b >= a })
    check("门槛分单调不增", plan.map { it.limits.minScore }.zipWithNext().all { (a, b) -> b <= a })
    check("带宽地板单调不增", plan.map { it.limits.minDownloadMbps }.zipWithNext().all { (a, b) -> b <= a })
    check("末级门槛分为 0", near(plan.last().limits.minScore, 0.0))
    check("末级 TTFB 上限 2500", plan.last().limits.maxTtfbMs == 2_500L)
    check("末级带宽地板为 0", near(plan.last().limits.minDownloadMbps, 0.0))
    check("地区配额全程沿用用户值", plan.all { it.limits.maxPerRegion == 4 })
    check("单条上限全程沿用用户值", plan.all { it.limits.maxEntries == 12 })

    // 用户已经设得更松时不收紧、且完全相同级会被去掉
    val loose = base.copy(maxTtfbMs = 5_000, maxJitterMs = 3_000, minScore = 0.0, minDownloadMbps = 0.0)
    check("已最松时阶梯收敛为 1 级", CfAdaptiveGate.plan(loose).size == 1)

    check("satisfied(3,3)", CfAdaptiveGate.satisfied(3, 3))
    check("satisfied(2,3) 为假", !CfAdaptiveGate.satisfied(2, 3))
    check("门槛 0 按 1 处理", CfAdaptiveGate.satisfied(1, 0))

    // ---- 5. 名单合并 ----
    check("prefixKey /24", CfEntryMerge.prefixKey("1.2.3.4", 24) == "1.2.3")
    check("prefixKey /16", CfEntryMerge.prefixKey("1.2.3.4", 16) == "1.2")
    check("prefixKey /32 保留全地址", CfEntryMerge.prefixKey("1.2.3.4", 32) == "1.2.3.4")
    check("prefixKey IPv6 取 3 段", CfEntryMerge.prefixKey("2606:4700:10::1", 24) == "2606:4700:10")
    check("prefixKey 非 IP 返回 null", CfEntryMerge.prefixKey("not-an-ip", 24) == null)

    val current = listOf(entry("1.2.3.4", 443, 60.0), entry("5.6.7.8", 443, 50.0))
    val previous = listOf(
        entry("9.9.9.9", 443, 0.0),      // 新网段 → 补进来
        entry("1.2.3.99", 443, 0.0),     // 同 /24 → 冲突，跳过
        entry("5.6.7.8", 8443, 0.0),     // 同地址不同端口 → 允许
        entry("7.7.7.7", 443, 0.0),      // 新网段 → 补进来
        entry("8.8.8.8", 443, 0.0),      // 超过 maxExtra=3 → 不补
    )
    val merged = CfEntryMerge.merge(current, previous, maxExtra = 3, prefixLength = 24)
    check("合并后条数 = 2 + 3", merged.size == 5)
    check("本轮结果排在前", merged.take(2).map { it.address } == listOf("1.2.3.4", "5.6.7.8"))
    check("同 /24 旧节点被跳过", merged.none { it.address == "1.2.3.99" })
    // 同一 IP 的另一端口仍落在同一 /24 —— 原版 `cidr_seen` 语义下同样被去重（不是按 address:port 判）
    check("同地址不同端口仍受 /24 去重", merged.none { it.address == "5.6.7.8" && it.port == 8443 })
    check("补满 maxExtra（3 条）后停止", merged.count { it.address !in setOf("1.2.3.4", "5.6.7.8") } == 3)
    check("maxExtra=0 原样返回", CfEntryMerge.merge(current, previous, 0, 24) == current)

    // ---- 6. 记忆融合 ----
    val ranked = entry("1.1.1.1", 443, 40.0)
    check("融合：40×0.6 + 100×0.4 = 64", near(CfOptimizerEngine.fusedScoreOf(ranked, mapOf("1.1.1.1:443" to 100.0), 0.4), 64.0))
    check("无记忆记录 → 本轮分", near(CfOptimizerEngine.fusedScoreOf(ranked, emptyMap(), 0.4), 40.0))
    check("权重 0 → 本轮分", near(CfOptimizerEngine.fusedScoreOf(ranked, mapOf("1.1.1.1:443" to 100.0), 0.0), 40.0))
    check("权重 NaN → 本轮分", near(CfOptimizerEngine.fusedScoreOf(ranked, mapOf("1.1.1.1:443" to 100.0), Double.NaN), 40.0))
    check("置信度超出 0–100 被夹住", near(CfOptimizerEngine.fusedScoreOf(ranked, mapOf("1.1.1.1:443" to 500.0), 1.0), 100.0))

    // 排序：A 本轮分更高但无历史，B 本轮分低但历史稳定 → 开融合后 B 在前
    val a = CandidateIp("1.1.1.1", 443)
    val b = CandidateIp("8.8.8.8", 443)
    val metrics = mapOf(
        a to ProbeMetrics(100, 0, 30.0, 3),   // 0.6*0.875 + 0.4*0.75 = 0.825 → 82.5
        b to ProbeMetrics(400, 0, 8.0, 3),    // 0.6*0.5 + 0.4*0.2 = 0.38 → 38.0
    )
    val limits = OptimizerLimits(maxEntries = 12, minScore = 0.0, bwRefMbps = 40.0)
    val plain = CfOptimizerEngine.rank(listOf(a, b), metrics, limits)
    val fused = CfOptimizerEngine.rank(
        candidates = listOf(a, b),
        metrics = metrics,
        limits = limits,
        memoryConfidence = mapOf("8.8.8.8:443" to 95.0, "1.1.1.1:443" to 10.0),
        memoryWeight = 0.4,
    )
    check("无融合时 A 在前", plain.first().address == "1.1.1.1")
    check("开融合后 B 在前", fused.first().address == "8.8.8.8")
    check("融合不改写展示分", near(fused.first { it.address == "8.8.8.8" }.score, 38.0))

    // ---- 7. 默认值对齐蓝本（防"改了常量但默认没生效"的回退） ----
    check("带宽参考默认 40", near(CfOptimizerTuning.SCORE_BW_REF_MBPS_DEFAULT, 40.0))
    check("带宽地板默认 2.0", near(CfOptimizerTuning.MIN_DOWNLOAD_MBPS_DEFAULT, 2.0))
    check("盲扫默认 3000", CfOptimizerTuning.CIDR_SCAN_COUNT_DEFAULT == 3_000)
    check("源数上限已放开到 ≥96", CfOptimizerTuning.SOURCES_PER_RUN_MAX >= 96)
    check("测速池默认 120", CfOptimizerTuning.DOWNLOAD_POOL_LIMIT_DEFAULT == 120)
    check("质量门默认 3", CfOptimizerTuning.MIN_UPLOAD_ENTRIES_DEFAULT == 3)
    check("cidrScanCount 空串回落 3000", CfOptimizerTuning.cidrScanCount("") == 3_000)
    check("cidrScanCount 0 = 关闭", CfOptimizerTuning.cidrScanCount("0") == 0)
    check("cidrScanCount 超上限被夹住", CfOptimizerTuning.cidrScanCount("999999") == 20_000)

    if (failures > 0) {
        println("CF parity harness: $failures/$total FAILED")
        throw AssertionError("$failures of $total checks failed")
    }
}
