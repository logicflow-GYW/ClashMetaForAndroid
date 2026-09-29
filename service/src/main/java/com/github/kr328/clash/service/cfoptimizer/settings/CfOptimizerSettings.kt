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
