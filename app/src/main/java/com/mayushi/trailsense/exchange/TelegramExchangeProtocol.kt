package com.mayushi.trailsense.exchange

import com.mayushi.trailsense.data.AiJobEntity
import com.mayushi.trailsense.network.AnalysisInputHash
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

data class ExchangeAttachment(
    val mimeType: String,
    val fileName: String,
    val bytes: ByteArray,
)

data class ExchangeRequestTask(
    val job: AiJobEntity,
    val attachment: ExchangeAttachment? = null,
)

data class ExchangeResponseResult(
    val jobId: Long,
    val inputHash: String,
    val responseJson: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val analysisCreatedAtMs: Long? = null,
)

data class ExchangeResponse(
    val requestId: String,
    val results: List<ExchangeResponseResult>,
)

data class ExchangeManifestTask(
    val jobId: Long,
    val inputHash: String,
    val inputRevision: Long? = null,
)

data class ExchangeManifest(
    val requestId: String,
    val tasks: List<ExchangeManifestTask>,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    fun validate(response: ExchangeResponse) {
        orderedResults(response)
    }

    fun orderedResults(response: ExchangeResponse): List<ExchangeResponseResult> {
        require(response.requestId == requestId) { "Ответ относится к другому запросу TrailSense" }
        val expected = tasks.associate { it.jobId to it.inputHash }
        val received = response.results.associateBy { it.jobId }
        require(received.size == response.results.size) { "Ответ содержит повторяющиеся задания" }
        response.results.forEach { result ->
            val hash = expected[result.jobId]
                ?: throw IllegalArgumentException("Ответ Mayushi содержит неизвестное задание ${result.jobId}")
            require(hash == result.inputHash) { "Контрольная сумма задания ${result.jobId} не совпадает" }
        }
        val missing = expected.keys - received.keys
        require(missing.isEmpty()) { "Ответ Mayushi неполный" }
        return tasks.map { received.getValue(it.jobId) }
    }

    fun toJson(): String {
        val taskArray = JSONArray()
        tasks.forEach { task -> taskArray.put(JSONObject().put("job_id", task.jobId).put("input_hash", task.inputHash).apply {
            task.inputRevision?.let { put("input_revision", it) }
        }) }
        return JSONObject()
            .put("protocol", TelegramExchangeProtocol.PROTOCOL)
            .put("schema_version", TelegramExchangeProtocol.SCHEMA_VERSION)
            .put("request_id", requestId)
            .put("created_at_ms", createdAtMs)
            .put("tasks", taskArray)
            .toString()
    }

    companion object {
        fun fromJson(raw: String): ExchangeManifest {
            val json = try { JSONObject(raw) } catch (_: Exception) { throw IllegalArgumentException("Повреждён внутренний манифест запроса") }
            require(json.optString("protocol") == TelegramExchangeProtocol.PROTOCOL) { "Повреждён внутренний манифест запроса" }
            require(exactJsonInt(json, "schema_version") == TelegramExchangeProtocol.SCHEMA_VERSION) { "Версия внутреннего манифеста не поддерживается" }
            val requestId = json.optString("request_id").trim()
            require(requestId.isNotBlank()) { "Повреждён внутренний манифест запроса" }
            val array = json.optJSONArray("tasks") ?: throw IllegalArgumentException("Повреждён внутренний манифест запроса")
            require(array.length() > 0) { "Повреждён внутренний манифест запроса" }
            val seen = mutableSetOf<Long>()
            val tasks = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: throw IllegalArgumentException("Повреждён внутренний манифест запроса")
                    val jobId = exactJsonLong(item, "job_id") ?: -1
                    val hash = item.optString("input_hash")
                    require(jobId > 0 && seen.add(jobId) && hash.matches(TelegramExchangeProtocol.HASH_REGEX)) { "Повреждён внутренний манифест запроса" }
                    val revision = if (item.has("input_revision")) exactJsonLong(item, "input_revision") else null
                    require(!item.has("input_revision") || (revision != null && revision > 0)) { "Повреждена версия данных задания" }
                    add(ExchangeManifestTask(jobId, hash, revision))
                }
            }
            return ExchangeManifest(requestId, tasks, exactJsonLong(json, "created_at_ms") ?: 0)
        }
    }
}

