package com.mayushi.trailsense.exchange

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.WorkManager
import com.mayushi.trailsense.data.*
import com.mayushi.trailsense.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
class AnalysisFreshness041Test {
    private class Fixture {
        val context: Context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, TrailDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.trailDao()
        val prefs = AppPreferences(context).apply { directHermesEnabled = false; sleepNormHours = 8.0 }
        val manager = AiAnalysisManager(context, dao, prefs)
        val exchange = TelegramExchangeManager(context, db, prefs)
        init { runCatching { WorkManager.initialize(context, Configuration.Builder().build()) } }
        suspend fun activity(n: Int = 1): ActivityEntity {
            val id = dao.insertActivity(ActivityEntity(title = "Synthetic $n", sourceFileName = "synthetic", sourceFormat = "SYNTHETIC",
                trackEncoded = "[]", distanceM = 1000.0, elevationGainM = 100.0, elevationLossM = 100.0, updatedAt = n.toLong()))
            return dao.activity(id)!!
        }
        fun payload(a: ActivityEntity) = AnalysisPayloadBuilder.activity(a, emptyList(), 8.0, null)
        suspend fun enqueue(a: ActivityEntity) = manager.enqueue("ACTIVITY", a.id, "POST_HIKE", payload(a))
        suspend fun request() = JSONObject(exchange.createRequestFile().file.readText())
        fun response(request: JSONObject, time: Long? = null, text: String = "Synthetic analysis"): JSONObject {
            val tasks = request.getJSONArray("tasks")
            return JSONObject().put("protocol", TelegramExchangeProtocol.PROTOCOL).put("schema_version", 1)
                .put("direction", "analysis_response").put("request_id", request.getString("request_id"))
                .put("results", JSONArray().apply {
                    for (i in 0 until tasks.length()) {
                        val task = tasks.getJSONObject(i)
                        put(JSONObject().put("job_id", task.getLong("job_id")).put("input_hash", task.getString("input_hash"))
                            .put("status", "ok").put("response", JSONObject().put("summary", text)
                                .put("route_facts", JSONObject.NULL).put("activity_facts", JSONObject.NULL)).apply {
                                time?.let { put("analysis_created_at_ms", it) }
                            })
                    }
                })
        }
        suspend fun import(json: JSONObject): TelegramImportSummary {
            val file = File(context.cacheDir, "synthetic-freshness.json").apply { writeText(json.toString()) }
            return exchange.importResponse(Uri.fromFile(file))
        }
        suspend fun result(job: AiJobEntity) = dao.analysisResult(AnalysisResultFreshness.key(job))!!
    }
    private fun test(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { fixture.block() } finally { fixture.db.close() }
    }

    @Test fun newerResultReplacesCompletedJobOlderAndReplayDoNotWrite() = test {
        val a = activity(); assertTrue(enqueue(a)); val job = dao.pendingAiJobs().single(); val req = request()
        val t = req.getLong("created_at_ms")
        assertEquals(TelegramImportSummary(1, 0), import(response(req, t + 20, "First")))
        assertEquals(TelegramImportSummary(1, 0), import(response(req, t + 30, "Newer")))
        val newest = result(job)
        assertTrue(newest.responseJson.contains("Newer"))
        val cache = dao.aiCache("ACTIVITY:${a.id}:POST_HIKE:${job.inputHash}")!!
        assertEquals(t + 30, cache.createdAt)
        assertEquals(TelegramImportSummary(0, 1), import(response(req, t + 10, "Older")))
        assertEquals(TelegramImportSummary(0, 1), import(response(req, t + 30, "Conflicting same time")))
        assertEquals(newest, result(job))
        assertEquals(cache, dao.aiCache(cache.cacheKey))
        assertEquals(a, dao.activity(a.id))
    }

    @Test fun legacyResponseImportsOnceButCannotReplaceCompletedResult() = test {
        val a = activity(); enqueue(a); val job = dao.pendingAiJobs().single(); val req = request()
        assertEquals(TelegramImportSummary(1, 0), import(response(req)))
        assertEquals(0L, result(job).analysisCreatedAtMs)
        val before = result(job)
        assertEquals(TelegramImportSummary(0, 1), import(response(req, text = "Changed legacy")))
        assertEquals(TelegramImportSummary(0, 1), import(response(req, req.getLong("created_at_ms") + 100, "Unknown ordering")))
        assertEquals(before, result(job))
    }

    @Test fun editedQuestionnaireRejectsEvenNewerCompletedJobResponse() = test {
        val a = activity(); enqueue(a); val job = dao.pendingAiJobs().single(); val req = request(); val t = req.getLong("created_at_ms")
        import(response(req, t + 10)); val before = result(job)
        dao.updateActivity(a.copy(comment = "Edited synthetic", updatedAt = 500))
        assertTrue(runCatching { import(response(req, t + 1000)) }.isFailure)
        assertEquals(before, result(job))
    }

