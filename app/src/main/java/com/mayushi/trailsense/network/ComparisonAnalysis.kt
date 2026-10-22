package com.mayushi.trailsense.network

import org.json.JSONArray
import org.json.JSONObject

/** One job for the exact displayed set. Order and selected calendar bounds are not identity. */
object ComparisonAnalysis {
    const val TYPE = "COMPARISON"
    const val KIND = "ROUTE_DYNAMICS"
    data class Recording(val payload: JSONObject, val startIndex: Int, val endIndex: Int, val seconds: Long,
        val sectionMetrics: JSONObject = JSONObject())

    fun sectionMetrics(activity: com.mayushi.trailsense.data.ActivityEntity, start: Int, end: Int): JSONObject {
        val points = com.mayushi.trailsense.domain.TrackCodec.decode(activity.trackEncoded)
        require(start >= 0 && end > start && end < points.size)
        val section = points.subList(start, end + 1)
        require(section.none { it.inferred })
        val hr = section.mapNotNull { it.heartRateBpm?.takeIf { v -> v > 0 } }
        val cadence = section.mapNotNull { it.cadencePerMinute?.takeIf { v -> v > 0 } }
        return JSONObject().put("metric_scope", "SELECTED_RECORDED_SECTION")
            .put("average_hr_bpm", hr.takeIf { it.isNotEmpty() }?.average() ?: JSONObject.NULL)
            .put("max_hr_bpm", hr.maxOrNull() ?: JSONObject.NULL)
            .put("average_cadence_per_minute", cadence.takeIf { it.isNotEmpty() }?.average() ?: JSONObject.NULL)
            .put("whole_activity_metrics_scope", "WHOLE_WATCH_RECORDING_NOT_SECTION")
    }

    fun scopeKey(ids: List<Long>, ascent: Boolean): String {
        require(ids.size >= 2 && ids.all { it > 0 } && ids.distinct().size == ids.size) { "Нужно не менее двух разных прохождений" }
        return "v1:${if (ascent) "ASCENT" else "WHOLE"}:${ids.sorted().joinToString(",")}"
    }

    fun payload(title: String, ascent: Boolean, recordings: List<Recording>): JSONObject {
        val sorted = recordings.sortedBy { it.payload.getJSONObject("activity").getLong("id") }
        val ids = sorted.map { it.payload.getJSONObject("activity").getLong("id") }
        val scope = scopeKey(ids, ascent)
        require(sorted.all { it.startIndex >= 0 && it.endIndex > it.startIndex && it.seconds > 0 })
        val dates = sorted.mapNotNull { it.payload.getJSONObject("activity").optString("date").takeIf { d -> d.isNotBlank() && d != "null" } }
        return JSONObject().put("comparison_scope", scope).put("title", title).put("recording_count", ids.size)
            .put("period_start", dates.minOrNull() ?: JSONObject.NULL).put("period_end", dates.maxOrNull() ?: JSONObject.NULL)
            .put("section", if (ascent) "COMMON_RECORDED_ASCENT" else "COMMON_RECORDED_ROUTE")
            .put("recordings", JSONArray().apply {
                sorted.forEach { recording ->
                    put(JSONObject().put("input", JSONObject(recording.payload.toString()).put("algorithm_version", AnalysisPrompt.VERSION).apply {
                            // This batch carries normalized facts, not individual screenshot attachments.
                            getJSONObject("activity").put("sleep_screenshot_attached", false)
                        })
                        .put("start_index", recording.startIndex).put("end_index", recording.endIndex).put("recorded_seconds", recording.seconds)
                        .put("section_metrics", recording.sectionMetrics))
                }
            })
    }

    suspend fun requireCurrent(dao: com.mayushi.trailsense.data.TrailDao, prefs: AppPreferences, job: com.mayushi.trailsense.data.AiJobEntity, input: JSONObject) {
        val rows = input.getJSONArray("recordings")
        val ids = (0 until rows.length()).map { rows.getJSONObject(it).getJSONObject("input").getJSONObject("activity").getLong("id") }
        val scope = scopeKey(ids, input.getString("section") == "COMMON_RECORDED_ASCENT")
        require(job.scopeKey == scope && input.getString("comparison_scope") == scope) { "Изменился набор прохождений" }
        for (index in 0 until rows.length()) {
            val snapshot = rows.getJSONObject(index).getJSONObject("input")
            AiJobFreshness.requireCurrent(dao, prefs, job.copy(entityType = "ACTIVITY", entityId = ids[index], inputJson = snapshot.toString()))
        }
    }
}
