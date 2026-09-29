package com.github.kr328.clash.service.cfoptimizer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.startForegroundCompat
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.id.UndefinedIds
import com.github.kr328.clash.service.BaseService
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerIntents
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * CF 优选手动触发的前台服务。设置页（app 进程）通过包内广播
 * [CfOptimizerIntents.ACTION_RUN_NOW] → [CfOptimizerReceiver] → 本服务执行一次完整优选。
 *
 * 生命周期与 [com.github.kr328.clash.service.ProfileWorker] 同型：队列空 + 10s 后自停。
 * 不放 TunService：优选不需要 VPN 活着，TunService 只在 VPN 运行时存在——
 * 放那里会让「VPN 关闭时点了没反应」。
 */
class CfOptimizerService : BaseService() {
    private val service: CfOptimizerService
        get() = this

    override fun onCreate() {
        super.onCreate()

        createChannels()

        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setContentTitle(getString(R.string.cf_optimizer_service))
            .setContentText(getString(R.string.running))
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        startForegroundCompat(R.id.nf_cf_optimizer, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == CfOptimizerIntents.ACTION_RUN_NOW) {
            launch {
                runOptimization()
                delay(TimeUnit.SECONDS.toMillis(2))
                stopSelf()
            }
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private suspend fun runOptimization() {
        var lastTick = -1

        val result = try {
            CfOptimizerCoordinator(this).run { stage, progress, total ->
                // 确定性反馈：前台服务通知实时更新阶段与进度——挂在后台也看得到它在动。
                // 节流：探测阶段每完成 5 个才刷一次通知（其余阶段量少直接刷）。
                val tick = if (stage == CfOptimizerCoordinator.STAGE_PROBE) progress / 5 else -1
                if (stage != CfOptimizerCoordinator.STAGE_PROBE || tick != lastTick) {
                    lastTick = tick
                    updateForegroundNotification(progressText(stage, progress, total))
                }
            }
        } catch (e: Exception) {
            StateStore(this).saveRunState(CfOptimizerCoordinator.STAGE_DONE, 0, 0)
            updateForegroundNotification(getString(R.string.running))
            notifyResult(getString(R.string.cf_optimizer_result_failure, e.message ?: "unknown"))
            return
        }

        // 本轮结束标记：设置页的状态行据此从「进行中」切回「上次成功 …」。
        StateStore(this).saveRunState(CfOptimizerCoordinator.STAGE_DONE, 0, 0)

        updateForegroundNotification(getString(R.string.running))

        when {
            !result.uploaded ->
                notifyResult(getString(R.string.cf_optimizer_result_failure, result.uploadReason ?: "unknown"))
            !result.profileUpdated && result.profileError != null ->
                notifyResult(getString(R.string.cf_optimizer_result_profile_failed, result.profileError ?: "unknown"))
            else ->
                notifyResult(getString(R.string.cf_optimizer_result_success, result.qualifiedCount))
        }
    }

    /** 阶段进度 → 用户可读文本（拉源 / 探测 i/total / 下载测速 / 评分 / 上传）。 */
    private fun progressText(stage: String, progress: Int, total: Int): String = when (stage) {
        CfOptimizerCoordinator.STAGE_SOURCES -> getString(R.string.cf_optimizer_stage_sources)
        CfOptimizerCoordinator.STAGE_PROBE ->
            getString(R.string.cf_optimizer_stage_probe, progress, total)
        CfOptimizerCoordinator.STAGE_DOWNLOAD ->
            getString(R.string.cf_optimizer_stage_download, progress, total)
        CfOptimizerCoordinator.STAGE_RANK -> getString(R.string.cf_optimizer_stage_rank)
        CfOptimizerCoordinator.STAGE_UPLOAD -> getString(R.string.cf_optimizer_stage_upload)
        else -> getString(R.string.running)
    }

    /** 重发前台服务通知（更新进度文本；FGS 通知可反复 startForeground 刷新）。 */
    private fun updateForegroundNotification(text: String) {
        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setContentTitle(getString(R.string.cf_optimizer_service))
            .setContentText(text)
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        startForegroundCompat(R.id.nf_cf_optimizer, notification)
    }

    private fun notifyResult(text: String) {
        NotificationManagerCompat.from(this)
            .notify(
                UndefinedIds.next(),
                NotificationCompat.Builder(this, RESULT_CHANNEL)
                    .setColor(getColorCompat(R.color.color_clash))
                    .setSmallIcon(R.drawable.ic_logo_service)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setContentTitle(getString(R.string.cf_optimizer_service))
                    .setContentText(text)
                    .build()
            )
    }

    private fun createChannels() {
        NotificationManagerCompat.from(this).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(
                    SERVICE_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.cf_optimizer_service)).build(),
                NotificationChannelCompat.Builder(
                    RESULT_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT
                ).setName(getString(R.string.cf_optimizer_service)).build(),
            )
        )
    }

    override fun onBind(intent: Intent?): android.os.IBinder {
        return android.os.Binder()
    }

    companion object {
        private const val SERVICE_CHANNEL = "cf_optimizer_service_channel"
        private const val RESULT_CHANNEL = "cf_optimizer_result_channel"
    }
}

/**
 * 包内广播接收器（manifest 注册，exported=false）：把设置页的 run-now 广播转成前台服务启动。
 * 只有 app 自身（前台 Activity）能发出该广播；不接收任何外部输入。
 */
class CfOptimizerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CfOptimizerIntents.ACTION_RUN_NOW) return

        context.startForegroundServiceCompat(
            Intent(context, CfOptimizerService::class.java)
                .setAction(CfOptimizerIntents.ACTION_RUN_NOW)
        )
    }
}