    @Test fun pendingQueueUpdatesSameIdAndRevisionAndRejectsOldExport() = test {
        val a = activity(); assertTrue(enqueue(a)); val first = dao.pendingAiJobs().single(); val req = request()
        assertFalse(enqueue(a)); assertEquals(first, dao.pendingAiJobs().single())
        val changed = a.copy(comment = "Edited synthetic", updatedAt = 200)
        dao.updateActivity(changed); assertTrue(enqueue(changed))
        val next = dao.pendingAiJobs().single()
        assertEquals(first.id, next.id); assertEquals(first.createdAt, next.createdAt)
        assertEquals(first.inputRevision + 1, next.inputRevision); assertNotEquals(first.inputHash, next.inputHash)
        assertTrue(runCatching { import(response(req, req.getLong("created_at_ms") + 100)) }.isFailure)
        assertEquals(next, dao.pendingAiJobs().single())
        assertEquals(TelegramImportSummary(1, 0), import(response(request())))
    }

    @Test fun hashReturningToOldValueDoesNotReviveExportedRevision() = test {
        val a = activity(); enqueue(a); val old = dao.pendingAiJobs().single(); val req = request()
        dao.enqueuePendingAiJob(old.copy(inputJson = "{}", inputHash = "1".repeat(64)))
        dao.enqueuePendingAiJob(old)
        val current = dao.pendingAiJobs().single()
        assertEquals(old.id, current.id); assertEquals(old.inputHash, current.inputHash)
        assertEquals(old.inputRevision + 2, current.inputRevision)
        assertTrue(runCatching { import(response(req)) }.isFailure)
        assertEquals(current, dao.pendingAiJobs().single())
    }

    @Test fun newerInputRevisionWinsEvenIfAnalysisClockIsEarlier() = test {
        val a = activity(); enqueue(a); val first = dao.pendingAiJobs().single(); val req = request()
        import(response(req, 1000)); val previous = result(first)
        val changed = a.copy(comment = "New context", updatedAt = 200)
        dao.updateActivity(changed); assertTrue(enqueue(changed)); val second = dao.pendingAiJobs().single()
        assertTrue(second.inputRevision > first.inputRevision)
        import(response(request(), 500, "Revised input"))
        val current = result(second); assertEquals(500L, current.analysisCreatedAtMs)
        assertEquals("Revised input", dao.observeLatestAi("ACTIVITY", a.id, "POST_HIKE").first()!!.response)
        assertTrue(current.inputRevision > previous.inputRevision)
        assertEquals(TelegramImportSummary(0, 1), import(response(req, 9999999, "Late older input")))
        assertEquals(current, result(second))
    }

    @Test fun comparisonHasOneJobCanonicalSetIdentityAndSeparateSets() = test {
        val a = activity(1); val b = activity(2); val c = activity(3)
        fun recording(v: ActivityEntity) = ComparisonAnalysis.Recording(payload(v), 0, 10, 3600)
        assertTrue(manager.enqueueComparison("Synthetic route", true, listOf(recording(a), recording(b))))
        val first = dao.pendingAiJobs().single()
        assertEquals(ComparisonAnalysis.TYPE, first.entityType)
        assertFalse(manager.enqueueComparison("Synthetic route", true, listOf(recording(b), recording(a))))
        assertTrue(manager.enqueueComparison("Synthetic route", true, listOf(recording(a), recording(c))))
        assertEquals(2, dao.pendingAiJobs().size)
        val req = request(); assertEquals(2, req.getJSONArray("tasks").length())
        assertEquals(TelegramImportSummary(2, 0), import(response(req, 100)))
        val results = dao.observeAnalysisResults(ComparisonAnalysis.TYPE, 0, ComparisonAnalysis.KIND).first()
        assertEquals(2, results.size); assertEquals(2, results.map { it.scopeKey }.distinct().size)
        assertEquals(listOf(a, b, c), listOf(a, b, c).map { dao.activity(it.id) })
        assertNotEquals(ComparisonAnalysis.scopeKey(listOf(a.id, b.id), true), ComparisonAnalysis.scopeKey(listOf(a.id, b.id), false))
    }

