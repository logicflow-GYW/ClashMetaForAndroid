package com.github.kr328.clash.service.cfoptimizer.settings

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.cfoptimizer.ScoreWeights
import com.github.kr328.clash.service.cfoptimizer.memory.CfMemoryTuning
import java.util.UUID

/**
 * Snapshot of CF optimizer configuration.
 *
 * Deliberately contains NO password field — the Worker password never appears in
 * settings data classes or plain preference files; it is held exclusively by
 * [CfOptimizerSecretStore] as ciphertext.
 */
data class CfOptimizerSettings(
    val enabled: Boolean,
    val workerBaseUrl: String,
    val subscriptionProfileId: UUID?,
    val confirmedSharedListOwnership: Boolean,
    val customEntries: List<String>,
    val autoHealEnabled: Boolean,
    val lastRunAt: Long,
)

/**
 * Multiprocess-safe settings store for the CF optimizer module.
 *
 * Backed by [PreferenceProvider.createSharedPreferencesFromContext] (MultiProcessPreference
 * from the UI process / direct prefs from service processes), so both processes observe
 * the same values.
 *
 * Custom entries are persisted as a newline-joined string to preserve user ordering
 * (the existing Store only offers unordered stringSet).
 */
class CfOptimizerSettingsStore(context: Context) {
    private val store = Store(
        PreferenceProvider
            .createSharedPreferencesFromContext(context)
            .asStoreProvider()
    )

    var enabled: Boolean by store.boolean(
        key = "cfoptimizer_enabled",
        defaultValue = false,
    )

    var workerBaseUrl: String by store.string(
        key = "cfoptimizer_worker_base_url",
        defaultValue = "",
    )

    var subscriptionProfileId: UUID? by store.typedString(
        key = "cfoptimizer_subscription_profile_id",
        from = { if (it.isBlank()) null else UUID.fromString(it) },
        to = { it?.toString() ?: "" },
    )

    var confirmedSharedListOwnership: Boolean by store.boolean(
        key = "cfoptimizer_shared_list_confirmed",
        defaultValue = false,
    )

    var autoHealEnabled: Boolean by store.boolean(
        key = "cfoptimizer_auto_heal_enabled",
        defaultValue = false,
    )

    var lastRunAt: Long by store.long(
        key = "cfoptimizer_last_run_at",
        defaultValue = 0L,
    )

    // ── 运行参数（模仿原版 cf_config.py，全部带默认值；空/非法时回落默认）──
    // Raw String 委托供设置页 UI 绑定；typed 计算属性供协调器消费。

    /** 排除国家（原版 EXCLUDE_COUNTRIES）：逗号分隔 ISO 码，默认 RU,KP,CN,HK。 */
    var excludeCountriesRaw: String by store.string(
        key = "cfoptimizer_exclude_countries",
        defaultValue = "RU,KP,CN,HK",
    )

    val excludeCountries: Set<String>
        get() = excludeCountriesRaw.split(',', ';')
            .map { it.trim().uppercase() }
            .filter { it.length == 2 }
            .toSet()

    /** 只选国家（原版 ONLY_COUNTRIES）：空 = 不启用白名单。 */
    var onlyCountriesRaw: String by store.string(
        key = "cfoptimizer_only_countries",
        defaultValue = "",
    )

    val onlyCountries: Set<String>
        get() = onlyCountriesRaw.split(',', ';')
            .map { it.trim().uppercase() }
            .filter { it.length == 2 }
            .toSet()

    /** 每轮从源池抽取的源数（原版动态通讯录；默认 8——源池约百个，多抽几个只多几 KB 流量）。 */
    var sourcesPerRunRaw: String by store.string(
        key = "cfoptimizer_sources_per_run",
        defaultValue = "8",
    )

    val sourcesPerRun: Int get() = CfOptimizerTuning.sourcesPerRun(sourcesPerRunRaw)

