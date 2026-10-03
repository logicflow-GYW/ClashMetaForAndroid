package com.github.kr328.clash.service.cfoptimizer

/**
 * CF 优选引擎 — 可复现纯 Kotlin 测试 harness（仓库 service 模块无 JUnit 测试设施，
 * 按任务书不添加测试依赖；本文件用 `main()` 跑全部断言，期望值全部写死字面量，
 * 不从被测常量推导）。
 *
 * 沙箱复现命令（见任务回报）：
 *   /opt/kotlinc/bin/kotlinc <src/main 两个文件> <本文件> -d out
 *   java -cp out:<kotlin-stdlib.jar> com.github.kr328.clash.service.cfoptimizer.CfOptimizerEngineHarnessKt
 */
import com.github.kr328.clash.service.cfoptimizer.CandidateIp
import com.github.kr328.clash.service.cfoptimizer.CfOptimizerEngine
import com.github.kr328.clash.service.cfoptimizer.OptimizerLimits
import com.github.kr328.clash.service.cfoptimizer.OptimizedEntry
import com.github.kr328.clash.service.cfoptimizer.ProbeMetrics

private var failures = 0
private var total = 0

private fun check(name: String, cond: Boolean) {
    total++
    if (!cond) {
        failures++
        println("FAIL: $name")
    }
}

private fun near(a: Double, b: Double, eps: Double = 1e-6) = kotlin.math.abs(a - b) < eps