    @Test fun comparisonChangedMemberRejectsWholeResultAndUnrelatedRecordingIsNotScope() = test {
        val a = activity(1); val b = activity(2); val c = activity(3)
        val rows = listOf(a, b).map { ComparisonAnalysis.Recording(payload(it), 0, 10, 300) }
        manager.enqueueComparison("Synthetic route", false, rows); val req = request()
        dao.updateActivity(c.copy(comment = "Unrelated edit", updatedAt = 700))
        assertEquals(TelegramImportSummary(1, 0), import(response(req, 100)))
        val job = dao.aiJob(req.getJSONArray("tasks").getJSONObject(0).getLong("job_id"))!!
        val before = result(job)
        dao.updateActivity(b.copy(comment = "Selected member edit", updatedAt = 900))
        assertTrue(runCatching { import(response(req, 200)) }.isFailure)
        assertEquals(before, result(job))
    }

    @Test fun newTrackNotesStayEmptyAndReplacementPreservesExistingNotes() = test {
        val file = File(context.cacheDir, "synthetic-notes.gpx").apply {
            writeText("""<gpx version="1.1"><trk><name>Synthetic track</name><trkseg><trkpt lat="0" lon="0"><ele>0</ele><time>2020-01-01T00:00:00Z</time></trkpt><trkpt lat="0.01" lon="0"><ele>100</ele><time>2020-01-01T01:00:00Z</time></trkpt></trkseg></trk></gpx>""")
        }
        val repo = TrailRepository(dao, context.contentResolver, context.filesDir)
        val id = repo.importActivityFile(Uri.fromFile(file))
        val imported = dao.activity(id)!!
        assertEquals("", imported.comment)
        val routeId = dao.insertRoute(RouteEntity(title = "Synthetic route", trackEncoded = imported.trackEncoded))
        val existing = imported.copy(routeId = routeId, comment = " Exact existing notes\nDo not delete ")
        dao.updateActivity(existing)
        repo.importActivityFile(Uri.fromFile(file), routeId, replaceForRoute = true)
        assertEquals(existing.comment, dao.activity(id)!!.comment)
        val suggestedId = repo.importActivityFile(Uri.fromFile(file))
        assertEquals("", dao.activity(suggestedId)!!.comment)
        assertEquals(routeId, dao.activity(suggestedId)!!.suggestedRouteId)
    }

    @Test fun screenshotImportDoesNotInsertServiceTextIntoPersonalNotes() = test {
        val image = File(context.cacheDir, "synthetic-notes.png")
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val repo = TrailRepository(dao, context.contentResolver, context.filesDir)
        val (id, _) = repo.importHuaweiScreenshot(Uri.fromFile(image))
        assertEquals("", dao.activity(id)!!.comment)
    }

    @Test fun repositoryRefreshesExistingJobWithoutManualEnqueueOrNewJobs() = test {
        val refresh = PendingAnalysisRefresh(dao, prefs)
        val repo = TrailRepository(dao, context.contentResolver, context.filesDir) { refresh.refresh() }
        val a = activity(); val unrelated = activity(2)
        repo.saveActivity(unrelated.copy(comment = "No automatic analysis"))
        assertTrue(dao.pendingAiJobs().isEmpty())
        enqueue(a); val initial = dao.pendingAiJobs().single(); val exported = request()
        repo.saveActivity(a.copy(comment = "Updated questionnaire"))
        val current = dao.pendingAiJobs().single()
        assertEquals(initial.id, current.id); assertEquals(initial.createdAt, current.createdAt)
        assertEquals(initial.inputRevision + 1, current.inputRevision)
        assertEquals("Updated questionnaire", JSONObject(current.inputJson).getJSONObject("activity").getString("comment"))
        assertTrue(runCatching { import(response(exported, 100)) }.isFailure)
        refresh.refresh(); assertEquals(current, dao.pendingAiJobs().single())
        repo.setHeavyBackpack(a.id, true)
        val backpack = dao.pendingAiJobs().single()
        assertEquals(initial.id, backpack.id); assertEquals(current.inputRevision + 1, backpack.inputRevision)
        assertTrue(JSONObject(backpack.inputJson).getJSONObject("activity").getBoolean("heavy_backpack"))
        repo.saveCompanions(a.id, setOf(dao.insertCompanion(CompanionEntity(name = "Synthetic companion"))))
        assertEquals(1, dao.pendingAiJobs().size)
        assertEquals(1, JSONObject(dao.pendingAiJobs().single().inputJson).getJSONObject("activity").getJSONArray("companions").length())
    }

    @Test fun exportRefreshesStalePendingInPlaceWithoutSuperseding() = test {
        val a = activity(); enqueue(a); val initial = dao.pendingAiJobs().single()
        dao.updateActivity(a.copy(comment = "Changed source", updatedAt = 55))
        val req = request(); val current = dao.pendingAiJobs().single()
        assertEquals(initial.id, current.id); assertEquals(initial.inputRevision + 1, current.inputRevision)
        assertEquals(current.inputHash, req.getJSONArray("tasks").getJSONObject(0).getString("input_hash"))
        assertEquals(TelegramImportSummary(1, 0), import(response(req, 100)))
    }

