package com.mayushi.trailsense.network

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.mayushi.trailsense.data.*
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object AnalysisPrompt {
    const val VERSION = "5"
    private const val BASE_INSTRUCTIONS = """Ты — Mayushi, личный аналитик походов Ксюши. Отвечай по-русски, кратко, конкретно и без медицинских выводов. Используй значимые проверенные данные из input и персонализируй вывод по фактическому походу, личным оценкам, сну, спутникам, ограничению темпа и нейтральному контексту назначенной схемы, если они переданы. Разделяй объективную сложность маршрута, наблюдаемую нагрузку и контекст поездки; не смешивай плановые данные AllTrails с фактическими метриками часов. Если у ROUTE есть linked_activity, обязательно сопоставь дистанцию, набор, время, темп, пульс и субъективные оценки с параметрами маршрута. Если linked_activity=null, анализируй только маршрут и не добавляй шаблонный раздел про отсутствие нагрузки. Не перечисляй отсутствующие показатели и не пиши, что нет данных о температуре, пульсе, темпе или других полях, если эти поля не переданы либо не относятся к заданию. Не называй этап схемы комбинированных таблеток естественной фазой цикла, не советуй менять приём и не приписывай ему причинность. Не выдумывай причины необычной нагрузки: отмечай только наблюдаемую связь и контекст. Если передан source_url AllTrails, по инициативе пользователя проверь только публичную страницу web/browser-инструментами; не обходи логин, CAPTCHA или ограничения. Если приложен скриншот Huawei Health, выпиши только явно видимые показатели и укажи сомнения в summary. Верни СТРОГО JSON без markdown: {"summary":"текст","route_facts":null,"activity_facts":null}. route_facts при маршруте: {"title":null,"distance_m":null,"gain_m":null,"loss_m":null,"difficulty":null,"rating":null}. activity_facts при скриншоте: {"title":null,"distance_m":null,"gain_m":null,"loss_m":null,"elapsed_s":null,"moving_s":null,"stopped_s":null,"avg_hr":null,"max_hr":null}. Неприменимый объект оставь null."""
    // Editorial changes live outside inputJson: do not rewrite exported jobs or their hashes.
    private const val RICH_FORMAT = """

Правила текста ниже уточняют стиль, не технический JSON-контракт. Не требуется перечислять все переданные поля: используй только значимые для полезного вывода данные. Однодневное мероприятие называй «хайк», с ночёвкой «поход».
Одиночный анализ: краткий содержательный итог и характер маршрута; полезные выводы о нагрузке и ощущениях; сон и стресс при значимости; динамика только при реальных повторах; конкретные советы при наличии пользы; короткое восстановление; разнообразная тёплая поддержка. «О данных» добавляй только для существенных расхождений. Пропускай пустые и бесполезные разделы, не навязывай таблицы и фиксированные заголовки.
Сравнительный анализ: в заголовке только количество прохождений и период, без списка всех отдельных дат. Дай основной вывод динамики, значимые изменения времени, пульса и ощущений, объяснения только при доказанном контексте и краткий практический итог. Описывай ход серии, а не пересказывай анкеты по очереди. Числа пульса используй только как опору содержательного вывода, без случайных перечней среднего и максимального пульса по датам. Не объясняй средний пульс остановками.
Не делай скорость или замедление эмоционально главным результатом и не используй заголовок «Главное». Разницу времени сообщай нейтрально внутри динамики. Небольшие различия GPS-дистанции и набора не делают подтверждённый повтор другим маршрутом. Существенно другой старт или финиш требуют проверки; достройка геометрии не создаёт измеренное время и пульс.
Пиши сразу результат, а не цепочку рассуждений. Не пересказывай подряд имена, сон, впечатления и оценки без нового смысла. Каждое предложение должно давать факт, полезный вывод, действие или уместную поддержку. Не диагностируй форму или здоровье. Не придумывай причины тяжести, перерывы в тренировках, погоду или договорённости компании. Погоду бери только из добровольно заполненной анкеты, исторический поиск погоды не выполняй. Не выдумывай последовательность и длительность погодных условий.
Тяжёлый рюкзак является приблизительным контекстом для ночёвки, не измеренным весом и не основанием для расчёта калорий. Плановая схема таблеток не является журналом фактического приёма. Балл сна часов не упоминай. Пустые оценки не переосмысливай: экспозиция 1 означает обычный маршрут, техничность 1 простой маршрут, пустые значения означают нет данных. Пустая оценка спуска сама по себе не доказывает отсутствие спуска.
Восстановление описывай кратко и естественно. Точные часы восстановления допустимы только при подтверждённых единицах и реальной оценке часов для этой записи; неизвестные единицы recovery не переводи в часы. Не отображай тренировочный эффект TE. Не повторяй без пользы еду и сон в каждом отчёте. Поддержку варьируй, избегай преувеличений и шаблонных фраз.
Перед выдачей перечитай весь текст как независимый редактор: проверь факты, охват, числа и пользу каждого предложения; убери банальности, выдуманные объяснения, повторы и пересказ анкеты. Авторский текст без тире. Итоговый текст и summary должны совпадать. Верни JSON по уже указанному контракту; Markdown допустим только внутри строки summary, не вокруг JSON.
"""
    const val INSTRUCTIONS = BASE_INSTRUCTIONS + RICH_FORMAT
}