object TelegramExchangeProtocol {
    const val PROTOCOL = "com.mayushi.trailsense.telegram-exchange"
    const val SCHEMA_VERSION = 1
    internal val HASH_REGEX = Regex("[0-9a-f]{64}")

    fun buildRequest(
        requestId: String,
        createdAtMs: Long,
        appVersion: String,
        instructions: String,
        tasks: List<ExchangeRequestTask>,
    ): String {
        require(requestId.isNotBlank()) { "Не удалось создать идентификатор запроса" }
        require(tasks.isNotEmpty()) { "Очередь анализа пуста" }

        val taskArray = JSONArray()
        tasks.forEach { item ->
            val job = item.job
            require(job.id > 0) { "У задания нет идентификатора" }
            require(job.inputHash.matches(HASH_REGEX)) { "Некорректная контрольная сумма задания" }
            val input = try { JSONObject(job.inputJson) } catch (_: Exception) { throw IllegalArgumentException("Повреждены данные задания ${job.id}") }
            val task = JSONObject()
                .put("job_id", job.id)
                .put("entity_type", job.entityType)
                .put("entity_id", job.entityId)
                .put("analysis_kind", job.analysisKind)
                .put("input_hash", job.inputHash)
                .put("input", input)
            item.attachment?.let { attachment ->
                task.put(
                    "attachment",
                    JSONObject()
                        .put("mime_type", attachment.mimeType)
                        .put("file_name", attachment.fileName)
                        .put("sha256", AnalysisInputHash.sha256(attachment.bytes))
                        .put("encoding", "base64")
                        .put("data_base64", Base64.getEncoder().encodeToString(attachment.bytes)),
                )
            }
            taskArray.put(task)
        }

        return requestEnvelope(requestId, createdAtMs, appVersion, instructions)
            .put("tasks", taskArray)
            .toString(2)
    }

    fun requestEnvelope(requestId: String, createdAtMs: Long, appVersion: String, instructions: String): JSONObject {
        val responseContract = JSONObject()
            .put("file_name", "trailsense-response-$requestId.json")
            .put("protocol", PROTOCOL)
            .put("schema_version", SCHEMA_VERSION)
            .put("direction", "analysis_response")
            .put("request_id", requestId)
            .put("completeness", "Return exactly one result for every task in this request; do not omit or add jobs.")
            .put(
                "required_result_shape",
                JSONObject()
                    .put("job_id", "copy exactly from task")
                    .put("input_hash", "copy exactly from task")
                    .put("status", "ok")
                    .put("analysis_created_at_ms", "positive Unix milliseconds when this analysis was created, not downloaded; required for replacing a completed result")
                    .put("response", JSONObject().put("summary", "analysis text").put("route_facts", JSONObject.NULL).put("activity_facts", JSONObject.NULL))
                    .put("prompt_tokens", 0)
                    .put("completion_tokens", 0),
            )
        return JSONObject()
            .put("protocol", PROTOCOL)
            .put("schema_version", SCHEMA_VERSION)
            .put("direction", "analysis_request")
            .put("request_id", requestId)
            .put("created_at_ms", createdAtMs)
            .put("sender", JSONObject().put("app", "TrailSense").put("version", appVersion))
            .put("purpose", "Analyze every task and return one self-contained JSON response file. No chat context is required.")
            .put("privacy", "Contains only the compact data explicitly queued in TrailSense and an optional user-selected screenshot. It contains no API key or raw GPS track.")
            .put("analysis_instructions", instructions)
            .put("response_contract", responseContract)
    }