    @Test fun screenshotFactsAllowNewerSameSourceReplacementButNotLaterUserEdit() = test {
        val a = activity().copy(sourceFormat = "HUAWEI_SCREENSHOT")
        dao.updateActivity(a); enqueue(a); val req = request(); val job = dao.pendingAiJobs().single()
        fun facts(time: Long, distance: Int): JSONObject = response(req, time).apply {
            getJSONArray("results").getJSONObject(0).getJSONObject("response")
                .put("activity_facts", JSONObject().put("title", "Synthetic recognized title").put("distance_m", distance))
        }
        assertEquals(TelegramImportSummary(1, 0), import(facts(100, 1200)))
        assertEquals(1200.0, dao.activity(a.id)!!.distanceM, 0.0)
        assertEquals(TelegramImportSummary(1, 0), import(facts(200, 1300)))
        assertEquals(1300.0, dao.activity(a.id)!!.distanceM, 0.0)
        val before = result(job)
        assertEquals(TelegramImportSummary(0, 1), import(facts(150, 9999)))
        val edited = dao.activity(a.id)!!.let { it.copy(comment = "User edit", updatedAt = it.updatedAt + 1) }
        dao.updateActivity(edited)
        assertTrue(runCatching { import(facts(300, 1500)) }.isFailure)
        assertEquals(edited, dao.activity(a.id)); assertEquals(before, result(job))
    }

    @Test fun routeFactsAllowReplacementOnlyWhilePostMergeSourceIsExact() = test {
        val id = dao.insertRoute(RouteEntity(title = "Synthetic route", updatedAt = 1))
        val route = dao.route(id)!!
        manager.enqueue("ROUTE", id, "SHORT", AnalysisPayloadBuilder.route(route, null, emptyList(), 8.0, null))
        val req = request()
        fun facts(time: Long, distance: Int) = response(req, time).apply {
            getJSONArray("results").getJSONObject(0).getJSONObject("response").put("route_facts", JSONObject().put("distance_m", distance))
        }
        import(facts(100, 1500)); assertEquals(TelegramImportSummary(1, 0), import(facts(200, 1600)))
        val current = dao.route(id)!!; assertEquals(1600.0, current.distanceM!!, 0.0)
        dao.updateRoute(current.copy(notes = "Changed", updatedAt = current.updatedAt + 1))
        assertTrue(runCatching { import(facts(300, 1700)) }.isFailure)
        assertEquals(1600.0, dao.route(id)!!.distanceM!!, 0.0)
    }

    @Test fun deleteComparisonPreservesEverySourceAndPreventsReplay() = test {
        val a = activity(1); val b = activity(2)
        manager.enqueueComparison("Synthetic route", true, listOf(a,b).map { ComparisonAnalysis.Recording(payload(it), 0, 10, 3600) })
        val req = request(); import(response(req, 100)); val jobId = req.getJSONArray("tasks").getJSONObject(0).getLong("job_id")
        val key = AnalysisResultFreshness.key(dao.aiJob(jobId)!!)
        dao.deleteComparisonAnalysis(key)
        assertNull(dao.analysisResult(key)); assertNull(dao.aiJob(jobId))
        assertEquals(listOf(a,b), listOf(a,b).map { dao.activity(it.id) })
        assertTrue(runCatching { import(response(req, 200)) }.isFailure)
    }

    @Test fun sectionMetricsNeverUseWholeRecordingOrInferredGeometry() = test {
        val points = (0..12).map { com.mayushi.trailsense.domain.TrackPoint(0.0, it * .001, it * 10.0, it * 60000L, 100 + it, cadencePerMinute = 60 + it) }
        val a = activity().copy(trackEncoded = com.mayushi.trailsense.domain.TrackCodec.encode(points), averageHeartRateBpm = 190)
        val metrics = ComparisonAnalysis.sectionMetrics(a, 2, 4)
        assertEquals(103.0, metrics.getDouble("average_hr_bpm"), 0.0)
        assertEquals(63.0, metrics.getDouble("average_cadence_per_minute"), 0.0)
        assertEquals(104, metrics.getInt("max_hr_bpm"))
        assertTrue(runCatching { ComparisonAnalysis.sectionMetrics(a.copy(trackEncoded = com.mayushi.trailsense.domain.TrackCodec.encode(points.map { it.copy(inferred = true) })), 2, 4) }.isFailure)
    }

