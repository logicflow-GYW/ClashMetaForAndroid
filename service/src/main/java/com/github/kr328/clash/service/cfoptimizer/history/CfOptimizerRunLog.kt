package com.github.kr328.clash.service.cfoptimizer.history

import android.content.Context
import com.github.kr328.clash.service.cfoptimizer.CandidateIp
import com.github.kr328.clash.service.cfoptimizer.OptimizedEntry
import com.github.kr328.clash.service.cfoptimizer.ProbeMetrics
import com.github.kr328.clash.service.cfoptimizer.memory.CfMemoryStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一轮优选的运行快照：由协调器在评分后填入，落盘在 [CfOptimizerRunLog.persist]。
 *
 * 用可变持有者而不是一路传参，是为了让协调器的早退分支（质量门不足 / 上传失败）
 * 也能带着"这一轮到底测到了什么"退出 —— 早退的那几轮恰恰是最需要留证据的。
 */
class CfRunRecorder {
    var candidates: List<CandidateIp> = emptyList()
    var metrics: Map<CandidateIp, ProbeMetrics> = emptyMap()
    var ranked: List<OptimizedEntry> = emptyList()

    var networkTag: String = ""
    var memorySize: Int = 0
    var memoryReused: Int = 0
    var memoryBlocked: Int = 0

    /** 异常退出的类名（正常返回时为空）。 */
    var failReason: String? = null

    val captured: Boolean get() = candidates.isNotEmpty()

    fun capture(
        candidates: List<CandidateIp>,
        metrics: Map<CandidateIp, ProbeMetrics>,
        ranked: List<OptimizedEntry>,
        networkTag: String,
        memorySize: Int,
        memoryReused: Int,
        memoryBlocked: Int,
    ) {
        this.candidates = candidates
        this.metrics = metrics
        this.ranked = ranked
        this.networkTag = networkTag
        this.memorySize = memorySize
        this.memoryReused = memoryReused
        this.memoryBlocked = memoryBlocked
    }
}

/**
 * 运行历史与数据导出。
 *
 * 四份文件写在 app 私有目录 `files/cfoptimizer/`：
 * - `candidates.csv` —— **最近一轮**全部候选的实测指标。表头用中文列名，与原脚本导出的
 *   CSV 对齐，`analyze_csv.py` 可直接读（它按 `IP地址/端口/网络延迟/下载速度/地区/数据中心` 取值）。
 * - `runs.jsonl` —— 每轮一行摘要（追加，超过 [MAX_RUN_LINES] 行后保留后半段）。
 *   "第 1 轮 vs 第 N 轮"的达标数与耗时就靠它，这是"越用越好"的可复核证据。
 * - `entry_ip_<网络标签>.json` —— 本轮入选结果，结构照抄原脚本 `Output/entry_ip_*.json`
 *   （`{generated_at, net_name, count, nodes:[{ip,port,cc,ttfb_ms,mbps,score}]}`）。
 * - `memory.json` —— 跨轮记忆库（由 [CfMemoryStore] 维护，导出时一并复制）。
 *
 * **导出 = 复制**：四个文件原样拷到应用专属外部目录 `Android/data/<包名>/files/cfoptimizer/`，
 * 不需要任何存储权限、卸载即清理，Termux / Shizuku / `adb pull` 都能直接取走。
 */
