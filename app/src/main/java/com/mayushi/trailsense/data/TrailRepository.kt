package com.mayushi.trailsense.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.mayushi.trailsense.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

class TrailRepository(private val dao: TrailDao, private val resolver: ContentResolver, private val filesDir: File,
    private val refreshPending: suspend () -> Unit = {}) {
    private val writeMutex = Mutex()
    val routes = dao.observeRoutes().flowOn(Dispatchers.IO)
    val routeIndex = dao.observeStoredRoutes()
    val recordingIndex = dao.observeStoredActivities()
    suspend fun loadRecordedTrack(reference:String):String = withContext(Dispatchers.IO) { requireNotNull(dao.loadTrack(reference)) }
    val activities = combine(dao.observeActivities(), dao.observeRoutes()) { activities, routes -> activities.map { it.copy(title=DisplayTitle.resolve(it,routes)) } }.flowOn(Dispatchers.IO)
    val companions = dao.observeCompanions()
    suspend fun refreshExistingPending() = withContext(Dispatchers.IO) { refreshPending() }
    val pillPlan = dao.observePillPlan()
    val pillPlanVersions = dao.observePillPlanVersions()
    val pillEvents = dao.observePillEvents()
    val calendarContexts = dao.observeCalendarContexts()
    val pendingAiCount = dao.observePendingAiCount()

    fun route(id: Long) = dao.observeRoute(id)
    fun activity(id: Long) = combine(dao.observeActivity(id),dao.observeRoutes()) { a,routes -> a?.copy(title=DisplayTitle.resolve(a,routes)) }
    fun activitiesForRoute(routeId: Long) = combine(dao.observeActivitiesForRoute(routeId),dao.observeRoutes()) { activities,routes -> activities.map {it.copy(title=DisplayTitle.resolve(it,routes))} }
    fun companionIds(activityId: Long) = dao.observeCompanionIds(activityId)
    fun paceSegments(activityId: Long) = dao.observePaceSegments(activityId)
    fun latestAi(type: String, id: Long, kind: String) = dao.observeLatestAi(type, id, kind)

    suspend fun importSharedRoute(text: String): Long = withContext(Dispatchers.IO) {
        createRouteEvent("", text)
    }

    suspend fun createRouteEvent(title: String, sharedText: String): Long = serializedWrite {
        val shared = if (sharedText.isBlank()) null else (AllTrailsImport.parseSharedText(sharedText)
            ?: throw IllegalArgumentException("Вставь корректную ссылку AllTrails"))
        dao.insertRoute(RouteEntity(
            title = title.trim().ifBlank { shared?.suggestedTitle ?: "Поход с часов" },
            sourceUrl = shared?.originalUrl,
            canonicalUrl = null,
            sourceLabel = (if (shared == null) "Часы" else "AllTrails") + if(title.isNotBlank()) "; CUSTOM_TITLE" else "",
        ))
    }

    suspend fun importRouteFile(uri: Uri): Long = withContext(Dispatchers.IO) {
        val fileName = displayName(uri)
        val parsed = resolver.openInputStream(uri)?.use { TrackParser.parse(it, fileName) } ?: error("Не удалось открыть файл")
        val metrics = TrackAnalyzer.analyze(parsed.points, parsed.summary)
        dao.insertRoute(RouteEntity(
            title = parsed.name ?: fileName.substringBeforeLast('.').ifBlank { "Импортированный маршрут" },
            sourceLabel = "${parsed.sourceFormat} файл", trackEncoded = TrackCodec.encode(parsed.points),
            distanceM = metrics.distanceM, elevationGainM = metrics.elevationGainM, elevationLossM = metrics.elevationLossM,
        ))
    }

    suspend fun importActivityFile(uri: Uri, routeId: Long? = null, replaceForRoute: Boolean = false): Long = serializedWrite {
        val fileName = displayName(uri)
        val parsed = resolver.openInputStream(uri)?.use { TrackParser.parse(it, fileName) } ?: error("Не удалось открыть файл")
        val metrics = TrackAnalyzer.analyze(parsed.points, parsed.summary)
        val encodedTrack = TrackCodec.encode(parsed.points)
        val selectedRoute = if (routeId != null) dao.route(routeId) else null
        val best = if (routeId == null) {
            daoRouteSnapshot()
                .mapNotNull { route -> route.trackEncoded?.let { encoded -> route to RouteMatcher.match(parsed.points, TrackCodec.decode(encoded)) } }
                .maxByOrNull { it.second.confidence }
        } else null
        val now = System.currentTimeMillis()
        val existing = if (routeId != null && replaceForRoute) dao.latestActivityForRoute(routeId) else null
        val activity = if (routeId != null && selectedRoute != null) {
            WatchActivityUpdater.merge(
                existing = existing,
                routeId = routeId,
                routeTitle = selectedRoute.title,
                fileName = fileName,
                format = parsed.sourceFormat,
                encodedTrack = encodedTrack,
                startedAt = HikeDateResolver.fromParsedTrack(parsed),
                metrics = metrics,
                now = now,
            )
        } else {
            ActivityEntity(
                routeId = routeId,
                suggestedRouteId = best?.takeIf { it.second.shouldSuggest }?.first?.id,
                title = parsed.name ?: fileName.substringBeforeLast('.').ifBlank { "Поход" },
                sourceFileName = fileName,
                sourceFormat = parsed.sourceFormat.name,
                trackEncoded = encodedTrack,
                originalTrackEncoded = encodedTrack,
                importedStartedAt = HikeDateResolver.fromParsedTrack(parsed),
                originalMetricsJson = OriginalWatchMetrics.encode(metrics),
                startedAt = HikeDateResolver.fromParsedTrack(parsed),
                distanceM = metrics.distanceM,
                elevationGainM = metrics.elevationGainM,
                elevationLossM = metrics.elevationLossM,
                elapsedSeconds = metrics.elapsedSeconds,
                movingSeconds = metrics.movingSeconds,
                stoppedSeconds = metrics.stoppedSeconds,
                averagePaceMinutesPerKm = metrics.averagePaceMinutesPerKm,
                averageHeartRateBpm = metrics.averageHeartRateBpm,
                maxHeartRateBpm = metrics.maxHeartRateBpm,
                matchConfidence = best?.second?.confidence,
                // Suggestions belong to metadata, never prefill the user's notes.
                comment = "",
                createdAt = now,
                updatedAt = now,
            )
        }
        val activityId = if (existing != null) {
            dao.replaceWatchActivity(existing, activity)
            existing.id
        } else {
            dao.insertActivity(activity)
        }
        dao.invalidateAiForActivity(activityId)
        if (selectedRoute != null) {
            dao.updateRouteAndInvalidate(selectedRoute.copy(
                trackEncoded = encodedTrack,
                distanceM = selectedRoute.distanceM ?: metrics.distanceM,
                elevationGainM = selectedRoute.elevationGainM ?: metrics.elevationGainM,
                elevationLossM = selectedRoute.elevationLossM ?: metrics.elevationLossM,
                sourceLabel = "AllTrails + ${parsed.sourceFormat.name} с часов" + if(selectedRoute.sourceLabel.contains("CUSTOM_TITLE") || selectedRoute.sourceLabel == "Вручную") "; CUSTOM_TITLE" else "",
                updatedAt = System.currentTimeMillis(),
            ))
        }
        activityId
    }

    suspend fun importHuaweiScreenshot(uri: Uri): Pair<Long, String> = withContext(Dispatchers.IO) {
        val bitmap = resolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            ?: error("Не удалось прочитать скриншот")
        val scale = (1800.0 / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1.0)
        val normalized = if (scale < 1.0) Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        ) else bitmap
        val dir = File(filesDir, "huawei_screenshots").apply { mkdirs() }
        val file = File(dir, "workout-${System.currentTimeMillis()}.jpg")
        file.outputStream().use { output -> normalized.compress(Bitmap.CompressFormat.JPEG, 88, output) }
        if (normalized !== bitmap) normalized.recycle()
        bitmap.recycle()
        val id = dao.insertActivity(ActivityEntity(
            title = "Тренировка Huawei",
            sourceFileName = displayName(uri),
            sourceFormat = "HUAWEI_SCREENSHOT",
            trackEncoded = TrackCodec.encode(emptyList()),
            screenshotPath = file.absolutePath,
            distanceM = 0.0,
            elevationGainM = 0.0,
            elevationLossM = 0.0,
            comment = "",
        ))
        id to file.absolutePath
    }

    private suspend fun daoRouteSnapshot(): List<RouteEntity> {
        return dao.observeRoutes().first()
    }

    suspend fun saveRoute(form: RouteEntity) = serializedWrite {
        val current = dao.route(form.id) ?: error("Маршрут не найден")
        dao.updateRouteAndInvalidate(current.copy(
            title = form.title,
            sourceUrl = form.sourceUrl,
            sourceLabel = form.sourceLabel,
            overallDifficulty = form.overallDifficulty,
            climbDifficulty = form.climbDifficulty,
            technicality = form.technicality,
            exposure = form.exposure,
            beauty = form.beauty,
            notes = form.notes,
            reviewExcerpts = form.reviewExcerpts,
            updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1),
        ))
    }
    suspend fun deleteRouteEvent(routeId: Long) = serializedWrite {
        val images = dao.activitiesForRoute(routeId).flatMap { listOfNotNull(it.screenshotPath, it.sleepScreenshotPath) }
        dao.deleteRouteEvent(routeId)
        images.forEach { runCatching { File(it).delete() } }
    }
    suspend fun saveActivity(activity: ActivityEntity, companionIds: Set<Long> = emptySet()) =
        saveQuestionnaire(activity, companionIds)

    suspend fun saveRouteQuestionnaire(form: RouteEntity) = serializedWrite {
        val current = dao.route(form.id) ?: error("Маршрут не найден")
        dao.updateRouteAndInvalidate(current.copy(
            overallDifficulty = form.overallDifficulty,
            climbDifficulty = form.climbDifficulty,
            technicality = form.technicality,
            exposure = form.exposure,
            beauty = form.beauty,
            notes = form.notes,
            updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1),
        ))
    }

    suspend fun saveQuestionnaire(form: ActivityEntity, companionIds: Set<Long>, routeForm: RouteEntity? = null) = serializedWrite {
        val current = dao.activity(form.id) ?: error("Поход не найден")
        // An unseen OCR completion or track edit must not be overwritten by a stale form.
        require(routeForm == null || current.updatedAt == form.updatedAt) { "Запись обновилась во время заполнения. Открой анкету заново; исходные данные сохранены." }
        HikeAnnotations.validate(form.customRatings, form.pointsOfInterest)
        val merged = current.copy(
            questionnaireHistory = if(form.questionnaireCompletedAt!=null) DescentEvidence.savedHistory(form.questionnaireHistory) else form.questionnaireHistory,
            heavyBackpack = form.heavyBackpack,
            customRatings = form.customRatings,
            pointsOfInterest = form.pointsOfInterest,
            overallEffort = form.overallEffort,
            climbEffort = form.climbEffort,
            descentEffort = form.descentEffort,
            technicality = form.technicality,
            enjoyment = form.enjoyment,
            // Preserve OCR arriving while an untouched form is saved.
            sleepHours = if (form.sleepHours == null && current.sleepOcrMinutes != null) current.sleepHours else form.sleepHours,
            sleepQuality = form.sleepQuality,
            feltRested = form.feltRested,
            anomaly = form.anomaly,
            paceSetter = form.paceSetter,
            paceConstraint = form.paceConstraint,
            hadWaitingStops = form.hadWaitingStops,
            representativeSpeed = form.representativeSpeed,
            questionnaireCompletedAt = form.questionnaireCompletedAt,
            comment = form.comment,
            updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1),
        )
        if (routeForm == null) dao.updateActivityWithCompanions(merged, companionIds)
        else {
            val currentRoute = dao.route(routeForm.id) ?: error("Маршрут не найден")
            dao.saveUnifiedQuestionnaire(currentRoute.copy(overallDifficulty = routeForm.overallDifficulty, climbDifficulty = routeForm.climbDifficulty,
                technicality = routeForm.technicality, exposure = routeForm.exposure, beauty = routeForm.beauty, notes = "",
                updatedAt = maxOf(System.currentTimeMillis(),currentRoute.updatedAt+1)), merged.copy(comment = HikeComments.merge(form.comment,routeForm.notes)), companionIds)
        }
    }

    suspend fun savePaceSegments(activityId: Long, segments: List<PaceSegmentEntity>) = serializedWrite {
        require(segments.all { it.startFraction in 0.0..1.0 && it.endFraction in 0.0..1.0 && it.endFraction > it.startFraction }) {
            "У каждого участка должны быть корректные начало и конец"
        }
        val current = dao.activity(activityId) ?: error("Поход не найден")
        val now = maxOf(System.currentTimeMillis(), current.updatedAt + 1)
        dao.replacePaceSegments(
            activityId,
            segments.sortedBy { it.startFraction }.map { it.copy(id = 0, activityId = activityId, updatedAt = now) },
            now,
        )
    }

    suspend fun trimActivityTrack(activityId: Long, startFraction: Double, endFraction: Double) = serializedWrite {
        val activity = dao.activity(activityId) ?: error("Поход не найден")
        val baseline = TrackCodec.decode(activity.trackEncoded)
        val inferredDistance = baseline.filter { it.inferred }.takeIf { it.size >= 2 }?.let { TrackGeometry.cumulative(it).last() } ?: 0.0
        val distance = (activity.distanceM - inferredDistance).coerceAtLeast(0.0)
        val axis = TrackDistanceAxis.build(baseline, distance)
        val selected = TrackCorrection.trim(baseline, startFraction, endFraction, distance)
        val first = baseline.indexOf(selected.first()); val last = baseline.indexOf(selected.last())
        updateCorrectedTrack(activity, selected, "TRIM", axis.provenance + ". Высоты и время выбранных записанных участков рассчитаны по сохранённым точкам; восстановленные участки остаются оценкой.", axis.meters[last]-axis.meters[first])
    }

    suspend fun inferMissingOutAndBackStart(activityId: Long, joinIndex: Int? = null) = serializedWrite {
        val activity = dao.activity(activityId) ?: error("Поход не найден")
        val original = TrackCodec.decode(activity.originalTrackEncoded ?: activity.trackEncoded)
        updateCorrectedTrack(activity, TrackCorrection.inferMissingOutAndBackStart(original, manualJoinIndex = joinIndex), "INFERRED_START", "Начало восстановлено зеркально по обратному участку")
    }

    suspend fun resetTrackCorrection(activityId: Long) = serializedWrite {
        val activity = dao.activity(activityId) ?: error("Поход не найден")
        val original = TrackCodec.decode(activity.originalTrackEncoded ?: activity.trackEncoded)
        updateCorrectedTrack(activity, original, "NONE", "")
    }

    suspend fun recoverAlongTrails(activityId: Long, route: List<TrackPoint>) = serializedWrite {
        val activity = dao.activity(activityId) ?: error("Поход не найден")
        val original = TrackCodec.decode(activity.originalTrackEncoded ?: activity.trackEncoded)
        require(activity.originalMetricsJson != null) { "Исходные показатели часов неизвестны. Импортируй исходную запись заново." }
        require(route.size >= 2 && route.all { it.inferred }) { "Нет маршрута для восстановления" }
        val estimate = RecoveryEstimator.estimate(route,original)
        updateCorrectedTrack(activity, estimate.points + original, "INFERRED_START", RecoveryEstimator.summary(estimate) + "\nДобавленная часть — восстановленная геометрия, не измерение часов. Время, пульс и исходные показатели записи сохранены; длительность добавленной части неизвестна.")
    }
    private suspend fun updateCorrectedTrack(activity: ActivityEntity, points: List<TrackPoint>, mode: String, note: String, cutDistanceM: Double? = null) {
        val calculated = TrackAnalyzer.analyze(points)
        val originalMetrics = activity.originalMetricsJson?.let(OriginalWatchMetrics::decode)
        require(mode != "NONE" || originalMetrics != null) { "Исходные числа часов старой коррекции неизвестны. Повторно импортируй оригинальный файл." }
        val original = TrackCodec.decode(activity.originalTrackEncoded ?: activity.trackEncoded)
        val metrics = when {
            mode == "NONE" -> originalMetrics!!
            // Restored geometry is not a second measurement of the watch recording.
            mode == "INFERRED_START" && originalMetrics != null -> originalMetrics
            mode == "TRIM" && originalMetrics != null -> {
                val axis = TrackDistanceAxis.build(original, originalMetrics.distanceM).meters
                val first = original.indexOf(points.first()).coerceAtLeast(0)
                val last = original.indexOf(points.last()).coerceAtLeast(first)
                val distance = cutDistanceM ?: (axis[last] - axis[first])
                calculated.copy(distanceM = distance, averagePaceMinutesPerKm = calculated.movingSeconds?.let { it / 60.0 / (distance / 1000.0) })
            }
            else -> calculated
        }
        val now = maxOf(System.currentTimeMillis(), activity.updatedAt + 1)
        val corrected = activity.copy(
            trackEncoded = if (mode == "NONE") activity.originalTrackEncoded ?: activity.trackEncoded else TrackCodec.encode(points),
            startedAt = activity.startedAt ?: HikeDateResolver.afterTrackCorrection(activity.importedStartedAt, original),
            distanceM = metrics.distanceM,
            elevationGainM = metrics.elevationGainM,
            elevationLossM = metrics.elevationLossM,
            elapsedSeconds = metrics.elapsedSeconds,
            movingSeconds = metrics.movingSeconds,
            stoppedSeconds = metrics.stoppedSeconds,
            averagePaceMinutesPerKm = metrics.averagePaceMinutesPerKm,
            averageHeartRateBpm = metrics.averageHeartRateBpm,
            maxHeartRateBpm = metrics.maxHeartRateBpm,
            trackCorrectionMode = mode,
            trackCorrectionNote = note,
            updatedAt = now,
        )
        val route = activity.routeId?.let { dao.route(it) } ?: error("Маршрут похода не найден")
        dao.updateCorrectedTrack(
            corrected,
            route.copy(
                trackEncoded = corrected.trackEncoded,
                distanceM = route.distanceM ?: corrected.distanceM,
                elevationGainM = route.elevationGainM ?: corrected.elevationGainM,
                elevationLossM = route.elevationLossM ?: corrected.elevationLossM,
                updatedAt = now,
            ),
        )
    }

    suspend fun setHeavyBackpack(activityId: Long, enabled: Boolean) = serializedWrite {
        val current = dao.activity(activityId) ?: error("Поход не найден")
        dao.updateBackpack(current.copy(heavyBackpack = enabled,
            updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1)))
    }

    suspend fun saveCompanions(activityId: Long, ids: Set<Long>) = serializedWrite {
        val current = dao.activity(activityId) ?: error("Поход не найден")
        dao.updateActivityWithCompanions(current.copy(updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1)), ids)
    }

    suspend fun setManualDate(activityId: Long, date: java.time.LocalDate?) = serializedWrite {
        val current = dao.activity(activityId) ?: error("Поход не найден")
        dao.updateActivity(current.copy(manualDateEpochDay = date?.toEpochDay(),
            startedAt = date?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: current.importedStartedAt,
            updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1)))
        dao.invalidateAiForActivity(activityId)
    }

    suspend fun stageSleepScreenshot(uri: Uri): String = withContext(Dispatchers.IO) { saveNormalizedImage(uri, "sleep_screenshots", "sleep") }

    suspend fun commitSleepScreenshot(activityId:Long, expectedRevision:Long, path:String, minutes:Int?, reason:String) = serializedWrite {
        val current=dao.activity(activityId) ?: error("Поход не найден")
        require(current.updatedAt == expectedRevision) { "Поход изменился. Повтори добавление сна: прежние данные сохранены." }
        require(File(path).isFile) { "Изображение отсутствует" }
        dao.updateActivityAndInvalidate(current.copy(sleepScreenshotPath=path, sleepOcrMinutes=minutes, sleepOcrStatus=reason,
            sleepHours=minutes?.div(60.0) ?: current.sleepHours, updatedAt=maxOf(System.currentTimeMillis(),current.updatedAt+1)))
        // Keep prior image bytes; canceled pending uploads never own that file.
    }

    suspend fun saveSleepScreenshot(activityId: Long, uri: Uri): String = serializedWrite {
        val activity = dao.activity(activityId) ?: error("Поход не найден")
        val path = saveNormalizedImage(uri, "sleep_screenshots", "sleep")
        dao.updateActivityAndInvalidate(activity.copy(sleepScreenshotPath = path, updatedAt = System.currentTimeMillis()))
        activity.sleepScreenshotPath?.takeIf { it != path }?.let { runCatching { File(it).delete() } }
        path
    }

    suspend fun saveCalendarContext(context: CalendarContextEntity) = serializedWrite {
        require(context.ongoing || context.endEpochDay >= context.startEpochDay) { "Дата окончания раньше начала" }
        require(context.note.isNotBlank() || context.type != "NOTE") { "Добавь текст заметки" }
        val old = if(context.id != 0L) dao.calendarContext(context.id) ?: error("Запись не найдена") else null
        val updated = context.copy(createdAt = old?.createdAt ?: context.createdAt, updatedAt = maxOf(System.currentTimeMillis(), (old?.updatedAt ?: 0) + 1))
        if(old == null) dao.insertCalendarContext(updated) else dao.updateCalendarContext(updated)
        dao.invalidateAllAiCache()
    }
    suspend fun savePillRegimen(start: java.time.LocalDate, active: Int, pause: Int,
        subBlockDays: Int = 21, subBlockCount: Int = 4, effective: java.time.LocalDate = start) = serializedWrite {
        val plan = PillPlanVersions.normalizedPlan(start, active, pause, subBlockDays, subBlockCount)
        val revision = maxOf(System.currentTimeMillis(), (dao.pillPlan()?.updatedAt ?: 0) + 1)
        dao.saveVersionedPillPlan(PillPlanEntity(anchorEpochDay=plan.anchorEpochDay,activeDays=active,breakDays=pause,
            updatedAt=revision,subBlockDays=plan.subBlockDays,subBlockCount=plan.subBlockCount),
            PillPlanVersions.effectiveDate(effective).toEpochDay())
        dao.invalidateAllAiCache()
    }
    suspend fun savePillEvent(form: PillEventEntity) = serializedWrite {
        val old = if(form.id != 0L) dao.pillEvent(form.id) ?: error("Факт не найден") else null
        if(old == null) dao.insertPillEvent(form) else dao.updatePillEvent(form.copy(createdAt=old.createdAt))
        dao.pillPlan()?.let { dao.savePillPlan(it.copy(updatedAt = maxOf(System.currentTimeMillis(), it.updatedAt + 1))) }
        dao.invalidateAllAiCache()
    }
    suspend fun applySleepOcr(activityId: Long, path: String, minutes: Int?, reason: String) = serializedWrite {
        val current = dao.activity(activityId) ?: error("Поход не найден")
        if(current.sleepScreenshotPath != path) return@serializedWrite // stale replaced image
        val wasAuto = current.sleepOcrMinutes?.let { kotlin.math.abs((current.sleepHours ?: -1.0) - it / 60.0) < 0.001 } == true
        val conflict = minutes != null && current.sleepHours != null && !wasAuto && kotlin.math.abs(current.sleepHours-minutes/60.0)>0.001
        dao.updateActivityAndInvalidate(current.copy(sleepOcrMinutes=minutes,
            sleepOcrStatus=if(conflict) "CONFLICT: $reason" else reason,
            sleepHours=if(minutes != null && (current.sleepHours==null || wasAuto)) minutes/60.0 else current.sleepHours,
            updatedAt=maxOf(System.currentTimeMillis(),current.updatedAt+1)))
    }

    suspend fun addCalendarContext(context: CalendarContextEntity) = serializedWrite {
        require(context.endEpochDay >= context.startEpochDay) { "Дата окончания не может быть раньше начала" }
        require(context.note.isNotBlank() || context.type != "NOTE") { "Добавь текст заметки" }
        dao.insertCalendarContext(context.copy(updatedAt = System.currentTimeMillis()))
        dao.invalidateAllAiCache()
    }
    suspend fun deleteCalendarContext(context: CalendarContextEntity) = serializedWrite {
        dao.deleteCalendarContext(context)
        dao.touchCalendarContextsInRange(context.startEpochDay, if(context.ongoing) maxOf(context.endEpochDay, java.time.LocalDate.now().toEpochDay()) else context.endEpochDay, maxOf(System.currentTimeMillis(), context.updatedAt + 1))
        dao.invalidateAllAiCache()
    }
    suspend fun addCompanion(name: String): Long = withContext(Dispatchers.IO) { dao.createUniqueCompanion(name) }
    suspend fun savePillPlan(plan: PillPlanEntity, effective: java.time.LocalDate = java.time.LocalDate.now()) = serializedWrite {
        PillPlanVersions.normalizedPlan(java.time.LocalDate.ofEpochDay(plan.anchorEpochDay), plan.activeDays, plan.breakDays,
            if (plan.subBlockDays == 0) { if (plan.activeDays == 84) 21 else plan.activeDays } else plan.subBlockDays,
            if (plan.subBlockCount == 0) { if (plan.activeDays == 84) 4 else 1 } else plan.subBlockCount)
        dao.saveVersionedPillPlan(plan.copy(updatedAt = maxOf(System.currentTimeMillis(), (dao.pillPlan()?.updatedAt ?: 0) + 1)),
            PillPlanVersions.effectiveDate(effective).toEpochDay())
        dao.invalidateAllAiCache()
    }
    suspend fun addPillEvent(event: PillEventEntity) = serializedWrite {
        dao.insertPillEvent(event)
        dao.pillPlan()?.let { dao.savePillPlan(it.copy(updatedAt = maxOf(System.currentTimeMillis(), it.updatedAt + 1))) }
        dao.invalidateAllAiCache()
    }
    suspend fun pillEventsOnce() = withContext(Dispatchers.IO) { dao.pillEvents() }
    suspend fun routeOnce(id: Long) = withContext(Dispatchers.IO) { dao.route(id) }
    suspend fun activityOnce(id: Long) = withContext(Dispatchers.IO) { dao.activity(id) }
    suspend fun latestActivityForRouteOnce(routeId: Long) = withContext(Dispatchers.IO) { dao.latestActivityForRoute(routeId) }
    suspend fun companionNamesOnce(activityId: Long) = withContext(Dispatchers.IO) { dao.companionNamesForActivity(activityId) }
    suspend fun paceSegmentsOnce(activityId: Long) = withContext(Dispatchers.IO) { dao.paceSegments(activityId) }
    suspend fun calendarContextsForActivityOnce(activity: ActivityEntity?): List<CalendarContextEntity> = withContext(Dispatchers.IO) {
        activity?.startedAt?.let { timestamp ->
            val day = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
            dao.calendarContextsForDay(day).filter { !it.ongoing || day <= java.time.LocalDate.now().toEpochDay() }
        }.orEmpty()
    }
    suspend fun calendarContextRevisionForActivityOnce(activity: ActivityEntity?): Long? = withContext(Dispatchers.IO) {
        activity?.startedAt?.let { timestamp ->
            val day = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
            dao.calendarContextRevisionForDay(day)
        }
    }
    suspend fun pillPlanOnce() = withContext(Dispatchers.IO) { dao.pillPlan() }
    suspend fun pillPlanForActivity(activity: ActivityEntity?) = withContext(Dispatchers.IO) {
        val day = activity?.manualDateEpochDay?.let(java.time.LocalDate::ofEpochDay) ?: activity?.startedAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        day?.let { PillPlanVersions.forDate(dao.pillPlanVersions(),dao.pillPlan(),it) }
    }

    suspend fun invalidateAllAiCache() = serializedWrite { dao.invalidateAllAiCache() }

    private suspend fun <T> serializedWrite(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { writeMutex.withLock { val result = block(); refreshPending(); result } }

    private fun displayName(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: "track"
        }
        return uri.lastPathSegment ?: "track"
    }

    private fun saveNormalizedImage(uri: Uri, directory: String, prefix: String): String {
        val bitmap = resolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            ?: error("Не удалось прочитать изображение")
        val scale = (1800.0 / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1.0)
        val normalized = if (scale < 1.0) Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        ) else bitmap
        val dir = File(filesDir, directory).apply { mkdirs() }
        val file = File(dir, "$prefix-${System.currentTimeMillis()}.jpg")
        file.outputStream().use { output -> normalized.compress(Bitmap.CompressFormat.JPEG, 88, output) }
        if (normalized !== bitmap) normalized.recycle()
        bitmap.recycle()
        return file.absolutePath
    }
}
