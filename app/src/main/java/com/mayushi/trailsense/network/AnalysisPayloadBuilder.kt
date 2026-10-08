package com.mayushi.trailsense.network

import com.mayushi.trailsense.data.ActivityEntity
import com.mayushi.trailsense.data.CalendarContextEntity
import com.mayushi.trailsense.data.PaceSegmentEntity
import com.mayushi.trailsense.data.PillPlanEntity
import com.mayushi.trailsense.data.RouteEntity
import com.mayushi.trailsense.domain.PillPhase
import com.mayushi.trailsense.domain.PillPlan
import com.mayushi.trailsense.domain.PillSchedule
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

object AnalysisPayloadBuilder {
    fun route(
        route: RouteEntity,
        linkedActivity: ActivityEntity?,
        companionNames: List<String>,
        sleepNormHours: Double,
        pillPlan: PillPlanEntity?,
        paceSegments: List<PaceSegmentEntity> = emptyList(),
        calendarContext: List<CalendarContextEntity> = emptyList(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): JSONObject = JSONObject()
        .put("entity_updated_at", route.updatedAt)
        .put("analysis_context_snapshot", AnalysisContextSnapshot.create(sleepNormHours, pillPlan, calendarContext.maxOfOrNull { it.updatedAt }))
        .put("route", routeJson(route))
        .put("linked_activity", linkedActivity?.let { activityJson(it, companionNames, paceSegments, sleepNormHours, zoneId) } ?: JSONObject.NULL)
        .put("calendar_context", calendarContextJson(calendarContext))
        .put("medication_schedule_context", medicationContext(linkedActivity, pillPlan, zoneId) ?: JSONObject.NULL)

    fun activity(
        activity: ActivityEntity,
        companionNames: List<String>,
        sleepNormHours: Double,
        pillPlan: PillPlanEntity?,
        paceSegments: List<PaceSegmentEntity> = emptyList(),
        calendarContext: List<CalendarContextEntity> = emptyList(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): JSONObject = JSONObject()
        .put("entity_updated_at", activity.updatedAt)
        .put("analysis_context_snapshot", AnalysisContextSnapshot.create(sleepNormHours, pillPlan, calendarContext.maxOfOrNull { it.updatedAt }))
        .put("activity", activityJson(activity, companionNames, paceSegments, sleepNormHours, zoneId))
        .put("calendar_context", calendarContextJson(calendarContext))
        .put("medication_schedule_context", medicationContext(activity, pillPlan, zoneId) ?: JSONObject.NULL)

    private fun routeJson(route: RouteEntity): JSONObject = JSONObject()
        .put("title", route.title)
        .putNullable("source_url", route.sourceUrl)
        .put("metric_provenance", "UNCONFIRMED_REFERENCE_NOT_ACTIVITY_FACT")
        .putNullable("distance_m", route.distanceM)
        .putNullable("gain_m", route.elevationGainM)
        .putNullable("loss_m", route.elevationLossM)
        .putNullable("source_difficulty", route.sourceDifficulty)
        .putNullable("source_rating", route.sourceRating)
        .putNullable("overall_rating", route.overallDifficulty)
        .putNullable("climb_rating", route.climbDifficulty)
        .putNullable("technicality", route.technicality)
        .putNullable("exposure", route.exposure)
        .putNullable("beauty", route.beauty)
        .put("notes", route.notes)
        .put("review_excerpts_user_supplied", route.reviewExcerpts)

    private fun activityJson(
        activity: ActivityEntity,
        companionNames: List<String>,
        paceSegments: List<PaceSegmentEntity>,
        sleepNormHours: Double,
        zoneId: ZoneId,
    ): JSONObject {
        val screenshotPlaceholder = activity.sourceFormat == "HUAWEI_SCREENSHOT"
        val correction = if (activity.trackCorrectionMode == "NONE") null else JSONObject()
            .put("mode", activity.trackCorrectionMode)
            .put("note", activity.trackCorrectionNote)
            .put("metrics_recalculated_from_corrected_track", true)
        return JSONObject()
            .put("id", activity.id)
            .put("updated_at", activity.updatedAt)
            .putNullable("route_id", activity.routeId)
            .put("title", activity.title)
            .put("source_format", activity.sourceFormat)
            .put("source_file_name", activity.sourceFileName)
            .putNullable("started_at_ms", activity.startedAt)
            .putNullable("date", activity.startedAt?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate().toString() })
            .putNullable("distance_m", activity.distanceM.takeUnless { screenshotPlaceholder && it <= 0.0 })
            .putNullable("gain_m", activity.elevationGainM.takeUnless { screenshotPlaceholder && it <= 0.0 })
            .putNullable("loss_m", activity.elevationLossM.takeUnless { screenshotPlaceholder && it <= 0.0 })
            .putNullable("elapsed_s", activity.elapsedSeconds)
            .putNullable("moving_s", activity.movingSeconds)
            .putNullable("stopped_s", activity.stoppedSeconds)
            .putNullable("pace_min_km", activity.averagePaceMinutesPerKm)
            .putNullable("avg_hr", activity.averageHeartRateBpm)
            .putNullable("max_hr", activity.maxHeartRateBpm)
            .putNullable("effort", activity.overallEffort)
            .putNullable("climb_effort", activity.climbEffort)
            .putNullable("descent_effort", activity.descentEffort)
            .putNullable("technicality", activity.technicality)
            .putNullable("enjoyment", activity.enjoyment)
            .putNullable("sleep_hours", activity.sleepHours)
            .put("sleep_norm_hours", sleepNormHours)
            .putNullable("sleep_quality", activity.sleepQuality)
            .putNullable("felt_rested", activity.feltRested)
            .put("sleep_screenshot_attached", !activity.sleepScreenshotPath.isNullOrBlank())
            .put("anomaly", activity.anomaly)
            .put("pace_setter", activity.paceSetter)
            .put("pace_segments", JSONArray().apply {
                paceSegments.forEach { segment ->
                    put(JSONObject()
                        .put("start_percent", (segment.startFraction * 100).toInt())
                        .put("end_percent", (segment.endFraction * 100).toInt())
                        .put("pace_setter", segment.paceSetterName)
                        .put("note", segment.note))
                }
            })
            .put("representative_speed", activity.representativeSpeed)
            .put("pace_constraint", activity.paceConstraint)
            .put("had_waiting_stops", activity.hadWaitingStops)
            .put("companions", JSONArray().apply { companionNames.forEach(::put) })
            .put("track_correction", correction ?: JSONObject.NULL)
            .put("custom_ratings", activity.customRatings)
            .put("points_of_interest", activity.pointsOfInterest)
            .putNullable("imported_started_at_ms", activity.importedStartedAt)
            .putNullable("manual_date_epoch_day", activity.manualDateEpochDay)
            .put("date_provenance", if (activity.manualDateEpochDay == null) "IMPORTED_UNCONFIRMED" else "USER_OVERRIDE")
            .put("original_watch_metrics", activity.originalMetricsJson?.let(::JSONObject) ?: JSONObject.NULL)
            .put("comment", activity.comment)
    }

    private fun calendarContextJson(context: List<CalendarContextEntity>) = JSONArray().apply {
        context.forEach { item ->
            put(JSONObject()
                .put("type", item.type)
                .put("start_date", java.time.LocalDate.ofEpochDay(item.startEpochDay).toString())
                .put("end_date", java.time.LocalDate.ofEpochDay(item.endEpochDay).toString())
                .putNullable("severity", item.severity)
                .put("note", item.note))
        }
    }

    private fun medicationContext(
        activity: ActivityEntity?,
        planEntity: PillPlanEntity?,
        zoneId: ZoneId,
    ): JSONObject? {
        val startedAt = activity?.startedAt ?: return null
        val plan = planEntity ?: return null
        val date = Instant.ofEpochMilli(startedAt).atZone(zoneId).toLocalDate()
        val day = PillSchedule.day(PillPlan(plan.anchorEpochDay, plan.activeDays, plan.breakDays), date)
        return JSONObject()
            .put("date", date.toString())
            .put("schedule_stage", if (day.phase == PillPhase.ACTIVE) "ACTIVE_TABLETS" else "HORMONE_FREE_INTERVAL")
            .put("day_in_stage", day.dayInPhase)
            .put("day_in_block", day.dayInBlock)
            .put("planned_schedule_only", true)
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject = put(name, value ?: JSONObject.NULL)
}

object AnalysisContextSnapshot {
    fun create(sleepNormHours: Double, pillPlan: PillPlanEntity?, calendarContextRevision: Long? = null): JSONObject = JSONObject()
        .put("sleep_norm_bits", java.lang.Double.doubleToRawLongBits(sleepNormHours))
        .put("pill_plan_updated_at", pillPlan?.updatedAt ?: JSONObject.NULL)
        .put("calendar_context_revision", calendarContextRevision ?: JSONObject.NULL)

    fun requireCurrent(
        input: JSONObject,
        sleepNormHours: Double,
        pillPlan: PillPlanEntity?,
        calendarContextRevision: Long? = null,
    ) {
        val snapshot = input.optJSONObject("analysis_context_snapshot")
            ?: throw IllegalArgumentException("Задание создано без снимка личного контекста — создай анализ заново")
        val expectedSleepBits = exactLong(snapshot, "sleep_norm_bits")
            ?: throw IllegalArgumentException("Повреждён снимок личного контекста")
        require(expectedSleepBits == java.lang.Double.doubleToRawLongBits(sleepNormHours)) {
            "Личная норма сна изменилась — создай анализ заново"
        }
        val expectedPillPlanUpdatedAt = nullableLong(snapshot, "pill_plan_updated_at")
        require(expectedPillPlanUpdatedAt == pillPlan?.updatedAt) {
            "Контекст назначенной схемы изменился — создай анализ заново"
        }
        val expectedCalendarRevision = nullableLong(snapshot, "calendar_context_revision")
        require(expectedCalendarRevision == calendarContextRevision) {
            "Календарный контекст похода изменился — создай анализ заново"
        }
    }

    private fun nullableLong(json: JSONObject, name: String): Long? {
        if (!json.has(name) || json.isNull(name)) return null
        return exactLong(json, name) ?: throw IllegalArgumentException("Повреждён снимок личного контекста")
    }

    private fun exactLong(json: JSONObject, name: String): Long? {
        val value = json.opt(name) as? Number ?: return null
        return try { java.math.BigDecimal(value.toString()).longValueExact() } catch (_: Exception) { null }
    }
}
