package com.github.kr328.clash.service.cfoptimizer.settings

import com.github.kr328.clash.service.cfoptimizer.ScoreWeights

/**
 * CF 优选用户可调参数 —— 默认值、取值范围与解析规则的**单一来源**（纯逻辑，无 Android 依赖，
 * 沙箱 JVM 可直接测）。
 *
 * 三件事只在这里定义，其它地方一律引用：
 *  1. **默认值** = 移植时的实测值（原版同机日志/原版 `cf_config.py`），不是拍脑袋；
 *  2. **取值范围** = 只拦"填了会把自己搞坏"的输入（0 并发、负超时、空权重…），
 *     范围内的值一律照用户填的用 —— 它不是替用户做判断的推荐区间；
 *  3. **解析规则** = 空串/非法值回落默认（设置页允许清空，清空 = 恢复默认）。
 *
 * 每个参数的原版对应物写在注释里，便于与原脚本逐项对账。
 */
object CfOptimizerTuning {
    // ───────────────────────── 抓取配额（原版 URL_SOURCES/sample_limit 的手机端等价物） ─────────────────────────

    /** 每轮从源池抽取的源数 —— 原版动态通讯录（约百个源）。默认 8：多抽几个只多几 KB 流量。 */
    const val SOURCES_PER_RUN_DEFAULT = 8
    const val SOURCES_PER_RUN_MIN = 1
    const val SOURCES_PER_RUN_MAX = 12

    /** 单源抽样条数 —— 原版 `sample_limit`（1000/3000）。默认 250：250 × 8 源 ≈ 2000 条，与原版同量级。 */
    const val PER_SOURCE_SAMPLE_DEFAULT = 250
    const val PER_SOURCE_SAMPLE_MIN = 1
    const val PER_SOURCE_SAMPLE_MAX = 3_000

    /**
     * 每轮进入 TTFB 探测的候选上限（**昂贵层**）—— 默认 600。
     *
     * 原始池 = 本值 × [RAW_POOL_FACTOR_DEFAULT]，存活率 71.4%（原版真机实测）时约 850 存活、探 600。
     * 上限沿用引擎的原始池天花板（`RAW_POOL_CEILING`），免得填出一个把手机烤了的数。
     */
    const val MAX_CANDIDATES_DEFAULT = 600
    const val MAX_CANDIDATES_MIN = 1
    const val MAX_CANDIDATES_MAX = 20_000

    /** 上传质量门 —— 原版 `SMART_PUSH_MIN_NODES`（3；原版真机一轮推了 8 个）。默认 6。 */
    const val MIN_UPLOAD_ENTRIES_DEFAULT = 6
    const val MIN_UPLOAD_ENTRIES_MIN = 1
    const val MIN_UPLOAD_ENTRIES_MAX = 100

    /** 每地区最多保留条数 —— 原版 `SMART_PUSH_IPS_PER_CC`（4；原版实测每地区 4、合计 8）。0 = 不限。 */
    const val MAX_PER_REGION_DEFAULT = 3
    const val MAX_PER_REGION_MIN = 0
    const val MAX_PER_REGION_MAX = 50

    // ───────────────────────── 探测段（原版 cf_config.py 探测参数） ─────────────────────────

    /**
     * TCP 预筛并发 —— 原版 `MAX_TCP_WORKERS`（500，区间 `TCP_WORKERS_MIN_CAP 100`–`MAX_CAP 500`）。
     *
     * 默认 400 = 原版稳态的 80%：原版有 PID 自整定 + 10% 均值回归兜底，我们的默认值是静态的，
     * 留 20% 余量补这个差。原版在**同一台手机**上以 500 并发 7 秒筛完 6397 个候选 ——
     * 400 不是拍的：纯 connect 不占 TLS 栈、失败快，单价远低于 TTFB。
     */
    const val TCP_CONCURRENCY_DEFAULT = 400
    const val TCP_CONCURRENCY_MIN = 10
    const val TCP_CONCURRENCY_MAX = 2000

    /**
     * TCP connect 超时（毫秒）—— 原版 `TCP_TIMEOUT`（1.0 秒；自动模式区间 0.6–2.0）。
     *
     * 默认 900ms：原版真机日志自整定到 0.86–0.88s。黑洞地址靠它兜底，只给一次机会 ——
     * 漏斗的意义就是把"确认它是死的"做便宜。900ms 对 300–400ms RTT 的真实节点仍有余量。
     */
    const val TCP_TIMEOUT_MS_DEFAULT = 900
    const val TCP_TIMEOUT_MS_MIN = 200
    const val TCP_TIMEOUT_MS_MAX = 5000

