package com.github.kr328.clash.service.cfoptimizer.source

import android.net.Network
import com.github.kr328.clash.service.cfoptimizer.CandidateIp
import com.github.kr328.clash.service.cfoptimizer.CfLog
import com.github.kr328.clash.service.cfoptimizer.CfOptimizerEngine
import com.github.kr328.clash.service.cfoptimizer.net.PhysicalNetwork
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.random.Random

/**
 * CF 候选源 — 对齐原 Python 脚本「动态通讯录」（auto_update_config）语义：
 *
 * 1. 源发现：从固定导航站抓取最新 .txt 源链接（原版 scrape `<span class="url-text">`）；
 * 2. 三层兜底：导航站失败 → 本地缓存（上轮发现的源） → 内置备胎源（与原版一致）；
 * 3. 每轮随机抽取有界子集（原版 sample_limit 语义：每次测的 IP 不重样，且不撑爆内存）；
 * 4. 国家黑名单预过滤：行尾 `#CN` 等后缀落在 [EXCLUDED_SOURCE_REGIONS] 的行直接丢弃
 *    （与原版 EXCLUDE_COUNTRIES 同值；trace 权威过滤在 Coordinator 探测后做）。
 *
 * 候选来源刻意**不开放用户自填**（v1 契约，INTERFACES.md C/D 部分）：URL 全部来自
 * 固定导航站或内置常量，没有用户输入 → 没有 SSRF 面。
 *
 * ponytail: 来源仅信任格式不信任成员；有效性由 CfProbe 实际探测（TTFB + cdn-cgi/trace）
 * 兜底——原版同样不做网段成员过滤（CF_CIDR_LIST 仅用于盲扫范围，2026-09-29 实测
 * zip.cm.edu.kg/all.txt 的 15490 行优选 IP 全部在官方 15 段之外，按网段过滤会杀掉全部数据）。
 */
object CfCandidateSource {
    /**
     * 内置备胎源（原版 auto_update_config 终极兜底列表）。
     */
    /**
     * 内置源 —— 只在导航站发现失败时兜底用，所以这里放"实测活着且富"的源。
     *
     * 2026-09-30 实测（逐源 curl 计数有效 IP 行）：`zip.cm.edu.kg` 15490 行、
     * `bestcf.pages.dev/s5gy` 293 行、`LancelotRar/best-cf-ips` top400 400 行。
     * **换掉的旧源** `https://mirror.ghproxy.com/...gslege/CloudflareIP/main/All.txt` 实测 **0 行**
     * （ghproxy 镜像已失效）—— 留着一个死源不会报错，只会静默少一份候选。
     *
     * 刻意**不收** WARP-MASQUE 两个富源（各 2048 行）：那是 WARP/MASQUE（UDP）的地址，
     * 与本功能要的"TCP 直连入口 IP"不是一回事。
     */
    val BUILTIN_SOURCE_URLS: List<String> = listOf(
        "https://zip.cm.edu.kg/all.txt",
        "https://bestcf.pages.dev/s5gy/all.txt",
        "https://raw.githubusercontent.com/LancelotRar/best-cf-ips/refs/heads/main/best-cf-ip-scanned-top400.txt",
    )

    /**
     * 固定导航站（原版 auto_update_config 抓取的页面）。
     */
    const val NAVIGATION_URL: String = "https://bestcf.cfmofa.eu.cc/"

    /**
     * 国家黑名单：行尾地区后缀在黑名单内的候选行直接丢弃。
     * 与原版 EXCLUDE_COUNTRIES = {"RU","KP","CN","HK"} 同值（网络封锁国家默认排除）。
     */
    val EXCLUDED_SOURCE_REGIONS: Set<String> = setOf("RU", "KP", "CN", "HK")

    /**
     * 单源单轮抽样上限的默认参数值（调用方不传时才生效）。生产路径由
     * [com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerTuning.PER_SOURCE_SAMPLE_DEFAULT]
     * 驱动 —— 默认 1000 条/源 × [com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerTuning.SOURCES_PER_RUN_DEFAULT]
     * 个源，再由每轮候选池上限封顶。
     */
    const val PER_SOURCE_SAMPLE: Int = 250

    /**
     * 单源抽样条数的**绝对上限**（防用户把便宜层填到爆内存）。
     * 3000 = 原版单源 `sample_limit` 的上限量级；再大只是多占内存，TCP 预筛并不会更快。
     */
    const val MAX_PER_SOURCE_SAMPLE: Int = 3_000

    /**
     * 单源响应字节上限（原版对大源按 sample_limit 抽样控内存；这里按字节截断兜底）。
     */
    private const val MAX_SOURCE_BYTES: Int = 4 * 1024 * 1024

