package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCfOptimizerBinding
import com.github.kr328.clash.design.dialog.requestModelTextInput
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.R as ServiceR
import com.github.kr328.clash.service.cfoptimizer.CfOptimizerCoordinator
import com.github.kr328.clash.service.cfoptimizer.StateStore
import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerSecretStore
import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerSettingsStore
import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerUrl
import com.github.kr328.clash.service.model.Profile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CfOptimizerSettingsDesign(
    context: Context,
    cfSettings: CfOptimizerSettingsStore,
    secrets: CfOptimizerSecretStore,
    urlProfiles: List<Profile>,
) : Design<CfOptimizerSettingsDesign.Request>(context) {
    enum class Request {
        RunCfOptimizer,
    }

    private val binding = DesignSettingsCfOptimizerBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private val stateStore = StateStore(context)

    /** 非空 String 参数的适配器（editableText 需要 NullableTextAdapter<T>）。 */
    private val stringAdapter = object : NullableTextAdapter<String> {
        override fun from(value: String): String? = value
        override fun to(text: String?): String = text ?: ""
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val screen = preferenceScreen(context) {
            category(R.string.cf_optimizer)

            tips(R.string.cf_optimizer_tips_overview)

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

            val runNowPref = clickable(
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

            // 运行参数（模仿原版 cf_config.py，全部可配、空/非法回落默认）。
            category(R.string.cf_optimizer_params)

            tips(R.string.cf_optimizer_tips_params)

            editableText(
                value = cfSettings::excludeCountriesRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_exclude_countries,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::onlyCountriesRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_only_countries,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::sourcesPerRunRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_sources_per_run,
            )

            editableText(
                value = cfSettings::perSourceSampleRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_per_source_sample,
            )

            editableText(
                value = cfSettings::maxCandidatesRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_max_candidates,
            )

            editableText(
                value = cfSettings::minUploadEntriesRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_min_upload_entries,
            )

            editableText(
                value = cfSettings::maxPerRegionRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_max_per_region,
            )

            switch(
                value = cfSettings::downloadTestEnabled,
                title = R.string.cf_optimizer_download_test,
                summary = R.string.cf_optimizer_download_test_summary,
            )

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
                        try {
                            withContext(Dispatchers.IO) { secrets.setPassword(text) }
                        } catch (e: Exception) {
                            // Never crash the screen on a failed secret write — surface it.
                            launch {
                                showToast(
                                    R.string.cf_optimizer_password_save_failed,
                                    ToastDuration.Long
                                )
                            }
                        }
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

            // 运行状态直接写在「立即运行优选」的副标题上 —— 不再单独占一张卡片：
            // 通知栏已经实时显示阶段与进度，页面顶部再放一块既是重复信息，也吃掉一大块留白。
            launch(Dispatchers.Main) {
                while (isActive) {
                    runNowPref.summary = withContext(Dispatchers.IO) { statusText() }

                    delay(STATUS_POLL_MILLIS)
                }
            }
        }

        binding.content.addView(screen.root)
    }

    /**
     * 「立即运行优选」的副标题：跑的时候显示阶段(+进度)，跑完显示上次成功时间，
     * 从没跑过则回落到说明文案。状态由 [StateStore] 的 stage 驱动（服务结束时写 DONE），
     * 不靠时间窗猜。
     */
    private fun statusText(): String {
        val stage = stateStore.runStage()
        val stale = System.currentTimeMillis() - stateStore.runUpdatedAt() > RUNNING_STALE_MILLIS

        if (stage.isNotEmpty() && stage != CfOptimizerCoordinator.STAGE_DONE && !stale) {
            return stageText(stage)
        }

        val success = stateStore.lastSuccessAt()
        if (success > 0) {
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(success))

            return context.getString(R.string.cf_optimizer_last_success, time)
        }

        return context.getText(R.string.cf_optimizer_run_now_summary).toString()
    }

    private fun stageText(stage: String): String {
        val progress = stateStore.runProgress()
        val total = stateStore.runTotal()

        return when (stage) {
            CfOptimizerCoordinator.STAGE_SOURCES ->
                context.getString(ServiceR.string.cf_optimizer_stage_sources)
            CfOptimizerCoordinator.STAGE_PROBE ->
                context.getString(ServiceR.string.cf_optimizer_stage_probe, progress, total)
            CfOptimizerCoordinator.STAGE_DOWNLOAD ->
                context.getString(ServiceR.string.cf_optimizer_stage_download, progress, total)
            CfOptimizerCoordinator.STAGE_RANK ->
                context.getString(ServiceR.string.cf_optimizer_stage_rank)
            CfOptimizerCoordinator.STAGE_UPLOAD ->
                context.getString(ServiceR.string.cf_optimizer_stage_upload)
            else -> context.getText(R.string.cf_optimizer_run_now_summary).toString()
        }
    }

    private companion object {
        /** 状态行刷新间隔（毫秒）：只改一行文字，比原来 2s 轮询一整张卡片便宜。 */
        const val STATUS_POLL_MILLIS: Long = 2_000

        /**
         * 运行状态过期阈值：前台服务被系统杀掉时 stage 会停在中间态，
         * 超过这个时间就不再声称「正在运行」（避免永远显示假的进行中）。
         */
        const val RUNNING_STALE_MILLIS: Long = 10 * 60 * 1000
    }
}