    /**
     * 单源抽样条数（原版 `sample_limit` 的手机端等价物；默认 250）。
     *
     * 250 × [sourcesPerRun]=8 ≈ 2000 条原始候选 —— 与原版 `sample_limit=3000` 同一量级。
     * 这个数是**便宜层**：只付 TCP connect 的价钱（1s 超时、200 并发），
     * 真正进 TTFB 段的由 [maxCandidates] 单独封顶。
     */
    var perSourceSampleRaw: String by store.string(
        key = "cfoptimizer_per_source_sample",
        defaultValue = "250",
    )

    val perSourceSample: Int get() = CfOptimizerTuning.perSourceSample(perSourceSampleRaw)

    /**
     * 每轮进入 TTFB 探测的候选上限（**昂贵层**，默认 600）。
     *
     * 只作用在 TCP 存活集上：原始池 = 本值 × [CfOptimizerTuning.RAW_POOL_FACTOR_DEFAULT]（默认 2，可调），
     * 即默认 600 → 原始池 1200，存活率 71.4%（原版真机实测）时约 850 个存活，探 600（覆盖约 70%）。
     * 旧默认 80 之所以"选不出东西"，是因为它同时也是原始池上限 —— 探索面只有 80 条，
     * 而其中大部分还是死的；漏斗把"确认死 IP"的成本压下去之后，探索面才谈得上放大。
     */
    var maxCandidatesRaw: String by store.string(
        key = "cfoptimizer_max_candidates",
        defaultValue = "600",
    )

    val maxCandidates: Int get() = CfOptimizerTuning.maxCandidates(maxCandidatesRaw)

    /** 上传质量门（原版 `SMART_PUSH_MIN_NODES`；真机日志一轮推了 8 个）。默认 6：与原版产出规模相称，仍留着"太少就宁可不动共享列表"的保护。 */
    var minUploadEntriesRaw: String by store.string(
        key = "cfoptimizer_min_upload_entries",
        defaultValue = "6",
    )

    val minUploadEntries: Int get() = CfOptimizerTuning.minUploadEntries(minUploadEntriesRaw)

    /** 每地区最多保留条数（原版 `SMART_PUSH_IPS_PER_CC`；真机日志实测每地区贡献 4 个、合计 8 个）。取 3：介于我们原默认 2 与原版实测 4 之间。 */
    var maxPerRegionRaw: String by store.string(
        key = "cfoptimizer_max_per_region",
        defaultValue = "3",
    )

    val maxPerRegion: Int get() = CfOptimizerTuning.maxPerRegion(maxPerRegionRaw)

    // ── 探测与测速（原版 cf_config.py 的并发/超时/大小）──
    // 默认值一律从 CfOptimizerTuning 取（单一来源）：空串/非法值 → 回落默认，
    // 所以"清空输入框"就是"恢复默认"。范围只拦会把手机搞坏的输入，不替用户做判断。

    /** TCP 预筛并发（原版 MAX_TCP_WORKERS）。 */
    var tcpConcurrencyRaw: String by store.string(
        key = "cfoptimizer_tcp_concurrency",
        defaultValue = CfOptimizerTuning.TCP_CONCURRENCY_DEFAULT.toString(),
    )

    val tcpConcurrency: Int get() = CfOptimizerTuning.tcpConcurrency(tcpConcurrencyRaw)

    /** TCP connect 超时（毫秒，原版 TCP_TIMEOUT）。 */
    var tcpTimeoutMsRaw: String by store.string(
        key = "cfoptimizer_tcp_timeout_ms",
        defaultValue = CfOptimizerTuning.TCP_TIMEOUT_MS_DEFAULT.toString(),
    )

    val tcpTimeoutMs: Int get() = CfOptimizerTuning.tcpTimeoutMs(tcpTimeoutMsRaw)

    /** TTFB/trace 探测并发（原版 MAX_TTFB_WORKERS）。 */
    var probeConcurrencyRaw: String by store.string(
        key = "cfoptimizer_probe_concurrency",
        defaultValue = CfOptimizerTuning.PROBE_CONCURRENCY_DEFAULT.toString(),
    )