class CfOptimizerRunLog(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, CfMemoryStore.DIR_NAME)

    private val runsFile = File(directory, RUNS_FILE)
    private val candidatesFile = File(directory, CANDIDATES_FILE)
    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    /** 本轮快照落盘。`outcome == null` 表示异常退出（此时只写一行失败摘要）。 */
    fun persist(recorder: CfRunRecorder, outcome: Outcome?, startedAtMs: Long) {
        runCatching {
            directory.mkdirs()

            if (recorder.captured) {
                writeCandidatesCsv(recorder)
                writeEntryJson(recorder)
            }

            appendRunSummary(recorder, outcome, startedAtMs)
        }
    }

    /** 把私有目录下的四个文件复制到应用专属外部目录，返回落点（供 UI 显示）。 */
    fun exportToExternalStorage(): List<File> {
        val target = appContext.getExternalFilesDir(CfMemoryStore.DIR_NAME) ?: return emptyList()

        target.mkdirs()

        val copied = mutableListOf<File>()

        exportableFiles().forEach { source ->
            if (!source.isFile) return@forEach

            runCatching {
                val destination = File(target, source.name)

                source.copyTo(destination, overwrite = true)
                copied += destination
            }
        }

        return copied
    }

    /**
     * 可导出文件集合 = 固定三份（候选明细 / 每轮摘要 / 记忆库）+ 最新的若干份入选结果。
     *
     * `entry_ip_<网络标签>.json` 是按网络命名的，用户每换一次网络就多一份；全量带出会让
     * 分享面板被几十个文件塞满，所以只带最近修改的 [MAX_EXPORTED_ENTRIES] 份。
     * ponytail: 只按时间取最新的几份 | 天花板: 需要跨网络对比历史入选结果时看不到旧的 | 升级触发: 用户反馈要对比多网络的历史结果
     */
    private fun exportableFiles(): List<File> {
        val entries = directory
            .listFiles { file -> file.isFile && file.name.startsWith(ENTRY_PREFIX) && file.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_EXPORTED_ENTRIES)
            .orEmpty()

        return listOf(candidatesFile, runsFile, File(directory, CfMemoryStore.FILE_NAME)) + entries
    }

    /** 外部导出目录（UI 提示用；可能尚未创建）。 */
    fun externalDirectory(): File? = appContext.getExternalFilesDir(CfMemoryStore.DIR_NAME)

    fun privateDirectory(): File = directory

    /** 已记录的轮数（UI 摘要用；只数行，不解析 JSON）。 */
    fun runCount(): Int =
        if (runsFile.isFile) {
            runCatching { runsFile.readLines().count { it.isNotBlank() } }.getOrDefault(0)
        } else {
            0
        }

    private fun writeCandidatesCsv(recorder: CfRunRecorder) {
        val selected = recorder.ranked.associateBy { it.address to it.port }

        val builder = StringBuilder()
        builder.append("IP地址,端口,地区,网络延迟,下载速度,数据中心,来源,状态,评分\n")

        recorder.candidates.forEach { candidate ->
            val metrics = recorder.metrics[candidate]
            val entry = selected[candidate.address to candidate.port]

            val region = metrics?.region ?: entry?.region ?: ""
            val ttfb = metrics?.ttfbMs ?: entry?.ttfbMs
            val mbps = when {
                entry != null && entry.downloadMbps > 0.0 -> entry.downloadMbps
                metrics != null && metrics.downloadMbps > 0.0 -> metrics.downloadMbps
                else -> 0.0
            }
            val status = when {
                entry != null -> "selected"
                metrics != null -> "reachable"
                else -> "unreachable"
            }

            builder
                .append(candidate.address).append(',')
                .append(candidate.port).append(',')
                .append(region).append(',')
                .append(ttfb?.let { "$it ms" } ?: "").append(',')
                .append(if (mbps > 0.0) "${"%.1f".format(Locale.US, mbps * 125)} kB/s" else "").append(',')
                .append("").append(',')
                .append(candidate.source).append(',')
                .append(status).append(',')
                .append(entry?.let { "%.1f".format(Locale.US, it.score) } ?: "")
                .append('\n')
        }

        writeAtomically(candidatesFile, builder.toString())
    }

    private fun writeEntryJson(recorder: CfRunRecorder) {
        val nodes = JSONArray()

        recorder.ranked.forEach { entry ->
            nodes.put(
                JSONObject().apply {
                    put("ip", entry.address)
                    put("port", entry.port)
                    put("cc", entry.region)
                    put("ttfb_ms", entry.ttfbMs)
                    put("mbps", entry.downloadMbps)
                    put("score", entry.score)
                },
            )
        }

        val tag = recorder.networkTag.ifBlank { "Unknown" }
        val payload = JSONObject().apply {
            put("generated_at", stamp.format(Date()))
            put("net_name", tag)
            put("count", nodes.length())
            put("nodes", nodes)
        }

        writeAtomically(File(directory, "entry_ip_$tag.json"), payload.toString(2))
    }

    private fun appendRunSummary(recorder: CfRunRecorder, outcome: Outcome?, startedAtMs: Long) {
        val entry = JSONObject().apply {
            put("ts", stamp.format(Date(startedAtMs)))
            put("duration_s", ((System.currentTimeMillis() - startedAtMs) / 1000.0))
            put("candidates", recorder.candidates.size)
            put("probed", recorder.metrics.size)
            put("ranked", recorder.ranked.size)
            put("uploaded", outcome?.uploaded ?: false)
            put("profile_updated", outcome?.profileUpdated ?: false)
            put("reason", outcome?.reason ?: recorder.failReason ?: "")
            put("memory_size", recorder.memorySize)
            put("memory_reused", recorder.memoryReused)
            put("memory_blocked", recorder.memoryBlocked)
        }

        val lines = if (runsFile.isFile) {
            runCatching { runsFile.readLines() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val kept = (lines + entry.toString()).takeLast(MAX_RUN_LINES)

        writeAtomically(runsFile, kept.joinToString("\n") + "\n")
    }

    /** 三种导出文件共用的原子写：先写临时文件再替换，避免进程被杀留下半个文件。 */
    private fun writeAtomically(target: File, content: String) {
        runCatching {
            target.parentFile?.mkdirs()

            val temporary = File(target.parentFile, "${target.name}.tmp")

            temporary.writeText(content)
            temporary.renameTo(target)
        }
    }

    /** 一轮的结局（协调器早退原因 / 上传与订阅刷新结果）。 */
    data class Outcome(
        val uploaded: Boolean,
        val profileUpdated: Boolean,
        val reason: String?,
    )

    companion object {
        const val RUNS_FILE = "runs.jsonl"
        const val CANDIDATES_FILE = "candidates.csv"

        /** 入选结果文件前缀（`entry_ip_<网络标签>.json`，与原脚本 `Output/entry_ip_*.json` 同名式）。 */
        const val ENTRY_PREFIX = "entry_ip_"

        /** 导出时最多带出几份入选结果（按修改时间取最新）。 */
        const val MAX_EXPORTED_ENTRIES = 3

        /** 历史摘要保留的轮数上限（每行约 200 字节，200 行 ≈ 40 KB）。 */
        const val MAX_RUN_LINES = 200
    }
}
