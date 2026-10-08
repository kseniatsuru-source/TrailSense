package com.mayushi.trailsense.exchange

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import androidx.work.WorkManager
import com.mayushi.trailsense.BuildConfig
import com.mayushi.trailsense.data.AiCacheEntity
import com.mayushi.trailsense.data.AiJobEntity
import com.mayushi.trailsense.data.TrailDatabase
import com.mayushi.trailsense.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.util.Base64
import java.util.UUID


data class TelegramRequestFile(
    val file: File,
    val taskCount: Int,
    val supersededCount: Int,
)

data class TelegramImportSummary(
    val imported: Int,
    val alreadyImported: Int,
)

internal data class PreparedAttachment(
    val file: File,
    val mimeType: String,
    val fileName: String,
    val sha256: String,
)

internal data class PreparedTask(
    val job: AiJobEntity,
    val attachment: PreparedAttachment?,
)

class TelegramExchangeManager(
    private val context: Context,
    private val database: TrailDatabase,
    private val preferences: AppPreferences,
    private val resolver: ContentResolver = context.contentResolver,
) {
    private val dao get() = database.trailDao()

    suspend fun createRequestFile(): TelegramRequestFile = withContext(Dispatchers.IO) {
        val snapshot = dao.pendingAiJobs()
        require(snapshot.isNotEmpty()) { "Очередь анализа пуста" }
        var supersededCount = 0
        val tasks = buildList {
            snapshot.forEach { job ->
                try {
                    AiJobFreshness.requireCurrent(dao, preferences, job)
                    add(prepareTask(job))
                } catch (error: IllegalArgumentException) {
                    val changed = dao.supersedePendingAiJob(
                        job.id,
                        job.inputHash,
                        (error.message ?: "Задание устарело").take(500),
                        System.currentTimeMillis(),
                    )
                    supersededCount += changed
                }
            }
        }
        require(tasks.isNotEmpty()) { "В очереди не осталось актуальных заданий — создай анализ заново" }
        val requestId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val envelope = TelegramExchangeProtocol.requestEnvelope(requestId, createdAt, BuildConfig.VERSION_NAME, AnalysisPrompt.INSTRUCTIONS)
        val estimatedBytes = TelegramRequestStreamWriter.estimateBytes(envelope, tasks)
        require(estimatedBytes <= MAX_REQUEST_BYTES) {
            "Вся очередь не помещается в один безопасный Telegram-файл (${formatMb(estimatedBytes)} МБ). Уменьши число крупных скриншотов и повтори экспорт."
        }

        val directory = File(context.cacheDir, EXCHANGE_DIRECTORY).apply { require(mkdirs() || isDirectory) { "Не удалось подготовить файл обмена" } }
        val manifestDirectory = manifestDirectory().apply { require(mkdirs() || isDirectory) { "Не удалось сохранить манифест обмена" } }
        cleanupOldFiles(directory)
        cleanupOldFiles(manifestDirectory)

        val output = File(directory, "trailsense-request-$requestId.json")
        val temporary = File(directory, ".trailsense-request-$requestId.tmp")
        try {
            FileOutputStream(temporary).use { raw ->
                MaxSizeOutputStream(raw, MAX_REQUEST_BYTES).use { limited -> TelegramRequestStreamWriter.write(limited, envelope, tasks) }
            }
            require(temporary.renameTo(output)) { "Не удалось завершить файл обмена" }
            val manifest = ExchangeManifest(
                requestId = requestId,
                tasks = tasks.map { ExchangeManifestTask(it.job.id, it.job.inputHash) },
                createdAtMs = createdAt,
            )
            writeManifestAtomically(manifestDirectory, manifest)
            TelegramRequestFile(output, tasks.size, supersededCount)
        } catch (error: Exception) {
            temporary.delete()
            output.delete()
            throw error
        }
    }

    suspend fun importResponse(uri: Uri): TelegramImportSummary = withContext(Dispatchers.IO) {
        val raw = readBoundedResponse(uri)
        val response = TelegramExchangeProtocol.parseResponse(raw)
        val manifest = readManifest(response.requestId)
        val orderedResults = manifest.orderedResults(response)
        val decoded = orderedResults.associate { result -> result.jobId to AiResponseParser.parseStrict(result.responseJson) }

        var imported = 0
        var alreadyImported = 0
        database.withTransaction {
            val jobsById = orderedResults.associate { result ->
                val job = dao.aiJob(result.jobId) ?: throw IllegalArgumentException("Задание ${result.jobId} уже удалено из TrailSense")
                require(job.inputHash == result.inputHash) { "Ответ не относится к текущей версии задания ${job.id}" }
                when (job.status) {
                    "DONE" -> Unit
                    "PENDING" -> { AiJobFreshness.requireInputUnchanged(job); AiJobFreshness.requireCurrent(dao, preferences, job) }
                    else -> throw IllegalArgumentException("Задание ${job.id} нельзя импортировать в состоянии ${job.status}")
                }
                result.jobId to job
            }
            orderedResults.forEach { result ->
                val job = jobsById.getValue(result.jobId)
                if (job.status == "DONE") {
                    alreadyImported++
                    return@forEach
                }
                check(dao.claimPendingAiJob(job.id, job.inputHash, IMPORTING_STATUS, System.currentTimeMillis()) == 1) {
                    "Состояние задания ${job.id} изменилось во время импорта"
                }
                val parsed = decoded.getValue(job.id)
                when (job.entityType) {
                    "ROUTE" -> {
                        val route = dao.route(job.entityId) ?: throw IllegalArgumentException("Маршрут для задания ${job.id} удалён")
                        dao.updateRoute(AiResultMerger.mergeRoute(route, parsed))
                    }
                    "ACTIVITY" -> {
                        val activity = dao.activity(job.entityId) ?: throw IllegalArgumentException("Поход для задания ${job.id} удалён")
                        dao.updateActivity(AiResultMerger.mergeActivity(activity, parsed))
                    }
                    else -> throw IllegalArgumentException("Неизвестный тип задания ${job.entityType}")
                }
                val cacheKey = "${job.entityType}:${job.entityId}:${job.analysisKind}:${job.inputHash}"
                dao.insertAiCache(
                    AiCacheEntity(
                        cacheKey = cacheKey,
                        entityType = job.entityType,
                        entityId = job.entityId,
                        analysisKind = job.analysisKind,
                        inputHash = job.inputHash,
                        response = parsed.summary,
                        promptTokens = result.promptTokens,
                        completionTokens = result.completionTokens,
                    )
                )
                check(dao.finishClaimedAiJob(job.id, job.inputHash, IMPORTING_STATUS, System.currentTimeMillis()) == 1) {
                    "Не удалось завершить задание ${job.id}"
                }
                imported++
            }
        }
        val workManager = WorkManager.getInstance(context)
        orderedResults.forEach { workManager.cancelUniqueWork(AiAnalysisManager.workName(it.jobId)) }
        TelegramImportSummary(imported, alreadyImported)
    }

    fun looksLikeExchangeJson(uri: Uri): Boolean {
        return try {
            val type = resolver.getType(uri)?.lowercase()
            if (type in setOf("application/json", "text/json")) {
                true
            } else {
                val name = displayName(uri).lowercase()
                name.startsWith("trailsense-response-") && name.endsWith(".json")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun prepareTask(job: AiJobEntity): PreparedTask {
        require(job.id > 0) { "У задания нет идентификатора" }
        require(job.inputHash.matches(TelegramExchangeProtocol.HASH_REGEX)) { "Некорректная контрольная сумма задания ${job.id}" }
        try { JSONObject(job.inputJson) } catch (_: Exception) { throw IllegalArgumentException("Повреждены данные задания ${job.id}") }
        val attachment = prepareAttachment(job)
        val digest = AnalysisInputHash.calculate(job.inputJson, attachment?.file, attachment?.mimeType)
        require(digest.inputHash == job.inputHash) { "Вложение или данные задания ${job.id} изменились — создай анализ заново" }
        if (attachment != null) require(digest.attachmentSha256 == attachment.sha256) { "Не удалось проверить вложение задания ${job.id}" }
        return PreparedTask(job, attachment)
    }

    private fun prepareAttachment(job: AiJobEntity): PreparedAttachment? {
        val rawPath = job.imagePath ?: return null
        val file = File(rawPath)
        val allowedRoot = context.filesDir.canonicalFile
        val canonical = file.canonicalFile
        require(canonical.path.startsWith(allowedRoot.path + File.separator)) { "Вложение задания ${job.id} находится вне хранилища TrailSense" }
        require(canonical.isFile) { "Вложение задания ${job.id} больше недоступно" }
        require(canonical.length() <= MAX_ATTACHMENT_BYTES) { "Вложение задания ${job.id} слишком большое" }
        val mime = job.imageMimeType?.lowercase()?.takeIf { it in ALLOWED_IMAGE_MIME_TYPES }
            ?: throw IllegalArgumentException("Неподдерживаемый формат вложения задания ${job.id}")
        val extension = when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        return PreparedAttachment(canonical, mime, "attachment-${job.id}.$extension", AnalysisInputHash.sha256(canonical))
    }

    private fun readBoundedResponse(uri: Uri): String {
        return resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_RESPONSE_BYTES) { "Файл ответа слишком большой" }
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        } ?: throw IllegalArgumentException("Не удалось открыть файл ответа")
    }

    private fun manifestDirectory(): File = File(context.filesDir, MANIFEST_DIRECTORY)

    private fun manifestFile(requestId: String): File {
        require(UUID_REGEX.matches(requestId)) { "Некорректный идентификатор запроса" }
        return File(manifestDirectory(), "$requestId.json")
    }

    private fun readManifest(requestId: String): ExchangeManifest {
        val file = manifestFile(requestId)
        require(file.isFile) { "TrailSense не находила такой запрос — экспортируй очередь заново" }
        require(file.length() <= MAX_MANIFEST_BYTES) { "Повреждён внутренний манифест запроса" }
        return ExchangeManifest.fromJson(file.readText(Charsets.UTF_8))
    }

    private fun writeManifestAtomically(directory: File, manifest: ExchangeManifest) {
        val destination = File(directory, "${manifest.requestId}.json")
        val temporary = File(directory, ".${manifest.requestId}.tmp")
        temporary.writeText(manifest.toJson(), Charsets.UTF_8)
        require(temporary.renameTo(destination)) { "Не удалось сохранить манифест обмена" }
    }

    private fun cleanupOldFiles(directory: File) {
        val now = System.currentTimeMillis()
        directory.listFiles()?.filter { now - it.lastModified() > CACHE_MAX_AGE_MS }?.forEach(File::delete)
    }

    private fun displayName(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index).orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun formatMb(bytes: Long): String = String.format(java.util.Locale.US, "%.1f", bytes / (1024.0 * 1024.0))

    companion object {
        const val EXCHANGE_DIRECTORY = "trailsense_exchange"
        private const val MANIFEST_DIRECTORY = "trailsense_exchange_manifests"
        private const val MAX_ATTACHMENT_BYTES = 5L * 1024 * 1024
        private const val MAX_REQUEST_BYTES = 25L * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 10 * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
        private const val CACHE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        private val ALLOWED_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        private val UUID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
        private const val IMPORTING_STATUS = "IMPORTING_TELEGRAM"
    }
}

internal object TelegramRequestStreamWriter {
    fun estimateBytes(envelope: JSONObject, tasks: List<PreparedTask>): Long {
        var total = envelope.toString().toByteArray(Charsets.UTF_8).size.toLong() + 32
        tasks.forEach { task ->
            total = Math.addExact(total, task.job.inputJson.toByteArray(Charsets.UTF_8).size.toLong() + TASK_JSON_OVERHEAD)
            task.attachment?.let { attachment ->
                val encoded = 4L * ((attachment.file.length() + 2L) / 3L)
                total = Math.addExact(total, encoded + ATTACHMENT_JSON_OVERHEAD)
            }
        }
        return total
    }

    fun write(output: OutputStream, envelope: JSONObject, tasks: List<PreparedTask>) {
        require(tasks.isNotEmpty()) { "Очередь анализа пуста" }
        val prefix = envelope.toString().dropLast(1) + ",\"tasks\":["
        output.writeUtf8(prefix)
        tasks.forEachIndexed { index, task ->
            if (index > 0) output.writeUtf8(",")
            val job = task.job
            val header = JSONObject()
                .put("job_id", job.id)
                .put("entity_type", job.entityType)
                .put("entity_id", job.entityId)
                .put("analysis_kind", job.analysisKind)
                .put("input_hash", job.inputHash)
                .put("input", JSONObject(job.inputJson))
            val attachment = task.attachment
            if (attachment == null) {
                output.writeUtf8(header.toString())
            } else {
                output.writeUtf8(header.toString().dropLast(1))
                val metadata = JSONObject()
                    .put("mime_type", attachment.mimeType)
                    .put("file_name", attachment.fileName)
                    .put("sha256", attachment.sha256)
                    .put("encoding", "base64")
                output.writeUtf8(",\"attachment\":" + metadata.toString().dropLast(1) + ",\"data_base64\":\"")
                val encoder = Base64.getEncoder().wrap(NonClosingOutputStream(output))
                attachment.file.inputStream().use { input -> input.copyTo(encoder) }
                encoder.close()
                output.writeUtf8("\"}}")
            }
        }
        output.writeUtf8("]}")
    }

    private const val TASK_JSON_OVERHEAD = 1_024L
    private const val ATTACHMENT_JSON_OVERHEAD = 1_024L
}

internal class MaxSizeOutputStream(output: OutputStream, private val maxBytes: Long) : FilterOutputStream(output) {
    private var written = 0L

    override fun write(value: Int) {
        checkCapacity(1)
        out.write(value)
        written++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        checkCapacity(length.toLong())
        out.write(buffer, offset, length)
        written += length
    }

    private fun checkCapacity(additional: Long) {
        require(written + additional <= maxBytes) { "Файл запроса превысил безопасный лимит размера" }
    }
}

private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    override fun close() = flush()
}

private fun OutputStream.writeUtf8(value: String) = write(value.toByteArray(Charsets.UTF_8))
