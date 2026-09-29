package com.github.kr328.clash.service.cfoptimizer

import android.content.Context
import com.github.kr328.clash.service.ProfileProcessor
import com.github.kr328.clash.service.cfoptimizer.probe.CfProbe
import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerSettingsStore
import com.github.kr328.clash.service.cfoptimizer.settings.KeystoreCfOptimizerSecretStore
import com.github.kr328.clash.service.cfoptimizer.source.CfCandidateSource
import com.github.kr328.clash.service.cfoptimizer.worker.CfWorkerClient
import com.github.kr328.clash.service.cfoptimizer.worker.CfWorkerSettings
import com.github.kr328.clash.service.cfoptimizer.worker.WorkerUploadResult
import java.util.UUID

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

    suspend fun run(): Result {
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

        // 1. 拉取候选（固定来源 + CF 网段校验 + 有界）。
        val candidates = try {
            CfCandidateSource.fetch()
        } catch (e: Exception) {
            return Result(0, 0, false, "candidates_${e.javaClass.simpleName}", false, null)
        }

        if (candidates.isEmpty()) {
            return Result(0, 0, false, "no_candidates", false, null)
        }

        // 2. 物理网络探测（绑定 Network 的 socket，绕开本应用 VPN）。
        val probe = CfProbe(context)
        val metrics = probe.measure(candidates)

        // 3. 引擎评分。下载测速本轮默认关：metrics.downloadMbps 全为 0，
        //    评分只含 TTFB 分量（上限 60），门槛分按 30/60 比例折半为 15，
        //    排序仍由 TTFB 主导，不因关闭下载而把所有候选拦在门外。
        val limits = OptimizerLimits(minScore = DOWNLOAD_OFF_MIN_SCORE)
        val ranked = CfOptimizerEngine.rank(candidates, metrics, limits)

        if (ranked.size < MIN_UPLOAD_ENTRIES) {
            return Result(candidates.size, ranked.size, false, "below_quality_gate", false, null)
        }

        // 4. 组装 Worker 列表：用户自定义静态行在前（与原脚本 custom_add.txt 语义一致），
        //    本轮优选行在后。空列表不上传（客户端 fail closed，双保险）。
        val networkTag = probeTag(probe)
        val entries = settingsStore.customEntries.filter { it.isNotBlank() } +
                ranked.map { it.toWorkerLine(networkTag) }

        // 5. 上传 Worker（整体覆写语义：只在本轮有达标结果时才覆盖）。
        val client = CfWorkerClient(CfWorkerSettings(baseUrl, password))
        val uploadResult = client.upload(entries)

        val uploaded = uploadResult is WorkerUploadResult.Success
        val uploadReason = (uploadResult as? WorkerUploadResult.Failure)?.reason?.name

        if (!uploaded) {
            return Result(candidates.size, ranked.size, false, uploadReason, false, null)
        }

        // 6. 上传成功：先保存 last-known-good（下次空扫描/失败时可参考），再刷新订阅。
        StateStore(context).saveLastKnownGood(entries)

        var profileUpdated = false
        var profileError: String? = null

        if (profileId != null) {
            try {
                updateProfile(profileId)
                profileUpdated = true
            } catch (e: Exception) {
                // 上传已成功、订阅刷新失败：如实区分，不称全链路成功。
                profileError = e.message ?: "unknown"
            }
        }

        return Result(candidates.size, ranked.size, true, null, profileUpdated, profileError)
    }

    /** 只刷新用户绑定的那一个 URL Profile，走现有 ProfileProcessor 校验/原子替换/重载链。 */
    private suspend fun updateProfile(uuid: UUID) {
        ProfileProcessor.update(context, uuid, null)
    }

    /** 网络标签：按探测用网络的传输类型命名（Wi‑Fi/Cellular），不从 IP 猜。 */
    private fun probeTag(probe: CfProbe): String {
        val network = probe.candidateNetworks().firstOrNull() ?: return DEFAULT_TAG

        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
            ?: return DEFAULT_TAG
        val caps = connectivity.getNetworkCapabilities(network) ?: return DEFAULT_TAG

        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            else -> DEFAULT_TAG
        }
    }

    companion object {
        /** 上传质量门：合格条目少于此数不上传（防止 1-2 个侥幸节点覆盖共享列表）。 */
        const val MIN_UPLOAD_ENTRIES: Int = 3

        /**
         * 下载测速关闭时的门槛分。契约基线 30 分按「评分上限 60」等比折半——
         * 关闭下载时评分只含 TTFB 分量，不折半会把所有候选拦在门外。
         */
        const val DOWNLOAD_OFF_MIN_SCORE: Double = 15.0

        /** 探测网络不可用时的兜底标签。 */
        const val DEFAULT_TAG: String = "DefaultNet"
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

    fun lastSuccessAt(): Long = preferences.getLong(KEY_LAST_SUCCESS_AT, 0L)

    companion object {
        private const val STATE_FILE = "cfoptimizer_state"
        private const val KEY_LAST_KNOWN_GOOD = "last_known_good"
        private const val KEY_LAST_SUCCESS_AT = "last_success_at"
    }
}