    val probeConcurrency: Int get() = CfOptimizerTuning.probeConcurrency(probeConcurrencyRaw)

    /** 单候选 TTFB 采样次数（原版硬编码 3）。 */
    var ttfbSamplesRaw: String by store.string(
        key = "cfoptimizer_ttfb_samples",
        defaultValue = CfOptimizerTuning.TTFB_SAMPLES_DEFAULT.toString(),
    )

    val ttfbSamples: Int get() = CfOptimizerTuning.ttfbSamples(ttfbSamplesRaw)

    /** 进测速的窄池大小（原版 BW_TOP_N / TTFB_POOL_LIMIT）。 */
    var downloadPoolLimitRaw: String by store.string(
        key = "cfoptimizer_download_pool_limit",
        defaultValue = CfOptimizerTuning.DOWNLOAD_POOL_LIMIT_DEFAULT.toString(),
    )

    val downloadPoolLimit: Int get() = CfOptimizerTuning.downloadPoolLimit(downloadPoolLimitRaw)

    /** 测速并发（原版 MAX_BW_WORKERS）。 */
    var downloadConcurrencyRaw: String by store.string(
        key = "cfoptimizer_download_concurrency",
        defaultValue = CfOptimizerTuning.DOWNLOAD_CONCURRENCY_DEFAULT.toString(),
    )

    val downloadConcurrency: Int get() = CfOptimizerTuning.downloadConcurrency(downloadConcurrencyRaw)

    /** 单节点测速下载量（MiB，原版 BW_MB）—— 整轮流量 ≈ 本值 × 测速池大小。 */
    var downloadSizeMbRaw: String by store.string(
        key = "cfoptimizer_download_size_mb",
        defaultValue = CfOptimizerTuning.DOWNLOAD_SIZE_MB_DEFAULT.toString(),
    )

    val downloadSizeMb: Int get() = CfOptimizerTuning.downloadSizeMb(downloadSizeMbRaw)

    /** 单节点测速超时（毫秒，原版 BW_TIMEOUT）。 */
    var downloadTimeoutMsRaw: String by store.string(
        key = "cfoptimizer_download_timeout_ms",
        defaultValue = CfOptimizerTuning.DOWNLOAD_TIMEOUT_MS_DEFAULT.toString(),
    )

    val downloadTimeoutMs: Int get() = CfOptimizerTuning.downloadTimeoutMs(downloadTimeoutMsRaw)

    /** 到速即停（Mbps，原版 BW_EARLY_STOP_MBPS）。 */
    var downloadEarlyStopMbpsRaw: String by store.string(
        key = "cfoptimizer_download_early_stop_mbps",
        defaultValue = CfOptimizerTuning.DOWNLOAD_EARLY_STOP_MBPS_DEFAULT.toString(),
    )

    val downloadEarlyStopMbps: Double
        get() = CfOptimizerTuning.downloadEarlyStopMbps(downloadEarlyStopMbpsRaw)

    // ── 质量门槛与输出形态 ──

    /** TTFB 上限（毫秒，原版 TTFB_MAX）：超过直接丢弃。 */
    var maxTtfbMsRaw: String by store.string(
        key = "cfoptimizer_max_ttfb_ms",
        defaultValue = CfOptimizerTuning.MAX_TTFB_MS_DEFAULT.toString(),
    )

    val maxTtfbMs: Int get() = CfOptimizerTuning.maxTtfbMs(maxTtfbMsRaw)

    /** 抖动上限（毫秒，原版 TTFB_JITTER_LIMIT）：采样全距超过即丢弃。 */
    var maxJitterMsRaw: String by store.string(
        key = "cfoptimizer_max_jitter_ms",
        defaultValue = CfOptimizerTuning.MAX_JITTER_MS_DEFAULT.toString(),
    )

    val maxJitterMs: Int get() = CfOptimizerTuning.maxJitterMs(maxJitterMsRaw)

    /** 门槛分（0–100 量纲，原版 SMART_PUSH_MIN_SCORE）。关下载测速时自动折半。 */
    var minScoreRaw: String by store.string(
        key = "cfoptimizer_min_score",
        defaultValue = CfOptimizerTuning.MIN_SCORE_DEFAULT.toString(),
    )

