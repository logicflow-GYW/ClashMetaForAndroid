package com.github.kr328.clash.service.cfoptimizer.probe

import android.content.Context
import android.net.Network
import com.github.kr328.clash.service.cfoptimizer.CandidateIp
import com.github.kr328.clash.service.cfoptimizer.ProbeMetrics
import com.github.kr328.clash.service.cfoptimizer.net.PhysicalNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
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
class CfProbe(private val context: Context, private val config: CfProbeConfig = CfProbeConfig()) {
    /** HTTP 探测目标 host（SNI/Host 头）。 */
    private val probeHost: String = PROBE_HOST

    /**
     * 对候选取 TTFB 采样。[regions] 由 [resolveRegions] 先行产出（trace 前置）——
     * 本函数只做采样，trace 失败的候选在这里按 [REGION_FALLBACK] 记录（未知地区照测，
     * 由协调器决定是否保留）。
     */
    suspend fun measure(
        candidates: List<CandidateIp>,
        regions: Map<CandidateIp, String> = emptyMap(),
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<CandidateIp, ProbeMetrics> =
        withContext(Dispatchers.IO) {
            val network = primaryNetwork() ?: return@withContext emptyMap()
            val semaphore = Semaphore(config.probeConcurrency)
            var done = 0

            coroutineScope {
                candidates.map { candidate ->
                    async {
                        semaphore.withPermit {
                            val result = measureOne(network, candidate, regions[candidate] ?: REGION_FALLBACK)

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
     * TCP 连通预筛 —— 漏斗第一段（原版 `MAX_TCP_WORKERS=500` / `TCP_TIMEOUT≈1s` 的手机端等价物）。
     *
     * 只做 `connect`：不握手、不发 HTTP。死 IP 的代价从"3 次完整 TLS+HTTP × [CONNECT_TIMEOUT_MS]"
     * 压到"1 次 connect × [CfProbeConfig.tcpTimeoutMs]"。
     *
     * 为什么这一段敢给 [CfProbeConfig.tcpConcurrency] 而 TTFB 段只给
     * [CfProbeConfig.probeConcurrency]：
     * connect 不占 TLS 栈、不做证书校验、失败快；TTFB 要握手 + 传输，并发越高越容易在移动网络上
     * 排队、把尾延迟测歪。两类操作的单价差一个数量级，就不该共用一个并发数。
     *
     * 返回**保序**的存活子集（调用方依赖顺序做"记忆池在前"的截断）。
     * 网络不可用或候选为空时原样返回 —— 探测设施异常不该把整轮清空。
     */
    suspend fun filterTcpReachable(
        candidates: List<CandidateIp>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<CandidateIp> =
        withContext(Dispatchers.IO) {
            val network = primaryNetwork() ?: return@withContext candidates

            if (candidates.isEmpty()) return@withContext candidates

            val semaphore = Semaphore(config.tcpConcurrency)
            var done = 0

            coroutineScope {
                candidates.map { candidate ->
                    async {
                        semaphore.withPermit {
                            val reachable = runCatching { tcpReachable(network, candidate) }.getOrDefault(false)

                            synchronized(Unit) { done += 1 }
                            runCatching { onProgress(done, candidates.size) }

                            candidate.takeIf { reachable }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }

    /** 单次 TCP connect（超时/拒绝都算不可达）。socket 由物理网络工厂创建，路由不经本应用 TUN。 */
    private fun tcpReachable(network: Network, candidate: CandidateIp): Boolean =
        network.socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(candidate.address, candidate.port), config.tcpTimeoutMs)

            true
        }

    /**
     * 地区解析（trace）—— 跑在 TTFB 采样**之前**（原版顺序：trace 循环里就把黑名单国家挡掉）。
     *
     * 为什么顺序重要（真机实测）：一轮 445 次 TTFB 探测里有 **229 次（51%）**打在随后就被丢弃的
     * 黑名单地区上 —— 同一轮 probe 段耗时 163s，占整轮 232s 的 70%。trace 是单次 GET
     * （几百字节、无采样），把它前置就能让"丢弃"这件事只付 trace 的价钱，等效把探测面翻倍。
     *
     * 返回 trace 成功的 `候选 -> 地区`；trace 失败的候选**不出现在结果里**（调用方按
     * "未知 ≠ 封锁"保留它们，region 回落 [REGION_FALLBACK]）。
     */
    suspend fun resolveRegions(
        candidates: List<CandidateIp>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<CandidateIp, String> =
        withContext(Dispatchers.IO) {
            val network = primaryNetwork() ?: return@withContext emptyMap()

            if (candidates.isEmpty()) return@withContext emptyMap()

            val semaphore = Semaphore(config.traceConcurrency)
            var done = 0

            coroutineScope {
                candidates.map { candidate ->
                    async {
                        semaphore.withPermit {
                            val region = runCatching { resolveRegionOne(network, candidate) }.getOrNull()

                            synchronized(Unit) { done += 1 }
                            runCatching { onProgress(done, candidates.size) }

                            region?.let { candidate to it }
                        }
                    }
                }.awaitAll().filterNotNull().toMap()
            }
        }

    /**
     * 探测单个候选的 TTFB（[config.ttfbSamples] 次采样）。
     * 地区由 [resolveRegions] 先行确定、经 [regions] 传入 —— 本函数不再发 trace 请求。
     * 失败返回 null（该候选本轮无指标，引擎会丢弃）。
     */
    private suspend fun measureOne(
        network: Network,
        candidate: CandidateIp,
        region: String,
    ): Pair<CandidateIp, ProbeMetrics>? {
        val https = candidate.port in HTTPS_PORTS

        val samples = mutableListOf<Long>()
        repeat(config.ttfbSamples) {
            val elapsed = runCatching {
                timedGet(network, candidate.address, candidate.port, https, "/generate_204?ed=2560", setOf(200, 204))
            }.getOrNull()

            if (elapsed != null) samples.add(elapsed.first)
            if (samples.size < config.ttfbSamples) kotlinx.coroutines.delay(SAMPLE_GAP_MS)
        }

        // 与原脚本口径一致：>=2 样本取中位数 + 全距抖动；仅 1 样本时无抖动数据（jitter 记 0，
        // 交给 maxJitterMs 门槛无差别放行，由 successfulSamples=1 可追溯）。
        if (samples.isEmpty()) return null

        samples.sort()
        val median = samples[samples.size / 2]
        val jitter = if (samples.size >= 2) samples.last() - samples.first() else 0L

        return candidate to ProbeMetrics(
            ttfbMs = median,
            jitterMs = jitter,
            downloadMbps = 0.0, // 带宽由测速段回填（measureDownloads）；未测到的节点由门槛拦下。
            successfulSamples = samples.size,
            region = region,
        )
    }

    /** 单候选地区解析：GET `/cdn-cgi/trace` 取 `loc=`，两次大写字母才认，否则 null（不猜）。 */
    private fun resolveRegionOne(network: Network, candidate: CandidateIp): String? {
        val https = candidate.port in HTTPS_PORTS

        return timedGet(
            network,
            candidate.address,
            candidate.port,
            https,
            "/cdn-cgi/trace",
            setOf(200),
            wantLoc = true,
        )?.second?.let { loc -> loc.trim().uppercase().takeIf { it.length == 2 } }
    }

    /**
     * 下载测速（原版 `speed.cloudflare.com/__down` 语义）：给 TTFB 已通过的候选补上吞吐指标，
     * 让评分里占 40% 的带宽分量真正参与排序（此前该分量恒为 0，等于只按 TTFB 选节点）。
     *
     * 只对窄池做：调用方（协调器）传 TTFB 最优的 [CfProbeConfig.downloadPoolLimit] 个候选，
     * 单次最多读 [CfProbeConfig.downloadMaxBytes]、最长 [CfProbeConfig.downloadTimeoutMs]，速率达标提前收工。
     *
     * 返回 `候选 -> Mbps`；失败候选不出现在结果里 —— 协调器保留其 TTFB 分量（带宽记 0），
     * 不编造带宽，也不因为测速失败丢掉本来可用的节点。
     */
    suspend fun measureDownloads(
        candidates: List<CandidateIp>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<CandidateIp, Double> = withContext(Dispatchers.IO) {
        val network = primaryNetwork() ?: return@withContext emptyMap()
        val semaphore = Semaphore(config.downloadConcurrency)
        var done = 0

        coroutineScope {
            candidates.map { candidate ->
                async {
                    semaphore.withPermit {
                        val mbps = runCatching { measureOneDownload(network, candidate) }.getOrNull()

                        synchronized(Unit) { done += 1 }
                        runCatching { onProgress(done, candidates.size) }

                        if (mbps == null) null else candidate to mbps
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    /** 单候选下载测速：TLS（SNI/Host = speed.cloudflare.com）→ 跳过响应头 → 计时读 body。 */
    private fun measureOneDownload(network: Network, candidate: CandidateIp): Double? {
        val https = candidate.port in HTTPS_PORTS
        val socket = network.socketFactory.createSocket()

        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(candidate.address, candidate.port), CONNECT_TIMEOUT_MS)
            // 读超时与整体截止对齐（原版语义）：中途卡住时按**已读字节**结算速率，
            // 而不是整条丢弃。用 10s 的通用读超时会怎样（真机实测）：池子 40 个只出 19 条读数 ——
            // 慢节点被判成"没有读数"，于是最终名单里留下的反而是"没被测速过"的节点。
            socket.soTimeout = config.downloadTimeoutMs.toInt()

            val io = if (https) {
                val ssl = sslWrap(socket, DOWNLOAD_HOST, candidate.port)
                ssl.startHandshake()
                ssl
            } else {
                socket
            }

            val output = io.getOutputStream()
            output.write(
                ("GET ${config.downloadPath} HTTP/1.1\r\n" +
                        "Host: $DOWNLOAD_HOST\r\n" +
                        "User-Agent: $USER_AGENT\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
            )
            output.flush()

            val input = BufferedInputStream(io.getInputStream(), DOWNLOAD_BUFFER_BYTES)
            if (!skipResponseHeaders(input)) return null

            val start = System.nanoTime()
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            var bytes = 0L

            while (bytes < config.downloadMaxBytes) {
                val want = minOf(buffer.size.toLong(), config.downloadMaxBytes - bytes).toInt()
                val read = try {
                    input.read(buffer, 0, want)
                } catch (e: SocketTimeoutException) {
                    // 读到截止还没出下一段 —— 按已读字节结算（原版 `bytes/elapsed` 语义），不整条丢弃。
                    break
                }
                if (read < 0) break

                bytes += read

                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                if (elapsedMs >= config.downloadTimeoutMs) break
                if (elapsedMs >= DOWNLOAD_EARLY_STOP_MIN_MILLIS &&
                    mbpsOf(bytes, elapsedMs) >= config.downloadEarlyStopMbps
                ) {
                    break
                }
            }

            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            if (bytes <= 0 || elapsedMs <= 0) return null

            return mbpsOf(bytes, elapsedMs)
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * 读到空行结束响应头（`\r\n\r\n`）。超长头或 EOF 视为失败。
     * 输出压缩不会发生：请求未带 Accept-Encoding。
     */
    private fun skipResponseHeaders(input: BufferedInputStream): Boolean {
        var matched = 0
        var consumed = 0

        while (consumed < DOWNLOAD_MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return false

            consumed++
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                else -> 0
            }

            if (matched == 4) return true
        }

        return false
    }

    private fun mbpsOf(bytes: Long, elapsedMs: Long): Double =
        bytes * 8.0 / (elapsedMs / 1000.0) / 1_000_000.0

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

    /**
     * 主物理网络（探测 / 源拉取 / Worker 上传统一使用同一条）。
     * 实现委托 [PhysicalNetwork]：排除 VPN、Wi‑Fi 优先、蜂窝次之。
     */
    fun primaryNetwork(): Network? = PhysicalNetwork.pick(context)

    /** 候选探测用网络（保留给协调器做网络标签；语义同 [PhysicalNetwork.candidates]）。 */
    fun candidateNetworks(): List<Network> = PhysicalNetwork.candidates(context)

    companion object {
        /**
         * 这里只留**不可调**的固定项（协议端点、端口组、读写缓冲）。
         *
         * 可调参数（并发 / 超时 / 采样次数 / 测速大小与早停）已集中到
         * [CfOptimizerTuning]（默认值与取值范围）与 [CfProbeConfig]（本轮生效值快照），
         * 理由与实测依据随默认值写在那边 —— 单一来源，避免两处各说一套。
         */

        /** 探测目标 host（Cloudflare anycast 入口，SNI/Host 一致）。 */
        const val PROBE_HOST: String = "cp.cloudflare.com"

        /** Cloudflare HTTPS 端口组（与引擎/来源白名单一致）。 */
        private val HTTPS_PORTS: Set<Int> = setOf(443, 8443, 2053, 2083, 2087, 2096)

        /** 采样间隔（毫秒）。原脚本 100ms。 */
        const val SAMPLE_GAP_MS: Long = 100

        /** 连接超时（毫秒）。公网 anycast 入口，5s 足够；超时候选本轮无指标。 */
        const val CONNECT_TIMEOUT_MS: Int = 5_000

        /** 读取超时（毫秒）。单请求最迟 10s，防止慢节点占住并发槽。 */
        const val READ_TIMEOUT_MS: Int = 10_000

        /** 地区缺失时的占位（不从 IP 反推地理位置）。 */
        const val REGION_FALLBACK: String = "ZZ"

        /** 下载测速 host（与原脚本一致，走 CF 官方测速端点；路径由 [CfProbeConfig.downloadPath] 推导）。 */
        const val DOWNLOAD_HOST: String = "speed.cloudflare.com"

        /** 提前收工的最小测量时长（毫秒）。没测够这么久不判"达标"，防瞬时尖峰误判。 */
        const val DOWNLOAD_EARLY_STOP_MIN_MILLIS: Long = 1_000

        /** 读缓冲与响应头上限。 */
        private const val DOWNLOAD_BUFFER_BYTES: Int = 64 * 1024
        private const val DOWNLOAD_MAX_HEADER_BYTES: Int = 8 * 1024

        /** 探测请求 UA（与原脚本一致）。 */
        const val USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    }
}