    /**
     * TTFB/trace 探测并发 —— 原版 `MAX_TTFB_WORKERS`（50，真机实测 54→57）/ `MAX_TRACE_WORKERS`（100）。
     *
     * 默认 40 ≈ 原版稳态的 70%：这里是完整 TLS 握手 + HTTP 请求，比 TCP connect 重，
     * 每个候选还要排 3 次采样。原版同机 1008 候选 × 3 采样 76 秒跑完；我们 600 候选在
     * 40 并发下约 45–60 秒，同一量级。
     */
    const val PROBE_CONCURRENCY_DEFAULT = 40
    const val PROBE_CONCURRENCY_MIN = 1
    const val PROBE_CONCURRENCY_MAX = 200

    /** 单候选 TTFB 采样次数 —— 原版硬编码 `for _ in range(3)`（取中位数 + 全距抖动）。 */
    const val TTFB_SAMPLES_DEFAULT = 3
    const val TTFB_SAMPLES_MIN = 1
    const val TTFB_SAMPLES_MAX = 5

    // ───────────────────────── 测速段（流量消耗直接由这一族决定） ─────────────────────────

    /**
     * 进测速的窄池大小 —— 原版 `BW_TOP_N`（4，测速前 N 个）/ `TTFB_POOL_LIMIT`（120，池闸门，实测 80→76）。
     *
     * 默认 40：40 × 3 MiB = 最坏 120 MB，但早停（100 Mbps、至少测 1 秒）会让快节点远低于此，
     * 典型一轮 30–60 MB。流量敏感用户可把这个值调小，或直接关掉下载测速。
     */
    const val DOWNLOAD_POOL_LIMIT_DEFAULT = 40
    const val DOWNLOAD_POOL_LIMIT_MIN = 1
    const val DOWNLOAD_POOL_LIMIT_MAX = 200

    /** 测速并发 —— 原版 `MAX_BW_WORKERS`（10）。默认比探测低：每个连接都在持续吃带宽，并发高会互相抢。 */
    const val DOWNLOAD_CONCURRENCY_DEFAULT = 4
    const val DOWNLOAD_CONCURRENCY_MIN = 1
    const val DOWNLOAD_CONCURRENCY_MAX = 32

    /** 单节点测速下载量（MiB）—— 原版 `BW_MB`（10）。默认 3 MiB：足够区分 30 / 150 Mbps 量级，又不烧流量。 */
    const val DOWNLOAD_SIZE_MB_DEFAULT = 3
    const val DOWNLOAD_SIZE_MB_MIN = 1
    const val DOWNLOAD_SIZE_MB_MAX = 20

    /** 单节点测速超时（毫秒）—— 原版 `BW_TIMEOUT`（15 秒）。到点按已读字节结算。 */
    const val DOWNLOAD_TIMEOUT_MS_DEFAULT = 4_000
    const val DOWNLOAD_TIMEOUT_MS_MIN = 1_000
    const val DOWNLOAD_TIMEOUT_MS_MAX = 30_000

    /**
     * 到速即停（Mbps）—— 原版 `BW_EARLY_STOP_MBPS`（80）。
     *
     * 默认 100 = 评分参考带宽（150）的三分之二：过线后带宽分量已拿到大部分分数，再测只烧流量。
     * **别照抄原版的 30** —— 原版日志里 33.2/33.0/32.9 密集堆在早停线附近，那些读数是下界不是
     * 真实带宽，用来排序分不出节点好坏。
     */
    const val DOWNLOAD_EARLY_STOP_MBPS_DEFAULT = 100.0
    const val DOWNLOAD_EARLY_STOP_MBPS_MIN = 1.0
    const val DOWNLOAD_EARLY_STOP_MBPS_MAX = 1_000.0

    // ───────────────────────── 质量门槛与输出形态 ─────────────────────────

    /** TTFB 上限（毫秒），超过即丢弃 —— 原版 `TTFB_MAX`（0.8 秒；原版会在节点不足时自动放宽）。 */
    const val MAX_TTFB_MS_DEFAULT = 800
    const val MAX_TTFB_MS_MIN = 100
    const val MAX_TTFB_MS_MAX = 5_000

