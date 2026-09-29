package com.github.kr328.clash.service.cfoptimizer.memory

import org.json.JSONArray
import org.json.JSONObject

/**
 * 记忆库 JSON 编解码。
 *
 * 键名与原版 `cf_memory.py` 写出的 `cf_memory.json` **完全一致**（snake_case），
 * 因此两个方向都通：App 导出的文件能被原版脚本直接读，原版脚本养出来的记忆库也能导入 App。
 * 这是「格式对齐」这条设计决策的落点，不要为了 Kotlin 风格改名。
 */
object CfMemoryCodec {
    fun encode(db: Map<String, MemoryRecord>): String {
        val root = JSONObject()

        db.forEach { (key, record) ->
            val stageScores = JSONObject()

            record.stageScores.forEach { (bucket, scores) ->
                stageScores.put(bucket, JSONArray(scores.toList()))
            }

            root.put(
                key,
                JSONObject().apply {
                    put("cc", record.cc)
                    put("first_seen", record.firstSeen)
                    put("runs", record.runs)
                    put("passes", record.passes)
                    put("fail_streak", record.failStreak)
                    put("last_seen", record.lastSeen)
                    put("last_score", record.lastScore)
                    put("last_ttfb_ms", record.lastTtfbMs)
                    put("last_mbps", record.lastMbps)
                    put("last_range_ok", record.lastRangeOk)
                    put("avg_score", record.avgScore)
                    put("tcp_hits", record.tcpHits)
                    put("stage_scores", stageScores)
                },
            )
        }

        return root.toString()
    }

    /** 解析失败的单条记录被跳过（坏一条不该毁掉整个记忆库）。 */
    fun decode(text: String): Map<String, MemoryRecord> {
        if (text.isBlank()) return emptyMap()

        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, MemoryRecord>()

        root.keys().forEach { key ->
            val obj = root.optJSONObject(key) ?: return@forEach

            val stageScores = LinkedHashMap<String, List<Double>>()

            obj.optJSONObject("stage_scores")?.let { raw ->
                raw.keys().forEach { bucket ->
                    val array = raw.optJSONArray(bucket) ?: return@forEach

                    stageScores[bucket] = (0 until array.length()).mapNotNull { index ->
                        val value = array.optDouble(index, Double.NaN)

                        if (value.isNaN()) null else value
                    }
                }
            }

            out[key] = MemoryRecord(
                cc = obj.optString("cc", ""),
                firstSeen = obj.optLong("first_seen", 0L),
                runs = obj.optInt("runs", 0),
                passes = obj.optInt("passes", 0),
                failStreak = obj.optInt("fail_streak", 0),
                lastSeen = obj.optLong("last_seen", 0L),
                lastScore = obj.optDouble("last_score", 0.0),
                lastTtfbMs = obj.optDouble("last_ttfb_ms", 0.0),
                lastMbps = obj.optDouble("last_mbps", 0.0),
                lastRangeOk = obj.optBoolean("last_range_ok", false),
                avgScore = obj.optDouble("avg_score", 0.0),
                stageScores = stageScores,
                tcpHits = obj.optInt("tcp_hits", 0),
            )
        }

        return out
    }
}
