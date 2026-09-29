package com.github.kr328.clash.service.cfoptimizer.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import java.net.URL
import java.net.URLConnection

/**
 * 物理网络出口 — CF 优选模块的**所有** Java 侧网络 I/O 都必须走真实底层网络，
 * 绝不能走本应用自己的 VPN。
 *
 * 为什么必须有这个类（真机实测到的故障形状）：开着代理时，「探测」走物理网络正常，
 * 而「候选源拉取 / Worker 上传」用的是裸 `URL(url).openConnection()` = 系统默认网络，
 * 于是请求回流进本应用自己的 TUN，被自己的规则匹配后送进代理节点（节点可能正是上一轮
 * 刚写入的优选 IP），出现「关代理一切顺利、开代理上传失败 / 订阅更新失败」。
 *
 * 语义：选择带 INTERNET 能力且**不含 TRANSPORT_VPN** 的网络；Wi‑Fi 优先，其次蜂窝。
 * 一个都选不中时返回 null —— 调用方回退系统默认网络（不做静默丢弃，行为可观测）。
 */
object PhysicalNetwork {
    /**
     * 主物理网络（探测 / 源拉取 / 上传统一使用同一条，避免同轮内跨网络不一致）。
     * 无可用物理网络（例如仅剩 VPN）时返回 null。
     */
    fun pick(context: Context): Network? = candidates(context).firstOrNull()

    /** 候选物理网络：有 INTERNET 且非 VPN；Wi‑Fi > 蜂窝 > 其他。 */
    fun candidates(context: Context): List<Network> {
        val connectivity = context.getSystemService<ConnectivityManager>() ?: return emptyList()

        return connectivity.allNetworks
            .map { it to connectivity.getNetworkCapabilities(it) }
            .filter { (_, caps) ->
                caps != null &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            .sortedByDescending { (_, caps) ->
                when {
                    caps!!.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                    else -> 0
                }
            }
            .map { it.first }
    }

    /**
     * 在物理网络上打开连接；[network] 为空时回退系统默认。
     *
     * 绑定 Network 的连接其 DNS 解析与路由都固定在该网络，
     * 因此 VPN 开关不影响结果 —— 这是「关代理顺利、开代理失败」的根治点。
     */
    fun openConnection(network: Network?, url: URL): URLConnection =
        network?.openConnection(url) ?: url.openConnection()

    /** 便捷重载：自行选择当前主物理网络。 */
    fun openConnection(context: Context, url: URL): URLConnection =
        openConnection(pick(context), url)

}
