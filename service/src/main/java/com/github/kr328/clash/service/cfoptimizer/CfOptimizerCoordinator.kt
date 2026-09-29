package com.github.kr328.clash.service.cfoptimizer

import android.content.Context
import com.github.kr328.clash.service.ProfileProcessor
import com.github.kr328.clash.service.cfoptimizer.history.CfOptimizerRunLog
import com.github.kr328.clash.service.cfoptimizer.history.CfRunRecorder
import com.github.kr328.clash.service.cfoptimizer.memory.CfMemoryScoring
import com.github.kr328.clash.service.cfoptimizer.memory.CfMemoryStore
import com.github.kr328.clash.service.cfoptimizer.net.PhysicalNetwork
import com.github.kr328.clash.service.cfoptimizer.probe.CfProbe
import com.github.kr328.clash.service.cfoptimizer.probe.CfProbeConfig
import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerSettingsStore
import com.github.kr328.clash.service.cfoptimizer.settings.KeystoreCfOptimizerSecretStore
import com.github.kr328.clash.service.cfoptimizer.source.CfCandidateSource
import com.github.kr328.clash.service.cfoptimizer.worker.CfWorkerClient
import com.github.kr328.clash.service.cfoptimizer.worker.CfWorkerSettings
import com.github.kr328.clash.service.cfoptimizer.worker.UrlConnectionWorkerHttpTransport
import com.github.kr328.clash.service.cfoptimizer.worker.WorkerUploadResult
import kotlinx.coroutines.delay
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * CF 优选协调器 — 把候选源、物理网络探测、引擎评分、Worker 上传与订阅刷新串成完整流程。
 *
 * 失败语义（fail-safe）：
 * - 任何一步失败都**不覆盖** Worker 现有列表（空列表/上传失败/取消均不上传）；
 * - 只有非空、越过质量门的列表才上传；上传成功后先保存 last-known-good，再刷新订阅；
 * - 订阅刷新失败时 last-known-good 已保存，Worker 侧仍是本轮新列表（可手动恢复），
 *   结果里如实区分「上传成功、订阅刷新失败」，不称全链路成功。
 */
class CfOptimizerCoordinator(private val context: Context) {
    /** 每轮结果（给服务层发通知/写状态用；不含密码、Cookie 或完整 profile URL）。 */
    data class Result(
        val candidateCount: Int,
        val qualifiedCount: Int,
        val uploaded: Boolean,
        val uploadReason: String?,
        val profileUpdated: Boolean,
        val profileError: String?,
    )

    /**
     * 跑一轮优选，并把这一轮的痕迹留下来（记忆库写回 + 运行历史落盘）。
     *
     * 落盘放在 `finally`：质量门不足 / 上传失败 / 异常退出同样留证据——
     * 恰恰是这些轮次最需要事后能看（"为什么这轮没出结果"再也无法复盘的时代结束了）。
     */
    suspend fun run(onProgress: suspend (stage: String, progress: Int, total: Int) -> Unit = { _, _, _ -> }): Result {
        val startedAt = System.currentTimeMillis()
        val recorder = CfRunRecorder()
        val runLog = CfOptimizerRunLog(context)

        var outcome: CfOptimizerRunLog.Outcome? = null

        try {
            val result = runRecorded(recorder, onProgress)

            outcome = CfOptimizerRunLog.Outcome(
                uploaded = result.uploaded,
                profileUpdated = result.profileUpdated,
                reason = result.uploadReason,
            )

            return result
        } catch (e: Exception) {
            recorder.failReason = e.javaClass.simpleName

            throw e
        } finally {
            runLog.persist(recorder, outcome, startedAt)
        }
    }