    /** 生效门槛分 —— 带宽分量关掉时满分只有 60，门槛按比例下调（见 CfOptimizerTuning）。 */
    val effectiveMinScore: Double
        get() = CfOptimizerTuning.effectiveMinScore(minScoreRaw, downloadTestEnabled)

    /** 最终输出条数上限（原版 SMART_PUSH_MAX_TOTAL）。 */
    var maxEntriesRaw: String by store.string(
        key = "cfoptimizer_max_entries",
        defaultValue = CfOptimizerTuning.MAX_ENTRIES_DEFAULT.toString(),
    )

    val maxEntries: Int get() = CfOptimizerTuning.maxEntries(maxEntriesRaw)

    /** 原始池 ÷ 探测上限的倍数（原版 MAX_TASK_LIMIT 的倍数表达）。 */
    var rawPoolFactorRaw: String by store.string(
        key = "cfoptimizer_raw_pool_factor",
        defaultValue = CfOptimizerTuning.RAW_POOL_FACTOR_DEFAULT.toString(),
    )

    val rawPoolFactor: Int get() = CfOptimizerTuning.rawPoolFactor(rawPoolFactorRaw)

    /** 评分权重：TTFB 分量（原版 SCORE_TTFB_WEIGHT）。 */
    var scoreTtfbWeightRaw: String by store.string(
        key = "cfoptimizer_score_ttfb_weight",
        defaultValue = CfOptimizerTuning.SCORE_TTFB_WEIGHT_DEFAULT.toString(),
    )

    /** 评分权重：带宽分量（原版 SCORE_BW_WEIGHT）。 */
    var scoreBwWeightRaw: String by store.string(
        key = "cfoptimizer_score_bw_weight",
        defaultValue = CfOptimizerTuning.SCORE_BW_WEIGHT_DEFAULT.toString(),
    )

    /** 归一化后的评分权重（两项都为 0 → 回落默认，见 CfOptimizerTuning.scoreWeights）。 */
    val scoreWeights: ScoreWeights
        get() = CfOptimizerTuning.scoreWeights(scoreTtfbWeightRaw, scoreBwWeightRaw)

    /** IPv4 去重前缀长度（原版 IPV4_DEDUP_PREFIX）：同前缀只留最高分，防名单挤在一个网段。 */
    var dedupPrefixV4Raw: String by store.string(
        key = "cfoptimizer_dedup_prefix_v4",
        defaultValue = CfOptimizerTuning.DEDUP_PREFIX_V4_DEFAULT.toString(),
    )

    val dedupPrefixV4: Int get() = CfOptimizerTuning.dedupPrefixV4(dedupPrefixV4Raw)

    /**
     * 劣化自动补货的最小间隔（小时，原版无此项）：距上次优选不足这个时长不触发。
     * **0 = 不节流** —— 失败循环（劣化又修不好）失去兜底，质量门每 30 分钟空跑一轮。
     */
    var minIntervalHoursRaw: String by store.string(
        key = "cfoptimizer_min_interval_hours",
        defaultValue = CfOptimizerTuning.MIN_INTERVAL_HOURS_DEFAULT.toString(),
    )

    val minIntervalHours: Int get() = CfOptimizerTuning.minIntervalHours(minIntervalHoursRaw)

    // ── 记忆库（原版 cf_memory.py）──

    /** 超期清除（天，原版 STALE_DAYS）。 */
    var memoryStaleDaysRaw: String by store.string(
        key = "cfoptimizer_memory_stale_days",
        defaultValue = CfOptimizerTuning.MEMORY_STALE_DAYS_DEFAULT.toString(),
    )

    /** 衰减起点（天，原版 DECAY_DAYS）。 */
    var memoryDecayDaysRaw: String by store.string(
        key = "cfoptimizer_memory_decay_days",
        defaultValue = CfOptimizerTuning.MEMORY_DECAY_DAYS_DEFAULT.toString(),
    )