    /** Version 2 wraps complete version-1 responses; it never changes a task identity/hash. */
    fun parseResponseFile(raw: String): List<ExchangeResponse> {
        val json = try { JSONObject(raw) } catch (_: Exception) {
            throw IllegalArgumentException("Повреждённый файл ответа TrailSense")
        }
        require(json.optString("protocol") == PROTOCOL) { "Это не файл ответа TrailSense" }
        val version = exactJsonInt(json, "schema_version")
        if (version == SCHEMA_VERSION) return listOf(parseResponse(raw))
        require(version == 2) { "Версия файла ответа не поддерживается" }
        require(json.optString("direction") == "analysis_response_batch") { "Ожидался пакет ответов Mayushi" }
        require(!json.has("results") && !json.has("request_id")) { "Неоднозначный формат пакета ответов" }
        val array = json.optJSONArray("responses") ?: throw IllegalArgumentException("В пакете нет ответов")
        require(array.length() > 0) { "В пакете нет ответов" }
        val requests = mutableSetOf<String>()
        val jobs = mutableSetOf<Long>()
        return (0 until array.length()).map { index ->
            val item = array.optJSONObject(index) ?: throw IllegalArgumentException("Повреждён ответ ${index + 1}")
            val response = parseResponse(item.toString())
            require(requests.add(response.requestId)) { "Пакет содержит повторяющиеся запросы" }
            // The same job may have been exported in several requests. Never choose a winner silently.
            require(response.results.all { jobs.add(it.jobId) }) { "Пакет содержит повторяющиеся задания из разных запросов" }
            response
        }
    }

    fun parseResponse(raw: String): ExchangeResponse {
        val json = try { JSONObject(raw) } catch (_: Exception) { throw IllegalArgumentException("Повреждённый файл ответа TrailSense") }
        require(json.optString("protocol") == PROTOCOL) { "Это не файл ответа TrailSense" }
        require(exactJsonInt(json, "schema_version") == SCHEMA_VERSION) { "Версия файла ответа не поддерживается" }
        require(json.optString("direction") == "analysis_response") { "Ожидался файл ответа Mayushi" }
        val requestId = json.optString("request_id").trim()
        require(requestId.isNotBlank()) { "В ответе нет идентификатора запроса" }
        val array = json.optJSONArray("results") ?: throw IllegalArgumentException("В ответе нет результатов")
        require(array.length() > 0) { "В ответе нет результатов" }

        val seen = mutableSetOf<Long>()
        val results = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: throw IllegalArgumentException("Повреждён результат ${index + 1}")
                val jobId = exactJsonLong(item, "job_id") ?: -1
                require(jobId > 0) { "В результате нет идентификатора задания" }
                require(seen.add(jobId)) { "Ответ содержит повторяющиеся задания" }
                val inputHash = item.optString("input_hash")
                require(inputHash.matches(HASH_REGEX)) { "Некорректная контрольная сумма ответа" }
                require(item.optString("status") == "ok") { "Mayushi не завершила задание $jobId" }
                val response = item.optJSONObject("response") ?: throw IllegalArgumentException("В результате $jobId нет анализа")
                require(response.optString("summary").isNotBlank()) { "В ответе Mayushi нет текста анализа" }
                val promptTokens = tokenCount(item, "prompt_tokens")
                val completionTokens = tokenCount(item, "completion_tokens")
                val createdAt = if (item.has("analysis_created_at_ms")) {
                    val timestamp = exactJsonLong(item, "analysis_created_at_ms")
                    require(timestamp != null && timestamp > 0) { "Некорректное время создания анализа" }
                    timestamp
                } else null
                add(ExchangeResponseResult(jobId, inputHash, response.toString(), promptTokens, completionTokens, createdAt))
            }
        }
        return ExchangeResponse(requestId, results)
    }

    private fun tokenCount(json: JSONObject, name: String): Int {
        if (!json.has(name) || json.isNull(name)) return 0
        val value = json.opt(name) as? Number ?: throw IllegalArgumentException("Некорректные данные о токенах")
        val long = value.toLong()
        require(value.toDouble() == long.toDouble() && long in 0..MAX_TOKEN_COUNT) { "Некорректные данные о токенах" }
        return long.toInt()
    }

    private const val MAX_TOKEN_COUNT = 10_000_000L
}

private fun exactJsonLong(json: JSONObject, name: String): Long? {
    if (!json.has(name) || json.isNull(name)) return null
    val number = json.opt(name) as? Number ?: return null
    return try {
        java.math.BigDecimal(number.toString()).longValueExact()
    } catch (_: ArithmeticException) {
        null
    } catch (_: NumberFormatException) {
        null
    }
}

private fun exactJsonInt(json: JSONObject, name: String): Int? {
    val value = exactJsonLong(json, name) ?: return null
    return value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
}