    /** 抖动上限（毫秒）—— 原版 `TTFB_JITTER_LIMIT`（0.20 秒；可放宽到 `TTFB_JITTER_MAX` 0.25）。 */
    const val MAX_JITTER_MS_DEFAULT = 200
    const val MAX_JITTER_MS_MIN = 10
    const val MAX_JITTER_MS_MAX = 2_000

    /** 评分门槛分（0–100 量纲）—— 原版 `SMART_PUSH_MIN_SCORE`（30）。关下载测速时按 `MIN_SCORE_OFF_RATIO` 折半。 */
    const val MIN_SCORE_DEFAULT = 30.0
    const val MIN_SCORE_MIN = 0.0
    const val MIN_SCORE_MAX = 100.0

    /** 关下载测速时的门槛折半系数 —— 带宽分量（0.4 权重）恒为 0，满分只剩 60，门槛等比下调。 */
    const val MIN_SCORE_OFF_RATIO = 0.5

    /** 最终输出条数上限 —— 原版 `SMART_PUSH_MAX_TOTAL`（12）。 */
    const val MAX_ENTRIES_DEFAULT = 12
    const val MAX_ENTRIES_MIN = 1
    const val MAX_ENTRIES_MAX = 100

    /**
     * 原始池 ÷ 探测上限的倍数 —— 原版 `MAX_TASK_LIMIT`（5000，入口探测任务上限）的倍数表达。
     *
     * 默认 2，依据（2026-09-29 实测，替换原先"存活率 5–15%"的错误假设）：原版真机日志给出这批源
     * （bestcf / zip.cm 精选列表，不是随机 CIDR）的 **TCP 存活率 71.4%**（6397 存活 4566）。
     * 旧的 10 倍会这样失效：原始池 3000 → 约 2100 存活，而昂贵层只探 300 →
     * **86% 的存活节点连测都没测就被随机丢掉**，白付 TCP 那一段的钱。
     * 2 倍：`maxCandidates=600` → 原始池 1200 → 约 850 存活 → 探 600（覆盖约 70%），
     * 覆盖率与耗时同时优于 10 倍。只有存活率极低（随机 CIDR 盲扫，约 1–5%）才需要调大。
     */
    const val RAW_POOL_FACTOR_DEFAULT = 2
    const val RAW_POOL_FACTOR_MIN = 1
    const val RAW_POOL_FACTOR_MAX = 10

    // ───────────────────────── 评分与去重 ─────────────────────────

    /** TTFB 分量权重 —— 原版 `SCORE_TTFB_WEIGHT`（0.6）。 */
    const val SCORE_TTFB_WEIGHT_DEFAULT = 0.6
    const val SCORE_TTFB_WEIGHT_MIN = 0.0
    const val SCORE_TTFB_WEIGHT_MAX = 1.0

    /** 带宽分量权重 —— 原版 `SCORE_BW_WEIGHT`（0.4）。 */
    const val SCORE_BW_WEIGHT_DEFAULT = 0.4
    const val SCORE_BW_WEIGHT_MIN = 0.0
    const val SCORE_BW_WEIGHT_MAX = 1.0

    /** IPv4 去重前缀长度 —— 原版 `IPV4_DEDUP_PREFIX`（24）。同前缀只留最高分，防名单挤在一个网段。 */
    const val DEDUP_PREFIX_V4_DEFAULT = 24
    const val DEDUP_PREFIX_V4_MIN = 8
    const val DEDUP_PREFIX_V4_MAX = 32

    // ───────────────────────── 记忆库（原版 cf_memory.py） ─────────────────────────

    /** 超期清除（天）—— 原版 `STALE_DAYS`（14）。 */
    const val MEMORY_STALE_DAYS_DEFAULT = 14.0
    const val MEMORY_STALE_DAYS_MIN = 1.0
    const val MEMORY_STALE_DAYS_MAX = 365.0

    /** 衰减起点（天）—— 原版 `DECAY_DAYS`（7）。 */
    const val MEMORY_DECAY_DAYS_DEFAULT = 7.0
    const val MEMORY_DECAY_DAYS_MIN = 0.5
    const val MEMORY_DECAY_DAYS_MAX = 180.0

    /** 连续失败多少次移出优先池 —— 原版 `MAX_FAIL_STREAK`（3）。 */
    const val MEMORY_MAX_FAIL_STREAK_DEFAULT = 3
    const val MEMORY_MAX_FAIL_STREAK_MIN = 1
    const val MEMORY_MAX_FAIL_STREAK_MAX = 20

