package com.mayushi.trailsense.network

import com.mayushi.trailsense.data.*

/** Revisions are local monotonic input versions, never replacements for content hashes. */
object AnalysisResultFreshness {
    fun key(job: AiJobEntity): String = "${job.entityType}:${job.entityId}:${job.analysisKind}:${job.scopeKey}"

    fun isNewer(job: AiJobEntity, createdAtMs: Long, existing: AnalysisResultEntity?): Boolean {
        require(createdAtMs >= 0) { "Некорректное время создания анализа" }
        if (existing == null) return true
        return job.inputRevision > existing.inputRevision ||
            (job.inputRevision == existing.inputRevision && job.inputHash == existing.inputHash &&
                existing.analysisCreatedAtMs > 0 && createdAtMs > existing.analysisCreatedAtMs)
    }

    suspend fun store(dao: TrailDao, job: AiJobEntity, createdAtMs: Long, responseJson: String, prefs: AppPreferences? = null) {
        val local = org.json.JSONObject(responseJson)
        if (prefs != null && job.entityType != ComparisonAnalysis.TYPE)
            local.put(AppliedAnalysisSource.FIELD, AppliedAnalysisSource.fingerprint(dao, prefs, job))
        if (job.entityType == ComparisonAnalysis.TYPE) {
            val input = org.json.JSONObject(job.inputJson)
            val metadata = org.json.JSONObject()
            listOf("comparison_scope", "title", "recording_count", "period_start", "period_end", "section").forEach { metadata.put(it, input.get(it)) }
            metadata.put("recordings", org.json.JSONArray().apply {
                val rows = input.getJSONArray("recordings")
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i); val activity = row.getJSONObject("input").getJSONObject("activity")
                    put(org.json.JSONObject().put("id", activity.getLong("id")).put("date", activity.opt("date"))
                        .put("start_index", row.getInt("start_index")).put("end_index", row.getInt("end_index")))
                }
            })
            local.put("_trailsense_comparison_scope", metadata)
        }
        val stored = local.toString()
        dao.putAnalysisResult(AnalysisResultEntity(key(job), job.entityType, job.entityId, job.analysisKind,
            job.scopeKey, job.id, job.inputHash, job.inputRevision, createdAtMs, stored))
    }
}
