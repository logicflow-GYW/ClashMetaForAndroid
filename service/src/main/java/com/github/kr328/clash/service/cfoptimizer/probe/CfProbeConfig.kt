package com.github.kr328.clash.service.cfoptimizer.probe

import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerTuning

/**
 * 一轮优选用到的探测 / 测速参数快照。
 *
 * 默认值 = 移植时的实测值（单一来源在 [CfOptimizerTuning]）。协调器从设置页取值后
 * 构造一次、整轮复用 —— 探针层只读这份快照，不直接读设置、不碰 Android 偏好。
 *
 * 单位一律写进字段名。[downloadSizeMb] 是"下载多大"的唯一来源：URL 里的 `bytes=`
 * 与读取上限都由它推导，二者不同源就会出现"请求 10 MiB 却只读 3 MiB"（早停与均速
 * 全部失真）。
 */
data class CfProbeConfig(
    /** TCP 预筛并发（原版 `MAX_TCP_WORKERS`）。 */
    val tcpConcurrency: Int = CfOptimizerTuning.TCP_CONCURRENCY_DEFAULT,
    /** TCP connect 超时（毫秒，原版 `TCP_TIMEOUT`）。 */
    val tcpTimeoutMs: Int = CfOptimizerTuning.TCP_TIMEOUT_MS_DEFAULT,
    /** TTFB/trace 探测并发（原版 `MAX_TTFB_WORKERS`）。 */
    val probeConcurrency: Int = CfOptimizerTuning.PROBE_CONCURRENCY_DEFAULT,
    /** 地区解析（trace）并发（原版 `MAX_TRACE_WORKERS`）—— 单次 GET，单价低于 TTFB 采样。 */
    val traceConcurrency: Int = CfOptimizerTuning.TRACE_CONCURRENCY_DEFAULT,
    /** 单候选 TTFB 采样次数（原版硬编码 3）。 */
    val ttfbSamples: Int = CfOptimizerTuning.TTFB_SAMPLES_DEFAULT,
    /** 进测速的窄池大小（原版 `BW_TOP_N` / `TTFB_POOL_LIMIT`）。 */
    val downloadPoolLimit: Int = CfOptimizerTuning.DOWNLOAD_POOL_LIMIT_DEFAULT,
    /** 测速并发（原版 `MAX_BW_WORKERS`）。 */
    val downloadConcurrency: Int = CfOptimizerTuning.DOWNLOAD_CONCURRENCY_DEFAULT,
    /** 单节点下载量（MiB，原版 `BW_MB`）—— 整轮流量 ≈ 本值 × 窄池大小。 */
    val downloadSizeMb: Int = CfOptimizerTuning.DOWNLOAD_SIZE_MB_DEFAULT,
    /** 单节点测速超时（毫秒，原版 `BW_TIMEOUT`）。 */
    val downloadTimeoutMs: Long = CfOptimizerTuning.DOWNLOAD_TIMEOUT_MS_DEFAULT.toLong(),
    /** 到速即停（Mbps，原版 `BW_EARLY_STOP_MBPS`）。 */
    val downloadEarlyStopMbps: Double = CfOptimizerTuning.DOWNLOAD_EARLY_STOP_MBPS_DEFAULT,
) {
    /** 单节点下载字节上限 —— 与 [downloadPath] 里的字节参数同源。 */
    val downloadMaxBytes: Long
        get() = downloadSizeMb.toLong() * BYTES_PER_MIB

    /** 测速路径：字节数由 [downloadSizeMb] 推导，不另设常量。 */
    val downloadPath: String
        get() = "/__down?bytes=$downloadMaxBytes"

    companion object {
        const val BYTES_PER_MIB: Long = 1024L * 1024L
    }
}
