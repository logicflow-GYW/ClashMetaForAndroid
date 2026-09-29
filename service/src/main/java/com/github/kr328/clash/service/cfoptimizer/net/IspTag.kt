package com.github.kr328.clash.service.cfoptimizer.net

/**
 * ISP → 网络标签的**纯逻辑**（无 Android 依赖，可直接 JVM 单测）。
 *
 * 为什么要有这个标签：它进 `entry_ip_<标签>.json` 的文件名，也进推送到 Worker 的
 * `IP:port#CC-[标签]` —— 是订阅侧「哪份名单给哪家运营商」的**选择键**。原版用
 * `get_current_net_name()` 查公网出口的 ISP（Unicom / Mobile / Telecom）；我们之前用的是
 * Android 的传输类型（`Cellular` / `WiFi`），蜂窝下会把联通测出来的名单标成通用的
 * `[Cellular]`，同一个订阅下别的运营商用户就会拿到对自己不适用的名单。
 *
 * 令牌与原版逐字兼容（`Mobile` / `Unicom` / `Telecom` / 清洗兜底），
 * 好让已经按原版标签配置好的订阅继续命中。
 */
object IspTag {
    /** 标签会进文件名与 Worker 行，限长防止异常长的 ISP 名把路径撑坏。 */
    const val MAX_CHARS: Int = 32

    /**
     * 按原版 `get_current_net_name()` 的口径映射 ISP 名；识别不出时返回清洗后的原文。
     *
     * 比原版多认中文：原版请求时带 `lang=zh-CN`，却只用 `"unicom" in isp` 匹配英文 ——
     * 服务端一旦回「中国联通」，同一个运营商就会分裂成 `Unicom` 和 `中国联通` 两个标签。
     */
    fun parse(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        val lower = text.lowercase()

        return when {
            "mobile" in lower || "移动" in text -> "Mobile"
            "unicom" in lower || "联通" in text -> "Unicom"
            "telecom" in lower || "电信" in text -> "Telecom"
            else -> sanitize(text)
        }
    }

    /** 原版同款清洗：保留字母 / 数字 / 中文，其余字符逐个替换成 `_`，并限长。 */
    fun sanitize(raw: String): String =
        raw.trim()
            .map { char -> if (char.isLetterOrDigit() || char.code in 0x4E00..0x9FA5) char else '_' }
            .joinToString("")
            .take(MAX_CHARS)
}
