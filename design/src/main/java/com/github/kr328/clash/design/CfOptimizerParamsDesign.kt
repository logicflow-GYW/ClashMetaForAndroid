package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCfOptimizerParamsBinding
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerSettingsStore

/**
 * CF 优选「运行参数」子页面 —— 所有可调参数集中在这里，分组展示：
 * 筛选（排除/仅限国家）、配额（每轮源数/单源抽样/候选上限/上传门槛/每地区上限）、
 * 探测与测速（并发/超时/采样/下载量与早停）、质量门槛与输出、评分与去重、记忆库、
 * 行为（下载测速/记忆库/网络变化扫描）、手动条目。
 *
 * 每行都是「值 + 标题」，标题里带默认值与取值范围；清空输入框 = 恢复默认
 * （解析规则集中在 service 模块的 CfOptimizerTuning，这里是纯展示 + 绑定）。
 *
 * 主页面（[CfOptimizerSettingsDesign]）只留开关 / 立即运行 / Worker 配置 / 数据导出 ——
 * 之前 16 行全平铺在一起，用户反馈"进去看着挺乱"。
 */
class CfOptimizerParamsDesign(
    context: Context,
    cfSettings: CfOptimizerSettingsStore,
) : Design<Unit>(context) {
    private val binding = DesignSettingsCfOptimizerParamsBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    /** 非空 String 属性的适配器（[NullableTextAdapter.String] 是 String? 版，类型不匹配）。 */
    private val stringAdapter = object : NullableTextAdapter<String> {
        override fun from(value: String): String? = value
        override fun to(text: String?): String = text ?: ""
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val screen = preferenceScreen(context) {
            tips(R.string.cf_optimizer_tips_params)

            category(R.string.cf_optimizer_group_filter)

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

            category(R.string.cf_optimizer_group_quota)

            editableText(
                value = cfSettings::sourcesPerRunRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_sources_per_run,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::perSourceSampleRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_per_source_sample,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::maxCandidatesRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_max_candidates,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::minUploadEntriesRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_min_upload_entries,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::maxPerRegionRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_max_per_region,
                empty = R.string.cf_optimizer_not_set,
            )

            category(R.string.cf_optimizer_group_probe)

            editableText(
                value = cfSettings::tcpConcurrencyRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_tcp_concurrency,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::tcpTimeoutMsRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_tcp_timeout_ms,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::probeConcurrencyRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_probe_concurrency,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::ttfbSamplesRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_ttfb_samples,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::downloadPoolLimitRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_download_pool_limit,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::downloadConcurrencyRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_download_concurrency,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::downloadSizeMbRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_download_size_mb,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::downloadTimeoutMsRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_download_timeout_ms,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::downloadEarlyStopMbpsRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_download_early_stop_mbps,
                empty = R.string.cf_optimizer_not_set,
            )

            category(R.string.cf_optimizer_group_quality)

            editableText(
                value = cfSettings::maxTtfbMsRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_max_ttfb_ms,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::maxJitterMsRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_max_jitter_ms,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::minScoreRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_min_score,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::maxEntriesRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_max_entries,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::rawPoolFactorRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_raw_pool_factor,
                empty = R.string.cf_optimizer_not_set,
            )

            category(R.string.cf_optimizer_group_score)

            editableText(
                value = cfSettings::scoreTtfbWeightRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_score_ttfb_weight,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::scoreBwWeightRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_score_bw_weight,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::dedupPrefixV4Raw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_dedup_prefix_v4,
                empty = R.string.cf_optimizer_not_set,
            )

            category(R.string.cf_optimizer_group_memory)

            editableText(
                value = cfSettings::memoryStaleDaysRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_memory_stale_days,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::memoryDecayDaysRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_memory_decay_days,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::memoryMaxFailStreakRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_memory_max_fail_streak,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::memoryMaxSizeRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_memory_max_size,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::memoryHourBucketsRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_memory_hour_buckets,
                empty = R.string.cf_optimizer_not_set,
            )

            editableText(
                value = cfSettings::memoryPoolLimitRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_memory_pool_limit,
                empty = R.string.cf_optimizer_not_set,
            )

            category(R.string.cf_optimizer_group_behavior)

            switch(
                value = cfSettings::downloadTestEnabled,
                title = R.string.cf_optimizer_download_test,
                summary = R.string.cf_optimizer_download_test_summary,
            )

            switch(
                value = cfSettings::memoryEnabled,
                title = R.string.cf_optimizer_memory,
                summary = R.string.cf_optimizer_memory_summary,
            )

            switch(
                value = cfSettings::autoHealEnabled,
                title = R.string.cf_optimizer_auto_heal,
                summary = R.string.cf_optimizer_auto_heal_summary,
            )

            editableText(
                value = cfSettings::minIntervalHoursRaw,
                adapter = stringAdapter,
                title = R.string.cf_optimizer_min_interval_hours,
                empty = R.string.cf_optimizer_not_set,
            )

            category(R.string.cf_optimizer_group_manual)

            editableTextList(
                value = cfSettings::customEntries,
                adapter = TextAdapter.String,
                title = R.string.cf_optimizer_custom_entries,
                placeholder = R.string.cf_optimizer_not_set,
            )
        }

        binding.content.addView(screen.root)
    }
}