    private suspend fun runRecorded(
        recorder: CfRunRecorder,
        onProgress: suspend (stage: String, progress: Int, total: Int) -> Unit,
    ): Result {
        val settingsStore = CfOptimizerSettingsStore(context)
        val secretStore = KeystoreCfOptimizerSecretStore(context)

        val baseUrl = settingsStore.workerBaseUrl
        val profileId = settingsStore.subscriptionProfileId

        if (baseUrl.isBlank()) {
            return Result(0, 0, false, "not_configured", false, null)
        }

        val password = secretStore.readPassword()
        if (password.isNullOrEmpty()) {
            return Result(0, 0, false, "password_missing", false, null)
        }

        // 用户运行参数（模仿原版 cf_config.py，全部可配、空/非法回落默认）。
        val excludeCountries = settingsStore.excludeCountries
        val onlyCountries = settingsStore.onlyCountries
        val sourcesPerRun = settingsStore.sourcesPerRun
        val perSourceSample = settingsStore.perSourceSample
        val maxCandidates = settingsStore.maxCandidates

        // 探测 / 测速参数（原版 cf_config.py 的并发、超时、下载大小）：一轮读一次快照、整轮复用 ——
        // 中途改设置不能让同一轮出现两套并发；探针层只认这份快照，不直接读设置。
        val probeConfig = CfProbeConfig(
            tcpConcurrency = settingsStore.tcpConcurrency,
            tcpTimeoutMs = settingsStore.tcpTimeoutMs,
            probeConcurrency = settingsStore.probeConcurrency,
            ttfbSamples = settingsStore.ttfbSamples,
            downloadPoolLimit = settingsStore.downloadPoolLimit,
            downloadConcurrency = settingsStore.downloadConcurrency,
            downloadSizeMb = settingsStore.downloadSizeMb,
            downloadTimeoutMs = settingsStore.downloadTimeoutMs.toLong(),
            downloadEarlyStopMbps = settingsStore.downloadEarlyStopMbps,
        )

        // 两层配额（漏斗结构，对齐原版"先便宜后贵"）：
        //   便宜层 rawPool = maxCandidates × 倍数（可调，默认 2）—— 只付 TCP connect 的价钱；
        //   昂贵层 maxCandidates —— 3 次 TTFB 采样 + trace + 下载测速，只作用在 TCP 存活集上。
        // 原版 3000 候选之所以"轻松"，正是因为它先花几秒把死 IP 筛掉；删掉漏斗后每个死 IP 都要付
        // 3 次完整 TLS+HTTP 超时（最坏 15 秒），80 个候选就能吃掉两分钟 —— 这才是"选不出来"的真正原因：
        // 探索面被死 IP 的确认成本挤没了。
        val rawPoolLimit = CfOptimizerEngine.rawPoolLimit(maxCandidates, settingsStore.rawPoolFactor)

        // 阶段墙钟耗时（秒），写进 runs.jsonl：没有它就没法回答"时间花在哪一段"。
        val stageSeconds = LinkedHashMap<String, Double>()
        var stageStartedAtMs = System.currentTimeMillis()

        fun markStage(stage: String) {
            stageSeconds[stage] = (System.currentTimeMillis() - stageStartedAtMs) / 1000.0
            stageStartedAtMs = System.currentTimeMillis()
        }
        val minUploadEntries = settingsStore.minUploadEntries
        val maxPerRegion = settingsStore.maxPerRegion
        val downloadTestEnabled = settingsStore.downloadTestEnabled
        val memoryEnabled = settingsStore.memoryEnabled
        val memoryPoolLimit = settingsStore.memoryPoolLimit

        // 跨轮记忆库（原版 cf_memory.py 的 App 等价物）：默认开。
        // 关掉 = 每轮从零开始（原版脚本的默认行为），用于对照实验。
        // 淘汰/衰减阈值随用户参数走（原版那 5 个常量在设置页可调）。
        val memory = if (memoryEnabled) CfMemoryStore(context, settingsStore.memoryTuning) else null

        // 物理网络出口：本模块**所有** Java 侧网络 I/O（拉源 / 探测 / 上传）统一绑它。
        // 不绑的后果（真机实测）：开着代理时请求回流进本应用 TUN → 被自己的规则送进代理
        // 节点 → 上传失败/超时，而关掉代理就一切正常。
        val physicalNetwork = PhysicalNetwork.pick(context)

        // 1. 源发现（导航站 → 缓存 → 内置兜底）+ 拉取候选。
        val state = StateStore(context)
        state.saveRunState(STAGE_SOURCES, 0, 0)
        onProgress(STAGE_SOURCES, 0, 0)

        val discovered = try {
            CfCandidateSource.discoverSourceUrls(physicalNetwork)
        } catch (e: Exception) {
            emptyList()
        }
        if (discovered.isNotEmpty()) {
            state.saveSourceUrls(discovered)
        }

        val sources = CfCandidateSource.BUILTIN_SOURCE_URLS +
                (discovered.ifEmpty { state.cachedSourceUrls() }).shuffled()
                    .take(sourcesPerRun.coerceIn(1, 12))

        val candidates = try {
            CfCandidateSource.fetchFrom(
                sources,
                excludedRegions = excludeCountries,
                perSourceSample = perSourceSample,
                maxCandidates = rawPoolLimit,
                network = physicalNetwork,
            )
        } catch (e: Exception) {
            return Result(0, 0, false, "candidates_${e.javaClass.simpleName}", false, null)
        }

        markStage(STAGE_SOURCES)

        // 1.5 优先复测池 = 记忆库置信度 top-N（跨轮历史）+ 上轮写入 Worker 的节点。
        //     记忆库比 last-known-good 更准：它记得谁**稳定**，而不只是谁上一轮在名单里；
        //     置信度含成功率、新鲜度衰减与"当前时段"加成（原版 get_priority_candidates 口径）。
        val memoryCandidates = memory?.priorities(memoryPoolLimit).orEmpty().mapNotNull { priority ->
            val candidate = CandidateIp(priority.address, priority.port, source = "memory")

            if (candidates.any { it.address == candidate.address && it.port == candidate.port }) {
                null
            } else {
                candidate
            }
        }

        val lastGood = state.lastKnownGood()
        val lastGoodCandidates = lastGood.mapNotNull { line ->
            parseWorkerLine(line)?.let { CandidateIp(it.first, it.second, source = "lastgood") }
        }.filter { lg -> candidates.none { it.address == lg.address && it.port == lg.port } }

        // 记忆库里连续失败达阈值的地址：冷却期内不再从源池取样探测（把配额留给新 IP）。
        // 只过滤"新来的"源候选——优先池里的节点是历史验证过的，不在此列。
        val blockedAddresses = memory?.blockedAddresses().orEmpty()
        val freshCandidates = candidates.filter { it.address !in blockedAddresses }

        // 优先池在前、源候选在后，总池封顶 —— 记忆池越大，留给"探索新 IP"的名额越少，
        // 这就是探索/利用的权衡点：上一轮稳定节点越可信，本轮越省探测时间。
        val pooled = (memoryCandidates + lastGoodCandidates + freshCandidates)
            .distinctBy { it.address to it.port }
            .take(rawPoolLimit + memoryPoolLimit)

        if (pooled.isEmpty()) {
            return Result(0, 0, false, "no_candidates", false, null)
        }

        val probe = CfProbe(context, probeConfig)

        // 1.7 TCP 连通预筛（漏斗第一段）：死 IP 在这里以"1 次 connect × 1s"的代价淘汰，
        //     只有存活集进昂贵的 TTFB/trace 段。保序 —— 记忆池在最前，截断时天然优先保留。
        state.saveRunState(STAGE_TCP, 0, pooled.size)
        onProgress(STAGE_TCP, 0, pooled.size)

        val tcpAlive = probe.filterTcpReachable(pooled) { done, total ->
            state.saveRunState(STAGE_TCP, done, total)
            onProgress(STAGE_TCP, done, total)
        }

        markStage(STAGE_TCP)

        val allCandidates = tcpAlive.take(maxCandidates + memoryPoolLimit)

        if (allCandidates.isEmpty()) {
            return Result(0, 0, false, "no_reachable_candidates", false, null)
        }

        // 2. 物理网络探测（绑定 Network 的 socket，绕开本应用 VPN）。进度实时上报。
        val metrics = probe.measure(allCandidates) { done, total ->
            state.saveRunState(STAGE_PROBE, done, total)
            onProgress(STAGE_PROBE, done, total)
        }

        markStage(STAGE_PROBE)

        // 2.5 国家过滤（原版 EXCLUDE_COUNTRIES / ONLY_COUNTRIES，应用在 trace 阶段）：
        //     黑名单（trace loc 命中）不进评分/上传；白名单启用时只保留命中国家。
        //     trace 失败（region 缺失）的候选保留——未知 ≠ 封锁，不因 trace 抖动把整轮清空。
        val probed = allCandidates.filter { cand ->
            val region = metrics[cand]?.region?.uppercase() ?: return@filter true

            if (region in excludeCountries) return@filter false
            if (onlyCountries.isNotEmpty() && region !in onlyCountries) return@filter false

            true
        }

        // 2.7 下载测速（默认开）：评分公式里带宽占 40%，此前该分量恒为 0 —— 等于只按
        //     延迟选节点。只测 TTFB 最优的窄池（原脚本 TTFB_POOL_LIMIT 语义），
        //     控制手机流量；测速失败的候选保留 TTFB 分量（不编造带宽、不丢节点）。
        val downloads = if (downloadTestEnabled && probed.isNotEmpty()) {
            val pool = probed
                .mapNotNull { candidate -> metrics[candidate]?.let { candidate to it.ttfbMs } }
                .sortedBy { it.second }
                .take(probeConfig.downloadPoolLimit)
                .map { it.first }

            state.saveRunState(STAGE_DOWNLOAD, 0, pool.size)
            onProgress(STAGE_DOWNLOAD, 0, pool.size)

            probe.measureDownloads(pool) { done, total ->
                state.saveRunState(STAGE_DOWNLOAD, done, total)
                onProgress(STAGE_DOWNLOAD, done, total)
            }
        } else {
            emptyMap()
        }

        markStage(STAGE_DOWNLOAD)

        val scoredMetrics = if (downloads.isEmpty()) {
            metrics
        } else {
            metrics.mapValues { (candidate, m) ->
                downloads[candidate]?.let { mbps -> m.copy(downloadMbps = mbps) } ?: m
            }
        }

        // 3. 引擎评分。门槛分用户可调（原版 SMART_PUSH_MIN_SCORE）：关掉下载测速时带宽分量恒为 0、
        //    满分只剩 60，门槛由 CfOptimizerTuning 按比例折半（否则会把所有候选拦在门外）。
        //    maxPerRegion（原版 SMART_PUSH_IPS_PER_CC）：防单地区垄断 Worker 列表。
        state.saveRunState(STAGE_RANK, probed.size, allCandidates.size)
        onProgress(STAGE_RANK, probed.size, allCandidates.size)

        // maxTtfbMs / maxJitterMs / maxEntries / weights 此前只用了引擎默认值（协调器没传参）──
        // 引擎本来支持，只是没人把用户值递进去：现在一并接上，默认值与旧行为逐字相同。
        val limits = OptimizerLimits(
            maxEntries = settingsStore.maxEntries,
            maxTtfbMs = settingsStore.maxTtfbMs.toLong(),
            maxJitterMs = settingsStore.maxJitterMs.toLong(),
            minScore = settingsStore.effectiveMinScore,
            maxPerRegion = maxPerRegion,
            weights = settingsStore.scoreWeights,
        )
        var ranked = CfOptimizerEngine.rank(probed, scoredMetrics, limits)

        // 3.5 CIDR 前缀去重（原版 cidr_seen 语义）：同一前缀只保留最高分，
        //     让 Worker 列表分散在不同网段；去重后不足质量门则不上传。
        ranked = dedupeByPrefix(ranked, settingsStore.dedupPrefixV4)

        // 3.7 记忆库写回（原版 record_result）。放在这里而不是"上传成功后"：
        //     质量门不足 / 上传失败的轮次同样长记忆——失败轮恰恰是记忆库最该记住的。
        recordMemory(memory, allCandidates, scoredMetrics, ranked)

        markStage(STAGE_RANK)

        recorder.capture(
            candidates = allCandidates,
            metrics = scoredMetrics,
            ranked = ranked,
            memorySize = memory?.size() ?: 0,
            memoryReused = memoryCandidates.size,
            memoryBlocked = blockedAddresses.size,
        )

        // 漏斗计数与分阶段耗时（写进 runs.jsonl）：回答"时间花在哪一段、池子有多大"。
        recorder.candidatesRaw = pooled.size
        recorder.tcpAlive = tcpAlive.size
        recorder.stageSeconds = stageSeconds.toMap()

        if (ranked.size < minUploadEntries) {
            return Result(allCandidates.size, ranked.size, false, "below_quality_gate", false, null)
        }

        // 4. 组装 Worker 列表：用户自定义静态行在前（与原脚本 custom_add.txt 语义一致），
        //    本轮优选行在后。空列表不上传（客户端 fail closed，双保险）。
        state.saveRunState(STAGE_UPLOAD, 0, 1)
        onProgress(STAGE_UPLOAD, 0, 1)
        val entries = settingsStore.customEntries.orEmpty().filter { it.isNotBlank() } +
                ranked.map { it.toWorkerLine() }

        // 5. 上传 Worker（整体覆写语义：只在本轮有达标结果时才覆盖）。
        val client = CfWorkerClient(
            CfWorkerSettings(baseUrl, password),
            UrlConnectionWorkerHttpTransport(context),
        )
        val uploadResult = client.upload(entries)

        val uploaded = uploadResult is WorkerUploadResult.Success
        val uploadReason = (uploadResult as? WorkerUploadResult.Failure)?.reason?.name

        if (!uploaded) {
            return Result(allCandidates.size, ranked.size, false, uploadReason, false, null)
        }

        state.saveRunState(STAGE_UPLOAD, 1, 1)
        onProgress(STAGE_UPLOAD, 1, 1)

        // 6. 上传成功：先保存 last-known-good（下次空扫描/失败时可参考），再刷新订阅。
        StateStore(context).saveLastKnownGood(entries)

        var profileUpdated = false
        var profileError: String? = null

        if (profileId != null) {
            // 订阅刷新重试（gRPC deadline 抖动一次重试可解，见真机
            // 「列表已上传，订阅更新失败: context deadline exceeded」）。
            repeat(2) { attempt ->
                if (profileUpdated) return@repeat

                try {
                    if (attempt > 0) delay(TimeUnit.SECONDS.toMillis(2))
                    updateProfile(profileId)
                    profileUpdated = true
                } catch (e: Exception) {
                    // 上传已成功、订阅刷新失败：如实区分，不称全链路成功。
                    profileError = e.message ?: "unknown"
                }
            }
        }

        return Result(candidates.size, ranked.size, true, null, profileUpdated, profileError)
    }

