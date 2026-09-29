package com.github.kr328.clash.service.cfoptimizer

import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerTuning

/**
 * CF 候选优选 — 纯逻辑数据模型（无 Android 依赖、无 IO、无网络）。
 *
 * 契约来源：/var/minis/shared/clash-cf-autopilot-2026/INTERFACES.md（A 部分）。
 * 本文件为会话 A 独占；B/C/D 只消费这里的类型与 [CfOptimizerEngine.rank]。
 */

/** 一个候选入口 IP（address 为字面 IP，port 必须在 CF 端口白名单内）。 */
data class CandidateIp(val address: String, val port: Int, val source: String = "")

/** Android Probe（会话 D）提供的实测指标；A 不创建套接字、不实现网络测量。 */
data class ProbeMetrics(
    val ttfbMs: Long,
    val jitterMs: Long,
    val downloadMbps: Double,
    val successfulSamples: Int,
    val region: String = "ZZ",
)

/** 优选门槛与限额。默认值来自原 Python 脚本（SMART_PUSH_MAX_TOTAL=12 / 门槛 30 / TTFB 800ms / jitter 200ms）。 */
data class OptimizerLimits(
    val maxEntries: Int = 12,
    val maxTtfbMs: Long = 800,
    val maxJitterMs: Long = 200,
    val minDownloadMbps: Double = 0.0,
    val minScore: Double = 30.0,
    /**
     * 每个地区最多保留条数；0 = 不启用地区配额（默认，行为与契约基线一致）。
     * 接口偏差说明：INTERFACES.md 的 OptimizerLimits 未定义地区多样性限额，但任务书
     * （session-task-A-engine.md）要求实现“地区多样性限额”。为不改变默认行为、不破坏
     * 主集成会话的现有调用，以默认关闭的可选字段落地，并已在任务回报中声明此偏差。
     */
    val maxPerRegion: Int = 0,
    /**
     * 评分权重；默认 0.6 / 0.4 与原脚本一致（用户可调，见 CfOptimizerTuning）。
     */
    val weights: ScoreWeights = ScoreWeights(),
    /**
     * 带宽分量"满分"的参考值（Mbps）—— 原脚本 `SCORE_MAX_BPS = 150`。
     *
     * 默认取 [CfOptimizerTuning.SCORE_BW_REF_MBPS_DEFAULT]（50）：150 在 10–30 Mbps 的移动链路上
     * 让带宽分量退化成常数（10 Mbps 与 30 Mbps 只差 5 分），排序实际上只剩 TTFB 一项 ——
     * 真机实测的后果是"握手快但下载慢"的节点当选。理由与调法写在那个常量上，这里只承载数值。
     */
    val bwRefMbps: Double = CfOptimizerTuning.SCORE_BW_REF_MBPS_DEFAULT,
)

/**
 * 评分权重（TTFB 分量 / 带宽分量）。
 *
 * 默认 0.6 / 0.4 = 原脚本 `SCORE_TTFB_WEIGHT` / `SCORE_BW_WEIGHT`。
 * 不要求两项之和为 1：[CfOptimizerEngine.scoreOf] 按和归一化，满分恒为 100，
 * 门槛分（0–100 量纲）才不会随权重漂移；两项都为 0 时回落默认（否则候选全军覆没）。
 */
data class ScoreWeights(
    val ttfb: Double = 0.6,
    val bw: Double = 0.4,
) {
    /**
     * 归一化后的权重（两项之和恒为 1）。和 ≤ 0 或非有限 → 回落默认 0.6 / 0.4 ——
     * 否则所有候选得分恒为 0，名单会被门槛清空，那不是"调参"，是把自己关在门外。
     */
    val normalized: ScoreWeights
        get() {
            val sum = ttfb + bw
            if (!sum.isFinite() || sum <= 0.0) return ScoreWeights()
            return ScoreWeights(ttfb = ttfb / sum, bw = bw / sum)
        }
}

/** 一条优选结果。由 [CfOptimizerEngine.rank] 产出。 */
data class OptimizedEntry(
    val address: String,
    val port: Int,
    val region: String,
    val ttfbMs: Long,
    val jitterMs: Long,
    val downloadMbps: Double,
    val score: Double,
) {
    /**
     * 推送行：`IP:port#国家`（如 `141.164.35.4:443#KR`）。
     *
     * 国家来自探测 trace 的权威定位；解析不出（trace 失败）时回 `ZZ` 占位，不猜。
     *
     * 2026-09-29 前是 `#国家-ISP标签`：ISP 靠四级回退解析（Worker /whoami → ipwho.is →
     * ip-api.com → 传输类型），任何一级失败都会污染输出 —— 实测出过 `#TW-__DOCTYPE_html_`
     * （Worker 首页 HTML 被当成 ISP 名，清洗后逐字符拼成这个）。标签的真实信息量为 0
     * （同一轮全部相同），原版要它只为公开分享时按运营商分发名单；单机场景 `#国家` 已够，
     * 整层砍掉（IspTag / IspTagResolver / transportTag 已删）。升级触发：要把输出分享给
     * 不同运营商用户时再加回（且只用 Worker /whoami 一级，成功判据必须是 JSON）。
     */
    fun toWorkerLine(): String {
        val reg = region.trim().uppercase()
        val safeRegion = if (reg.length == 2 && reg.all { it in 'A'..'Z' }) reg else "ZZ"
        val host = if (address.contains(':')) "[$address]" else address
        return "$host:$port#$safeRegion"
    }
}