    /** 记忆库容量 —— 原版 `MEMORY_MAX_SIZE`（5000）。 */
    const val MEMORY_MAX_SIZE_DEFAULT = 5_000
    const val MEMORY_MAX_SIZE_MIN = 100
    const val MEMORY_MAX_SIZE_MAX = 50_000

    /** 时段分桶数 —— 原版 `HOUR_BUCKETS`（4，每 6 小时一段）。 */
    const val MEMORY_HOUR_BUCKETS_DEFAULT = 4
    const val MEMORY_HOUR_BUCKETS_MIN = 1
    const val MEMORY_HOUR_BUCKETS_MAX = 12

    /** 每轮优先复测池大小 —— 原版 `get_priority_candidates(top_n=100)`（100）。0 = 不复测历史节点。 */
    const val MEMORY_POOL_LIMIT_DEFAULT = 100
    const val MEMORY_POOL_LIMIT_MIN = 0
    const val MEMORY_POOL_LIMIT_MAX = 500

    // ───────────────────────── 解析（空/非法 → 默认；范围内 → 照用） ─────────────────────────

    fun tcpConcurrency(raw: String?): Int =
        intOf(raw, TCP_CONCURRENCY_DEFAULT, TCP_CONCURRENCY_MIN, TCP_CONCURRENCY_MAX)

    fun sourcesPerRun(raw: String?): Int =
        intOf(raw, SOURCES_PER_RUN_DEFAULT, SOURCES_PER_RUN_MIN, SOURCES_PER_RUN_MAX)

    fun perSourceSample(raw: String?): Int =
        intOf(raw, PER_SOURCE_SAMPLE_DEFAULT, PER_SOURCE_SAMPLE_MIN, PER_SOURCE_SAMPLE_MAX)

    fun maxCandidates(raw: String?): Int =
        intOf(raw, MAX_CANDIDATES_DEFAULT, MAX_CANDIDATES_MIN, MAX_CANDIDATES_MAX)

    fun minUploadEntries(raw: String?): Int =
        intOf(raw, MIN_UPLOAD_ENTRIES_DEFAULT, MIN_UPLOAD_ENTRIES_MIN, MIN_UPLOAD_ENTRIES_MAX)

    fun maxPerRegion(raw: String?): Int =
        intOf(raw, MAX_PER_REGION_DEFAULT, MAX_PER_REGION_MIN, MAX_PER_REGION_MAX)

    fun tcpTimeoutMs(raw: String?): Int =
        intOf(raw, TCP_TIMEOUT_MS_DEFAULT, TCP_TIMEOUT_MS_MIN, TCP_TIMEOUT_MS_MAX)

    fun probeConcurrency(raw: String?): Int =
        intOf(raw, PROBE_CONCURRENCY_DEFAULT, PROBE_CONCURRENCY_MIN, PROBE_CONCURRENCY_MAX)

    fun ttfbSamples(raw: String?): Int =
        intOf(raw, TTFB_SAMPLES_DEFAULT, TTFB_SAMPLES_MIN, TTFB_SAMPLES_MAX)

    fun downloadPoolLimit(raw: String?): Int =
        intOf(raw, DOWNLOAD_POOL_LIMIT_DEFAULT, DOWNLOAD_POOL_LIMIT_MIN, DOWNLOAD_POOL_LIMIT_MAX)

    fun downloadConcurrency(raw: String?): Int =
        intOf(raw, DOWNLOAD_CONCURRENCY_DEFAULT, DOWNLOAD_CONCURRENCY_MIN, DOWNLOAD_CONCURRENCY_MAX)

    fun downloadSizeMb(raw: String?): Int =
        intOf(raw, DOWNLOAD_SIZE_MB_DEFAULT, DOWNLOAD_SIZE_MB_MIN, DOWNLOAD_SIZE_MB_MAX)

    fun downloadTimeoutMs(raw: String?): Int =
        intOf(raw, DOWNLOAD_TIMEOUT_MS_DEFAULT, DOWNLOAD_TIMEOUT_MS_MIN, DOWNLOAD_TIMEOUT_MS_MAX)

    fun downloadEarlyStopMbps(raw: String?): Double =
        doubleOf(
            raw,
            DOWNLOAD_EARLY_STOP_MBPS_DEFAULT,
            DOWNLOAD_EARLY_STOP_MBPS_MIN,
            DOWNLOAD_EARLY_STOP_MBPS_MAX,
        )

