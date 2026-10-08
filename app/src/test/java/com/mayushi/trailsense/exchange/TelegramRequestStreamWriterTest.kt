package com.mayushi.trailsense.exchange

import com.mayushi.trailsense.data.AiJobEntity
import com.mayushi.trailsense.network.AnalysisInputHash
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

class TelegramRequestStreamWriterTest {
    @Test fun `streams every task and attachment into valid json`() {
        val attachmentBytes = ByteArray(64 * 1024) { (it % 251).toByte() }
        val attachmentFile = File.createTempFile("trailsense-stream", ".jpg").apply { writeBytes(attachmentBytes) }
        try {
            val tasks = (1L..51L).map { id ->
                val job = AiJobEntity(
                    id = id,
                    entityType = "ROUTE",
                    entityId = id,
                    analysisKind = "SHORT_ROUTE",
                    inputJson = """{"entity_updated_at":$id,"route":{"title":"Route $id"}}""",
                    inputHash = "a".repeat(64),
                )
                val attachment = if (id == 1L) PreparedAttachment(
                    file = attachmentFile,
                    mimeType = "image/jpeg",
                    fileName = "attachment-1.jpg",
                    sha256 = AnalysisInputHash.sha256(attachmentFile),
                ) else null
                PreparedTask(job, attachment)
            }
            val envelope = TelegramExchangeProtocol.requestEnvelope("stream-test", 1, "0.1.8", "analyze")
            val estimated = TelegramRequestStreamWriter.estimateBytes(envelope, tasks)
            val output = ByteArrayOutputStream()

            MaxSizeOutputStream(output, estimated).use { TelegramRequestStreamWriter.write(it, envelope, tasks) }

            val raw = output.toString(Charsets.UTF_8.name())
            val json = JSONObject(raw)
            assertEquals(51, json.getJSONArray("tasks").length())
            val embedded = json.getJSONArray("tasks").getJSONObject(0).getJSONObject("attachment")
            assertArrayEquals(attachmentBytes, Base64.getDecoder().decode(embedded.getString("data_base64")))
            assertEquals(AnalysisInputHash.sha256(attachmentBytes), embedded.getString("sha256"))
            assertTrue(output.size().toLong() <= estimated)
        } finally {
            attachmentFile.delete()
        }
    }

    @Test fun `hard output limit aborts writing`() {
        val task = PreparedTask(
            AiJobEntity(
                id = 1,
                entityType = "ROUTE",
                entityId = 1,
                analysisKind = "SHORT_ROUTE",
                inputJson = """{"entity_updated_at":1,"route":{"title":"Route"}}""",
                inputHash = "a".repeat(64),
            ),
            null,
        )
        val envelope = TelegramExchangeProtocol.requestEnvelope("stream-test", 1, "0.1.8", "analyze")
        try {
            MaxSizeOutputStream(ByteArrayOutputStream(), 100).use { TelegramRequestStreamWriter.write(it, envelope, listOf(task)) }
            fail("Expected size limit failure")
        } catch (error: IllegalArgumentException) {
            assertEquals("Файл запроса превысил безопасный лимит размера", error.message)
        }
    }
}
