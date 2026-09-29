package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.service.cfoptimizer.quality.CfQualityGate
import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerIntents
import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerSettingsStore
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 劣化自动补货 —— 挂在 Clash 服务生命周期里（Clash 在跑才检查，停了协程随之取消）。
 *
 * Clash 核心的 url-test 组本来每 interval 就跑一轮健康检查；本模块每 30 分钟读一次
 * 核心里的延迟数据，多数节点失联/变慢且距上次优选超过节流窗（默认 6 小时，运行参数里可改，
 * 0 = 不限）时，走「立即运行」同一条
 * 广播链路触发一轮完整优选。任何一步失败只记日志，不影响代理本身。
 */
class CfQualityTriggerModule(service: Service) : Module<Unit>(service) {
    override suspend fun run() {
        // 本类不是 CoroutineScope（Module 基类只有 service），显式取本协程的上下文判定。
        while (coroutineContext.isActive) {
            delay(CHECK_INTERVAL_MS)
            checkAndTrigger()
        }
    }

    private suspend fun checkAndTrigger() {
        try {
            val settings = CfOptimizerSettingsStore(service)

            if (!settings.enabled) return
            if (!settings.autoHealEnabled) return
            if (settings.workerBaseUrl.isBlank()) return

            val delays = collectDelays()
            val verdict = CfQualityGate.shouldRun(
                delays,
                settings.lastRunAt,
                System.currentTimeMillis(),
                settings.minIntervalHours.toLong(),
            )

            Log.i(
                "CF quality check: nodes=${delays.size} verdict=${verdict.reason ?: "healthy"}",
            )

            if (!verdict.shouldRun) return

            // 与设置页「立即运行」同一条路：包内广播 → CfOptimizerReceiver → 前台服务。
            service.sendBroadcast(
                Intent(CfOptimizerIntents.ACTION_RUN_NOW).setPackage(service.packageName),
            )
        } catch (e: Exception) {
            Log.w("CF quality check failed", e)
        }
    }

    /** 收集当前 profile 全部节点的延迟（跨组去重；DIRECT/REJECT 不算样本）。 */
    private fun collectDelays(): List<Int> {
        val seen = HashSet<String>()
        val delays = mutableListOf<Int>()

        for (name in Clash.queryGroupNames(false)) {
            val group = Clash.queryGroup(name, ProxySort.Default)

            for (proxy in group.proxies) {
                if (proxy.isGroup) continue
                if (proxy.type == "Direct" || proxy.type == "Reject") continue
                if (!seen.add(proxy.name)) continue

                delays += proxy.delay
            }
        }

        return delays
    }

    companion object {
        /** 检查周期（分钟）：读核心内存数据，很便宜；节流在判定里另有可调门（默认 6 小时，0 = 不限）。 */
        const val CHECK_INTERVAL_MINUTES: Long = 30

        /** delay() 只认毫秒 —— 换算只在这里做一次，杜绝「分钟常量直接喂毫秒 API」。 */
        private val CHECK_INTERVAL_MS: Long = TimeUnit.MINUTES.toMillis(CHECK_INTERVAL_MINUTES)
    }
}