object AiJobFreshness {
    fun requireInputUnchanged(job: AiJobEntity) {
        val attachment = job.imagePath?.let(::File)
        if (attachment != null) require(attachment.isFile) { "Вложение задания ${job.id} больше недоступно" }
        val digest = AnalysisInputHash.calculate(job.inputJson, attachment, job.imageMimeType)
        require(digest.inputHash == job.inputHash) { "Вложение или данные задания ${job.id} изменились — создай анализ заново" }
    }

    suspend fun requireCurrent(dao: TrailDao, preferences: AppPreferences, job: AiJobEntity) {
        val input = try { JSONObject(job.inputJson) } catch (_: Exception) {
            throw IllegalArgumentException("Повреждены данные задания ${job.id}")
        }
        require(input.optString("algorithm_version") == AnalysisPrompt.VERSION) {
            "Задание ${job.id} создано старой версией TrailSense — создай анализ заново"
        }
        if (AppliedAnalysisSource.matches(dao, preferences, job)) return
        if (job.entityType == ComparisonAnalysis.TYPE) {
            ComparisonAnalysis.requireCurrent(dao, preferences, job, input)
            return
        }
        val expectedUpdatedAt = exactLong(input, "entity_updated_at") ?: -1
        require(expectedUpdatedAt > 0) { "Задание ${job.id} создано старой версией TrailSense — создай анализ заново" }
        val activitySnapshot = when (job.entityType) {
            "ROUTE" -> input.optJSONObject("linked_activity")
            "ACTIVITY" -> input.optJSONObject("activity")
            else -> null
        }
        val activityStartedAt = activitySnapshot?.let { exactLong(it, "started_at_ms") }
        activitySnapshot?.let { snapshot ->
            val activityId = exactLong(snapshot, "id") ?: throw IllegalArgumentException("Нет идентификатора хайка")
            val names = org.json.JSONArray().apply { dao.companionNamesForActivity(activityId).forEach { put(it) } }
            require(snapshot.optJSONArray("companions")?.toString() == names.toString()) { "Список путников изменился" }
        }
        if (input.has("pill_facts_and_deviations")) {
            require(input.getJSONArray("pill_facts_and_deviations").toString() == PendingAnalysisRefresh(dao, preferences).pillFacts().toString()) { "Факты личного расписания изменились" }
        }
        val contextRevision = activityStartedAt?.let { timestamp ->
            val epochDay = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
            dao.calendarContextRevisionForDay(epochDay)
        }
        val activityDate = activityStartedAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        val currentContexts = activityDate?.let { dao.calendarContextsForDay(it.toEpochDay()) }.orEmpty()
        require(input.optJSONArray("calendar_context")?.toString() == AnalysisPayloadBuilder.calendarContextJson(currentContexts).toString()) {
            "Личный контекст изменился"
        }
        val versions = dao.pillPlanVersions()
        val relevantPlan = if(versions.isEmpty()) dao.pillPlan() else activityDate?.let { com.mayushi.trailsense.domain.PillPlanVersions.forDate(versions,dao.pillPlan(),it) }
        AnalysisContextSnapshot.requireCurrent(input, preferences.sleepNormHours, relevantPlan, contextRevision)
        when (job.entityType) {
            "ROUTE" -> {
                val route = dao.route(job.entityId) ?: throw IllegalArgumentException("Маршрут для задания ${job.id} удалён")
                require(route.updatedAt == expectedUpdatedAt) { "Данные для задания ${job.id} изменились — создай анализ заново" }
                require(input.has("linked_activity")) { "Задание ${job.id} создано без снимка похода — создай анализ заново" }
                val latest = dao.latestActivityForRoute(job.entityId)
                if (input.isNull("linked_activity")) {
                    require(latest == null) { "К маршруту добавлен поход — создай персональный анализ заново" }
                } else {
                    val snapshot = input.optJSONObject("linked_activity")
                        ?: throw IllegalArgumentException("Повреждены данные похода в задании ${job.id}")
                    val expectedActivityId = exactLong(snapshot, "id") ?: -1
                    val expectedActivityUpdatedAt = exactLong(snapshot, "updated_at") ?: -1
                    require(latest != null && latest.id == expectedActivityId && latest.updatedAt == expectedActivityUpdatedAt) {
                        "Данные связанного похода изменились — создай персональный анализ заново"
                    }
                }
            }
            "ACTIVITY" -> {
                val activity = dao.activity(job.entityId) ?: throw IllegalArgumentException("Поход для задания ${job.id} удалён")
                require(activity.updatedAt == expectedUpdatedAt) { "Данные для задания ${job.id} изменились — создай анализ заново" }
                val displayTitle=activity.routeId?.let{dao.route(it)?.title} ?: activity.title
                require(activitySnapshot?.optString("title") == displayTitle) { "Название маршрута изменилось — создай анализ заново" }
            }
            else -> throw IllegalArgumentException("Неизвестный тип задания ${job.entityType}")
        }
    }

