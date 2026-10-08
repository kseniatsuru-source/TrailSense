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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

class TrailRepository(private val dao: TrailDao, private val resolver: ContentResolver, private val filesDir: File) {
    private val writeMutex = Mutex()
    val routes = dao.observeRoutes()
    val activities = dao.observeActivities()
    val companions = dao.observeCompanions()
    val pillPlan = dao.observePillPlan()
    val pillEvents = dao.observePillEvents()
    val calendarContexts = dao.observeCalendarContexts()
    val pendingAiCount = dao.observePendingAiCount()

    fun route(id: Long) = dao.observeRoute(id)
    fun activity(id: Long) = dao.observeActivity(id)
    fun activitiesForRoute(routeId: Long) = dao.observeActivitiesForRoute(routeId)
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
            sourceLabel = if (shared == null) "Часы" else "AllTrails",
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
                comment = best?.takeIf { it.second.shouldSuggest }?.let {
                    "Предложенное совпадение: ${it.first.title} (подтверди вручную)"
                } ?: "",
                createdAt = now,
                updatedAt = now,
            )
        }
        val activityId = if (existing != null) {
            dao.updateActivity(activity)
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
                sourceLabel = "AllTrails + ${parsed.sourceFormat.name} с часов",
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
            comment = "Показатели извлечены со скриншота; проверь их перед сохранением.",
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

    suspend fun saveQuestionnaire(form: ActivityEntity, companionIds: Set<Long>) = serializedWrite {
        val current = dao.activity(form.id) ?: error("Поход не найден")
        HikeAnnotations.validate(form.customRatings, form.pointsOfInterest)
        val merged = current.copy(
            customRatings = form.customRatings,
            pointsOfInterest = form.pointsOfInterest,
            overallEffort = form.overallEffort,
            climbEffort = form.climbEffort,
            descentEffort = form.descentEffort,
            technicality = form.technicality,
            enjoyment = form.enjoyment,
            sleepHours = form.sleepHours,
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
        dao.updateActivityWithCompanions(merged, companionIds)
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
        val original = TrackCodec.decode(activity.originalTrackEncoded ?: activity.trackEncoded)
        updateCorrectedTrack(activity, TrackCorrection.trim(original, startFraction, endFraction), "TRIM", "Оставлено ${(startFraction * 100).toInt()}–${(endFraction * 100).toInt()}% исходной записи")
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

    private suspend fun updateCorrectedTrack(activity: ActivityEntity, points: List<TrackPoint>, mode: String, note: String) {
        val calculated = TrackAnalyzer.analyze(points)
        val originalMetrics = activity.originalMetricsJson?.let(OriginalWatchMetrics::decode)
        require(mode != "NONE" || originalMetrics != null) { "Исходные числа часов старой коррекции неизвестны. Повторно импортируй оригинальный файл." }
        val original = TrackCodec.decode(activity.originalTrackEncoded ?: activity.trackEncoded)
        val metrics = when {
            mode == "NONE" -> originalMetrics!!
            mode == "INFERRED_START" && originalMetrics != null -> {
                val addedCount = points.size - original.size
                val added = TrackAnalyzer.analyze(points.take(addedCount + 1))
                val elapsed = originalMetrics.elapsedSeconds?.let { it + (added.elapsedSeconds ?: 0) }
                val moving = originalMetrics.movingSeconds?.let { it + (added.movingSeconds ?: 0) }
                val distance = originalMetrics.distanceM + added.distanceM
                originalMetrics.copy(distanceM = distance,
                    elevationGainM = originalMetrics.elevationGainM + added.elevationGainM,
                    elevationLossM = originalMetrics.elevationLossM + added.elevationLossM,
                    elapsedSeconds = elapsed, movingSeconds = moving,
                    stoppedSeconds = if (elapsed != null && moving != null) elapsed - moving else null,
                    averagePaceMinutesPerKm = moving?.let { it / 60.0 / (distance / 1000.0) })
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

    suspend fun setManualDate(activityId: Long, date: java.time.LocalDate?) = serializedWrite {
        val current = dao.activity(activityId) ?: error("Поход не найден")
        dao.updateActivity(current.copy(manualDateEpochDay = date?.toEpochDay(),
            startedAt = date?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: current.importedStartedAt,
            updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1)))
        dao.invalidateAiForActivity(activityId)
    }

    suspend fun saveSleepScreenshot(activityId: Long, uri: Uri): String = serializedWrite {
        val activity = dao.activity(activityId) ?: error("Поход не найден")
        val path = saveNormalizedImage(uri, "sleep_screenshots", "sleep")
        dao.updateActivityAndInvalidate(activity.copy(sleepScreenshotPath = path, updatedAt = System.currentTimeMillis()))
        activity.sleepScreenshotPath?.takeIf { it != path }?.let { runCatching { File(it).delete() } }
        path
    }

    suspend fun addCalendarContext(context: CalendarContextEntity) = serializedWrite {
        require(context.endEpochDay >= context.startEpochDay) { "Дата окончания не может быть раньше начала" }
        require(context.note.isNotBlank() || context.type != "NOTE") { "Добавь текст заметки" }
        dao.insertCalendarContext(context.copy(updatedAt = System.currentTimeMillis()))
        dao.invalidateAllAiCache()
    }
    suspend fun deleteCalendarContext(context: CalendarContextEntity) = serializedWrite {
        dao.deleteCalendarContext(context)
        dao.touchCalendarContextsInRange(context.startEpochDay, context.endEpochDay, maxOf(System.currentTimeMillis(), context.updatedAt + 1))
        dao.invalidateAllAiCache()
    }
    suspend fun addCompanion(name: String): Long = withContext(Dispatchers.IO) { dao.insertCompanion(CompanionEntity(name = name.trim())) }
    suspend fun savePillPlan(plan: PillPlanEntity) = serializedWrite {
        dao.savePillPlan(plan.copy(updatedAt = System.currentTimeMillis()))
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
            dao.calendarContextsForDay(day)
        }.orEmpty()
    }
    suspend fun calendarContextRevisionForActivityOnce(activity: ActivityEntity?): Long? = withContext(Dispatchers.IO) {
        activity?.startedAt?.let { timestamp ->
            val day = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
            dao.calendarContextRevisionForDay(day)
        }
    }
    suspend fun pillPlanOnce() = withContext(Dispatchers.IO) { dao.pillPlan() }

    suspend fun invalidateAllAiCache() = serializedWrite { dao.invalidateAllAiCache() }

    private suspend fun <T> serializedWrite(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { writeMutex.withLock { block() } }

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
