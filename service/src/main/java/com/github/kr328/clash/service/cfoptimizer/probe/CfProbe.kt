package com.github.kr328.clash.service.cfoptimizer.probe

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import com.github.kr328.clash.service.cfoptimizer.CandidateIp
import com.github.kr328.clash.service.cfoptimizer.ProbeMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket

/**
 * Android 物理网络探测 — 实测候选 CF 入口 IP 的 TTFB/抖动/地区。
 *
 * 关键约束（INTERFACES.md D）：探测必须走**真实底层物理网络**，不得绕回本应用 VPN。
 * 实现方式：从 [ConnectivityManager] 选出带 TRANSPORT_INTERNET 且**不含 TRANSPORT_VPN**
 * 的网络，用 `Network.socketFactory` 创建绑定该网络的 socket —— Android 文档语义：
 * 绑定到特定 Network 的 socket 的路由不经过 VPN（VPN 在网络列表里是独立条目，被排除）。
 *
 * TLS 用系统默认 trust manager + SNI `cp.cloudflare.com`，证书校验完整——
 * 不照搬 Python 脚本的 `ssl=False`。
 */
class CfProbe(private val context: Context) {
    /** HTTP 探测目标 host（SNI/Host 头）。 */
    private val probeHost: String = PROBE_HOST

    suspend fun measure(
        candidates: List<CandidateIp>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<CandidateIp, ProbeMetrics> =
        withContext(Dispatchers.IO) {
            val network = candidateNetworks().firstOrNull() ?: return@withContext emptyMap()
            val semaphore = Semaphore(PROBE_CONCURRENCY)
            var done = 0

            coroutineScope {
                candidates.map { candidate ->
                    async {
                        semaphore.withPermit {
                            val result = measureOne(network, candidate)

                            // 进度上报（探测是全程最长阶段，实时反馈给状态框与前台通知）。
                            synchronized(Unit) { done += 1 }
                            runCatching { onProgress(done, candidates.size) }

                            result
                        }
                    }
                }.awaitAll().filterNotNull().toMap()
            }
        }

    /**
     * 探测单个候选：3 次 TTFB 采样 + 一次地区 trace。
     * 失败返回 null（该候选本轮无指标，引擎会丢弃）。
     */
    private suspend fun measureOne(network: Network, candidate: CandidateIp): Pair<CandidateIp, ProbeMetrics>? {
        val https = candidate.port in HTTPS_PORTS

        val samples = mutableListOf<Long>()
        repeat(TTFB_SAMPLES) {
            val elapsed = runCatching {
                timedGet(network, candidate.address, candidate.port, https, "/generate_204?ed=2560", setOf(200, 204))
            }.getOrNull()

            if (elapsed != null) samples.add(elapsed.first)
            if (samples.size < TTFB_SAMPLES) kotlinx.coroutines.delay(SAMPLE_GAP_MS)
        }

        // 与原脚本口径一致：>=2 样本取中位数 + 全距抖动；仅 1 样本时无抖动数据（jitter 记 0，
        // 交给 maxJitterMs 门槛无差别放行，由 successfulSamples=1 可追溯）。
        if (samples.isEmpty()) return null

        samples.sort()
        val median = samples[samples.size / 2]
        val jitter = if (samples.size >= 2) samples.last() - samples.first() else 0L

        val region = runCatching {
            timedGet(network, candidate.address, candidate.port, https, "/cdn-cgi/trace", setOf(200), wantLoc = true)
                ?.second?.let { loc -> loc.trim().uppercase().takeIf { it.length == 2 } }
        }.getOrNull() ?: REGION_FALLBACK

        return candidate to ProbeMetrics(
            ttfbMs = median,
            jitterMs = jitter,
            downloadMbps = 0.0, // 下载测速本轮默认关（契约 D 部分）；下载关闭时评分仅含 TTFB 分量，门槛分由协调器按比例折半。
            successfulSamples = samples.size,
            region = region,
        )
    }

    /**
     * 在绑定 network 的 socket 上做一次 HTTP GET，返回 (耗时ms, 响应文本可选)。
     * HTTPS：先建裸 socket（绑定 network），再用系统默认 SSLSocketFactory 包一层 ——
     * 证书校验与 SNI 针对目标 host `cp.cloudflare.com` 完成，socket 仍绑定物理网络。
     */
    private fun timedGet(
        network: Network,
        ip: String,
        port: Int,
        https: Boolean,
        path: String,
        okStatuses: Set<Int>,
        wantLoc: Boolean = false,
    ): Pair<Long, String?>? {
        val socket = network.socketFactory.createSocket()

        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS

            val io = if (https) {
                val ssl = sslWrap(socket, probeHost, port)
                ssl.startHandshake()
                ssl
            } else {
                socket
            }

            val output = io.getOutputStream()
            val input = BufferedReader(InputStreamReader(io.getInputStream(), Charsets.UTF_8))

            output.write(
                ("GET $path HTTP/1.1\r\n" +
                        "Host: $probeHost\r\n" +
                        "User-Agent: ${USER_AGENT}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
            )
            output.flush()

            val start = System.nanoTime()
            val statusLine = input.readLine() ?: return null
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            val status = Regex("""HTTP/\d(?:\.\d)? (\d{3})""").find(statusLine)?.groupValues?.get(1)
                ?.toIntOrNull() ?: return null
            if (status !in okStatuses) return null

            if (!wantLoc) return elapsedMs to null

            var loc: String? = null
            for (line in input.lineSequence()) {
                if (line.startsWith("loc=")) {
                    loc = line.removePrefix("loc=").trim()
                    break
                }
            }

            return elapsedMs to loc
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun sslWrap(plain: Socket, host: String, port: Int): SSLSocket {
        val factory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val ssl = factory.createSocket(plain, host, port, true) as SSLSocket

        return ssl
    }

    /** 候选探测用网络：有 INTERNET 且**非 VPN**；Wi‑Fi 优先，其次蜂窝，其余次之。 */
    fun candidateNetworks(): List<Network> {
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

    companion object {
        /** 探测目标 host（Cloudflare anycast 入口，SNI/Host 一致）。 */
        const val PROBE_HOST: String = "cp.cloudflare.com"

        /** Cloudflare HTTPS 端口组（与引擎/来源白名单一致）。 */
        private val HTTPS_PORTS: Set<Int> = setOf(443, 8443, 2053, 2083, 2087, 2096)

        /** TTFB 采样次数（与原脚本一致：3 次，中位数 + 全距抖动）。 */
        const val TTFB_SAMPLES: Int = 3

        /** 采样间隔（毫秒）。原脚本 100ms。 */
        const val SAMPLE_GAP_MS: Long = 100

        /**
         * 探测并发硬上限。移动网络 + 电量敏感；原脚本 50 workers，Android MVP 收敛到 8 ——
         * 显式命名并附理由，不砍成魔数。
         */
        const val PROBE_CONCURRENCY: Int = 8

        /** 连接超时（毫秒）。公网 anycast 入口，5s 足够；超时候选本轮无指标。 */
        const val CONNECT_TIMEOUT_MS: Int = 5_000

        /** 读取超时（毫秒）。单请求最迟 10s，防止慢节点占住并发槽。 */
        const val READ_TIMEOUT_MS: Int = 10_000

        /** 地区缺失时的占位（不从 IP 反推地理位置）。 */
        const val REGION_FALLBACK: String = "ZZ"

        /** 探测请求 UA（与原脚本一致）。 */
        const val USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    }
}