    fun maxTtfbMs(raw: String?): Int = intOf(raw, MAX_TTFB_MS_DEFAULT, MAX_TTFB_MS_MIN, MAX_TTFB_MS_MAX)

    fun maxJitterMs(raw: String?): Int = intOf(raw, MAX_JITTER_MS_DEFAULT, MAX_JITTER_MS_MIN, MAX_JITTER_MS_MAX)

    fun minScore(raw: String?): Double = doubleOf(raw, MIN_SCORE_DEFAULT, MIN_SCORE_MIN, MIN_SCORE_MAX)

    /** 生效门槛：关下载测速时带宽分量恒为 0（满分只剩 60），门槛等比折半。 */
    fun effectiveMinScore(raw: String?, downloadTestEnabled: Boolean): Double =
        minScore(raw) * if (downloadTestEnabled) 1.0 else MIN_SCORE_OFF_RATIO

    fun maxEntries(raw: String?): Int = intOf(raw, MAX_ENTRIES_DEFAULT, MAX_ENTRIES_MIN, MAX_ENTRIES_MAX)

    fun rawPoolFactor(raw: String?): Int = intOf(raw, RAW_POOL_FACTOR_DEFAULT, RAW_POOL_FACTOR_MIN, RAW_POOL_FACTOR_MAX)

    fun dedupPrefixV4(raw: String?): Int =
        intOf(raw, DEDUP_PREFIX_V4_DEFAULT, DEDUP_PREFIX_V4_MIN, DEDUP_PREFIX_V4_MAX)

    fun memoryStaleDays(raw: String?): Double =
        doubleOf(raw, MEMORY_STALE_DAYS_DEFAULT, MEMORY_STALE_DAYS_MIN, MEMORY_STALE_DAYS_MAX)

    fun memoryDecayDays(raw: String?): Double =
        doubleOf(raw, MEMORY_DECAY_DAYS_DEFAULT, MEMORY_DECAY_DAYS_MIN, MEMORY_DECAY_DAYS_MAX)

    fun memoryMaxFailStreak(raw: String?): Int =
        intOf(raw, MEMORY_MAX_FAIL_STREAK_DEFAULT, MEMORY_MAX_FAIL_STREAK_MIN, MEMORY_MAX_FAIL_STREAK_MAX)

    fun memoryMaxSize(raw: String?): Int =
        intOf(raw, MEMORY_MAX_SIZE_DEFAULT, MEMORY_MAX_SIZE_MIN, MEMORY_MAX_SIZE_MAX)

    fun memoryHourBuckets(raw: String?): Int =
        intOf(raw, MEMORY_HOUR_BUCKETS_DEFAULT, MEMORY_HOUR_BUCKETS_MIN, MEMORY_HOUR_BUCKETS_MAX)

    fun memoryPoolLimit(raw: String?): Int =
        intOf(raw, MEMORY_POOL_LIMIT_DEFAULT, MEMORY_POOL_LIMIT_MIN, MEMORY_POOL_LIMIT_MAX)

    /**
     * 评分权重。两权重之和为 0（用户两个都填 0）时**回落默认** —— 否则所有候选得分恒为 0、
     * 名单被门槛清空，那不是"调参"，是把自己关在门外。
     * 返回值按权重之和归一化：满分恒为 100，门槛分（0–100 量纲）才不会随权重漂移。
     */
    fun scoreWeights(rawTtfb: String?, rawBw: String?): ScoreWeights {
        val ttfb = doubleOf(rawTtfb, SCORE_TTFB_WEIGHT_DEFAULT, SCORE_TTFB_WEIGHT_MIN, SCORE_TTFB_WEIGHT_MAX)
        val bw = doubleOf(rawBw, SCORE_BW_WEIGHT_DEFAULT, SCORE_BW_WEIGHT_MIN, SCORE_BW_WEIGHT_MAX)

        if (ttfb + bw <= 0.0) return ScoreWeights()

        return ScoreWeights(ttfb = ttfb, bw = bw)
    }

    private fun intOf(raw: String?, default: Int, min: Int, max: Int): Int =
        raw?.trim()?.toIntOrNull()?.coerceIn(min, max) ?: default

    private fun doubleOf(raw: String?, default: Double, min: Double, max: Double): Double =
        raw?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(min, max) ?: default
}
