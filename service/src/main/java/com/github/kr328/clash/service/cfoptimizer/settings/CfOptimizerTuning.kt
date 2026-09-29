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

    /**
     * 每轮从源池抽取的源数 —— 原版动态通讯录（2026-09-30 实测导航站 428 个链接，其中 104 个 .txt 源）。
     *
     * 默认 12，依据实测产出分布：单源产出极不均 —— 最富的 `zip.cm.edu.kg/all.txt` 有 15490 行，
     * 而导航站里多数 .txt 源只有 2–50 行。只抽 8 个源时很容易整套抽到小源，真机一轮实测
     * 原始候选只有 594 条（远低于 8 × 250 的预期）。多抽几个源的边际成本只是每个源一次 HTTP、
     * 几 KB–几十 KB 流量，买到的是"抽到富源"的概率。
     */
    const val SOURCES_PER_RUN_DEFAULT = 12
    const val SOURCES_PER_RUN_MIN = 1
    const val SOURCES_PER_RUN_MAX = 24

    /**
     * 单源抽样条数 —— 原版 `sample_limit`（1000/3000）。
     *
     * 默认 1000：原版单源就是 1000–3000 条，而实测最富的源有 15490 行 —— 250 的抽样等于
     * 把这个源丢掉了 98%。抽样条数只影响**便宜层**（TCP connect 的价钱），昂贵层由
     * [MAX_CANDIDATES_DEFAULT] 单独封顶，所以放大它是安全的。
     */
    const val PER_SOURCE_SAMPLE_DEFAULT = 1_000
    const val PER_SOURCE_SAMPLE_MIN = 1
    const val PER_SOURCE_SAMPLE_MAX = 3_000

    /**
     * 每轮进入 TTFB 探测的候选上限（**昂贵层**）—— 默认 1200（原版：全部存活集都探，实测一轮探过 1250）。
     *
     * 原始池 = 本值 × [RAW_POOL_FACTOR_DEFAULT] = 2400，存活率 71.4%（原版真机实测）时约 1700 存活、
     * 探 1200。这个数直接决定**最终能选出几个节点**：最终名单 ≈ 有效探测量的 3–6%
     * （抖动门槛 ≤200ms 刷掉绝大多数，实测 445 探出 6 个）。探 445 只有 6 个，探 1200 才有原版的 6–7 个
     * 且是从更大的池子里挑的 —— 真机一轮实测：探 445 时入选的节点带宽只有 0.31–12 Mbps，
     * 而原版 1250 探出的是 5.2–28.5 Mbps。
     * 上限沿用引擎的原始池天花板（`RAW_POOL_CEILING`），免得填出一个把手机烤了的数。
     */
    const val MAX_CANDIDATES_DEFAULT = 1_200
    const val MAX_CANDIDATES_MIN = 1
    const val MAX_CANDIDATES_MAX = 20_000

    /** 上传质量门 —— 原版 `SMART_PUSH_MIN_NODES`（3；原版真机一轮推了 8 个）。默认 6。 */
    const val MIN_UPLOAD_ENTRIES_DEFAULT = 6
    const val MIN_UPLOAD_ENTRIES_MIN = 1
    const val MIN_UPLOAD_ENTRIES_MAX = 100

    /** 每地区最多保留条数 —— 原版 `SMART_PUSH_IPS_PER_CC`（4；原版实测每地区 4、合计 8）。0 = 不限。 */
    const val MAX_PER_REGION_DEFAULT = 4
    const val MAX_PER_REGION_MIN = 0
    const val MAX_PER_REGION_MAX = 50

    // ───────────────────────── 探测段（原版 cf_config.py 探测参数） ─────────────────────────

    /**
     * TCP 预筛并发 —— 原版 `MAX_TCP_WORKERS`（500，区间 `TCP_WORKERS_MIN_CAP 100`–`MAX_CAP 500`）。
     *
     * 默认 500 = 原版稳态值：原版有 PID 自整定 + 10% 均值回归兜底，我们静态取值时先按原版走，
     * 不够再往上调（这是纯 connect，不占 TLS 栈、失败快，单价远低于 TTFB）。
     * 原版在**同一台手机**上以 500 并发 7 秒筛完 6397 个候选。
     */
    const val TCP_CONCURRENCY_DEFAULT = 500
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
     * 默认 50 = 原版 `MAX_TTFB_WORKERS` 稳态值（真机实测 54→57）。这里是完整 TLS 握手 + HTTP 请求，
     * 比 TCP connect 重，每个候选还要排 3 次采样 —— 但这一段的**吞吐直接决定最终产出**，
     * 宁可在这里多给一点并发。
     */
    const val PROBE_CONCURRENCY_DEFAULT = 50
    const val PROBE_CONCURRENCY_MIN = 1
    const val PROBE_CONCURRENCY_MAX = 200

    /**
     * 地区解析（trace）并发 —— 原版 `MAX_TRACE_WORKERS`（100）。
     *
     * trace 是**单次** GET `/cdn-cgi/trace`（几百字节、无采样），单价远低于 TTFB 的三次采样，
     * 所以单独给一档更高的并发：它跑在 TTFB 之前，用于把黑名单地区**挡在昂贵的采样之前**
     * （原版就是这个顺序，见 `Cloudflare_____v4.6.py` 的 trace 循环）。顺序反了会怎样：
     * 实测一轮 445 次 TTFB 探测里有 229 次（51%）打在随后就被丢弃的地区上。
     */
    const val TRACE_CONCURRENCY_DEFAULT = 100
    const val TRACE_CONCURRENCY_MIN = 1
    const val TRACE_CONCURRENCY_MAX = 500

    /** 单候选 TTFB 采样次数 —— 原版硬编码 `for _ in range(3)`（取中位数 + 全距抖动）。 */
    const val TTFB_SAMPLES_DEFAULT = 3
    const val TTFB_SAMPLES_MIN = 1
    const val TTFB_SAMPLES_MAX = 5

    // ───────────────────────── 测速段（流量消耗直接由这一族决定） ─────────────────────────

    /**
     * 进测速的窄池大小 —— 原版 `BW_TOP_N`（4，测速前 N 个）/ `TTFB_POOL_LIMIT`（120，池闸门，实测 80→76）。
     *
     * 默认 60（原版池闸门 120，实测 13–120）：池子只按 TTFB 排序取前 N 个，
     *     池子越大，"TTFB 快但带宽差"的节点越不容易把真正快的节点挤出测速名单。
     *     流量 = 池 × 单节点下载量 × 早停削减；60 × 6 MiB = 最坏 360 MB，典型一轮 40–80 MB。
     *     流量敏感用户可把这个值调小，或直接关掉下载测速。
     */
    const val DOWNLOAD_POOL_LIMIT_DEFAULT = 60
    const val DOWNLOAD_POOL_LIMIT_MIN = 1
    const val DOWNLOAD_POOL_LIMIT_MAX = 200

    /** 测速并发 —— 原版 `MAX_BW_WORKERS`（10）。默认 8：每个连接都在持续吃带宽，并发高了会互相抢，
     * 但太小会让池子排队（4 并发跑 40 个池要 10 轮），实测一轮 40 池只出 19 条读数。 */
    const val DOWNLOAD_CONCURRENCY_DEFAULT = 8
    const val DOWNLOAD_CONCURRENCY_MIN = 1
    const val DOWNLOAD_CONCURRENCY_MAX = 32

    /** 单节点测速下载量（MiB）—— 原版 `BW_MB`（10）。默认 6 MiB：3 MiB 在 20 Mbps 上 1.2 秒就读完，
     * 测到的主要是 TCP 慢启动那一段（偏低）；6 MiB 让读数更接近稳态，同时不到原版的一半流量。 */
    const val DOWNLOAD_SIZE_MB_DEFAULT = 6
    const val DOWNLOAD_SIZE_MB_MIN = 1
    const val DOWNLOAD_SIZE_MB_MAX = 20

    /** 单节点测速超时（毫秒）—— 原版 `BW_TIMEOUT` 15 秒，`TEST_DURATION = min(5, max(2, BW_TIMEOUT-1))`
     * = **实际 5 秒硬截止**（这是 `Cloudflare_____v4.6.py` 的真实行为）。默认 5000 对齐它。
     * 到点按已读字节结算（不丢弃部分读数）。 */
    const val DOWNLOAD_TIMEOUT_MS_DEFAULT = 5_000
    const val DOWNLOAD_TIMEOUT_MS_MIN = 1_000
    const val DOWNLOAD_TIMEOUT_MS_MAX = 30_000

    /**
     * 到速即停（Mbps）—— 原版 `BW_EARLY_STOP_MBPS`（80）。
     *
     * 默认 80 = 原版值：过线后带宽分量已拿到大部分分数，再测只烧流量。
     * **别照抄原版的 30**（那是另一处旧值）—— 原版日志里 33.2/33.0/32.9 密集堆在早停线附近，
     * 那些读数是下界不是真实带宽，用来排序分不出节点好坏。
     */
    const val DOWNLOAD_EARLY_STOP_MBPS_DEFAULT = 80.0
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

    /**
     * 评分里"带宽分量满分的参考值"（Mbps）—— 原版 `SCORE_MAX_BPS = 150`。
     *
     * **默认 50 是刻意的偏离，理由是真机实测**：原版 `score = 0.6×(1−ttfb/800ms) + 0.4×min(1, mbps/150)`，
     * 在 10–30 Mbps 的移动链路上 `mbps/150` 只有 0.07–0.2 —— 带宽项满打满算贡献 2.7–8 分，
     * 而 TTFB 每差 100ms 就是 7.5 分。结果就是**排序实际上只按 TTFB**：真机一轮选出的 6 条
     * TTFB 159–300ms 很好、带宽却只有 0.31–12 Mbps，同源同机的原版 1250 个候选选出的是 5.2–28.5 Mbps。
     * 50 让带宽项在自己的链路上真正有区分度（10 Mbps → 8 分，30 Mbps → 24 分）。
     * **调法**：设成"自己链路实测带宽的 1–2 倍"；链路很快（>200 Mbps）时可以调回 150 恢复原版口径。
     */
    const val SCORE_BW_REF_MBPS_DEFAULT = 50.0
    const val SCORE_BW_REF_MBPS_MIN = 1.0
    const val SCORE_BW_REF_MBPS_MAX = 1_000.0

    /**
     * 带宽下限（Mbps）—— 低于此值的候选直接不进最终名单。
     *
     * 原版没有这一条，但原版的最终名单是 `final = select(bw_results)` —— **只从测速成功的集合里挑**，
     * 而我们是"测速失败/极慢也留在池子里"。实测后果：一条 0.307 Mbps 的节点拿 48.2 分入选
     * （TTFB 项单独就给 60 分里的大头）。默认 1.0 Mbps：低于它实际不可用，不如把名额留给别的地区。
     * 0 = 不启用（回到旧行为）。
     */
    const val MIN_DOWNLOAD_MBPS_DEFAULT = 1.0
    const val MIN_DOWNLOAD_MBPS_MIN = 0.0
    const val MIN_DOWNLOAD_MBPS_MAX = 100.0

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

    fun traceConcurrency(raw: String?): Int =
        intOf(raw, TRACE_CONCURRENCY_DEFAULT, TRACE_CONCURRENCY_MIN, TRACE_CONCURRENCY_MAX)

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

    fun minDownloadMbps(raw: String?): Double =
        doubleOf(raw, MIN_DOWNLOAD_MBPS_DEFAULT, MIN_DOWNLOAD_MBPS_MIN, MIN_DOWNLOAD_MBPS_MAX)

    fun scoreBwRefMbps(raw: String?): Double =
        doubleOf(raw, SCORE_BW_REF_MBPS_DEFAULT, SCORE_BW_REF_MBPS_MIN, SCORE_BW_REF_MBPS_MAX)

    /**
     * 生效带宽下限 —— **关掉下载测速时必须归零**：那时全池的 `downloadMbps` 都是 0，
     * 门槛照用会把最终名单清空（与 [effectiveMinScore] 折半同一个原因）。
     * 放在这里而不是设置层，是为了让"折半/失效"这条规则能被纯逻辑装置直接验证。
     */
    fun effectiveMinDownloadMbps(raw: String?, downloadTestEnabled: Boolean): Double =
        if (downloadTestEnabled) minDownloadMbps(raw) else 0.0

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

    // ── 劣化自动补货节流（CfQualityTriggerModule / CfQualityGate 消费）──

    /**
     * 自动补货最小间隔（小时）：距上次优选不足这个时长一律不触发。
     * 唯一作用是兜住「劣化但优选修不好」的失败循环（上游源全挂 / 运营商本身差）——
     * 没有它，质量门每 30 分钟判一次就会每 30 分钟空跑一轮，白烧流量和电。
     * 默认 6 = 该循环每天最多 4 轮；**0 = 不节流**（用户自担代价）。
     */
    const val MIN_INTERVAL_HOURS_DEFAULT = 6
    const val MIN_INTERVAL_HOURS_MIN = 0
    const val MIN_INTERVAL_HOURS_MAX = 168

    fun minIntervalHours(raw: String?): Int =
        intOf(raw, MIN_INTERVAL_HOURS_DEFAULT, MIN_INTERVAL_HOURS_MIN, MIN_INTERVAL_HOURS_MAX)

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
