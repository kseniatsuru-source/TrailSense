package com.mayushi.trailsense.exchange

import com.mayushi.trailsense.data.AiJobEntity
import com.mayushi.trailsense.network.AnalysisInputHash
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TelegramExchangeProtocolTest {
    private val job = AiJobEntity(
        id = 17,
        entityType = "ROUTE",
        entityId = 4,
        analysisKind = "DEEP_ROUTE",
        inputJson = """{"entity_updated_at":10,"route":{"title":"Phelix","distance_m":15980},"analysis_kind":"DEEP_ROUTE"}""",
        inputHash = "a".repeat(64),
        imagePath = "/private/path/that/must/not/leak.jpg",
        imageMimeType = "image/jpeg",
    )

    @Test fun `request is self describing and embeds attachment digest without local path`() {
        val bytes = byteArrayOf(1, 2, 3)
        val raw = TelegramExchangeProtocol.buildRequest(
            requestId = "request-123",
            createdAtMs = 1234,
            appVersion = "0.1.8",
            instructions = "Верни строгий JSON",
            tasks = listOf(
                ExchangeRequestTask(
                    job = job,
                    attachment = ExchangeAttachment("image/jpeg", "huawei.jpg", bytes),
                )
            ),
        )

        val json = JSONObject(raw)
        assertEquals(TelegramExchangeProtocol.PROTOCOL, json.getString("protocol"))
        assertEquals(TelegramExchangeProtocol.SCHEMA_VERSION, json.getInt("schema_version"))
        assertEquals("analysis_request", json.getString("direction"))
        assertEquals("Верни строгий JSON", json.getString("analysis_instructions"))
        assertEquals("analysis_response", json.getJSONObject("response_contract").getString("direction"))

        val task = json.getJSONArray("tasks").getJSONObject(0)
        assertEquals("Phelix", task.getJSONObject("input").getJSONObject("route").getString("title"))
        val attachment = task.getJSONObject("attachment")
        assertArrayEquals(bytes, Base64.getDecoder().decode(attachment.getString("data_base64")))
        assertEquals(AnalysisInputHash.sha256(bytes), attachment.getString("sha256"))
        assertFalse(raw.contains(job.imagePath!!))
    }

    @Test fun `request and response support more than fifty jobs`() {
        val tasks = (1L..51L).map { id -> ExchangeRequestTask(job.copy(id = id)) }
        val raw = TelegramExchangeProtocol.buildRequest("many", 1, "0.1.8", "analyze", tasks)
        assertEquals(51, JSONObject(raw).getJSONArray("tasks").length())

        val results = (1L..51L).joinToString(",") { id ->
            """{"job_id":$id,"input_hash":"${"a".repeat(64)}","status":"ok","response":{"summary":"ok"}}"""
        }
        val response = """{"protocol":"${TelegramExchangeProtocol.PROTOCOL}","schema_version":${TelegramExchangeProtocol.SCHEMA_VERSION},"direction":"analysis_response","request_id":"many","results":[$results]}"""
        assertEquals(51, TelegramExchangeProtocol.parseResponse(response).results.size)
    }

    @Test fun `manifest rejects partial extra and mismatched results`() {
        val manifest = ExchangeManifest(
            requestId = "request-123",
            tasks = listOf(ExchangeManifestTask(17, "a".repeat(64)), ExchangeManifestTask(18, "b".repeat(64))),
        )
        val partial = ExchangeResponse("request-123", listOf(ExchangeResponseResult(17, "a".repeat(64), "{\"summary\":\"ok\"}", 0, 0)))
        assertFailsWithMessage("Ответ Mayushi неполный") { manifest.validate(partial) }

        val extra = ExchangeResponse("request-123", partial.results + ExchangeResponseResult(19, "c".repeat(64), "{\"summary\":\"ok\"}", 0, 0))
        assertFailsWithMessage("Ответ Mayushi содержит неизвестное задание 19") { manifest.validate(extra) }

        val mismatch = ExchangeResponse("request-123", listOf(
            ExchangeResponseResult(17, "b".repeat(64), "{\"summary\":\"ok\"}", 0, 0),
            ExchangeResponseResult(18, "b".repeat(64), "{\"summary\":\"ok\"}", 0, 0),
        ))
        assertFailsWithMessage("Контрольная сумма задания 17 не совпадает") { manifest.validate(mismatch) }
    }

    @Test fun `manifest canonicalizes result order`() {
        val manifest = ExchangeManifest(
            requestId = "request-123",
            tasks = listOf(ExchangeManifestTask(17, "a".repeat(64)), ExchangeManifestTask(18, "b".repeat(64))),
        )
        val response = ExchangeResponse("request-123", listOf(
            ExchangeResponseResult(18, "b".repeat(64), "{\"summary\":\"second\"}", 0, 0),
            ExchangeResponseResult(17, "a".repeat(64), "{\"summary\":\"first\"}", 0, 0),
        ))

        assertEquals(listOf(17L, 18L), manifest.orderedResults(response).map { it.jobId })
    }

    @Test fun `protocol rejects fractional integer fields`() {
        val fractionalVersion = """{"protocol":"${TelegramExchangeProtocol.PROTOCOL}","schema_version":1.9,"direction":"analysis_response","request_id":"x","results":[]}"""
        assertFailsWithMessage("Версия файла ответа не поддерживается") { TelegramExchangeProtocol.parseResponse(fractionalVersion) }

        val fractionalJob = """{"protocol":"${TelegramExchangeProtocol.PROTOCOL}","schema_version":1,"direction":"analysis_response","request_id":"x","results":[{"job_id":17.9,"input_hash":"${"a".repeat(64)}","status":"ok","response":{"summary":"ok"}}]}"""
        assertFailsWithMessage("В результате нет идентификатора задания") { TelegramExchangeProtocol.parseResponse(fractionalJob) }
    }

    @Test fun `parses valid response and preserves job binding`() {
        val response = """{
          "protocol":"${TelegramExchangeProtocol.PROTOCOL}",
          "schema_version":${TelegramExchangeProtocol.SCHEMA_VERSION},
          "direction":"analysis_response",
          "request_id":"request-123",
          "results":[{
            "job_id":17,
            "input_hash":"${"a".repeat(64)}",
            "status":"ok",
            "response":{"summary":"Маршрут сложный.","route_facts":{"title":"Phelix","gain_m":1312},"activity_facts":null},
            "prompt_tokens":120,
            "completion_tokens":45
          }]
        }"""

        val parsed = TelegramExchangeProtocol.parseResponse(response)
        assertEquals("request-123", parsed.requestId)
        assertEquals(1, parsed.results.size)
        assertEquals(17L, parsed.results.single().jobId)
        assertEquals("a".repeat(64), parsed.results.single().inputHash)
        assertEquals("Маршрут сложный.", JSONObject(parsed.results.single().responseJson).getString("summary"))
        assertEquals(120, parsed.results.single().promptTokens)
        assertEquals(45, parsed.results.single().completionTokens)
    }

    @Test fun `rejects foreign protocol`() {
        val raw = """{"protocol":"other","schema_version":1,"direction":"analysis_response","request_id":"x","results":[]}"""
        assertFailsWithMessage("Это не файл ответа TrailSense") { TelegramExchangeProtocol.parseResponse(raw) }
    }

    @Test fun `rejects duplicate jobs and missing summary`() {
        val item = """{"job_id":17,"input_hash":"${"a".repeat(64)}","status":"ok","response":{"summary":"ok"}}"""
        val duplicate = """{"protocol":"${TelegramExchangeProtocol.PROTOCOL}","schema_version":1,"direction":"analysis_response","request_id":"x","results":[$item,$item]}"""
        assertFailsWithMessage("Ответ содержит повторяющиеся задания") { TelegramExchangeProtocol.parseResponse(duplicate) }

        val missingSummary = """{"protocol":"${TelegramExchangeProtocol.PROTOCOL}","schema_version":1,"direction":"analysis_response","request_id":"x","results":[{"job_id":17,"input_hash":"${"a".repeat(64)}","status":"ok","response":{"route_facts":null}}]}"""
        assertFailsWithMessage("В ответе Mayushi нет текста анализа") { TelegramExchangeProtocol.parseResponse(missingSummary) }
    }

    private fun assertFailsWithMessage(expected: String, block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (error: IllegalArgumentException) {
            assertEquals(expected, error.message)
        }
    }
}