    /**
     * 每轮候选池上限的默认值（协调器会显式传"便宜层上限"= 昂贵层 × 用户可调的倍数（默认 2））。
     * 这里是**便宜层**语义：只付 TCP connect 的价钱，真正的昂贵段由 maxCandidates 单独封顶。
     */
    const val MAX_CANDIDATES: Int = 300

    /** 连接/读取超时（毫秒）。公网静态文本源；直连失败的有界回落见 [fetchFrom]，不做无界重试。 */
    private const val CONNECT_TIMEOUT_MS: Int = 5_000
    private const val READ_TIMEOUT_MS: Int = 10_000

    /** Cloudflare 支持的入口端口（与引擎白名单一致，双保险）。 */
    private val ALLOWED_PORTS: Set<Int> =
        setOf(80, 8080, 8880, 2052, 2082, 2086, 2095, 443, 2053, 2083, 2087, 2096, 8443)

    /**
     * 拉取候选。返回打乱后截断的候选列表；完全失败抛最后一个 [IOException]，由调用方决定终止路径。
     *
     * [excludedRegions]：黑名单地区（用户设置，默认 RU,KP,CN,HK）；[perSourceSample] /
     * [maxCandidates]：用户设置的有界参数。
     */
    fun fetchFrom(
        sources: List<String>,
        excludedRegions: Set<String> = EXCLUDED_SOURCE_REGIONS,
        perSourceSample: Int = PER_SOURCE_SAMPLE,
        maxCandidates: Int = MAX_CANDIDATES,
        network: Network? = null,
    ): List<CandidateIp> {
        val rnd = Random(System.nanoTime())

        val merged = LinkedHashMap<String, CandidateIp>()
        var lastError: IOException? = null

        // 直连回落（R1，2026-10-01 诊断）：CN 蜂窝直连 GitHub/pages.dev 等 SNI 封锁源会
        // 超时/复位，原始池因此塌缩（真机 7 轮实测 211-442 条；默认参数 15 源 × 1000/源，
        // 光 zip.cm 一家就有 14875 行）。主网络拉取失败时回落系统默认路由（null = 开代理时
        // 回流进本应用 TUN，由代理代拉）—— 拉源只是文本下载，不涉及探测真实性；探测 /
        // 上传仍固定走物理网络（见 Coordinator 绑定注释）。
        // ponytail: 每源至多一次回落、不跨轮记忆网络健康 | 天花板: 默认路由也不通时每源付双倍超时 | 升级触发: sources 段时长膨胀且 candidates_raw 仍低
        var useDefaultRouting = false

        // 单源失败不拖垮整轮（原版 fetch_urls_classified 同语义），记录最后一个错误；
        // 直连失败回落默认路由再试一次（有界：不叠第二次）。粘性：默认路由验证成功后，
        // 剩余源不再重复付直连超时的价钱。
        fun fetchLines(url: String): List<String>? = try {
            downloadLines(if (useDefaultRouting) null else network, url)
        } catch (e: IOException) {
            lastError = e
            if (useDefaultRouting || network == null) {
                CfLog.w("source fetch failed route=default error=${e.javaClass.simpleName}: ${e.message}")
                null
            } else {
                CfLog.w("source fetch failed route=physical error=${e.javaClass.simpleName}, retry via default routing")
                try {
                    downloadLines(null, url).also { useDefaultRouting = true }
                } catch (e2: IOException) {
                    lastError = e2
                    CfLog.w("source fallback retry also failed: ${e2.javaClass.simpleName}: ${e2.message}")
                    null
                }
            }
        }

        for (url in sources) {
            val startedAtMs = System.currentTimeMillis()
            val lines = fetchLines(url)

            if (lines == null) {
                CfLog.w(
                    "source failed url=${shortSourceUrl(url)} " +
                        "error=${lastError?.javaClass?.simpleName ?: "unknown"}",
                )
                continue
            }

            val parsed = parseCandidates(
                lines.shuffled(rnd).take(perSourceSample.coerceIn(1, MAX_PER_SOURCE_SAMPLE)),
                excludedRegions,
            )

            for (candidate in parsed) {
                merged.putIfAbsent("${candidate.address}:${candidate.port}", candidate)
            }

            CfLog.i(
                "source ok lines=${lines.size} kept=${parsed.size} merged=${merged.size} " +
                    "elapsed=${System.currentTimeMillis() - startedAtMs}ms url=${shortSourceUrl(url)}",
            )
        }

        // 上限不再写死 1000：那个数会把放大后的池子悄悄砍掉一半。
        // 用引擎侧的两层配额上限（RAW_POOL_CEILING），保证"用户设的池子"真的按用户设的来。
        val result = merged.values.shuffled(rnd)
            .take(maxCandidates.coerceIn(1, CfOptimizerEngine.RAW_POOL_CEILING))

        CfLog.i(
            "sources done total=${sources.size} merged=${merged.size} pool=${result.size} " +
                "lastError=${lastError?.javaClass?.simpleName ?: "none"}",
        )

        if (result.isEmpty() && lastError != null) throw lastError

        return result
    }

