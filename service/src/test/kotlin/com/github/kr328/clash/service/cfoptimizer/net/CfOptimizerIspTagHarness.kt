package com.github.kr328.clash.service.cfoptimizer.net

/**
 * ISP 网络标签 — 可复现纯 Kotlin 回归装置（与 [com.github.kr328.clash.service.cfoptimizer.CfOptimizerEngineHarness]
 * 同款：`main()` 跑全部断言，**期望值全部写死字面量**，不从被测常量推导 —— 否则改常量时期望值跟着变，
 * 变异会伪装成 PASS）。
 *
 * 期望值来源：
 * - `Unicom` / `Mobile` / `Telecom` 三个令牌 = 原版 `get_current_net_name()` 的口径，
 *   由原版真机日志实测确认（推送行 `13.250.70.157:443#SG-[Unicom]`，2026-09-29）；
 * - 清洗兜底 = 原版 `re.sub(r'[^a-zA-Z0-9\u4e00-\u9fa5]', '_', isp_raw)` 的逐字符等价；
 * - 中文映射是本实现比原版多认的一层（原版只匹配英文，服务端回中文时会退化成中文标签）。
 *
 * 沙箱复现命令：
 *   /opt/kotlinc/bin/kotlinc <IspTag.kt> <本文件> -include-runtime -d isptag.jar
 *   java -jar isptag.jar
 */
private var failures = 0
private var total = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    total++

    if (actual != expected) {
        failures++
        println("FAIL: $name -> 实际=$actual 期望=$expected")
    }
}

private fun main() {
    val parse = IspTag::parse

    // 英文 ISP 名 → 三个令牌（原版日志里的形状就是 "China Unicom ..."）
    check("unicom-en", parse("China Unicom (Beijing)"), "Unicom")
    check("mobile-en", parse("China Mobile Communications"), "Mobile")
    check("telecom-en", parse("China Telecom"), "Telecom")
    check("unicom-upper", parse("CHINA UNICOM"), "Unicom")

    // 中文 ISP 名（本实现比原版多认这一层）
    check("unicom-cn", parse("中国联通"), "Unicom")
    check("mobile-cn", parse("中国移动"), "Mobile")
    check("telecom-cn", parse("中国电信"), "Telecom")
    check("unicom-cn-prefixed", parse("北京联通"), "Unicom")

    // 兜底清洗：非字母/数字/中文逐个替换成下划线（与原版正则逐字符等价）
    check("sanitize", parse("Some ISP Co., Ltd."), "Some_ISP_Co___Ltd_")

    // 认不出的英文名不硬套令牌
    check("unknown-keeps-name", parse("CMNET"), "CMNET")

    // 空输入不产生标签（调用方回落传输类型标签 Cellular / WiFi）
    check("empty", parse(""), null)
    check("blank", parse("   "), null)
    check("null", parse(null), null)

    // 限长：标签会进文件名与 Worker 行，超长 ISP 名不能把路径撑坏
    check("max-chars", parse("A".repeat(100))?.length, 32)

    // 已知局限（与原版一致，登记而非回归）：意大利电信 "Telecom Italia" 会被判成 Telecom。
    check("italia-shared-flaw", parse("Telecom Italia"), "Telecom")

    println("================================")
    println("CF 网络标签回归装置：$total 项断言，$failures 项失败")
    println("（令牌口径与原版 get_current_net_name 一致，原版真机日志实测确认）")
    println("================================")

    if (failures > 0) {
        throw AssertionError("$failures / $total 项断言失败")
    }
}
