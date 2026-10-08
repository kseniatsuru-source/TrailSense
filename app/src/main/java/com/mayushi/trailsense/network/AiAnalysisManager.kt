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
    private const val BASE_INSTRUCTIONS = """Ты — Mayushi, личный аналитик походов Ксюши. Отвечай по-русски, кратко, конкретно и без медицинских выводов. Используй все ненулевые данные из input и персонализируй вывод по фактическому походу, личным оценкам, сну, спутникам, ограничению темпа и нейтральному контексту назначенной схемы, если они переданы. Разделяй объективную сложность маршрута, наблюдаемую нагрузку и контекст поездки; не смешивай плановые данные AllTrails с фактическими метриками часов. Если у ROUTE есть linked_activity, обязательно сопоставь дистанцию, набор, время, темп, пульс и субъективные оценки с параметрами маршрута. Если linked_activity=null, анализируй только маршрут и не добавляй шаблонный раздел про отсутствие нагрузки. Не перечисляй отсутствующие показатели и не пиши, что нет данных о температуре, пульсе, темпе или других полях, если эти поля не переданы либо не относятся к заданию. Не называй этап схемы комбинированных таблеток естественной фазой цикла, не советуй менять приём и не приписывай ему причинность. Не выдумывай причины необычной нагрузки: отмечай только наблюдаемую связь и контекст. Если передан source_url AllTrails, по инициативе пользователя проверь только публичную страницу web/browser-инструментами; не обходи логин, CAPTCHA или ограничения. Если приложен скриншот Huawei Health, выпиши только явно видимые показатели и укажи сомнения в summary. Верни СТРОГО JSON без markdown: {"summary":"текст","route_facts":null,"activity_facts":null}. route_facts при маршруте: {"title":null,"distance_m":null,"gain_m":null,"loss_m":null,"difficulty":null,"rating":null}. activity_facts при скриншоте: {"title":null,"distance_m":null,"gain_m":null,"loss_m":null,"elapsed_s":null,"moving_s":null,"stopped_s":null,"avg_hr":null,"max_hr":null}. Неприменимый объект оставь null."""
    private const val RICH_FORMAT = """

Используй участки с разным ведущим темпом, корректировку трека, календарные заметки и скриншот сна, если они переданы. Субъективная оценка сна Ксюши важнее оценки часов. Поле summary оформи разметкой с короткими заголовками, списками и, когда есть сопоставимые числа, Markdown-таблицей:
## Маршрут
## Факт и ориентир
| Показатель | Ориентир | Факт | Разница |
## Нагрузка и ощущения
## Контекст
## Вывод Mayushi
Не добавляй пустые разделы или строки таблиц. В конце дай один конкретный поддерживающий вывод. Можно честно сравнить время с публичным ориентиром AllTrails, если дистанция и набор сопоставимы; при различиях обязательно оговори их. Не ранжируй здоровье или физическую форму относительно других людей без сопоставимых данных. Верни JSON по уже указанному контракту; Markdown допустим только внутри строки summary, не вокруг JSON.
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
        val expectedUpdatedAt = exactLong(input, "entity_updated_at") ?: -1
        require(expectedUpdatedAt > 0) { "Задание ${job.id} создано старой версией TrailSense — создай анализ заново" }
        val activitySnapshot = when (job.entityType) {
            "ROUTE" -> input.optJSONObject("linked_activity")
            "ACTIVITY" -> input.optJSONObject("activity")
            else -> null
        }
        val activityStartedAt = activitySnapshot?.let { exactLong(it, "started_at_ms") }
        val contextRevision = activityStartedAt?.let { timestamp ->
            val epochDay = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
            dao.calendarContextRevisionForDay(epochDay)
        }
        AnalysisContextSnapshot.requireCurrent(input, preferences.sleepNormHours, dao.pillPlan(), contextRevision)
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
    suspend fun enqueue(type: String, entityId: Long, kind: String, payload: JSONObject, imagePath: String? = null, imageMimeType: String? = null): Boolean {
        val input = payload.put("analysis_kind", kind).put("algorithm_version", AnalysisPrompt.VERSION).toString()
        val attachment = imagePath?.let(::File)
        if (attachment != null) require(attachment.isFile) { "Вложение для анализа больше недоступно" }
        val digest = AnalysisInputHash.calculate(input, attachment, imageMimeType)
        val hash = digest.inputHash
        val cacheKey = "$type:$entityId:$kind:$hash"
        if (dao.aiCache(cacheKey) != null) return false
        val monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val used = dao.aiTokensSince(monthStart)
        if (prefs.directHermesEnabled && prefs.monthlyTokenBudget > 0 && used >= prefs.monthlyTokenBudget) throw IllegalStateException("Достигнут месячный лимит AI")
        val id = dao.enqueuePendingAiJob(AiJobEntity(entityType = type, entityId = entityId, analysisKind = kind, inputJson = input, inputHash = hash, imagePath = imagePath, imageMimeType = imageMimeType))
        if (id <= 0) return false
        if (prefs.directHermesEnabled) schedule(id, ExistingWorkPolicy.KEEP)
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
        val requestedId = inputData.getLong("jobId", -1)
        val job = if (requestedId > 0) dao.aiJob(requestedId) else dao.nextAiJob()
        if (job == null || job.status != "PENDING") return Result.success()
        val prefs = app.preferences
        if (!prefs.directHermesEnabled) return Result.success()
        try {
            AiJobFreshness.requireInputUnchanged(job)
            AiJobFreshness.requireCurrent(dao, prefs, job)
        } catch (error: IllegalArgumentException) {
            dao.supersedePendingAiJob(job.id, job.inputHash, (error.message ?: "Задание устарело").take(500), System.currentTimeMillis())
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
            )
            return Result.failure()
        }
        return try {
            val result = app.hermesClient.analyze(
                prefs.hermesEndpoint,
                key,
                AnalysisPrompt.INSTRUCTIONS,
                job.inputJson,
                "trailsense-${job.entityType}-${job.entityId}-${job.analysisKind}",
                job.imagePath,
                job.imageMimeType,
            )
            val decoded = AiResponseParser.parseStrict(result.text)
            database.withTransaction {
                val claimed = dao.claimPendingAiJob(job.id, job.inputHash, CLAIMED_STATUS, System.currentTimeMillis())
                if (claimed == 0) return@withTransaction
                AiJobFreshness.requireCurrent(dao, prefs, job)
                when (job.entityType) {
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
                check(dao.finishClaimedAiJob(job.id, job.inputHash, CLAIMED_STATUS, System.currentTimeMillis()) == 1) { "Состояние задания изменилось во время применения результата" }
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
            )
            if (terminal) Result.failure() else Result.retry()
        }
    }

    companion object {
        private const val CLAIMED_STATUS = "APPLYING_DIRECT"
    }
}
