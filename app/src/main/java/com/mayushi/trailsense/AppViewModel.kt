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
    val routeIndex = app.repository.routeIndex.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recordingIndex = app.repository.recordingIndex.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    suspend fun loadRecordedTrack(reference:String) = app.repository.loadRecordedTrack(reference)
    val activities = app.repository.activities.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val companions = app.repository.companions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val frequentCompanionIds = app.database.trailDao().observeFrequentCompanionIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pillPlanVersions = app.repository.pillPlanVersions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
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
    private val _createdRouteId = MutableStateFlow<Long?>(null)
    val createdRouteId = _createdRouteId.asStateFlow()
    fun consumeCreatedRoute() { _createdRouteId.value = null }

    fun route(id: Long) = app.repository.route(id)
    fun activity(id: Long) = app.repository.activity(id)
    fun activitiesForRoute(routeId: Long) = app.repository.activitiesForRoute(routeId)
    fun companionIds(id: Long) = app.repository.companionIds(id)
    private fun companionStore() = CompanionStore(app.database) { app.repository.refreshExistingPending() }
    suspend fun createCompanion(name: String) = withContext(Dispatchers.IO) { companionStore().create(name) }
    suspend fun renameCompanion(id: Long, name: String) = withContext(Dispatchers.IO) { companionStore().rename(id,name) }
    suspend fun archiveCompanion(id: Long, deleted: Boolean) = withContext(Dispatchers.IO) { companionStore().deleted(id,deleted) }
    suspend fun companionExamples(id: Long) = withContext(Dispatchers.IO) { app.database.trailDao().companionExamples(id) }
    suspend fun frequentCompanions() = withContext(Dispatchers.IO) { app.database.trailDao().companionsOnce().filterNot { it.deleted }.map { it.id to app.database.trailDao().companionUseCount(it.id) }.sortedByDescending { it.second }.filter { it.second>0 }.take(4).map { it.first } }
    fun observeHuaweiHeartRate(id: Long) = HuaweiHeartRate(app.database).observe(id)
    suspend fun huaweiHeartRate(id: Long): List<Pair<Long,Int>> = withContext(Dispatchers.IO) { HuaweiHeartRate(app.database).load(id) }
    private val _huaweiPreview=MutableStateFlow<HuaweiImporter.Preview?>(null)
    val huaweiPreview=_huaweiPreview.asStateFlow()
    private var huaweiFile: java.io.File?=null
    override fun onCleared() { huaweiFile?.delete(); huaweiFile=null; super.onCleared() }
    fun dismissHuawei() { _huaweiPreview.value=null; huaweiFile?.delete(); huaweiFile=null }
    fun previewHuawei(uri: Uri) = if(_busy.value) null else launchBusy {
        dismissHuawei()
        val dir=java.io.File(app.cacheDir,"huawei-import").apply { mkdirs() }
        val file=java.io.File.createTempFile("owned-", ".json",dir)
        try {
            withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { output ->
                    val buffer=ByteArray(65536); var size=0L
                    while(true) { val n=input.read(buffer); if(n<0) break; size+=n; require(size<=HuaweiPackage.MAX_BYTES) { "Слишком большой JSON" }; output.write(buffer,0,n) }
                } }
                _huaweiPreview.value=HuaweiImporter(app.database).preview(file)
            }
            huaweiFile=file
        } catch(e: Exception) { file.delete(); throw e }
    }
    fun importHuawei(choices: Map<String,String>) = if(_busy.value) null else launchBusy {
        val preview=requireNotNull(_huaweiPreview.value); val file=requireNotNull(huaweiFile)
        val count=withContext(Dispatchers.IO) { HuaweiImporter(app.database).import(file,preview,choices) }
        app.repository.refreshExistingPending() // refresh existing pending identities only, never enqueue
        dismissHuawei(); _message.value="Импортировано записей Huawei: $count"
    }
    fun paceSegments(id: Long) = app.repository.paceSegments(id)
    fun latestAi(type: String, id: Long, kind: String) = app.repository.latestAi(type, id, kind)
    val comparisonAnalyses = app.database.trailDao().observeAnalysisResults("COMPARISON", 0, "ROUTE_DYNAMICS")
    suspend fun comparisonInput(jobId: Long): org.json.JSONObject? = withContext(Dispatchers.IO) {
        app.database.trailDao().analysisJobSnapshot(jobId)?.let { org.json.JSONObject(it.inputJson) }
    }
    fun deleteComparisonAnalysis(key: String) = launchBusy {
        app.database.trailDao().deleteComparisonAnalysis(key)
        _message.value = "Анализ удалён; хайки, анкеты и треки сохранены"
    }
    fun requestComparisonAnalysis(title: String, ascent: Boolean, rows: List<AutomaticRepeatRoutes.Row>) = launchBusy {
        require(rows.size >= 2) { "Выбери не менее двух прохождений" }
        val builder = com.mayushi.trailsense.network.PendingAnalysisRefresh(app.database.trailDao(), app.preferences)
        val recordings = rows.map { row ->
            val current = app.repository.activityOnce(row.activity.id) ?: error("Хайк удалён")
            require(current.updatedAt == row.activity.updatedAt) { "Прохождения изменились. Повтори отправку." }
            com.mayushi.trailsense.network.ComparisonAnalysis.Recording(builder.activityPayload(current), row.start, row.end, row.seconds,
                com.mayushi.trailsense.network.ComparisonAnalysis.sectionMetrics(current, row.start, row.end))
        }
        val queued = app.aiManager.enqueueComparison(title, ascent, recordings, allowDirect = false)
        _message.value = if(queued) "Сравнение ${rows.size} прохождений добавлено одним заданием в очередь" else "Это сравнение уже ожидает анализа"
    }
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
                    intent.type in setOf("application/json","text/json") || uri.lastPathSegment?.endsWith(".json",true)==true -> previewHuawei(uri)
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

    fun createEvent(title: String, allTrailsLink: String, watchFile: Uri?, onCreated: () -> Unit = {}) = launchBusy {
        val id = app.repository.createRouteEvent(title, allTrailsLink)
        if (watchFile != null) {
            try { app.repository.importActivityFile(watchFile, id, replaceForRoute = false) }
            catch (e: Exception) { app.repository.deleteRouteEvent(id); throw e }
        }
        _createdRouteId.value = id
        onCreated()
        _message.value = if (watchFile != null) {
            if (allTrailsLink.isBlank()) "Поход создан из файла часов; ссылка необязательна" else "Событие создано: ссылка AllTrails и файл часов объединены"
        } else {
            "Событие создано — файл часов можно добавить внутри"
        }
    }

    fun importRouteLink(text: String) = launchBusy {
        val id = app.repository.importSharedRoute(text)
        _message.value = "Маршрут добавлен"
    }
    fun importRouteFile(uri: Uri) = launchBusy {
        val id = app.repository.importRouteFile(uri); _message.value = "Файл маршрута импортирован"
    }
    fun importActivity(uri: Uri) = launchBusy { app.repository.importActivityFile(uri); _message.value = "Тренировка импортирована — заполни впечатления" }
    fun importWatchFile(routeId: Long, uri: Uri) = launchBusy {
        app.repository.importActivityFile(uri, routeId, replaceForRoute = true)
        _message.value = "Данные обновлены: дистанция, набор, спуск и время пересчитаны по файлу часов"
    }
    fun importHuaweiScreenshot(uri: Uri) = launchBusy {
        val (id, path) = app.repository.importHuaweiScreenshot(uri)
        val activity = app.repository.activityOnce(id) ?: return@launchBusy
        _message.value = "Скриншот сохранён. Анализ отправляется только по твоему выбору."
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
    }
    data class PendingSleep(val activityId:Long,val revision:Long,val path:String,val result:SleepDurationReader.Result,val hikeDate:LocalDate?)
    private val _pendingSleep=MutableStateFlow<PendingSleep?>(null)
    val pendingSleep=_pendingSleep.asStateFlow()
    fun saveSleepScreenshot(activityId: Long, uri: Uri) = launchBusy {
        cancelSleepUpload()
        val current=app.repository.activityOnce(activityId) ?: error("Поход не найден")
        val path=app.repository.stageSleepScreenshot(uri)
        try {
            val result=SleepScreenshotReader(app).read(path)
            val date=RepeatRouteDynamics.date(current)
            val pending=PendingSleep(activityId,current.updatedAt,path,result,date)
            if(SleepWakingDate.mismatch(result.wakingDate,date)) _pendingSleep.value=pending
            else applySleepUpload(pending)
        } catch(e:Exception) { java.io.File(path).delete(); throw e }
    }
    private suspend fun applySleepUpload(pending:PendingSleep) {
        app.repository.commitSleepScreenshot(pending.activityId,pending.revision,pending.path,pending.result.minutes,pending.result.reason)
        _message.value=pending.result.minutes?.let { "Сон: ${it/60} ч ${it%60} мин" } ?: "Точная длительность не прочитана. Можно указать вручную."
    }
    fun confirmSleepUpload() = launchBusy {
        val pending=_pendingSleep.value ?: return@launchBusy
        try { applySleepUpload(pending) } finally { _pendingSleep.value=null }
    }
    fun cancelSleepUpload() {
        _pendingSleep.value?.let { java.io.File(it.path).delete() }
        _pendingSleep.value=null
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
        _message.value = "Начало маршрута восстановлено; исходная запись и показатели часов сохранены"
    }
    fun setHeavyBackpack(activityId: Long, enabled: Boolean) = launchBusy {
        app.repository.setHeavyBackpack(activityId, enabled)
        _message.value = "Контекст рюкзака сохранён; старый анализ сброшен"
    }
    fun saveCompanions(activityId: Long, ids: Set<Long>) = launchBusy {
        app.repository.saveCompanions(activityId, ids)
        _message.value = "Путники сохранены"
    }
    fun sendCurrentHike(context: Context, activityId: Long) = launchBusy {
        val activity = app.repository.activityOnce(activityId) ?: error("Поход не найден")
        requestActivityAnalysis(activity, "DEEP_ROUTE", allowDirect = false)
        shareAiQueue(context, activityId = activityId).join()
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
    fun saveCalendarContext(context: CalendarContextEntity) = launchBusy {
        app.repository.saveCalendarContext(context); _message.value = "Запись календаря сохранена"
    }
    fun savePillRegimen(start: LocalDate, active: Int, pause: Int, subBlockDays: Int = 21,
        subBlockCount: Int = 4, effective: LocalDate = start) = launchBusy {
        app.repository.savePillRegimen(start,active,pause,subBlockDays,subBlockCount,effective)
        schedulePillReminders()
        _message.value = "Назначенная схема сохранена с выбранной даты; история сохранена"
    }
    fun savePillEvent(event: PillEventEntity) = launchBusy {
        app.repository.savePillEvent(event); schedulePillReminders()
        _message.value = "Факт приёма сохранён; схема не сдвинута"
    }
    fun deleteCalendarContext(context: CalendarContextEntity) = launchBusy {
        app.repository.deleteCalendarContext(context)
        _message.value = "Запись удалена"
    }
    fun addCompanion(name: String) { if (name.isNotBlank()) launchBusy { app.repository.addCompanion(name); _message.value = "Спутник добавлен" } }
    fun savePillStart(date: LocalDate) = launchBusy {
        app.repository.savePillRegimen(date, 84, 7)
        schedulePillReminders()
        _message.value = "Схема сохранена; история сохранена"
    }
    fun logPillTaken(date: LocalDate, taken: Boolean) = launchBusy {
        app.repository.addPillEvent(PillEventEntity(epochDay = date.toEpochDay(), plannedEpochDay = date.toEpochDay(), type = "TAKEN", actualTaken = taken))
        schedulePillReminders()
        _message.value = "Факт приёма сохранён, план не сдвинут"
    }
    fun logPillDeviation(plannedDate: LocalDate, actualDate: LocalDate, type: String, policy: DeviationPolicy, note: String) = launchBusy {
        val current = app.repository.pillPlanOnce() ?: return@launchBusy
        val plan = current.toDomainPlan()
        val block = PillSchedule.day(plan,plannedDate).blockNumber
        val boundaries = (-1L..1L).flatMap { PillSchedule.reminderDates(plan,block+it).values }
        require(policy == DeviationPolicy.KEEP_PLAN || plannedDate in boundaries) { "Для сдвига выбери дату границы (последняя активная / начало перерыва / возобновление)" }
        val change = PillSchedule.applyBoundaryDeviation(plan, plannedDate, actualDate, policy)
        app.repository.addPillEvent(PillEventEntity(epochDay = actualDate.toEpochDay(), plannedEpochDay = plannedDate.toEpochDay(), type = type, note = note, deviationPolicy = policy.name))
        if (policy == DeviationPolicy.RECALCULATE_ALL) app.repository.savePillPlan(current.copy(anchorEpochDay = change.plan.anchorEpochDay))
        schedulePillReminders()
        _message.value = "Отклонение записано"
    }

    private suspend fun schedulePillReminders() = com.mayushi.trailsense.reminders.PillReminderScheduler.schedule(app)

    fun reschedulePillReminders() = launchBusy { schedulePillReminders() }

    fun requestRouteAnalysis(id: Long, kind: String, allowDirect: Boolean = true) = viewModelScope.launch {
        try {
            val route = app.repository.routeOnce(id) ?: return@launch
            val linkedActivity = app.repository.latestActivityForRouteOnce(id)
            val companionNames = linkedActivity?.let { app.repository.companionNamesOnce(it.id) }.orEmpty()
            val paceSegments = linkedActivity?.let { app.repository.paceSegmentsOnce(it.id) }.orEmpty()
            val calendarContext = app.repository.calendarContextsForActivityOnce(linkedActivity)
            val payload = AnalysisPayloadBuilder.route(
                route = route,
                linkedActivity = linkedActivity?.copy(title=route.title),
                companionNames = companionNames,
                sleepNormHours = app.preferences.sleepNormHours,
                pillPlan = app.repository.pillPlanForActivity(linkedActivity),
                paceSegments = paceSegments,
                calendarContext = calendarContext,
            )
            payload.put("pill_facts_and_deviations", pillFactsJson(app.repository.pillEventsOnce()))
            val sleepImage = linkedActivity?.sleepScreenshotPath
            if (sleepImage != null) payload.put("attachment_kind", "SLEEP_SCREENSHOT")
            val queued = app.aiManager.enqueue("ROUTE", id, kind, payload, sleepImage, sleepImage?.let { "image/jpeg" }, allowDirect)
            _message.value = if(!queued) "Этот анализ уже ожидает отправки" else if (kind == "DEEP_ROUTE") "Персональный глубокий анализ добавлен в очередь — отправь её через Telegram" else "Персональный анализ добавлен в очередь — отправь её через Telegram"
        } catch (e: Exception) { _message.value = e.message ?: "Не удалось запустить анализ" }
    }

    fun requestRecordingAnalysis(activityId: Long) = launchBusy {
        val activity = app.repository.activityOnce(activityId) ?: error("Поход не найден")
        val queued = requestActivityAnalysis(activity, "DEEP_ROUTE", allowDirect = false)
        _message.value = if(queued) "Анализ выбранной записи добавлен в очередь" else "Этот анализ уже ожидает отправки"
    }

    private suspend fun requestActivityAnalysis(activity: ActivityEntity, kind: String, imagePath: String? = null, allowDirect: Boolean = true): Boolean {
        val calendarContext = app.repository.calendarContextsForActivityOnce(activity)
        val payload = AnalysisPayloadBuilder.activity(
            activity = activity.copy(title=activity.routeId?.let {app.repository.routeOnce(it)?.title} ?: activity.title),
            companionNames = app.repository.companionNamesOnce(activity.id),
            sleepNormHours = app.preferences.sleepNormHours,
            pillPlan = app.repository.pillPlanForActivity(activity),
            paceSegments = app.repository.paceSegmentsOnce(activity.id),
            calendarContext = calendarContext,
        )
        payload.put("pill_facts_and_deviations", pillFactsJson(app.repository.pillEventsOnce()))
        val attachment = imagePath ?: activity.sleepScreenshotPath
        if (attachment != null) payload.put("attachment_kind", if (imagePath != null) "WORKOUT_SCREENSHOT" else "SLEEP_SCREENSHOT")
        return app.aiManager.enqueue("ACTIVITY", activity.id, kind, payload, attachment, attachment?.let { "image/jpeg" }, allowDirect)
    }

    fun saveAiQueue(uri: Uri) = launchBusy {
        val request = withContext(Dispatchers.IO) { app.telegramExchange.createRequestFile() }
        withContext(Dispatchers.IO) {
            app.contentResolver.openOutputStream(uri, "wt")?.use { output -> request.file.inputStream().use { it.copyTo(output) } } ?: error("Не удалось сохранить JSON")
        }
        _message.value = "JSON сохранён локально: ${request.taskCount} заданий"
    }

    fun shareAiQueue(context: Context, routeId: Long? = null, activityId: Long? = null) = launchBusy {
        _message.value = "Готовлю файл для Telegram…"
        val request = withContext(Dispatchers.IO) { app.telegramExchange.createRequestFile(routeId, if(routeId != null || activityId != null) "DEEP_ROUTE" else null, activityId) }
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
        com.mayushi.trailsense.network.PendingAnalysisRefresh(app.database.trailDao(), app.preferences).refresh()
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
