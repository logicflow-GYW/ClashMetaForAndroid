package com.github.kr328.clash.service.cfoptimizer.settings

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider
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
    val scanOnNetworkChange: Boolean,
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

    var scanOnNetworkChange: Boolean by store.boolean(
        key = "cfoptimizer_scan_on_network_change",
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

    /** 每轮从源池抽取的源数（原版动态通讯录；手机端有界化默认 4）。 */
    var sourcesPerRunRaw: String by store.string(
        key = "cfoptimizer_sources_per_run",
        defaultValue = "4",
    )

    val sourcesPerRun: Int get() = sourcesPerRunRaw.toIntOrNull() ?: 4

    /** 单源抽样条数（原版 sample_limit 的手机端等比；默认 40）。 */
    var perSourceSampleRaw: String by store.string(
        key = "cfoptimizer_per_source_sample",
        defaultValue = "40",
    )

    val perSourceSample: Int get() = perSourceSampleRaw.toIntOrNull() ?: 40

    /** 每轮进入探测的候选上限（默认 100）。 */
    var maxCandidatesRaw: String by store.string(
        key = "cfoptimizer_max_candidates",
        defaultValue = "100",
    )

    val maxCandidates: Int get() = maxCandidatesRaw.toIntOrNull() ?: 100

    /** 上传质量门（原版 SMART_PUSH_MIN_NODES=2；这里默认 3 防侥幸覆盖）。 */
    var minUploadEntriesRaw: String by store.string(
        key = "cfoptimizer_min_upload_entries",
        defaultValue = "3",
    )

    val minUploadEntries: Int get() = minUploadEntriesRaw.toIntOrNull() ?: 3

    /** 每地区最多保留条数（原版 SMART_PUSH_IPS_PER_CC=2）。 */
    var maxPerRegionRaw: String by store.string(
        key = "cfoptimizer_max_per_region",
        defaultValue = "2",
    )

    val maxPerRegion: Int get() = maxPerRegionRaw.toIntOrNull() ?: 2

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
            scanOnNetworkChange = scanOnNetworkChange,
            lastRunAt = lastRunAt,
        )
}