    /**
     * 把本轮实测写回记忆库（原版 `record_result` 的三态语义）：
     * - 入选（越过质量门且被 /24 去重保留）→ full + score：参与 avg_score EMA 与时段桶
     * - 可达但没入选 → tcp：只累 tcp_hits、清 fail_streak，**不拉低** avg_score
     * - 连不上 → fail：fail_streak +1，达到阈值后进入冷却，下轮不再浪费探测配额
     */
    private fun recordMemory(
        memory: CfMemoryStore?,
        candidates: List<CandidateIp>,
        metrics: Map<CandidateIp, ProbeMetrics>,
        ranked: List<OptimizedEntry>,
    ) {
        if (memory == null) return

        val selected = ranked.associateBy { it.address to it.port }

        candidates.forEach { candidate ->
            val entry = selected[candidate.address to candidate.port]
            val measured = metrics[candidate]
            val key = CfMemoryStore.key(candidate.address, candidate.port)

            when {
                entry != null -> memory.record(
                    key = key,
                    cc = entry.region,
                    passed = true,
                    score = entry.score,
                    ttfbMs = entry.ttfbMs.toDouble(),
                    mbps = entry.downloadMbps,
                    rangeOk = true,
                    stage = CfMemoryScoring.STAGE_FULL,
                )

                measured != null -> memory.record(
                    key = key,
                    cc = measured.region,
                    passed = false,
                    stage = CfMemoryScoring.STAGE_TCP,
                )

                else -> memory.record(
                    key = key,
                    cc = "",
                    passed = false,
                    stage = CfMemoryScoring.STAGE_FAIL,
                )
            }
        }

        memory.flush()
    }