    private fun exactLong(json: JSONObject, name: String): Long? {
        val value = json.opt(name) as? Number ?: return null
        return try { java.math.BigDecimal(value.toString()).longValueExact() } catch (_: Exception) { null }
    }
}

class AiAnalysisManager(private val context: Context, private val dao: TrailDao, private val prefs: AppPreferences) {
    suspend fun enqueueComparison(title: String, ascent: Boolean, recordings: List<ComparisonAnalysis.Recording>, allowDirect: Boolean = true): Boolean {
        val payload = ComparisonAnalysis.payload(title, ascent, recordings)
        return enqueue(ComparisonAnalysis.TYPE, 0, ComparisonAnalysis.KIND, payload,
            allowDirect = allowDirect, scopeKey = payload.getString("comparison_scope"))
    }

    suspend fun enqueue(type: String, entityId: Long, kind: String, payload: JSONObject, imagePath: String? = null, imageMimeType: String? = null, allowDirect: Boolean = true, scopeKey: String = ""): Boolean {
        val input = payload.put("analysis_kind", kind).put("algorithm_version", AnalysisPrompt.VERSION).toString()
        val attachment = imagePath?.let(::File)
        if (attachment != null) require(attachment.isFile) { "Вложение для анализа больше недоступно" }
        val digest = AnalysisInputHash.calculate(input, attachment, imageMimeType)
        val hash = digest.inputHash
        val cacheKey = "$type:$entityId:$kind:$hash"
        // Explicit requests may regenerate a cached result with current instructions.
        // The old exported input/hash remain immutable; pending identity still deduplicates.
        val monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val used = dao.aiTokensSince(monthStart)
        if (prefs.directHermesEnabled && prefs.monthlyTokenBudget > 0 && used >= prefs.monthlyTokenBudget) throw IllegalStateException("Достигнут месячный лимит AI")
        val id = dao.enqueuePendingAiJob(AiJobEntity(entityType = type, entityId = entityId, analysisKind = kind, inputJson = input, inputHash = hash, imagePath = imagePath, imageMimeType = imageMimeType, scopeKey = scopeKey))
        if (id <= 0) return false
        if (allowDirect && prefs.directHermesEnabled) schedule(id, ExistingWorkPolicy.REPLACE)
        return true
    }

    suspend fun retryPending(): Int {
        require(prefs.directHermesEnabled) { "Прямое подключение Hermes отключено" }
        val pending = dao.pendingAiJobs()
        pending.forEach { schedule(it.id, ExistingWorkPolicy.REPLACE) }
        return pending.size
    }

    private fun schedule(jobId: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<AiAnalysisWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("jobId" to jobId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(jobId), policy, request)
    }