fun runCfOptimizerEngineHarness() {
    // ---- 1. 固定分数 ----
    // 默认带宽参考值 50（原脚本 150）：0.6*(1-200/800) + 0.4*min(1,75/50) = 0.45 + 0.4 = 0.85
    check("score ttfb=200 bw=75 = 85.0（默认参考值 50）",
        near(CfOptimizerEngine.scoreOf(ProbeMetrics(200, 10, 75.0, 3)), 85.0))
    check("score ttfb=0 bw=150 = 100.0",
        near(CfOptimizerEngine.scoreOf(ProbeMetrics(0, 0, 150.0, 1)), 100.0))
    check("score ttfb=800 bw=0 = 0.0",
        near(CfOptimizerEngine.scoreOf(ProbeMetrics(800, 0, 0.0, 1)), 0.0))
    check("score ttfb=400 bw=0 = 30.0",
        near(CfOptimizerEngine.scoreOf(ProbeMetrics(400, 0, 0.0, 1)), 30.0))
    check("score bw>150 capped at 1.0",
        near(CfOptimizerEngine.scoreOf(ProbeMetrics(0, 0, 500.0, 1)), 100.0))
    check("score ttfb>800 floored at 0",
        near(CfOptimizerEngine.scoreOf(ProbeMetrics(2000, 0, 150.0, 1)), 40.0)) // 0.6*0 + 0.4*1 = 40

    // ---- 1b. 带宽参考值可调 + 非法值不产生 NaN/Inf ----
    check("参考值 50：bw=25 得一半带宽分（0.6+0.2 = 80.0）",
        near(CfOptimizerEngine.scoreOf(ProbeMetrics(0, 0, 25.0, 1)), 80.0))
    check("参考值可覆盖：传 150 → bw=75 得一半带宽分（80.0）",
        near(CfOptimizerEngine.scoreOf(ProbeMetrics(0, 0, 75.0, 1), bwRefMbps = 150.0), 80.0))
    check("参考值 0 → 回落默认 50（不产生 NaN/Inf）",
        near(CfOptimizerEngine.scoreOf(ProbeMetrics(0, 0, 25.0, 1), bwRefMbps = 0.0), 80.0))
    check("参考值负数 → 回落默认 50",
        near(CfOptimizerEngine.scoreOf(ProbeMetrics(0, 0, 25.0, 1), bwRefMbps = -5.0), 80.0))

    // ---- 2. 门槛边界 ----
    val m30 = ProbeMetrics(400, 0, 0.0, 1) // score = 30.0（>= minScore=30，应保留）
    check("score exactly 30 included",
        CfOptimizerEngine.rank(listOf(CandidateIp("1.2.3.4", 443)), mapOf(CandidateIp("1.2.3.4", 443) to m30)).size == 1)
    val m299 = ProbeMetrics(401, 0, 0.0, 1) // score = 29.925（< 30，应丢弃）
    check("score 29.925 excluded",
        CfOptimizerEngine.rank(listOf(CandidateIp("1.2.3.4", 443)), mapOf(CandidateIp("1.2.3.4", 443) to m299)).isEmpty())
    // ttfb 边界：800 不超过 maxTtfbMs=800（且分数 30 达标）→ 保留；801 → 丢弃
    val m800 = ProbeMetrics(800, 0, 112.5, 1) // score = 30.0
    check("ttfb=800 kept",
        CfOptimizerEngine.rank(listOf(CandidateIp("1.2.3.4", 443)), mapOf(CandidateIp("1.2.3.4", 443) to m800)).size == 1)
    val m801 = ProbeMetrics(801, 0, 150.0, 1)
    check("ttfb=801 dropped",
        CfOptimizerEngine.rank(listOf(CandidateIp("1.2.3.4", 443)), mapOf(CandidateIp("1.2.3.4", 443) to m801)).isEmpty())
    // jitter 边界：200 保留，201 丢弃
    check("jitter=200 kept",
        CfOptimizerEngine.rank(listOf(CandidateIp("1.2.3.4", 443)),
            mapOf(CandidateIp("1.2.3.4", 443) to ProbeMetrics(0, 200, 150.0, 1))).size == 1)
    check("jitter=201 dropped",
        CfOptimizerEngine.rank(listOf(CandidateIp("1.2.3.4", 443)),
            mapOf(CandidateIp("1.2.3.4", 443) to ProbeMetrics(0, 201, 150.0, 1))).isEmpty())
    // 自定义门槛：minScore=90 只留 100 分项
    val strict = CfOptimizerEngine.rank(
        listOf(CandidateIp("1.2.3.4", 443), CandidateIp("5.6.7.8", 443)),
        mapOf(
            CandidateIp("1.2.3.4", 443) to ProbeMetrics(0, 0, 150.0, 1),
            CandidateIp("5.6.7.8", 443) to ProbeMetrics(100, 0, 150.0, 1), // score 92.5
        ),
        OptimizerLimits(minScore = 95.0))
    check("custom minScore=95 keeps only 100-score entry", strict.size == 1 && strict[0].address == "1.2.3.4")

    // ---- 3. 全失败 → 空结果 ----
    val allBad = mapOf(
        CandidateIp("1.2.3.4", 443) to ProbeMetrics(2000, 0, 150.0, 1),
        CandidateIp("5.6.7.8", 443) to ProbeMetrics(3000, 0, 150.0, 1),
    )
    check("all candidates fail -> empty list",
        CfOptimizerEngine.rank(listOf(CandidateIp("1.2.3.4", 443), CandidateIp("5.6.7.8", 443)), allBad).isEmpty())
    check("empty candidates -> empty list", CfOptimizerEngine.rank(emptyList(), emptyMap()).isEmpty())
    check("maxEntries=0 -> empty list",
        CfOptimizerEngine.rank(listOf(CandidateIp("1.2.3.4", 443)),
            mapOf(CandidateIp("1.2.3.4", 443) to ProbeMetrics(0, 0, 150.0, 1)),
            OptimizerLimits(maxEntries = 0)).isEmpty())

    // ---- 4. 重复节点去重（同 addr:port 不同 source）----
    val dupMetrics = mapOf(
        CandidateIp("1.2.3.4", 443, "src-a") to ProbeMetrics(100, 0, 150.0, 1), // 52.5
        CandidateIp("1.2.3.4", 443, "src-b") to ProbeMetrics(50, 0, 150.0, 1),  // 56.25
    )
    val dup = CfOptimizerEngine.rank(
        listOf(CandidateIp("1.2.3.4", 443, "src-a"), CandidateIp("1.2.3.4", 443, "src-b")), dupMetrics)
    check("duplicate addr:port deduped to 1 entry", dup.size == 1)
    check("dedup keeps better score (ttfb=50)", dup.firstOrNull()?.ttfbMs == 50L)
    // 同地址不同端口 → 两条
    val twoPorts = CfOptimizerEngine.rank(
        listOf(CandidateIp("1.2.3.4", 443), CandidateIp("1.2.3.4", 8443)),
        mapOf(
            CandidateIp("1.2.3.4", 443) to ProbeMetrics(0, 0, 150.0, 1),
            CandidateIp("1.2.3.4", 8443) to ProbeMetrics(0, 0, 150.0, 1),
        ))
    check("same addr different ports -> 2 entries", twoPorts.size == 2)

    // ---- 5. 错误指标 ----
    fun one(m: ProbeMetrics?): Int =
        CfOptimizerEngine.rank(listOf(CandidateIp("1.2.3.4", 443)),
            if (m == null) emptyMap() else mapOf(CandidateIp("1.2.3.4", 443) to m)).size
    check("NaN downloadMbps dropped", one(ProbeMetrics(0, 0, Double.NaN, 1)) == 0)
    check("Infinity downloadMbps dropped", one(ProbeMetrics(0, 0, Double.POSITIVE_INFINITY, 1)) == 0)
    check("negative ttfb dropped", one(ProbeMetrics(-1, 0, 150.0, 1)) == 0)
    check("negative jitter dropped", one(ProbeMetrics(0, -5, 150.0, 1)) == 0)
    check("negative bw dropped", one(ProbeMetrics(0, 0, -1.0, 1)) == 0)
    check("zero successfulSamples dropped", one(ProbeMetrics(0, 0, 150.0, 0)) == 0)
    check("missing metrics dropped", one(null) == 0)

    // ---- 6. 地区配额 ----
    val quotaMetrics = mapOf(
        CandidateIp("1.2.3.4", 443) to ProbeMetrics(0, 0, 150.0, 1, "SG"),   // 100
        CandidateIp("5.6.7.8", 443) to ProbeMetrics(50, 0, 150.0, 1, "SG"),  // 93.75
        CandidateIp("9.9.9.9", 443) to ProbeMetrics(100, 0, 150.0, 1, "US"), // 92.5
    )
    val quotaCands = listOf(CandidateIp("1.2.3.4", 443), CandidateIp("5.6.7.8", 443), CandidateIp("9.9.9.9", 443))
    val capped = CfOptimizerEngine.rank(quotaCands, quotaMetrics, OptimizerLimits(maxPerRegion = 1))
    check("maxPerRegion=1 keeps 2 entries", capped.size == 2)
    check("cap keeps best SG and US", capped.map { it.address } == listOf("1.2.3.4", "9.9.9.9"))
    val uncapped = CfOptimizerEngine.rank(quotaCands, quotaMetrics)
    check("default maxPerRegion=0 disables quota (3 entries)", uncapped.size == 3)

    // ---- 7. 字符串格式 ----
    val e4 = OptimizedEntry("1.2.3.4", 443, "SG", 0, 0, 150.0, 100.0)
    check("ipv4 worker line", e4.toWorkerLine() == "1.2.3.4:443#SG")
    val e6 = OptimizedEntry("2606:4700::1", 443, "ZZ", 0, 0, 150.0, 100.0)
    check("ipv6 worker line bracketed", e6.toWorkerLine() == "[2606:4700::1]:443#ZZ")
    check("lowercase region normalized", e4.copy(region = "sg").toWorkerLine() == "1.2.3.4:443#SG")
    check("bad region falls back to ZZ", e4.copy(region = "toolong").toWorkerLine() == "1.2.3.4:443#ZZ")
    check("region normalization in rank output",
        CfOptimizerEngine.rank(listOf(CandidateIp("1.2.3.4", 443)),
            mapOf(CandidateIp("1.2.3.4", 443) to ProbeMetrics(0, 0, 150.0, 1, "sg")))[0].region == "SG")
    check("blank region in rank -> ZZ",
        CfOptimizerEngine.rank(listOf(CandidateIp("1.2.3.4", 443)),
            mapOf(CandidateIp("1.2.3.4", 443) to ProbeMetrics(0, 0, 150.0, 1, " ")))[0].region == "ZZ")

    // ---- 8. 坏 IP / 坏端口 ----
    val goodM = ProbeMetrics(0, 0, 150.0, 1)
    fun badIp(addr: String) = CfOptimizerEngine.rank(listOf(CandidateIp(addr, 443)),
        mapOf(CandidateIp(addr, 443) to goodM)).isEmpty()
    check("hostname rejected", badIp("example.com"))
    check("private 10.0.0.1 rejected", badIp("10.0.0.1"))
    check("private 192.168.1.1 rejected", badIp("192.168.1.1"))
    check("private 172.16.0.1 rejected", badIp("172.16.0.1"))
    check("loopback 127.0.0.1 rejected", badIp("127.0.0.1"))
    check("link-local 169.254.1.1 rejected", badIp("169.254.1.1"))
    check("multicast 224.0.0.1 rejected", badIp("224.0.0.1"))
    check("unspecified 0.0.0.0 rejected", badIp("0.0.0.0"))
    check("reserved 240.0.0.1 rejected", badIp("240.0.0.1"))
    check("cgnat 100.64.0.1 rejected", badIp("100.64.0.1"))
    check("leading-zero octal 010.1.1.1 rejected", badIp("010.1.1.1"))
    check("injection suffix 1.2.3.4#evil rejected", badIp("1.2.3.4#evil"))
    check("cidr suffix 1.2.3.4/24 rejected", badIp("1.2.3.4/24"))
    check("empty addr rejected", badIp(""))
    check("ipv6 loopback ::1 rejected", badIp("::1"))
    check("ipv6 link-local fe80::1 rejected", badIp("fe80::1"))
    check("ipv6 ULA fd00::1 rejected", badIp("fd00::1"))
    check("ipv6 global accepted",
        CfOptimizerEngine.rank(listOf(CandidateIp("2606:4700::1", 443)),
            mapOf(CandidateIp("2606:4700::1", 443) to goodM)).size == 1)
    check("ipv4 public accepted",
        CfOptimizerEngine.rank(listOf(CandidateIp("104.16.1.1", 443)),
            mapOf(CandidateIp("104.16.1.1", 443) to goodM)).size == 1)
    check("port 22 not in allowlist rejected",
        CfOptimizerEngine.rank(listOf(CandidateIp("104.16.1.1", 22)),
            mapOf(CandidateIp("104.16.1.1", 22) to goodM)).isEmpty())
    check("port 8443 in allowlist accepted",
        CfOptimizerEngine.rank(listOf(CandidateIp("104.16.1.1", 8443)),
            mapOf(CandidateIp("104.16.1.1", 8443) to goodM)).size == 1)

    // ---- 9. 排序与截断 ----
    val orderMetrics = mapOf(
        CandidateIp("1.2.3.4", 443) to ProbeMetrics(100, 0, 150.0, 1), // 92.5
        CandidateIp("5.6.7.8", 443) to ProbeMetrics(0, 0, 150.0, 1),   // 100
        CandidateIp("9.9.9.9", 443) to ProbeMetrics(50, 0, 150.0, 1),  // 96.25
    )
    val ordered = CfOptimizerEngine.rank(
        listOf(CandidateIp("1.2.3.4", 443), CandidateIp("5.6.7.8", 443), CandidateIp("9.9.9.9", 443)), orderMetrics)
    check("sorted by score desc",
        ordered.map { it.address } == listOf("5.6.7.8", "9.9.9.9", "1.2.3.4"))
    val truncated = CfOptimizerEngine.rank(
        listOf(CandidateIp("1.2.3.4", 443), CandidateIp("5.6.7.8", 443), CandidateIp("9.9.9.9", 443)),
        orderMetrics, OptimizerLimits(maxEntries = 2))
    check("maxEntries=2 truncates to 2", truncated.size == 2 && truncated[0].address == "5.6.7.8")

    // ---- 10. 两层配额（便宜层上限 = 昂贵层 × 倍数，硬上限 20000）----
    // 倍数自 2026-09-29 起由用户可调（设置项 raw_pool_factor，默认 2，见 CfOptimizerTuning）。
    // 这里**显式传字面量因子**，不从常量推导：默认值被改动时，下面这组期望值不会跟着变，
    // 而是由 CfOptimizerTuningHarness 里那条"默认值 == 2"的字面量断言负责打红。
    check("rawPoolLimit(600, 2) = 1200", CfOptimizerEngine.rawPoolLimit(600, 2) == 1200)
    check("rawPoolLimit(80, 2) = 160", CfOptimizerEngine.rawPoolLimit(80, 2) == 160)
    check("rawPoolLimit(600, 3) = 1800（用户调大倍数）", CfOptimizerEngine.rawPoolLimit(600, 3) == 1800)
    check("rawPoolLimit(10000, 2) = 20000（正好在上限）", CfOptimizerEngine.rawPoolLimit(10000, 2) == 20000)
    check("rawPoolLimit(5000, 2) = 10000（未到上限）", CfOptimizerEngine.rawPoolLimit(5000, 2) == 10000)
    check("rawPoolLimit(0, 2) = 2（非法输入只放行不放大）", CfOptimizerEngine.rawPoolLimit(0, 2) == 2)
    check("rawPoolLimit(-5, 2) = 2（负数同样只放行不放大）", CfOptimizerEngine.rawPoolLimit(-5, 2) == 2)
    check("rawPoolLimit(600, 0) = 600（倍数非法按 1）", CfOptimizerEngine.rawPoolLimit(600, 0) == 600)
    check("rawPoolLimit(600, 99) = 20000（倍数再大也被天花板截住）",
        CfOptimizerEngine.rawPoolLimit(600, 99) == 20000)
    check("rawPoolLimit 单调不减",
        CfOptimizerEngine.rawPoolLimit(1, 2) <= CfOptimizerEngine.rawPoolLimit(600, 2))

    println("TOTAL=$total FAILURES=$failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