    @Test fun exactSelectedPeriodQueuesOneJobAndRefreshKeepsSameSetMetadataImmutable() = test {
        val refresh = PendingAnalysisRefresh(dao, prefs)
        val repo = TrailRepository(dao, context.contentResolver, context.filesDir) { refresh.refresh() }
        val dates = listOf("2020-01-01", "2020-02-01", "2020-03-01")
        val recordings = dates.mapIndexed { index, date ->
            val started = java.time.LocalDate.parse(date).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val points = (0..12).map { com.mayushi.trailsense.domain.TrackPoint(0.0, it * .001, it * 40.0, started + it * 300000L, 100 + index, cadencePerMinute = 60 + index) }
            val id = dao.insertActivity(ActivityEntity(title = "Synthetic common route", sourceFileName = "synthetic", sourceFormat = "SYNTHETIC",
                startedAt = started, trackEncoded = com.mayushi.trailsense.domain.TrackCodec.encode(points), distanceM = 1000.0, elevationGainM = 480.0, elevationLossM = 0.0, updatedAt = 1))
            dao.activity(id)!!
        }
        val group = com.mayushi.trailsense.domain.AutomaticRepeatRoutes.discover(recordings, emptyList()).single()
        val selected = com.mayushi.trailsense.domain.RepeatChartPeriod.filter(group.rows, "Период", java.time.LocalDate.parse(dates[1]), java.time.LocalDate.parse(dates[2]))
        assertEquals(recordings.drop(1).map { it.id }, selected.map { it.activity.id })
        manager.enqueueComparison(group.title, group.ascent, selected.map { ComparisonAnalysis.Recording(refresh.activityPayload(it.activity), it.start, it.end, it.seconds, ComparisonAnalysis.sectionMetrics(it.activity, it.start, it.end)) })
        val first = dao.pendingAiJobs().single(); val req = request()
        assertEquals(1, req.getJSONArray("tasks").length())
        assertEquals(2, JSONObject(first.inputJson).getInt("recording_count"))
        repo.saveActivity(recordings[1].copy(comment = "Changed selected member"))
        val revised = dao.pendingAiJobs().single()
        assertEquals(first.id, revised.id); assertEquals(first.scopeKey, revised.scopeKey)
        assertEquals(first.inputRevision + 1, revised.inputRevision)
        assertTrue(runCatching { import(response(req, 100)) }.isFailure)
        val currentRequest = request(); import(response(currentRequest, 200))
        val stored = result(revised)
        val metadata = JSONObject(stored.responseJson).getJSONObject("_trailsense_comparison_scope")
        assertEquals(dates[1], metadata.getString("period_start")); assertEquals(dates[2], metadata.getString("period_end"))
        assertEquals(recordings.drop(1).map { it.id }, (0 until metadata.getJSONArray("recordings").length()).map { metadata.getJSONArray("recordings").getJSONObject(it).getLong("id") })
        val edited = dao.activity(recordings[1].id)!!
        repo.saveActivity(edited.copy(comment = "Later source"))
        manager.enqueueComparison(group.title, group.ascent, selected.map { row -> val a = dao.activity(row.activity.id)!!; ComparisonAnalysis.Recording(refresh.activityPayload(a), row.start, row.end, row.seconds) })
        assertEquals(stored, result(revised)) // New pending input cannot relabel an already stored result.
    }

