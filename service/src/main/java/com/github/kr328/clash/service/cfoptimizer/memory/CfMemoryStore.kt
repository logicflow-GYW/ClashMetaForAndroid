package com.github.kr328.clash.service.cfoptimizer.memory

import android.content.Context
import java.io.File
import java.util.TimeZone

/**
 * 记忆库持久化（跨轮、跨进程重启）。
 *
 * 存的文件是 `files/cfoptimizer/memory.json`，格式与原版 `cf_memory.json` 一致 ——
 * 导出即复制，不需要转码。
 *
 * 并发模型：只在 service 进程使用，一把锁 + 内存缓存；写盘用「临时文件 + rename」保证
 * 原子性，进程被杀不会留下半个 JSON（记忆库损坏 = 白攒）。
 */
class CfMemoryStore(context: Context) {
    private val directory = File(context.filesDir, DIR_NAME)
    private val file = File(directory, FILE_NAME)
    private val lock = Any()

    private var cache: MutableMap<String, MemoryRecord>? = null

    /** 只读快照（导出/展示用）。 */
    fun snapshot(): Map<String, MemoryRecord> = synchronized(lock) { loadLocked().toMap() }

    fun size(): Int = synchronized(lock) { loadLocked().size }

    fun isEmpty(): Boolean = size() == 0

    /**
     * 记录一次探测结果。key 形如 `ip:port`（与原版一致）。
     */
    fun record(
        key: String,
        cc: String,
        passed: Boolean,
        score: Double = 0.0,
        ttfbMs: Double = 0.0,
        mbps: Double = 0.0,
        rangeOk: Boolean = false,
        stage: String = CfMemoryScoring.STAGE_FULL,
        nowSeconds: Long = nowSeconds(),
        zone: TimeZone = TimeZone.getDefault(),
    ) {
        synchronized(lock) {
            val db = loadLocked()

            db[key] = CfMemoryScoring.recordResult(
                record = db[key],
                cc = cc,
                nowSeconds = nowSeconds,
                passed = passed,
                score = score,
                ttfbMs = ttfbMs,
                mbps = mbps,
                rangeOk = rangeOk,
                stage = stage,
                zone = zone,
            )
        }
    }

    /** 优先复测候选（置信度降序）。 */
    fun priorities(
        topN: Int,
        nowSeconds: Long = nowSeconds(),
        zone: TimeZone = TimeZone.getDefault(),
    ): List<PriorityCandidate> = synchronized(lock) {
        CfMemoryScoring.priorityCandidates(loadLocked(), nowSeconds, topN, zone)
    }

    /**
     * 已被判定为"不该再浪费探测配额"的地址集合（连续失败 ≥ [CfMemoryScoring.MAX_FAIL_STREAK]）。
     *
     * 与原版的**已声明偏差**：原版对 fail_streak 达标的记录做的是"永久移出优先候选"，
     * 而我们从上游源拿到的是一个远大于记忆库的随机 IP 池 —— 永久屏蔽会把配额浪费在
     * 已经证伪过的地址上。这里加冷却期：超过 [CfMemoryScoring.STALE_DAYS] / 2 天没再失败的
     * 记录放行复测一次（网络变好、路由变化都可能让它复活）。
     */
    fun blockedAddresses(nowSeconds: Long = nowSeconds()): Set<String> = synchronized(lock) {
        val cooldownSeconds = (CfMemoryScoring.STALE_DAYS / 2 * 86_400).toLong()

        loadLocked()
            .filter { (_, record) ->
                record.failStreak >= CfMemoryScoring.MAX_FAIL_STREAK &&
                    (nowSeconds - record.lastSeen) < cooldownSeconds
            }
            .keys
            .mapNotNull { it.substringBeforeLast(':', "").ifEmpty { null } }
            .toSet()
    }

    /** 轮末落盘：先修剪、再淘汰超限，然后原子写。 */
    fun flush(
        nowSeconds: Long = nowSeconds(),
    ) {
        synchronized(lock) {
            val db = loadLocked()

            CfMemoryScoring.pruneStale(db, nowSeconds)
            CfMemoryScoring.evictOverflow(db, nowSeconds)

            writeLocked(db)
        }
    }

    /** 把记忆库原样复制到目标文件（导出用）。 */
    fun exportTo(destination: File): Boolean = synchronized(lock) {
        runCatching {
            destination.parentFile?.mkdirs()

            val text = CfMemoryCodec.encode(loadLocked())

            destination.writeText(text)

            true
        }.getOrDefault(false)
    }

    /** 导入（从外部带回来的记忆库合并进来，同 key 以更新的 last_seen 为准）。 */
    fun mergeFrom(file: File): Int = synchronized(lock) {
        val imported = runCatching { CfMemoryCodec.decode(file.readText()) }.getOrDefault(emptyMap())

        if (imported.isEmpty()) return@synchronized 0

        val db = loadLocked()
        var merged = 0

        imported.forEach { (key, record) ->
            val existing = db[key]

            if (existing == null || record.lastSeen > existing.lastSeen) {
                db[key] = record
                merged++
            }
        }

        if (merged > 0) {
            CfMemoryScoring.evictOverflow(db, nowSeconds())
            writeLocked(db)
        }

        merged
    }

    fun clear(): Unit = synchronized(lock) {
        cache = mutableMapOf()
        runCatching { file.delete() }
    }

    private fun loadLocked(): MutableMap<String, MemoryRecord> {
        cache?.let { return it }

        val loaded = if (file.isFile) {
            runCatching { CfMemoryCodec.decode(file.readText()).toMutableMap() }
                .getOrElse { mutableMapOf() }
        } else {
            mutableMapOf()
        }

        cache = loaded

        return loaded
    }

    private fun writeLocked(db: MutableMap<String, MemoryRecord>) {
        runCatching {
            directory.mkdirs()

            val temporary = File(directory, "$FILE_NAME.tmp")

            temporary.writeText(CfMemoryCodec.encode(db))
            temporary.renameTo(file)
        }
    }

    companion object {
        const val DIR_NAME = "cfoptimizer"
        const val FILE_NAME = "memory.json"

        fun key(address: String, port: Int): String = "$address:$port"

        fun nowSeconds(): Long = System.currentTimeMillis() / 1000
    }
}