    /**
     * 解析 Worker 行 `IP:port#tag` 回 (address, port)——用于把 last-known-good 节点
     * 还原成候选（原版记忆库优先节点的等价物）。
     */
    private fun parseWorkerLine(line: String): Pair<String, Int>? {
        val entry = line.substringBefore('#').trim()
        val match = Regex("""^(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})$""").find(entry) ?: return null

        return match.groupValues[1] to (match.groupValues[2].toIntOrNull() ?: return null)
    }

    /**
     * CIDR 前缀去重（原版 cidr_seen 语义）：同一前缀网段只保留排名最高的条目，
     * 让 Worker 列表分散在不同网段。解析失败的行保留（不因 tag 格式抖动丢结果）。
     *
     * [prefixLength] 用户可调（原版 `IPV4_DEDUP_PREFIX`，默认 24）；非 IPv4 字面量（如 IPv6）
     * 不做前缀归并 —— 由输入校验决定它们是否还在池子里，不在这里猜。
     */
    private fun dedupeByPrefix(ranked: List<OptimizedEntry>, prefixLength: Int): List<OptimizedEntry> {
        val seen = HashSet<String>()
        val octetsKept = (prefixLength.coerceIn(8, 32) + 7) / 8

        return ranked.filter { entry ->
            val octets = entry.address.split('.')
            val prefix = if (octets.size == 4) octets.take(octetsKept).joinToString(".") else null

            prefix == null || seen.add(prefix)
        }
    }