    @Test fun questionnaireRefreshPreservesActualTrimmedBoundsAndGeometryChangeRequiresReselection() = test {
        val refresh=PendingAnalysisRefresh(dao,prefs)
        val repo=TrailRepository(dao,context.contentResolver,context.filesDir) {refresh.refresh()}
        val dates=listOf("2020-02-01","2020-03-01","2020-01-01")
        val recordings=dates.mapIndexed { index,date ->
            val started=java.time.LocalDate.parse(date).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val points=(if(index==2) 1..29 else 0..30).map {com.mayushi.trailsense.domain.TrackPoint(0.0,it*.0002,it*20.0,started+it*60000L,100+it,cadencePerMinute=60+it)}
            val id=dao.insertActivity(ActivityEntity(title="Synthetic offset ascent",sourceFileName="synthetic",sourceFormat="SYNTHETIC",startedAt=started,trackEncoded=com.mayushi.trailsense.domain.TrackCodec.encode(points),distanceM=660.0,elevationGainM=600.0,elevationLossM=0.0,updatedAt=1))
            dao.activity(id)!!
        }
        val group=com.mayushi.trailsense.domain.AutomaticRepeatRoutes.discover(recordings,emptyList()).single()
        val selected=com.mayushi.trailsense.domain.RepeatChartPeriod.filter(group.rows,"Период",java.time.LocalDate.parse(dates[0]),java.time.LocalDate.parse(dates[1]))
        assertEquals(2,selected.size);selected.forEach {assertEquals(1,it.start);assertEquals(29,it.end);assertEquals(1680L,it.seconds)}
        val expanded=com.mayushi.trailsense.domain.AutomaticRepeatRoutes.discover(recordings.take(2),emptyList()).single()
        expanded.rows.forEach {assertEquals(0,it.start);assertEquals(30,it.end);assertEquals(1800L,it.seconds)}
        manager.enqueueComparison(group.title,true,selected.map {ComparisonAnalysis.Recording(refresh.activityPayload(it.activity),it.start,it.end,it.seconds,ComparisonAnalysis.sectionMetrics(it.activity,it.start,it.end))})
        val first=dao.pendingAiJobs().single();val old=request()
        repo.saveActivity(recordings[0].copy(comment="Edited notes",questionnaireHistory=com.mayushi.trailsense.domain.WeatherContext(setOf("Холодно")).save("")))
        val revised=dao.pendingAiJobs().single();assertEquals(first.id,revised.id);assertEquals(first.createdAt,revised.createdAt);assertEquals(first.scopeKey,revised.scopeKey);assertEquals(first.inputRevision+1,revised.inputRevision)
        val before=JSONObject(first.inputJson).getJSONArray("recordings");val after=JSONObject(revised.inputJson).getJSONArray("recordings")
        for(i in 0 until after.length()) {val a=after.getJSONObject(i);val b=before.getJSONObject(i);for(k in listOf("start_index","end_index","recorded_seconds","section_metrics")) assertEquals(b.get(k).toString(),a.get(k).toString())}
        assertTrue(runCatching {import(response(old,100))}.isFailure)
        import(response(request(),200));val metadata=JSONObject(result(revised).responseJson).getJSONObject("_trailsense_comparison_scope").getJSONArray("recordings")
        for(i in 0 until metadata.length()) {assertEquals(1,metadata.getJSONObject(i).getInt("start_index"));assertEquals(29,metadata.getJSONObject(i).getInt("end_index"))}
        manager.enqueueComparison(group.title,true,selected.map {val a=dao.activity(it.activity.id)!!;ComparisonAnalysis.Recording(refresh.activityPayload(a),it.start,it.end,it.seconds)})
        val pending=dao.pendingAiJobs().single();val current=dao.activity(recordings[0].id)!!
        dao.updateActivity(current.copy(trackEncoded=com.mayushi.trailsense.domain.TrackCodec.encode(emptyList()),updatedAt=current.updatedAt+1))
        refresh.refresh();val blocked=dao.aiJob(pending.id)!!;assertEquals(pending.inputJson,blocked.inputJson);assertNotNull(blocked.lastError);assertTrue(runCatching {AiJobFreshness.requireCurrent(dao,prefs,blocked)}.isFailure)
    }

    @Test fun deletingOngoingContextRejectsOldSingleAndComparisonEvenWhenMaxRevisionUnchanged() = test {
        val day=java.time.LocalDate.now().minusDays(10).toEpochDay()
        val start=java.time.LocalDate.ofEpochDay(day).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val ongoing=CalendarContextEntity(startEpochDay=day-20,endEpochDay=day-20,ongoing=true,type="NOTE",note="Synthetic ongoing",updatedAt=100)
        val oid=dao.insertCalendarContext(ongoing)
        val survivor=CalendarContextEntity(startEpochDay=day-5,endEpochDay=day+5,type="NOTE",note="Synthetic survivor",updatedAt=200)
        dao.insertCalendarContext(survivor)
        val refresh=PendingAnalysisRefresh(dao,prefs)
        val points=(0..30).map {com.mayushi.trailsense.domain.TrackPoint(0.0,it*.0002,it*20.0,start+it*60000L)}
        val recordings=(1..2).map {n ->val a=activity(n);dao.updateActivity(a.copy(startedAt=start,trackEncoded=com.mayushi.trailsense.domain.TrackCodec.encode(points)));dao.activity(a.id)!!}
        manager.enqueue("ACTIVITY",recordings[0].id,"POST_HIKE",refresh.activityPayload(recordings[0]))
        manager.enqueueComparison("Synthetic",true,recordings.map {ComparisonAnalysis.Recording(refresh.activityPayload(it),1,29,1680)})
        val old=request();val before=dao.pendingAiJobs();assertEquals(200L,dao.calendarContextRevisionForDay(day))
        // Exact MAX collision, independent of repository timestamp touching.
        dao.deleteCalendarContext(ongoing.copy(id=oid));assertEquals(200L,dao.calendarContextRevisionForDay(day))
        before.forEach {assertTrue(runCatching {AiJobFreshness.requireCurrent(dao,prefs,it)}.isFailure)}
        refresh.refresh()
        for(job in before) {val revised=dao.aiJob(job.id)!!;assertEquals(job.inputRevision+1,revised.inputRevision);assertEquals(job.createdAt,revised.createdAt);assertFalse(revised.inputJson.contains("Synthetic ongoing"))}
        assertTrue(runCatching {import(response(old,100))}.isFailure)
        val single=dao.pendingAiJobs().single {it.entityType=="ACTIVITY"}
        val survivorRow=dao.calendarContextsForDay(day).single()
        val repo=TrailRepository(dao,context.contentResolver,context.filesDir) {refresh.refresh()}
        repo.deleteCalendarContext(survivorRow)
        assertEquals(0,JSONObject(dao.aiJob(single.id)!!.inputJson).getJSONArray("calendar_context").length())
        // Move an ongoing context away; no surviving MAX row can hide the deletion from either job.
        val movedId=dao.insertCalendarContext(ongoing)
        refresh.refresh();val withContext=dao.pendingAiJobs()
        repo.saveCalendarContext(ongoing.copy(id=movedId,startEpochDay=day+1,endEpochDay=day+1))
        for(job in withContext) {val revised=dao.aiJob(job.id)!!;assertEquals(job.inputRevision+1,revised.inputRevision);assertFalse(revised.inputJson.contains("Synthetic ongoing"))}
    }