    companion object {
        fun workName(jobId: Long): String = "trailsense-ai-$jobId"
    }
}

class AiAnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as com.mayushi.trailsense.TrailSenseApplication
        val database = app.database
        val dao = database.trailDao()
        database.withTransaction { PendingAnalysisRefresh(dao, app.preferences).refresh() }
        val requestedId = inputData.getLong("jobId", -1)
        val job = if (requestedId > 0) dao.aiJob(requestedId) else dao.nextAiJob()
        if (job == null || job.status != "PENDING") return Result.success()
        val prefs = app.preferences
        if (!prefs.directHermesEnabled) return Result.success()
        try {
            AiJobFreshness.requireInputUnchanged(job)
            AiJobFreshness.requireCurrent(dao, prefs, job)
        } catch (error: IllegalArgumentException) {
            dao.supersedePendingAiJob(job.id, job.inputHash, (error.message ?: "Задание устарело").take(500), System.currentTimeMillis(), job.inputRevision)
            return Result.success()
        }
        val key = app.secretStore.read()
        if (prefs.hermesEndpoint.isBlank() || key.isNullOrBlank()) {
            dao.recordPendingAiJobFailure(
                job.id,
                job.inputHash,
                "PENDING",
                "Прямое подключение Hermes не настроено",
                System.currentTimeMillis(),
                job.inputRevision,
            )
            return Result.failure()
        }
        return try {
            val result = app.hermesClient.analyze(
                prefs.hermesEndpoint,
                key,
                AnalysisPrompt.INSTRUCTIONS,
                job.inputJson,
                "trailsense-${job.entityType}-${job.entityId}-${job.analysisKind}-${job.id}-${job.inputRevision}",
                job.imagePath,
                job.imageMimeType,
            )
            val decoded = AiResponseParser.parseStrict(result.text)
            val analysisCreatedAt = System.currentTimeMillis()
            database.withTransaction {
                if (dao.aiJob(job.id)?.inputRevision != job.inputRevision) return@withTransaction
                val claimed = dao.claimPendingAiJob(job.id, job.inputHash, CLAIMED_STATUS, System.currentTimeMillis(), job.inputRevision)
                if (claimed == 0) return@withTransaction
                AiJobFreshness.requireCurrent(dao, prefs, job)
                when (job.entityType) {
                    ComparisonAnalysis.TYPE -> Unit // Comparison never changes source route/activity facts.
                    "ROUTE" -> {
                        val route = dao.route(job.entityId) ?: throw IllegalArgumentException("Маршрут для задания ${job.id} удалён")
                        dao.updateRoute(AiResultMerger.mergeRoute(route, decoded))
                    }
                    "ACTIVITY" -> {
                        val activity = dao.activity(job.entityId) ?: throw IllegalArgumentException("Поход для задания ${job.id} удалён")
                        dao.updateActivity(AiResultMerger.mergeActivity(activity, decoded))
                    }
                    else -> throw IllegalArgumentException("Неизвестный тип задания ${job.entityType}")
                }
                val cacheKey = "${job.entityType}:${job.entityId}:${job.analysisKind}:${job.inputHash}"
                dao.insertAiCache(AiCacheEntity(cacheKey = cacheKey, entityType = job.entityType, entityId = job.entityId, analysisKind = job.analysisKind, inputHash = job.inputHash, response = decoded.summary, promptTokens = result.promptTokens, completionTokens = result.completionTokens))
                AnalysisResultFreshness.store(dao, job, analysisCreatedAt, result.text, prefs)
                check(dao.finishClaimedAiJob(job.id, job.inputHash, CLAIMED_STATUS, System.currentTimeMillis(), job.inputRevision) == 1) { "Состояние задания изменилось во время применения результата" }
                PendingAnalysisRefresh(dao, prefs).refresh()
            }
            Result.success()
        } catch (error: Exception) {
            val terminal = runAttemptCount >= 5
            dao.recordPendingAiJobFailure(
                job.id,
                job.inputHash,
                if (terminal) "FAILED" else "PENDING",
                error.message?.take(500),
                System.currentTimeMillis(),
                job.inputRevision,
            )
            if (terminal) Result.failure() else Result.retry()
        }
    }

    companion object {
        private const val CLAIMED_STATUS = "APPLYING_DIRECT"
    }
}
