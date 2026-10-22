package com.mayushi.trailsense.network

import com.mayushi.trailsense.data.*
import org.json.JSONObject

/** Local-only provenance in the stored response. Never modifies exported input or hashes.
 * A DONE job can accept newer analysis only while the complete post-merge source is exact.
 * Every user edit (including A→B→A, via updatedAt) breaks this proof.
 */
object AppliedAnalysisSource {
    const val FIELD = "_trailsense_applied_source_sha256"
    suspend fun fingerprint(dao: TrailDao, prefs: AppPreferences, job: AiJobEntity): String {
        val refresh = PendingAnalysisRefresh(dao, prefs)
        val source = when (job.entityType) {
            "ACTIVITY" -> {
                val a = dao.activity(job.entityId) ?: error("Хайк удалён")
                a.toString() + refresh.activityPayload(a).toString()
            }
            "ROUTE" -> {
                val r = dao.route(job.entityId) ?: error("Маршрут удалён")
                val a = dao.latestActivityForRoute(r.id)
                r.toString() + a.toString() + (a?.let { refresh.activityPayload(it).toString() } ?: "") + prefs.sleepNormHours + dao.pillPlan().toString() + dao.pillPlanVersions().toString() + refresh.pillFacts().toString()
            }
            else -> return ""
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    suspend fun matches(dao: TrailDao, prefs: AppPreferences, job: AiJobEntity): Boolean {
        if (job.status != "DONE") return false
        val result = dao.analysisResult(AnalysisResultFreshness.key(job)) ?: return false
        if (result.jobId != job.id || result.inputRevision != job.inputRevision || result.inputHash != job.inputHash) return false
        val expected = JSONObject(result.responseJson).optString(FIELD)
        return expected.isNotBlank() && expected == fingerprint(dao, prefs, job)
    }
}
