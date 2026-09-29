package com.github.kr328.clash.design

import android.content.Context
import android.os.Build
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.dialog.requestModelTextInput
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerSecretStore
import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerSettingsStore
import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerUrl
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NetworkSettingsDesign(
    context: Context,
    uiStore: UiStore,
    srvStore: ServiceStore,
    running: Boolean,
    cfSettings: CfOptimizerSettingsStore,
    secrets: CfOptimizerSecretStore,
    urlProfiles: List<Profile>,
) : Design<NetworkSettingsDesign.Request>(context) {
    enum class Request {
        StartAccessControlList,
        RunCfOptimizer,
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val screen = preferenceScreen(context) {
            val vpnDependencies: MutableList<Preference> = mutableListOf()

            val vpn = switch(
                value = uiStore::enableVpn,
                icon = R.drawable.ic_baseline_vpn_lock,
                title = R.string.route_system_traffic,
                summary = R.string.routing_via_vpn_service
            ) {
                listener = OnChangedListener {
                    vpnDependencies.forEach {
                        it.enabled = uiStore.enableVpn
                    }
                }
            }

            category(R.string.vpn_service_options)

            switch(
                value = srvStore::bypassPrivateNetwork,
                title = R.string.bypass_private_network,
                summary = R.string.bypass_private_network_summary,
                configure = vpnDependencies::add,
            )

            switch(
                value = srvStore::dnsHijacking,
                title = R.string.dns_hijacking,
                summary = R.string.dns_hijacking_summary,
                configure = vpnDependencies::add,
            )

            switch(
                value = srvStore::allowBypass,
                title = R.string.allow_bypass,
                summary = R.string.allow_bypass_summary,
                configure = vpnDependencies::add,
            )

            switch(
                value = srvStore::allowIpv6,
                title = R.string.allow_ipv6,
                summary = R.string.allow_ipv6_summary,
                configure = vpnDependencies::add,
            )

            if (Build.VERSION.SDK_INT >= 29) {
                switch(
                    value = srvStore::systemProxy,
                    title = R.string.system_proxy,
                    summary = R.string.system_proxy_summary,
                    configure = vpnDependencies::add,
                )
            }

            selectableList(
                value = srvStore::tunStackMode,
                values = arrayOf(
                    "system",
                    "gvisor",
                    "mixed",
                    "mips"
                ),
                valuesText = arrayOf(
                    R.string.tun_stack_system,
                    R.string.tun_stack_gvisor,
                    R.string.tun_stack_mixed,
                    R.string.tun_stack_mips
                ),
                title = R.string.tun_stack_mode,
                configure = vpnDependencies::add,
            )

            selectableList(
                value = srvStore::accessControlMode,
                values = AccessControlMode.values(),
                valuesText = arrayOf(
                    R.string.allow_all_apps,
                    R.string.allow_selected_apps,
                    R.string.deny_selected_apps
                ),
                title = R.string.access_control_mode,
                configure = vpnDependencies::add,
            )

            clickable(
                title = R.string.access_control_packages,
                summary = R.string.access_control_packages_summary,
            ) {
                clicked {
                    requests.trySend(Request.StartAccessControlList)
                }
            }

            category(R.string.cf_optimizer)

            switch(
                value = cfSettings::enabled,
                title = R.string.cf_optimizer_enable,
                summary = R.string.cf_optimizer_enable_summary,
            )

            val baseUrlPref = clickable(
                title = R.string.cf_optimizer_worker_base_url,
            )

            val passwordPref = clickable(
                title = R.string.cf_optimizer_password,
            )

            val profilePref = clickable(
                title = R.string.cf_optimizer_subscription_profile,
            )

            editableTextList(
                value = cfSettings::customEntries,
                adapter = TextAdapter.String,
                title = R.string.cf_optimizer_custom_entries,
                placeholder = R.string.cf_optimizer_not_set,
            )

            switch(
                value = cfSettings::scanOnNetworkChange,
                title = R.string.cf_optimizer_scan_on_network_change,
                summary = R.string.cf_optimizer_scan_on_network_change_summary,
            )

            clickable(
                title = R.string.cf_optimizer_run_now,
                summary = R.string.cf_optimizer_run_now_summary,
            ) {
                clicked {
                    if (!cfSettings.confirmedSharedListOwnership) {
                        MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.cf_optimizer_shared_list_title)
                            .setMessage(R.string.cf_optimizer_shared_list_message)
                            .setPositiveButton(R.string.ok) { _, _ ->
                                cfSettings.confirmedSharedListOwnership = true

                                requests.trySend(Request.RunCfOptimizer)
                            }
                            .setNegativeButton(R.string.cancel) { _, _ -> }
                            .show()
                    } else {
                        requests.trySend(Request.RunCfOptimizer)
                    }
                }
            }

            // CF optimizer initial summaries — never echo the password value.
            launch(Dispatchers.Main) {
                val baseUrl = withContext(Dispatchers.IO) { cfSettings.workerBaseUrl }
                val passwordConfigured = withContext(Dispatchers.IO) { secrets.passwordConfigured() }
                val profileId = withContext(Dispatchers.IO) { cfSettings.subscriptionProfileId }

                baseUrlPref.summary = baseUrl.ifEmpty {
                    context.getText(R.string.cf_optimizer_not_set)
                }

                passwordPref.summary = context.getText(
                    if (passwordConfigured) R.string.cf_optimizer_password_set
                    else R.string.cf_optimizer_password_unset
                )

                profilePref.summary = urlProfiles.firstOrNull { it.uuid == profileId }?.name
                    ?: context.getText(R.string.cf_optimizer_subscription_profile_none)
            }

            baseUrlPref.clicked {
                launch(Dispatchers.Main) {
                    val current = withContext(Dispatchers.IO) { cfSettings.workerBaseUrl }

                    val text = context.requestModelTextInput(
                        initial = current,
                        title = context.getText(R.string.cf_optimizer_worker_base_url),
                        reset = context.getText(R.string.reset),
                        hint = context.getText(R.string.cf_optimizer_worker_base_url),
                        error = context.getText(R.string.cf_optimizer_url_invalid),
                        validator = { it.isBlank() || CfOptimizerUrl.validateHttpsOrigin(it) != null },
                    )

                    val normalized =
                        if (text.isNullOrBlank()) "" else CfOptimizerUrl.validateHttpsOrigin(text)

                    if (normalized != null) {
                        withContext(Dispatchers.IO) { cfSettings.workerBaseUrl = normalized }

                        baseUrlPref.summary = normalized.ifEmpty {
                            context.getText(R.string.cf_optimizer_not_set)
                        }
                    }
                }
            }

            passwordPref.clicked {
                launch(Dispatchers.Main) {
                    val text = context.requestModelTextInput(
                        initial = "",
                        title = context.getText(R.string.cf_optimizer_password),
                        reset = null,
                        hint = context.getText(R.string.cf_optimizer_password),
                        passwordInput = true,
                    )

                    if (!text.isNullOrEmpty()) {
                        withContext(Dispatchers.IO) { secrets.setPassword(text) }
                    }

                    val configured = withContext(Dispatchers.IO) { secrets.passwordConfigured() }

                    passwordPref.summary = context.getText(
                        if (configured) R.string.cf_optimizer_password_set
                        else R.string.cf_optimizer_password_unset
                    )
                }
            }

            profilePref.clicked {
                launch(Dispatchers.Main) {
                    val items = urlProfiles.map { it.name } +
                            context.getText(R.string.cf_optimizer_subscription_profile_none)
                                .toString()

                    val profileId = withContext(Dispatchers.IO) { cfSettings.subscriptionProfileId }
                    val currentIndex = urlProfiles.indexOfFirst { it.uuid == profileId }

                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.cf_optimizer_subscription_profile)
                        .setSingleChoiceItems(
                            items.toTypedArray(),
                            if (currentIndex >= 0) currentIndex else items.size - 1
                        ) { dialog, which ->
                            dialog.dismiss()

                            cfSettings.subscriptionProfileId =
                                urlProfiles.getOrNull(which)?.uuid

                            profilePref.summary = items[which]
                        }
                        .setNegativeButton(R.string.cancel) { _, _ -> }
                        .show()
                }
            }

            if (running) {
                vpn.enabled = false

                vpnDependencies.forEach {
                    it.enabled = false
                }
            } else {
                vpn.listener?.onChanged()
            }
        }

        binding.content.addView(screen.root)

        if (running) {
            launch {
                showToast(R.string.options_unavailable, ToastDuration.Indefinite)
            }
        }
    }
}