    @Test fun weatherPersistsMultiConditionsAndClearWithoutTouchingExistingAudit() = test {
        val history = "generic prior audit"
        val weather = com.mayushi.trailsense.domain.WeatherContext(setOf("Жарко", "Холодно"), setOf("Солнечно", "Дождь"), -2.5)
        val saved = weather.save(history)
        assertTrue(saved.startsWith(history)); assertEquals(weather, com.mayushi.trailsense.domain.WeatherContext.read(saved))
        assertEquals(saved, weather.save(saved))
        assertEquals(26.0, com.mayushi.trailsense.domain.WeatherContext.temperature("26")!!, 0.0)
        assertEquals(-2.5, com.mayushi.trailsense.domain.WeatherContext.temperature("-2,5")!!, 0.0)
        assertNull(com.mayushi.trailsense.domain.WeatherContext.temperature(""))
        assertTrue(runCatching { com.mayushi.trailsense.domain.WeatherContext.temperature("-") }.isFailure)
        val cleared = com.mayushi.trailsense.domain.WeatherContext().save(saved)
        assertEquals(com.mayushi.trailsense.domain.WeatherContext(), com.mayushi.trailsense.domain.WeatherContext.read(cleared))
        val a = activity().copy(questionnaireHistory = saved)
        assertEquals(weather.json().toString(), payload(a).getJSONObject("activity").getJSONObject("weather_context").toString())
    }

    @Test fun everyLateCallbackIsRevisionGuardedAcrossABA() = test {
        val a = activity(); enqueue(a); val first = dao.pendingAiJobs().single()
        dao.enqueuePendingAiJob(first.copy(inputHash = "b".repeat(64), inputJson = "{}"))
        dao.enqueuePendingAiJob(first)
        val latest = dao.pendingAiJobs().single()
        assertEquals(first.inputRevision + 2, latest.inputRevision)
        assertEquals(0, dao.recordPendingAiJobFailure(first.id, first.inputHash, "FAILED", "Late HTTP failure", 99, first.inputRevision))
        assertEquals(0, dao.recordPendingAiJobFailure(first.id, first.inputHash, "PENDING", "Late missing credentials", 99, first.inputRevision))
        assertEquals(0, dao.supersedePendingAiJob(first.id, first.inputHash, "Late stale check", 99, first.inputRevision))
        assertEquals(0, dao.claimPendingAiJob(first.id, first.inputHash, "APPLYING_DIRECT", 99, first.inputRevision))
        assertEquals(latest, dao.aiJob(first.id))
        assertEquals(1, dao.claimPendingAiJob(latest.id, latest.inputHash, "APPLYING_DIRECT", 100, latest.inputRevision))
        assertEquals(0, dao.finishClaimedAiJob(first.id, first.inputHash, "APPLYING_DIRECT", 101, first.inputRevision))
        assertEquals(1, dao.finishClaimedAiJob(latest.id, latest.inputHash, "APPLYING_DIRECT", 101, latest.inputRevision))
    }