    /** 连续失败多少次移出优先池（原版 MAX_FAIL_STREAK）。 */
    var memoryMaxFailStreakRaw: String by store.string(
        key = "cfoptimizer_memory_max_fail_streak",
        defaultValue = CfOptimizerTuning.MEMORY_MAX_FAIL_STREAK_DEFAULT.toString(),
    )

    /** 记忆库容量（原版 MEMORY_MAX_SIZE）。 */
    var memoryMaxSizeRaw: String by store.string(
        key = "cfoptimizer_memory_max_size",
        defaultValue = CfOptimizerTuning.MEMORY_MAX_SIZE_DEFAULT.toString(),
    )

    /** 时段分桶数（原版 HOUR_BUCKETS）。 */
    var memoryHourBucketsRaw: String by store.string(
        key = "cfoptimizer_memory_hour_buckets",
        defaultValue = CfOptimizerTuning.MEMORY_HOUR_BUCKETS_DEFAULT.toString(),
    )

    /** 每轮优先复测池大小（原版 get_priority_candidates(top_n)）。0 = 不复测历史节点。 */
    var memoryPoolLimitRaw: String by store.string(
        key = "cfoptimizer_memory_pool_limit",
        defaultValue = CfOptimizerTuning.MEMORY_POOL_LIMIT_DEFAULT.toString(),
    )

    /** 记忆库运行参数快照（一轮读一次，避免每个候选都解析字符串）。 */
    val memoryTuning: CfMemoryTuning
        get() = CfMemoryTuning(
            staleDays = CfOptimizerTuning.memoryStaleDays(memoryStaleDaysRaw),
            decayDays = CfOptimizerTuning.memoryDecayDays(memoryDecayDaysRaw),
            maxFailStreak = CfOptimizerTuning.memoryMaxFailStreak(memoryMaxFailStreakRaw),
            maxSize = CfOptimizerTuning.memoryMaxSize(memoryMaxSizeRaw),
            hourBuckets = CfOptimizerTuning.memoryHourBuckets(memoryHourBucketsRaw),
        )

    val memoryPoolLimit: Int get() = CfOptimizerTuning.memoryPoolLimit(memoryPoolLimitRaw)

    /**
     * 下载测速（默认开）：对 TTFB 最优的窄池测吞吐，补上评分里占 40% 的带宽分量。
     * 关掉可以省流量（单次最多约 20 × 3MB），但排序会退化成纯延迟排序。
     */
    var downloadTestEnabled: Boolean by store.boolean(
        key = "cfoptimizer_download_test_enabled",
        defaultValue = true,
    )

    /**
     * 跨轮记忆库（默认开）：记住每个 IP 的历史表现——下轮把稳定节点优先拉回复测、
     * 连续失败的在冷却期内不再取样探测。关掉 = 每轮从零开始（原版脚本默认行为），
     * 供"有记忆 vs 无记忆"的对照实验用。
     */
    var memoryEnabled: Boolean by store.boolean(
        key = "cfoptimizer_memory_enabled",
        defaultValue = true,
    )

    private val customEntriesDelegate by store.string(
        key = "cfoptimizer_custom_entries",
        defaultValue = "",
    )

    var customEntries: List<String>?
        get() =
            customEntriesDelegate
                .split('\n')
                .filter { it.isNotBlank() }
                .ifEmpty { null }
        set(value) {
            // Store delegate is not directly assignable via `by`; write through the provider.
            store.provider.setString("cfoptimizer_custom_entries", value?.joinToString("\n") ?: "")
        }

    fun snapshot(): CfOptimizerSettings =
        CfOptimizerSettings(
            enabled = enabled,
            workerBaseUrl = workerBaseUrl,
            subscriptionProfileId = subscriptionProfileId,
            confirmedSharedListOwnership = confirmedSharedListOwnership,
            customEntries = customEntries ?: emptyList(),
            autoHealEnabled = autoHealEnabled,
            lastRunAt = lastRunAt,
        )
}
