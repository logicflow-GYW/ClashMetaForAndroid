package com.github.kr328.clash.service.cfoptimizer

import android.util.Log

/**
 * CF 优选专属 logcat 标签 —— 让「只针对本功能的日志」可以被一条命令完整取出：
 *
 * ```
 * adb logcat -s CFOptimizer
 * ```
 *
 * 为什么不复用 common 的 Log（TAG=ClashMetaForAndroid）：全应用几百处日志混在一个
 * tag 里，优选一轮几十行会把别的日志淹没，反过来别的日志也会淹没优选日志；
 * 独立 tag 才让"看日志"从"大海捞针"变成一条命令。
 *
 * 消息统一 key=value 风格（与 CfQualityTriggerModule 既有 "CF quality check" 行同族），
 * 便于 grep；**绝不打印** 密码 / Cookie / Authorization 头 / 完整 Worker 地址
 * （隐私契约同 CfWorkerClient KDoc——那里连一行日志都不打，本类只在链路外围打点）。
 *
 * 本类只是 android.util.Log 的极薄包装（4 个方法 + 一个 TAG），不持有状态、不做队列或
 * buffer：调用点分散在整条链路，包装的唯一目的是让 tag 与调用形态只有一处定义。
 */
object CfLog {
    private const val TAG = "CFOptimizer"

    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    }

    fun e(message: String, error: Throwable? = null) {
        if (error == null) Log.e(TAG, message) else Log.e(TAG, message, error)
    }
}
