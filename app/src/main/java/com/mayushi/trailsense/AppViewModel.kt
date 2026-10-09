package com.mayushi.trailsense

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mayushi.trailsense.data.*
import com.mayushi.trailsense.domain.*
import com.mayushi.trailsense.network.AnalysisPayloadBuilder
import com.mayushi.trailsense.network.EndpointPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TrailSenseApplication
    val routes = app.repository.routes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activities = app.repository.activities.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val companions = app.repository.companions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pillPlan = app.repository.pillPlan.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val pillEvents = app.repository.pillEvents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val calendarContexts = app.repository.calendarContexts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pendingAi = app.repository.pendingAiCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _pendingSharedFile = MutableStateFlow<Uri?>(null)
    val pendingSharedFile = _pendingSharedFile.asStateFlow()

    fun route(id: Long) = app.repository.route(id)
    fun activity(id: Long) = app.repository.activity(id)
    fun activitiesForRoute(routeId: Long) = app.repository.activitiesForRoute(routeId)
    fun companionIds(id: Long) = app.repository.companionIds(id)
    fun paceSegments(id: Long) = app.repository.paceSegments(id)
    fun latestAi(type: String, id: Long, kind: String) = app.repository.latestAi(type, id, kind)
    fun clearMessage() { _message.value = null }

    fun handleIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in setOf(Intent.ACTION_SEND, Intent.ACTION_VIEW)) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val uri = when (action) {
            Intent.ACTION_VIEW -> intent.data
            else -> {
                val extra = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                extra ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
        }
        when {
            uri != null -> viewModelScope.launch {
                val isExchange = withContext(Dispatchers.IO) { app.telegramExchange.looksLikeExchangeJson(uri) }
                when {
                    isExchange -> importTelegramResponse(uri)
                    intent.type?.startsWith("image/") == true -> importHuaweiScreenshot(uri)
                    else -> _pendingSharedFile.value = uri
                }
            }
            !text.isNullOrBlank() -> importRouteLink(text)
        }
    }
    fun consumeSharedFile(routeId: Long) {
        val uri = _pendingSharedFile.value ?: return
        _pendingSharedFile.value = null
        importWatchFile(routeId, uri)
    }
    fun dismissSharedFile() { _pendingSharedFile.value = null }

    fun createEvent(title: String, allTrailsLink: String, watchFile: Uri?) = launchBusy {
        val id = app.repository.createRouteEvent(title, allTrailsLink)
        if (watchFile != null) app.repository.importActivityFile(watchFile, id, replaceForRoute = false)
        _message.value = if (watchFile != null) {
            if (allTrailsLink.isBlank()) "Поход создан из файла часов; ссылка необязательна" else "Событие создано: ссылка AllTrails и файл часов объединены"
        } else {
            "Событие создано — файл часов можно добавить внутри"
        }
        if (app.preferences.shortAutoAnalysis) requestRouteAnalysis(id, "SHORT_ROUTE")
    }

    fun importRouteLink(text: String) = launchBusy {
        val id = app.repository.importSharedRoute(text)
        _message.value = "Маршрут добавлен"
        if (app.preferences.shortAutoAnalysis) requestRouteAnalysis(id, "SHORT_ROUTE")
    }
    fun importRouteFile(uri: Uri) = launchBusy {
        val id = app.repository.importRouteFile(uri); _message.value = "Файл маршрута импортирован"
        if (app.preferences.shortAutoAnalysis) requestRouteAnalysis(id, "SHORT_ROUTE")
    }
    fun importActivity(uri: Uri) = launchBusy { app.repository.importActivityFile(uri); _message.value = "Тренировка импортирована — заполни впечатления" }
    fun importWatchFile(routeId: Long, uri: Uri) = launchBusy {
        app.repository.importActivityFile(uri, routeId, replaceForRoute = true)
        _message.value = "Данные обновлены: дистанция, набор, спуск и время пересчитаны по файлу часов"
    }
    fun importHuaweiScreenshot(uri: Uri) = launchBusy {
        val (id, path) = app.repository.importHuaweiScreenshot(uri)
        val activity = app.repository.activityOnce(id) ?: return@launchBusy
        requestActivityAnalysis(activity, "HUAWEI_SCREENSHOT", path)
        _message.value = "Скриншот добавлен в очередь — отправь задания Mayushi через Telegram"
    }
    fun saveRoute(route: RouteEntity) = launchBusy { app.repository.saveRoute(route); _message.value = "Оценки маршрута сохранены" }
    fun deleteEvent(routeId: Long, onDeleted: () -> Unit) = launchBusy {
        app.repository.deleteRouteEvent(routeId)
        _message.value = "Событие удалено"
        onDeleted()
    }
    fun saveActivity(activity: ActivityEntity, companionIds: Set<Long>) = launchBusy {
        val completed = activity.copy(questionnaireCompletedAt = System.currentTimeMillis())
        app.repository.saveActivity(completed, companionIds)
        _message.value = "Анкета сохранена"
        if (app.preferences.postHikeAutoAnalysis) {
            val current = app.repository.activityOnce(activity.id) ?: return@launchBusy
            requestActivityAnalysis(current, "POST_HIKE")
        }
    }
    fun saveHikeQuestionnaire(
        route: RouteEntity,
        activity: ActivityEntity,
        companionIds: Set<Long>,
        paceSegments: List<PaceSegmentEntity>,
        onSaved: () -> Unit = {},
    ) = launchBusy {
        HikeAnnotations.validate(activity.customRatings, activity.pointsOfInterest)
        app.repository.saveQuestionnaire(activity.copy(questionnaireCompletedAt = System.currentTimeMillis()), companionIds, route)
        _message.value = "Анкета сохранена и свёрнута"
        onSaved()
        if (app.preferences.postHikeAutoAnalysis) requestRouteAnalysis(route.id, "DEEP_ROUTE")
    }
    fun saveSleepScreenshot(activityId: Long, uri: Uri) = launchBusy {
        app.repository.saveSleepScreenshot(activityId, uri)
        _message.value = "Скриншот сна прикреплён; твоя собственная оценка сна останется главной"
    }
    fun trimTrack(activityId: Long, startFraction: Double, endFraction: Double) = launchBusy {
        app.repository.trimActivityTrack(activityId, startFraction, endFraction)
        _message.value = "Трек обрезан; расстояние, высота и время пересчитаны"
    }
    fun inferMissingTrackStart(activityId: Long, joinIndex: Int? = null) = launchBusy {
        app.repository.inferMissingOutAndBackStart(activityId, joinIndex)
        _message.value = "Начало восстановлено по обратному участку; все показатели пересчитаны"
    }
    fun recoverAlongTrails(activityId: Long, route: List<TrackPoint>) = launchBusy {
        app.repository.recoverAlongTrails(activityId, route)
        _message.value = "Добавлена оценка начала по тропам OSM; исходная запись сохранена"
    }
    fun setHeavyBackpack(activityId: Long, enabled: Boolean) = launchBusy {
        app.repository.setHeavyBackpack(activityId, enabled)
        _message.value = "Контекст рюкзака сохранён; старый анализ сброшен"
    }
    fun saveCompanions(activityId: Long, ids: Set<Long>) = launchBusy {
        app.repository.saveCompanions(activityId, ids)
        _message.value = "Путники сохранены"
    }
    fun sendCurrentHike(context: Context, routeId: Long) = viewModelScope.launch {
        requestRouteAnalysis(routeId, "DEEP_ROUTE", allowDirect = false).join()
        shareAiQueue(context, routeId).join()
    }
    fun setManualDate(activityId: Long, date: LocalDate?) = launchBusy { app.repository.setManualDate(activityId, date); _message.value = "Дата обновлена; старый анализ сброшен" }
    fun resetTrackCorrection(activityId: Long) = launchBusy {
        app.repository.resetTrackCorrection(activityId)
        _message.value = "Возвращён исходный трек"
    }
    fun addCalendarContext(start: LocalDate, end: LocalDate, type: String, severity: Int?, note: String) = launchBusy {
        app.repository.addCalendarContext(CalendarContextEntity(
            startEpochDay = start.toEpochDay(),
            endEpochDay = end.toEpochDay(),
            type = type,
            severity = severity,
            note = note.trim(),
        ))
        _message.value = "Контекст добавлен в календарь"
    }
    fun deleteCalendarContext(context: CalendarContextEntity) = launchBusy {
        app.repository.deleteCalendarContext(context)
        _message.value = "Запись удалена"
    }
    fun addCompanion(name: String) { if (name.isNotBlank()) launchBusy { app.repository.addCompanion(name); _message.value = "Спутник добавлен" } }
    fun savePillStart(date: LocalDate) = launchBusy {
        val plan = PillPlanEntity(anchorEpochDay = date.toEpochDay())
        app.repository.savePillPlan(plan)
        com.mayushi.trailsense.reminders.PillReminderScheduler.schedule(app, plan, app.repository.pillEventsOnce())
        _message.value = "Схема 84 + 7 и напоминания сохранены"
    }
    fun logPillTaken(date: LocalDate, taken: Boolean) = launchBusy {
        app.repository.addPillEvent(PillEventEntity(epochDay = date.toEpochDay(), plannedEpochDay = date.toEpochDay(), type = "TAKEN", actualTaken = taken))
        _message.value = "Факт приёма сохранён, план не сдвинут"
    }
    fun logPillDeviation(plannedDate: LocalDate, actualDate: LocalDate, type: String, policy: DeviationPolicy, note: String) = launchBusy {
        val current = pillPlan.value ?: return@launchBusy
        val plan = PillPlan(current.anchorEpochDay, current.activeDays, current.breakDays)
        val block = PillSchedule.day(plan,plannedDate).blockNumber
        val boundaries = (-1L..1L).flatMap { PillSchedule.reminderDates(plan,block+it).values }
        require(policy == DeviationPolicy.KEEP_PLAN || plannedDate in boundaries) { "Для сдвига выбери дату границы (последняя активная / начало перерыва / возобновление)" }
        val change = PillSchedule.applyBoundaryDeviation(plan, plannedDate, actualDate, policy)
        app.repository.addPillEvent(PillEventEntity(epochDay = actualDate.toEpochDay(), plannedEpochDay = plannedDate.toEpochDay(), type = type, note = note, deviationPolicy = policy.name))
        if (policy == DeviationPolicy.RECALCULATE_ALL) app.repository.savePillPlan(current.copy(anchorEpochDay = change.plan.anchorEpochDay))
        val effective = if (policy == DeviationPolicy.RECALCULATE_ALL) current.copy(anchorEpochDay = change.plan.anchorEpochDay) else current
        com.mayushi.trailsense.reminders.PillReminderScheduler.schedule(app, effective, app.repository.pillEventsOnce())
        _message.value = "Отклонение записано"
    }

    fun requestRouteAnalysis(id: Long, kind: String, allowDirect: Boolean = true) = viewModelScope.launch {
        try {
            val route = app.repository.routeOnce(id) ?: return@launch
            val linkedActivity = app.repository.latestActivityForRouteOnce(id)
            val companionNames = linkedActivity?.let { app.repository.companionNamesOnce(it.id) }.orEmpty()
            val paceSegments = linkedActivity?.let { app.repository.paceSegmentsOnce(it.id) }.orEmpty()
            val calendarContext = app.repository.calendarContextsForActivityOnce(linkedActivity)
            val payload = AnalysisPayloadBuilder.route(
                route = route,
                linkedActivity = linkedActivity,
                companionNames = companionNames,
                sleepNormHours = app.preferences.sleepNormHours,
                pillPlan = app.repository.pillPlanOnce(),
                paceSegments = paceSegments,
                calendarContext = calendarContext,
            )
            payload.put("pill_facts_and_deviations", pillFactsJson(app.repository.pillEventsOnce()))
            val sleepImage = linkedActivity?.sleepScreenshotPath
            if (sleepImage != null) payload.put("attachment_kind", "SLEEP_SCREENSHOT")
            app.aiManager.enqueue("ROUTE", id, kind, payload, sleepImage, sleepImage?.let { "image/jpeg" }, allowDirect)
            _message.value = if (kind == "DEEP_ROUTE") "Персональный глубокий анализ добавлен в очередь — отправь её через Telegram" else "Персональный анализ добавлен в очередь — отправь её через Telegram"
        } catch (e: Exception) { _message.value = e.message ?: "Не удалось запустить анализ" }
    }

    private suspend fun requestActivityAnalysis(activity: ActivityEntity, kind: String, imagePath: String? = null) {
        val calendarContext = app.repository.calendarContextsForActivityOnce(activity)
        val payload = AnalysisPayloadBuilder.activity(
            activity = activity,
            companionNames = app.repository.companionNamesOnce(activity.id),
            sleepNormHours = app.preferences.sleepNormHours,
            pillPlan = app.repository.pillPlanOnce(),
            paceSegments = app.repository.paceSegmentsOnce(activity.id),
            calendarContext = calendarContext,
        )
        payload.put("pill_facts_and_deviations", pillFactsJson(app.repository.pillEventsOnce()))
        val attachment = imagePath ?: activity.sleepScreenshotPath
        if (attachment != null) payload.put("attachment_kind", if (imagePath != null) "WORKOUT_SCREENSHOT" else "SLEEP_SCREENSHOT")
        app.aiManager.enqueue("ACTIVITY", activity.id, kind, payload, attachment, attachment?.let { "image/jpeg" })
    }

    fun saveAiQueue(uri: Uri) = launchBusy {
        val request = withContext(Dispatchers.IO) { app.telegramExchange.createRequestFile() }
        withContext(Dispatchers.IO) {
            app.contentResolver.openOutputStream(uri, "wt")?.use { output -> request.file.inputStream().use { it.copyTo(output) } } ?: error("Не удалось сохранить JSON")
        }
        _message.value = "JSON сохранён локально: ${request.taskCount} заданий"
    }

    fun shareAiQueue(context: Context, routeId: Long? = null) = launchBusy {
        _message.value = "Готовлю файл для Telegram…"
        val request = withContext(Dispatchers.IO) { app.telegramExchange.createRequestFile(routeId, routeId?.let { "DEEP_ROUTE" }) }
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", request.file)
        val readPermission = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val share = Intent(Intent.ACTION_SEND).apply {
            // Telegram reliably exposes its document target for the generic binary MIME.
            // The .json filename and protocol metadata still identify the file contents.
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "TrailSense: файл запроса для Mayushi. Внутри уже есть все данные и формат ответа.")
            clipData = ClipData.newUri(context.contentResolver, "TrailSense request", uri)
            addFlags(readPermission)
        }
        val externalTargets = context.packageManager.queryIntentActivities(share, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName != BuildConfig.APPLICATION_ID }
        require(externalTargets.isNotEmpty()) { "Не найдено приложение для отправки файла. Установи или открой Telegram и повтори." }
        val chooser = Intent.createChooser(share, "Отправить Mayushi через Telegram").apply {
            clipData = share.clipData
            addFlags(readPermission)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        _message.value = if (request.supersededCount > 0) {
            "Выбери Telegram и этот чат. Заданий: ${request.taskCount}; устаревших исключено: ${request.supersededCount}"
        } else {
            "Выбери Telegram → этот чат → Отправить. Заданий: ${request.taskCount}"
        }
    }

    fun importTelegramResponse(uri: Uri) = launchBusy {
        val result = app.telegramExchange.importResponse(uri)
        _message.value = when {
            result.imported > 0 && result.alreadyImported > 0 -> "Импортировано анализов: ${result.imported}; уже были в приложении: ${result.alreadyImported}"
            result.imported > 0 -> "Ответ Mayushi импортирован: ${result.imported}"
            result.alreadyImported > 0 -> "Этот ответ уже был импортирован"
            else -> "В файле нет новых результатов"
        }
    }

    fun saveAnalysisPreferences(sleepNorm: Double, shortAuto: Boolean, postAuto: Boolean) = launchBusy {
        app.preferences.directHermesEnabled = false
        app.preferences.sleepNormHours = sleepNorm
        app.preferences.shortAutoAnalysis = shortAuto
        app.preferences.postHikeAutoAnalysis = postAuto
        app.repository.invalidateAllAiCache()
        _message.value = "Настройки анализа сохранены"
    }

    fun saveHermesSettings(endpoint: String, key: String, budget: Int, sleepNorm: Double, shortAuto: Boolean, postAuto: Boolean, directEnabled: Boolean) = launchBusy {
        app.preferences.hermesEndpoint = if (endpoint.isBlank()) "" else EndpointPolicy.normalizeAndValidate(endpoint)
        if (key.isNotBlank()) app.secretStore.save(key)
        app.preferences.directHermesEnabled = directEnabled
        app.preferences.monthlyTokenBudget = budget
        app.preferences.sleepNormHours = sleepNorm
        app.preferences.shortAutoAnalysis = shortAuto
        app.preferences.postHikeAutoAnalysis = postAuto
        app.repository.invalidateAllAiCache()
        _message.value = "Настройки сохранены"
    }
    fun testHermes() = launchBusy {
        val endpoint = app.preferences.hermesEndpoint
        val key = app.secretStore.read() ?: error("Сначала сохрани API-ключ")
        val available = withContext(Dispatchers.IO) { app.hermesClient.health(endpoint, key) }
        _message.value = if (available) "Hermes доступна — можно запускать очередь" else "Hermes недоступна"
    }
    fun runAiQueue() = launchBusy {
        require(app.preferences.directHermesEnabled) { "Сначала включи и сохрани прямое подключение Hermes" }
        val endpoint = app.preferences.hermesEndpoint
        require(endpoint.isNotBlank()) { "Сначала сохрани адрес Hermes в настройках" }
        val key = app.secretStore.read() ?: error("Сначала сохрани API-ключ")
        val available = withContext(Dispatchers.IO) { app.hermesClient.health(endpoint, key) }
        require(available) { "Hermes недоступна: проверь адрес и соединение" }
        val count = app.aiManager.retryPending()
        _message.value = if (count > 0) "Запущено заданий: $count" else "Очередь пуста"
    }
    private fun pillFactsJson(events: List<PillEventEntity>) = org.json.JSONArray().apply {
        events.take(100).forEach { e -> put(org.json.JSONObject().put("date", LocalDate.ofEpochDay(e.epochDay).toString())
            .put("planned_date", e.plannedEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: org.json.JSONObject.NULL)
            .put("actual_taken", e.actualTaken ?: org.json.JSONObject.NULL).put("policy", e.deviationPolicy ?: "KEEP_PLAN").put("note", e.note)) }
    }
    fun preferences() = app.preferences
    fun hasSavedKey() = !app.secretStore.read().isNullOrBlank()

    private fun launchBusy(block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        try { block() } catch (e: Exception) { _message.value = e.message ?: "Что-то пошло не так" } finally { _busy.value = false }
    }
}