    @Test fun explicitRegenerationBypassesOldStyleCacheWithoutRewritingOldExport() = test {
        val a = activity(); enqueue(a); val old = dao.pendingAiJobs().single(); val req = request()
        import(response(req, 100, "Old cached style"))
        assertTrue(enqueue(a))
        val current = dao.pendingAiJobs().single()
        assertNotEquals(old.id, current.id); assertEquals(old.inputHash, current.inputHash); assertEquals(old.inputJson, current.inputJson)
        assertTrue(current.inputRevision > old.inputRevision)
        assertFalse(enqueue(a)); assertEquals(current, dao.pendingAiJobs().single())
        assertEquals(AnalysisPrompt.INSTRUCTIONS, request().getString("analysis_instructions"))
        import(response(request(), 200, "New style"))
        assertEquals(TelegramImportSummary(0, 1), import(response(req, 999999, "Late old style")))
        assertEquals("New style", dao.observeLatestAi("ACTIVITY", a.id, "POST_HIKE").first()!!.response)
        assertEquals(old.inputJson, dao.aiJob(old.id)!!.inputJson)
    }

    @Test fun deletingAnEventRemovesOwnedDurableAndIntersectingComparisonsOnly() = test {
        val routeId = dao.insertRoute(RouteEntity(title = "Synthetic deleted route"))
        val a = activity().copy(routeId = routeId); dao.updateActivity(a)
        val b = activity(2); val c = activity(3)
        enqueue(a); enqueue(b)
        fun recording(v: ActivityEntity) = ComparisonAnalysis.Recording(payload(v), 0, 10, 3600)
        manager.enqueueComparison("Synthetic common route", true, listOf(recording(a), recording(b)))
        manager.enqueueComparison("Synthetic surviving route", true, listOf(recording(b), recording(c)))
        import(response(request(), 100))
        val preserved = dao.comparisonResults().single { it.scopeKey == ComparisonAnalysis.scopeKey(listOf(b.id, c.id), true) }
        val bResult = dao.analysisResult("ACTIVITY:${b.id}:POST_HIKE:")
        dao.deleteRouteEvent(routeId)
        assertNull(dao.route(routeId)); assertNull(dao.activity(a.id)); assertNull(dao.analysisResult("ACTIVITY:${a.id}:POST_HIKE:"))
        assertEquals(listOf(preserved), dao.comparisonResults())
        assertEquals(bResult, dao.analysisResult("ACTIVITY:${b.id}:POST_HIKE:"))
        assertEquals(listOf(b,c), listOf(b,c).map { dao.activity(it.id) })
        assertEquals(listOf(preserved.scopeKey), dao.comparisonJobs().map { it.scopeKey })
    }

    @Test fun medicationFactEditWithVersionedPlanRejectsOldResponseAndRefreshesPending() = test {
        val refresh = PendingAnalysisRefresh(dao, prefs)
        val repo = TrailRepository(dao, context.contentResolver, context.filesDir) { refresh.refresh() }
        val date = java.time.LocalDate.of(2020, 1, 1)
        val timestamp = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val a = activity().copy(startedAt = timestamp); dao.updateActivity(a)
        dao.saveVersionedPillPlan(PillPlanEntity(anchorEpochDay = date.toEpochDay(), activeDays = 21, breakDays = 7, updatedAt = 10), date.toEpochDay())
        val versions = dao.pillPlanVersions()
        manager.enqueue("ACTIVITY", a.id, "POST_HIKE", refresh.activityPayload(a))
        val old = dao.pendingAiJobs().single(); val req = request()
        repo.addPillEvent(PillEventEntity(epochDay = date.toEpochDay(), type = "TAKEN", actualTaken = true))
        assertEquals(versions, dao.pillPlanVersions())
        assertTrue(runCatching { AiJobFreshness.requireCurrent(dao, prefs, old) }.isFailure)
        assertTrue(runCatching { import(response(req, 100)) }.isFailure)
        val revised = dao.pendingAiJobs().single(); assertEquals(old.id, revised.id); assertEquals(old.inputRevision + 1, revised.inputRevision)
        assertEquals(1, JSONObject(revised.inputJson).getJSONArray("pill_facts_and_deviations").length())
        assertEquals(TelegramImportSummary(1, 0), import(response(request(), 200)))
        repo.addPillEvent(PillEventEntity(epochDay = date.plusDays(1).toEpochDay(), type = "TAKEN", actualTaken = false))
        assertTrue(runCatching { import(response(req, 300)) }.isFailure)
    }

    @Test fun timestampValidationRejectsFractionsStringsNullAndNonpositive() = test {
        val a = activity(); enqueue(a); val req = request()
        for (invalid in listOf<Any>(0, -1, 1.5, "123", JSONObject.NULL)) {
            val json = response(req)
            json.getJSONArray("results").getJSONObject(0).put("analysis_created_at_ms", invalid)
            assertTrue("Must reject $invalid", runCatching { TelegramExchangeProtocol.parseResponseFile(json.toString()) }.isFailure)
        }
        assertEquals(1, dao.pendingAiJobs().size)
    }
}
