package com.mayushi.trailsense.exchange

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.WorkManager
import com.mayushi.trailsense.data.*
import com.mayushi.trailsense.network.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BatchResponseImportTest {
    @Test fun multipleRequestsImportAtomicallyAndReplayDoesNotRewrite() = exercise("success")
    @Test fun missingManifestDoesNotImportEarlierValidRequest() = exercise("manifest")
    @Test fun changedQuestionnaireDoesNotPartiallyImportBatch() = exercise("stale")
    @Test fun wrongHashDoesNotPartiallyImportBatch() = exercise("hash")
    @Test fun partialRequestDoesNotPartiallyImportBatch() = exercise("partial")

    private fun exercise(mode: String) = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        val db = Room.inMemoryDatabaseBuilder(context, TrailDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.trailDao()
            val prefs = AppPreferences(context).apply { directHermesEnabled = false; sleepNormHours = 8.0 }
            val exchange = TelegramExchangeManager(context, db, prefs)
            val manager = AiAnalysisManager(context, dao, prefs)
            val originals = (1..2).map { n ->
                val activity = ActivityEntity(title = "Synthetic hike $n", sourceFileName = "synthetic", sourceFormat = "SYNTHETIC",
                    trackEncoded = "", distanceM = 1000.0, elevationGainM = 100.0, elevationLossM = 100.0,
                    comment = "Synthetic questionnaire", updatedAt = 100L + n)
                val id = dao.insertActivity(activity)
                dao.activity(id)!!
            }
            val responses = originals.map { activity ->
                assertTrue(manager.enqueue("ACTIVITY", activity.id, "POST_HIKE", AnalysisPayloadBuilder.activity(activity, emptyList(), 8.0, null)))
                val request = JSONObject(exchange.createRequestFile(activityId = activity.id).file.readText())
                val task = request.getJSONArray("tasks").getJSONObject(0)
                JSONObject().put("protocol", TelegramExchangeProtocol.PROTOCOL).put("schema_version", 1)
                    .put("direction", "analysis_response").put("request_id", request.getString("request_id"))
                    .put("results", JSONArray().put(JSONObject().put("job_id", task.getLong("job_id"))
                        .put("input_hash", task.getString("input_hash")).put("status", "ok")
                        .put("response", JSONObject().put("summary", "Synthetic analysis ${activity.id}")
                            .put("route_facts", JSONObject.NULL).put("activity_facts", JSONObject.NULL))))
            }
            val beforeJobs = dao.pendingAiJobs()
            when (mode) {
                "manifest" -> responses[1].put("request_id", "00000000-0000-4000-8000-000000000000")
                "stale" -> dao.updateActivity(originals[1].copy(comment = "Changed synthetic answer", updatedAt = 999))
                "hash" -> responses[1].getJSONArray("results").getJSONObject(0).put("input_hash", "0".repeat(64))
                "partial" -> responses[1].put("results", JSONArray())
            }
            val expectedActivities = originals.map { dao.activity(it.id) }
            val batch = JSONObject().put("protocol", TelegramExchangeProtocol.PROTOCOL).put("schema_version", 2)
                .put("direction", "analysis_response_batch").put("responses", JSONArray(responses))
            val file = File(context.cacheDir, "synthetic-batch.json").apply { writeText(batch.toString()) }
            if (mode == "success") {
                assertEquals(TelegramImportSummary(2, 0), exchange.importResponse(Uri.fromFile(file)))
                val caches = beforeJobs.map { dao.aiCache("${it.entityType}:${it.entityId}:${it.analysisKind}:${it.inputHash}") }
                assertTrue(caches.all { it != null })
                assertEquals(TelegramImportSummary(0, 2), exchange.importResponse(Uri.fromFile(file)))
                assertEquals(caches, beforeJobs.map { dao.aiCache("${it.entityType}:${it.entityId}:${it.analysisKind}:${it.inputHash}") })
                assertTrue(dao.pendingAiJobs().isEmpty())
            } else {
                assertTrue("Invalid batch must fail: $mode", runCatching { exchange.importResponse(Uri.fromFile(file)) }.isFailure)
                assertEquals(beforeJobs, dao.pendingAiJobs())
                assertTrue(beforeJobs.all { dao.aiCache("${it.entityType}:${it.entityId}:${it.analysisKind}:${it.inputHash}") == null })
            }
            assertEquals(expectedActivities, originals.map { dao.activity(it.id) })
        } finally { db.close() }
    }

    @Test fun editorialInstructionsDoNotChangeInputIdentity() {
        assertEquals("5", AnalysisPrompt.VERSION)
        val input = """{"algorithm_version":"5","synthetic":true}"""
        val before = AnalysisInputHash.calculate(input, null, null).inputHash
        val request = TelegramExchangeProtocol.buildRequest("synthetic", 1, "test", AnalysisPrompt.INSTRUCTIONS,
            listOf(ExchangeRequestTask(AiJobEntity(id = 1, entityType = "ACTIVITY", entityId = 1,
                analysisKind = "POST_HIKE", inputJson = input, inputHash = before))))
        val task = JSONObject(request).getJSONArray("tasks").getJSONObject(0)
        assertEquals(before, task.getString("input_hash"))
        assertEquals(JSONObject(input).toString(), task.getJSONObject("input").toString())
        assertFalse(AnalysisPrompt.INSTRUCTIONS.contains("## Факт и ориентир"))
        assertTrue(AnalysisPrompt.INSTRUCTIONS.contains("без списка всех отдельных дат"))
        assertTrue(AnalysisPrompt.INSTRUCTIONS.contains("не переводи в часы"))
        assertTrue(AnalysisPrompt.INSTRUCTIONS.contains("исторический поиск погоды не выполняй"))
    }
}