    /** 只刷新用户绑定的那一个 URL Profile，走现有 ProfileProcessor 校验/原子替换/重载链。 */
    private suspend fun updateProfile(uuid: UUID) {
        ProfileProcessor.update(context, uuid, null)
    }

    companion object {
        /** 运行阶段标识（状态行 + 前台通知的确定性反馈来源）。 */
        const val STAGE_SOURCES: String = "sources"

        /** 漏斗第一段：TCP 连通预筛（只 connect，死 IP 在这里被便宜地淘汰）。 */
        const val STAGE_TCP: String = "tcp"

        const val STAGE_PROBE: String = "probe"
        const val STAGE_DOWNLOAD: String = "download"
        const val STAGE_RANK: String = "rank"
        const val STAGE_UPLOAD: String = "upload"
        const val STAGE_DONE: String = "done"

    }
}

/**
 * 优选运行状态存储（last-known-good 与最近一次结果摘要）。
 *
 * 独立 prefs 文件，不进 C 的设置存储（那是用户意图配置）；本文件由 D 独占。
 * 内容只有 IP 行与结果摘要，无凭据；随应用默认备份策略即可。
 */
class StateStore(context: Context) {
    private val preferences =
        context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)

    fun saveLastKnownGood(entries: List<String>) {
        preferences.edit()
            .putString(KEY_LAST_KNOWN_GOOD, entries.joinToString("\n"))
            .putLong(KEY_LAST_SUCCESS_AT, System.currentTimeMillis())
            .apply()
    }

    fun lastKnownGood(): List<String> =
        preferences.getString(KEY_LAST_KNOWN_GOOD, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    /** 上轮发现的动态源链接（原版 Data/latest_urls.txt 通讯录缓存的 App 等价物）。 */
    fun saveSourceUrls(urls: List<String>) {
        preferences.edit()
            .putString(KEY_SOURCE_URLS, urls.joinToString("\n"))
            .apply()
    }

    fun cachedSourceUrls(): List<String> =
        preferences.getString(KEY_SOURCE_URLS, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    /**
     * 运行状态（给状态框与前台服务通知）：stage + 进度，确定性反馈的来源。
     */
    fun saveRunState(stage: String, progress: Int, total: Int) {
        preferences.edit()
            .putString(KEY_RUN_STAGE, stage)
            .putInt(KEY_RUN_PROGRESS, progress)
            .putInt(KEY_RUN_TOTAL, total)
            .putLong(KEY_RUN_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun runStage(): String = preferences.getString(KEY_RUN_STAGE, "") ?: ""
    fun runProgress(): Int = preferences.getInt(KEY_RUN_PROGRESS, 0)
    fun runTotal(): Int = preferences.getInt(KEY_RUN_TOTAL, 0)
    fun runUpdatedAt(): Long = preferences.getLong(KEY_RUN_UPDATED_AT, 0L)

    fun lastSuccessAt(): Long = preferences.getLong(KEY_LAST_SUCCESS_AT, 0L)

    companion object {
        private const val STATE_FILE = "cfoptimizer_state"
        private const val KEY_LAST_KNOWN_GOOD = "last_known_good"
        private const val KEY_LAST_SUCCESS_AT = "last_success_at"
        private const val KEY_SOURCE_URLS = "source_urls"
        private const val KEY_RUN_STAGE = "run_stage"
        private const val KEY_RUN_PROGRESS = "run_progress"
        private const val KEY_RUN_TOTAL = "run_total"
        private const val KEY_RUN_UPDATED_AT = "run_updated_at"
    }
}
