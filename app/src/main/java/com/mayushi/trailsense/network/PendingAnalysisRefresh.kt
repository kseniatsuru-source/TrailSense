package com.mayushi.trailsense.network

import com.mayushi.trailsense.data.*
import com.mayushi.trailsense.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Rebuilds only already queued identities. Never inserts a job or starts analysis. */
class PendingAnalysisRefresh(private val dao: TrailDao, private val prefs: AppPreferences) {
    suspend fun activityPayload(activity: ActivityEntity): JSONObject = AnalysisPayloadBuilder.activity(
        activity.copy(title = activity.routeId?.let { dao.route(it)?.title } ?: activity.title),
        dao.companionNamesForActivity(activity.id), prefs.sleepNormHours, plan(activity),
        dao.paceSegments(activity.id), contexts(activity),
    ).put("pill_facts_and_deviations", pillFacts())
        .put("recorded_track_sha256", HuaweiPackage.sha(activity.trackEncoded))

    private suspend fun plan(activity: ActivityEntity?): PillPlanEntity? {
        val date = activity?.startedAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        return date?.let { PillPlanVersions.forDate(dao.pillPlanVersions(), dao.pillPlan(), it) }
    }
    private suspend fun contexts(activity: ActivityEntity?) = activity?.startedAt?.let {
        dao.calendarContextsForDay(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay())
    }.orEmpty()
    suspend fun pillFacts() = JSONArray().apply {
        dao.pillEvents().take(100).forEach { e -> put(JSONObject().put("date", LocalDate.ofEpochDay(e.epochDay).toString())
            .put("planned_date", e.plannedEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: JSONObject.NULL)
            .put("actual_taken", e.actualTaken ?: JSONObject.NULL).put("policy", e.deviationPolicy ?: "KEEP_PLAN").put("note", e.note)) }
    }

    suspend fun rebuild(job: AiJobEntity): AiJobEntity {
        val old = JSONObject(job.inputJson)
        var image = job.imagePath
        val payload = when (job.entityType) {
            "ACTIVITY" -> {
                val activity = dao.activity(job.entityId) ?: error("Хайк удалён")
                if (old.optString("attachment_kind") != "WORKOUT_SCREENSHOT") image = activity.sleepScreenshotPath
                activityPayload(activity)
            }
            "ROUTE" -> {
                val route = dao.route(job.entityId) ?: error("Маршрут удалён")
                val activity = dao.latestActivityForRoute(route.id)
                image = activity?.sleepScreenshotPath
                AnalysisPayloadBuilder.route(route, activity?.copy(title = route.title),
                    activity?.let { dao.companionNamesForActivity(it.id) }.orEmpty(), prefs.sleepNormHours, plan(activity),
                    activity?.let { dao.paceSegments(it.id) }.orEmpty(), contexts(activity))
                    .put("pill_facts_and_deviations", pillFacts())
            }
            ComparisonAnalysis.TYPE -> {
                val selected = old.getJSONArray("recordings")
                val ids = (0 until selected.length()).map { selected.getJSONObject(it).getJSONObject("input").getJSONObject("activity").getLong("id") }
                val ascent = old.getString("section") == "COMMON_RECORDED_ASCENT"
                image = null
                ComparisonAnalysis.payload(old.getString("title"), ascent, ids.mapIndexed { index, id ->
                    val saved = selected.getJSONObject(index)
                    val activity = dao.activity(id) ?: error("Хайк удалён")
                    require(activity.trackCorrectionMode=="NONE") { "Трек изменён. Выбери прохождения заново." }
                    require(saved.getJSONObject("input").optString("recorded_track_sha256") == HuaweiPackage.sha(activity.trackEncoded)) {
                        "Изменился трек или отсутствует снимок участка. Выбери прохождения заново."
                    }
                    val start = saved.getInt("start_index"); val end = saved.getInt("end_index")
                    ComparisonAnalysis.Recording(activityPayload(activity), start, end, saved.getLong("recorded_seconds"),
                        ComparisonAnalysis.sectionMetrics(activity, start, end))
                })
            }
            else -> error("Неизвестный тип задания")
        }
        if (image != null) payload.put("attachment_kind", if (old.optString("attachment_kind") == "WORKOUT_SCREENSHOT") "WORKOUT_SCREENSHOT" else "SLEEP_SCREENSHOT")
        val input = payload.put("analysis_kind", job.analysisKind).put("algorithm_version", AnalysisPrompt.VERSION).toString()
        val mime = image?.let { job.imageMimeType ?: "image/jpeg" }
        val attachment = image?.let(::File)
        require(attachment == null || attachment.isFile) { "Вложение задания недоступно" }
        return job.copy(inputJson = input, inputHash = AnalysisInputHash.calculate(input, attachment, mime).inputHash,
            imagePath = image, imageMimeType = mime, lastError = null, attempts = 0, updatedAt = System.currentTimeMillis())
    }

    suspend fun refresh() {
        dao.pendingAiJobs().forEach { job ->
            try {
                // Existing valid payloads remain byte-exact, including previously exported v1.
                AiJobFreshness.requireInputUnchanged(job)
                AiJobFreshness.requireCurrent(dao, prefs, job)
            } catch (_: IllegalArgumentException) {
                try { dao.refreshPendingAiJob(job, rebuild(job)) }
                catch (error: Exception) { dao.notePendingRefreshFailure(job.id, job.inputRevision, (error.message ?: "Не удалось обновить данные").take(500)) }
            }
        }
    }
}