    /**
     * 源发现：抓取导航站并提取 .txt IP 源链接。失败抛 [IOException]（调用方走缓存/内置兜底）。
     * 直连拉不动时有界回落系统默认路由（拉源只是文本下载；语义同 [fetchFrom] 的回落）。
     */
    fun discoverSourceUrls(network: Network? = null): List<String> = try {
        scrapeNavigationSite(network)
    } catch (e: IOException) {
        if (network == null) throw e
        CfLog.w("source discovery failed route=physical error=${e.javaClass.simpleName}, retry via default routing")
        scrapeNavigationSite(null)
    }

    /** 抓取导航站并提取 .txt IP 源链接（原版 scrape `<span class="url-text">` 语义）。 */
    private fun scrapeNavigationSite(network: Network?): List<String> {
        val html = downloadText(network, NAVIGATION_URL, MAX_SOURCE_BYTES)

        val regex = Regex("""<span class="url-text">(.*?)</span>""")

        return regex.findAll(html)
            .map { it.groupValues[1].trim() }
            .map { if (it.startsWith("http")) it else "https://$it" }
            .filter { it.endsWith(".txt") && !it.contains("sub://") && !it.contains("/CIDR/") }
            .distinct()
            .toList()
    }

    /** 源 URL 的日志形态：去掉 query（防个别源把 token 放 query 里被写进日志）。 */
    private fun shortSourceUrl(url: String): String = url.substringBefore('?')

    /** 下载源文本并解析为候选（黑名单地区后缀行直接丢弃）。 */
    private fun parseCandidates(lines: List<String>, excludedRegions: Set<String>): List<CandidateIp> =
        lines.asSequence()
            .map { line ->
                val region = line.substringAfter('#', "").trim().uppercase()
                line.substringBefore('#').trim() to region
            }
            .filter { (_, region) -> region !in excludedRegions }
            .mapNotNull { (entry, _) -> parseLine(entry) }
            .distinctBy { it.address to it.port }
            .toList()

    /** 下载并按行返回（10s 读超时；字节截断兜底）。失败抛 [IOException]。 */
    private fun downloadLines(network: Network?, url: String): List<String> =
        downloadText(network, url, MAX_SOURCE_BYTES).lineSequence().filter { it.isNotBlank() }.toList()

    /**
     * 下载文本源。连接默认走 [network] 绑定的网络（null = 系统默认路由）：开着代理时裸
     * `URL.openConnection()` 会回流进本应用 TUN，被自己的规则送进代理节点，
     * 表现为「关代理顺利、开代理拉源失败」。
     *
     * 直连回落由调用方做（[fetchFrom] / [discoverSourceUrls]）—— 本函数保持单路径纯下载。
     */
    private fun downloadText(network: Network?, url: String, maxBytes: Int): String {
        val connection = PhysicalNetwork.openConnection(network, URL(url)) as HttpURLConnection

        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false

        try {
            if (connection.responseCode !in 200..299) {
                // 异常消息会被上游原样带进日志（fetchLines catch 的 route=default 行、
                // Coordinator 的 candidates_* abort 行），所以这里就去 query——与
                // shortSourceUrl 同一契约：个别源把 token 放 query，不能经异常消息落 logcat。
                throw IOException("source HTTP ${connection.responseCode}: ${shortSourceUrl(url)}")
            }

            val stream = connection.inputStream
            val buffer = ByteArray(maxBytes)
            var offset = 0

            while (offset < buffer.size) {
                val read = stream.read(buffer, offset, buffer.size - offset)
                if (read < 0) break
                offset += read
            }

            return String(buffer, 0, offset, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    /** 行格式 `IP:port`（`#` 后缀已由调用方剥离）；拒绝一切非字面 IPv4。 */
    private fun parseLine(line: String): CandidateIp? {
        val match = Regex("""^(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})$""").find(line) ?: return null

        val address = match.groupValues[1]
        val port = match.groupValues[2].toIntOrNull() ?: return null

        if (port !in ALLOWED_PORTS) return null
        if (!isPublicLiteralIpv4(address)) return null

        return CandidateIp(address = address, port = port, source = "remote")
    }

    /** 字面公网 IPv4（不触发 DNS；拒绝 private/loopback/multicast/reserved/link-local/unspecified）。 */
    private fun isPublicLiteralIpv4(address: String): Boolean {
        val addr = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return false

        if (addr !is java.net.Inet4Address) return false

        return !(addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isMulticastAddress ||
                addr.isAnyLocalAddress || addr.isLinkLocalAddress)
    }
}
