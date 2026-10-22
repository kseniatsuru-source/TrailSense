package com.mayushi.trailsense.data

import com.mayushi.trailsense.network.AppPreferences
import android.content.Context
import androidx.room.Room
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
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
@Config(sdk=[28],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HuaweiImport041Test {
    private val context: Context get()=RuntimeEnvironment.getApplication()
    private fun db()=Room.inMemoryDatabaseBuilder(context,TrailDatabase::class.java).allowMainThreadQueries().build()
    private fun workout(id: String="w1",start: Long=1704103200000L) = JSONObject().put("sourceId",id).put("deviceId","synthetic").put("subType",282).put("sportType",282)
        .put("startTimeMs",start).put("endTimeMs",start+3600000).put("sourceOffset","+0000").put("distanceM",5000).put("gainDm",1234).put("lossDm",4321).put("totalTimeMs",3500000)
        .put("avgHrBpm",123).put("steps",6000).put("paceSecondsPerKm",700).put("raw",JSONObject().put("source","synthetic").put("unconfirmedRecovery",123))
    private fun sleep(id: String="s1",start: Long=1704067200000L,end: Long=1704092400000L,key: String="PROFESSIONAL_SLEEP_SHALLOW") = JSONObject().put("sourceId",id).put("deviceId","synthetic")
        .put("startTimeMs",start).put("endTimeMs",end).put("sourceOffset","+0000").put("raw",JSONObject().put("sleepScore",90))
        .put("stages",JSONArray().put(JSONObject().put("startTimeMs",start).put("endTimeMs",end).put("key",key)))
    private fun file(w: List<JSONObject>,s: List<JSONObject> = emptyList()): File = File.createTempFile("synthetic-huawei-",".json",context.cacheDir).apply {writeText(JSONObject().put("protocol","trailsense-huawei").put("schemaVersion",1).put("workouts",JSONArray(w)).put("sleeps",JSONArray(s)).toString())}
    private fun original(id: Long=1,start: Long=1704103200000L)=ActivityEntity(id=id,routeId=1,title="Synthetic recording",sourceFileName="synthetic.tcx",sourceFormat="TCX",trackEncoded="[]",originalTrackEncoded="[]",startedAt=start,importedStartedAt=start,distanceM=5000.0,elevationGainM=120.0,elevationLossM=80.0,elapsedSeconds=3500,comment=" exact personal synthetic note\n",sleepHours=8.0,sleepQuality=3,feltRested=false,descentEffort=4,originalMetricsJson="{\"distanceM\":5000,\"elapsedSeconds\":3500}",questionnaireHistory="synthetic-history",trackCorrectionMode="TRIMMED",createdAt=1,updatedAt=1)
    @Test fun databasePreviewEnrichesPreservingEveryUnrelatedFieldAndReplay()=runBlocking {
        db().useRoom {db -> val dao=db.trailDao();val r=RouteEntity(id=1,title="Synthetic route",sourceUrl="https://example.invalid/synthetic",notes="exact route note")
            dao.insertRoute(r);val original=original();dao.insertActivity(original)
            val f=file(listOf(workout()),listOf(sleep()));val importer=HuaweiImporter(db);val p=importer.preview(f)
            assertEquals(1,p.count("ENRICH"));assertEquals(1,p.count("NEW_SLEEP"));assertEquals(0,p.count("CONFLICT"));assertEquals(2,importer.import(f,p,emptyMap()))
            val saved=dao.activity(1)!!;assertEquals(original,saved.copy(huaweiFactsJson="",updatedAt=1));assertEquals(r,dao.route(1))
            val facts=JSONObject(saved.huaweiFactsJson);assertEquals(123.4,facts.getDouble("gainM"),0.001);assertEquals(432.1,facts.getDouble("lossM"),0.001)
            assertEquals(25200000L,facts.getJSONObject("sleep").getLong("durationMs"));assertFalse(facts.has("sleepScore"));assertFalse(facts.has("recoveryHours"))
            val again=importer.preview(f);assertEquals(2,again.count("UNCHANGED"));assertEquals(0,importer.import(f,again,emptyMap()));assertEquals(saved,dao.activity(1));assertTrue(dao.pendingAiJobs().isEmpty())
        }
    }
    @Test fun newArchiveHasNoInventedTimeQuestionnaireOrNotes()=runBlocking {db().useRoom {db ->val f=file(listOf(workout()));val i=HuaweiImporter(db);i.import(f,i.preview(f),emptyMap());val a=db.trailDao().huaweiActivityIndex().single();assertNull(a.elapsedSeconds);assertNull(a.movingSeconds);assertNull(a.sleepHours);assertNull(a.feltRested);assertEquals("",a.comment);assertEquals(com.mayushi.trailsense.domain.TrackCodec.encode(emptyList()),a.trackEncoded);assertEquals(3500000L,JSONObject(a.huaweiFactsJson).getLong("totalTimeMs"))}}
    @Test fun conflictingMatchesRequireExplicitChoiceAndStalePreviewRefused()=runBlocking {db().useRoom {db ->val d=db.trailDao();d.insertRoute(RouteEntity(id=1,title="Synthetic"));d.insertActivity(original());d.insertActivity(original(2));val f=file(listOf(workout()));val i=HuaweiImporter(db);val p=i.preview(f);assertEquals(1,p.count("CONFLICT"));assertTrue(runCatching {i.import(f,p,emptyMap())}.isFailure);assertTrue(d.huaweiSources().isEmpty());d.updateActivity(original().copy(updatedAt=2));assertTrue(runCatching {i.import(f,p,mapOf("workouts:w1" to "1"))}.isFailure);val fresh=i.preview(f);assertEquals(1,i.import(f,fresh,mapOf("workouts:w1" to "2")));assertEquals(2L,d.huaweiSources().single().activityId)}}
    @Test fun twoInputsForOneTargetRollbackEverythingIncludingNewChunks()=runBlocking {db().useRoom {db ->val d=db.trailDao();d.insertRoute(RouteEntity(id=1,title="Synthetic"));d.insertActivity(original());val w1=workout().put("raw",JSONObject().put("syntheticPadding","x".repeat(100000)));val f=file(listOf(w1,workout("w2")));val i=HuaweiImporter(db);val p=i.preview(f);assertEquals(2,p.count("CONFLICT"));assertTrue(runCatching {i.import(f,p,mapOf("workouts:w1" to "1","workouts:w2" to "1"))}.isFailure);assertEquals(original(),d.activity(1));assertTrue(d.huaweiSources().isEmpty());db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM track_chunks").use {it.moveToFirst();assertEquals(0,it.getInt(0))}}}
    @Test fun changedIdentityIsConflictAndExplicitSkipIsNoop()=runBlocking {db().useRoom {db ->val i=HuaweiImporter(db);val f=file(listOf(workout()));i.import(f,i.preview(f),emptyMap());val before=db.trailDao().huaweiActivityIndex();val changed=file(listOf(workout().put("steps",8000)));val p=i.preview(changed);assertEquals(1,p.count("CONFLICT"));assertTrue(runCatching {i.import(changed,p,emptyMap())}.isFailure);assertEquals(0,i.import(changed,p,mapOf("workouts:w1" to "SKIP")));assertEquals(before,db.trailDao().huaweiActivityIndex())}}
    @Test fun chunkedRawRoundtripNoMonolithicSqliteRow()=runBlocking {db().useRoom {db ->val w=workout().put("raw",JSONObject().put("syntheticPadding","z".repeat(200000)));val f=file(listOf(w));val i=HuaweiImporter(db);i.import(f,i.preview(f),emptyMap());val source=db.trailDao().huaweiSources().single();assertTrue(source.payloadRef.startsWith(TrackChunks.PREFIX));val raw=JSONObject(db.trailDao().loadTrack(source.payloadRef)!!);assertEquals(w.toString(),raw.toString());assertTrue(db.trailDao().trackChunks(source.payloadRef.removePrefix(TrackChunks.PREFIX)).all {it.payload.length<=65536})}}
    @Test fun namedStagesExcludeWakeAndDoNotUseClinicalSleepScore()=runBlocking {
        val s=sleep(); val start=s.getLong("startTimeMs");val end=s.getLong("endTimeMs");s.put("stages",JSONArray().put(JSONObject().put("startTimeMs",start).put("endTimeMs",start+60000).put("key","PROFESSIONAL_SLEEP_WAKE")).put(JSONObject().put("startTimeMs",start+60000).put("endTimeMs",end).put("key","PROFESSIONAL_SLEEP_DEEP")))
        val f=file(emptyList(),listOf(s));var result: HuaweiPackage.Sleep?=null;HuaweiPackage.read(f) {result=HuaweiPackage.sleep(it)};assertEquals(end-start-60000,result!!.durationMs)
    }
    @Test fun ambiguousNapOverlapIncompleteAndAfterStartNeverBind()=runBlocking {
        val base=HuaweiPackage.sleep(HuaweiPackage.Item("sleeps",sleep()));val start=1704103200000L;assertEquals(base,HuaweiPackage.matchingSleep(start,base.wakeDay,listOf(base)))
        for(other in listOf(base.copy(nap=true),base.copy(durationMs=null),base.copy(end=start+1))) assertNull(HuaweiPackage.matchingSleep(start,base.wakeDay,listOf(other)))
        assertNull(HuaweiPackage.matchingSleep(start,base.wakeDay,listOf(base,base.copy(id="other"))))
        assertNull(HuaweiPackage.matchingSleep(start,base.wakeDay,listOf(base,base.copy(id="overlap",wakeDay=base.wakeDay-1))))
        val incomplete=sleep().also {it.getJSONArray("stages").getJSONObject(0).put("startTimeMs",it.getLong("startTimeMs")+60000)}
        assertNull(HuaweiPackage.sleep(HuaweiPackage.Item("sleeps",incomplete)).durationMs)
    }
    @Test fun wakeDateUsesHistoricalOffsetNotPhoneZone()=runBlocking {val s=sleep().put("sourceOffset","-0800");val item=HuaweiPackage.Item("sleeps",s);assertEquals(java.time.Instant.ofEpochMilli(item.end).atOffset(java.time.ZoneOffset.of("-0800")).toLocalDate().toEpochDay(),HuaweiPackage.sleep(item).wakeDay)}
    @Test fun strictTypesDuplicatesUnsupportedVersionAndMalformedSamplesRejected()=runBlocking {
        val files=listOf(file(listOf(workout().put("gainDm","1234"))),file(listOf(workout().put("subType",5))),file(listOf(workout(),workout())),file(listOf(workout().put("heartRateSamples",JSONArray().put(JSONObject().put("timeMs",0).put("bpm",100))))),file(listOf(workout().put("sourceOffset","Mars"))))
        for(f in files) assertTrue(runCatching {HuaweiPackage.read(f) {}}.isFailure)
        val v=file(emptyList());v.writeText(v.readText().replace("\"schemaVersion\":1","\"schemaVersion\":2"));assertTrue(runCatching {HuaweiPackage.read(v) {}}.isFailure)
    }
    @Test fun canonicalReplayIgnoresObjectKeyOrder()=runBlocking {val a=workout();val b=JSONObject();a.keys().asSequence().toList().reversed().forEach {b.put(it,a.get(it))};assertEquals(HuaweiPackage.Item("workouts",a).hash,HuaweiPackage.Item("workouts",b).hash)}
    @Test fun publishedNeutralContractSampleImportsThroughProductionReaderAndDao()=runBlocking {db().useRoom {db ->
        val f=File.createTempFile("contract-sample-",".json",context.cacheDir)
        f.writeText(requireNotNull(javaClass.getResourceAsStream("/huawei-valid-v1.json")).bufferedReader().use {it.readText()})
        val importer=HuaweiImporter(db);val preview=importer.preview(f);assertEquals(1,preview.count("NEW"));assertEquals(1,preview.count("NEW_SLEEP"));assertEquals(2,importer.import(f,preview,emptyMap()))
        val a=db.trailDao().huaweiActivityIndex().single();assertEquals(7000,JSONObject(a.huaweiFactsJson).getInt("steps"));assertEquals(2,db.trailDao().huaweiSources().size)
    }}
    @Test fun importingFactsRefreshesOnlyAlreadyPendingJobKeepingQueueIdentity()=runBlocking {db().useRoom {db ->
        val d=db.trailDao();d.insertRoute(RouteEntity(id=1,title="Synthetic"));d.insertActivity(original())
        val prefs=AppPreferences(context);val refresh=com.mayushi.trailsense.network.PendingAnalysisRefresh(d,prefs)
        val seed=AiJobEntity(entityType="ACTIVITY",entityId=1,analysisKind="POST_HIKE",inputJson="{}",inputHash="",createdAt=100)
        val rebuilt=refresh.rebuild(seed);val id=d.enqueuePendingAiJob(rebuilt);val before=d.aiJob(id)!!
        val f=file(listOf(workout()));val importer=HuaweiImporter(db);importer.import(f,importer.preview(f),emptyMap());refresh.refresh()
        val after=d.aiJob(id)!!;assertEquals(before.id,after.id);assertEquals(before.createdAt,after.createdAt);assertEquals(before.inputRevision+1,after.inputRevision);assertNotEquals(before.inputHash,after.inputHash);assertEquals(1,d.pendingAiJobs().size)
        val exported=JSONObject(after.inputJson).getJSONObject("activity").getJSONObject("huawei_archive");assertEquals(6000,exported.getInt("steps"));assertFalse(exported.has("raw"));assertFalse(exported.has("sleepScore"));assertFalse(exported.has("unconfirmedRecovery"))
    }}
    @Test fun laterNapDoesNotStealTheOnlyCompletePriorNight() {
        val base=HuaweiPackage.sleep(HuaweiPackage.Item("sleeps",sleep()));val start=1704103200000L
        val nap=base.copy(id="later-nap",start=start+3600000,end=start+7200000,nap=true)
        assertEquals(base,HuaweiPackage.matchingSleep(start,base.wakeDay,listOf(base,nap)))
    }
    @Test fun largePackageStreamsMultipleBoundedItems()=runBlocking {val f=file((1..40).map {workout("synthetic-$it",1704103200000L+it*86400000L).put("raw",JSONObject().put("padding","p".repeat(100000)))});var count=0;HuaweiPackage.read(f) {count++};assertEquals(40,count);assertTrue(f.length()>4000000)}
    @Test fun hugeStringIsRejectedBeforeWholeFileAllocation()=runBlocking {val f=file(listOf(workout()));f.writeText(f.readText().replace("\"synthetic\"","\""+"x".repeat(3*1024*1024)+"\""));assertTrue(runCatching {HuaweiPackage.read(f) {}}.isFailure)}
    @Test fun deletedArchiveRecordingReimportsSameAndChangedHashAfterReopenPreservingOthers()=runBlocking {
        for(changed in listOf(false,true)) {
            val name="synthetic-huawei-lifecycle-$changed.db";context.deleteDatabase(name)
            fun open()=Room.databaseBuilder(context,TrailDatabase::class.java,name).allowMainThreadQueries().build()
            var database=open()
            val source=workout().put("raw",JSONObject().put("padding","x".repeat(100000)))
            val other=workout("other",1704103200000L+86400000L)
            val input=file(listOf(source,other),listOf(sleep()))
            var importer=HuaweiImporter(database);importer.import(input,importer.preview(input),emptyMap())
            val d=database.trailDao();val deleted=d.huaweiSources().single {it.sourceId=="w1"}.activityId!!
            val survivor=d.huaweiActivityIndex().single {it.id!=deleted}
            val otherSources=d.huaweiSources().filter {it.activityId!=deleted}
            fun chunks(db: TrailDatabase):List<String> = db.openHelper.readableDatabase.query("SELECT trackKey,ordinal,payload FROM track_chunks ORDER BY trackKey,ordinal").use {c ->buildList {while(c.moveToNext()) add("${c.getString(0)}:${c.getInt(1)}:${c.getString(2)}")}}
            val historicalChunks=chunks(database)
            d.deleteRouteEvent(d.storedActivity(deleted)!!.routeId!!)
            assertFalse(d.huaweiSources().any {it.activityId==deleted});assertEquals(historicalChunks,chunks(database))
            database.close();database=open();importer=HuaweiImporter(database)
            val replay=file(listOf(if(changed) source.put("steps",8001) else source))
            val preview=importer.preview(replay);assertEquals(1,preview.count("NEW"));assertFalse(preview.rows.single().existingIdentity)
            assertEquals(1,importer.import(replay,preview,emptyMap()))
            val fresh=database.trailDao();assertEquals(survivor,fresh.storedActivity(survivor.id));assertTrue(fresh.huaweiSources().containsAll(otherSources))
            assertEquals(2,fresh.huaweiActivityIndex().size);assertEquals(1,importer.preview(replay).count("UNCHANGED"))
            assertEquals(if(changed) 8001 else 6000,JSONObject(fresh.storedActivity(fresh.huaweiSources().single {it.sourceId=="w1"}.activityId!!)!!.huaweiFactsJson).getInt("steps"))
            assertTrue(chunks(database).containsAll(historicalChunks));database.close();context.deleteDatabase(name)
        }
    }
    @Test fun legacyDanglingIdentityIsActionableEvenWhenChangedSourceHasNearbyConflict()=runBlocking {db().useRoom {db ->
        val i=HuaweiImporter(db);val f=file(listOf(workout()));i.import(f,i.preview(f),emptyMap());val d=db.trailDao()
        val a=d.huaweiActivityIndex().single();d.deleteActivity(a)
        assertEquals(1,i.preview(f).count("NEW"))
        d.insertActivity(original(99).copy(routeId=a.routeId));d.insertActivity(original(100).copy(routeId=a.routeId))
        val changed=file(listOf(workout().put("steps",8000)));val p=i.preview(changed);assertEquals(1,p.count("CONFLICT"));assertFalse(p.rows.single().existingIdentity)
        assertEquals(1,i.import(changed,p,mapOf("workouts:w1" to "NEW")));assertEquals(3,d.huaweiActivityIndex().size)
    }}
    @Test fun watchReplacementProvesIdentityOrAtomicallyDetachesOnlyArchiveFactsAndSamples()=runBlocking {db().useRoom {db ->
        val d=db.trailDao();d.insertRoute(RouteEntity(id=1,title="Synthetic"));d.insertActivity(original().copy(trackEncoded=com.mayushi.trailsense.domain.TrackCodec.encode(emptyList()),originalTrackEncoded=com.mayushi.trailsense.domain.TrackCodec.encode(emptyList())))
        val companion=d.insertCompanion(CompanionEntity(name="Synthetic historical companion",deleted=true));d.linkCompanion(ActivityCompanionCrossRef(1,companion))
        val start=1704103200000L
        val w=workout().put("heartRateSamples",JSONArray().put(JSONObject().put("timeMs",start).put("bpm",123)))
        val f=file(listOf(w),listOf(sleep()));val i=HuaweiImporter(db);i.import(f,i.preview(f),emptyMap())
        val before=d.activity(1)!!;val sources=d.huaweiSources()
        fun tcx(time:Long,distance:Int):File=File.createTempFile("synthetic-replacement-",".tcx",context.cacheDir).apply {
            val stamp=java.time.Instant.ofEpochMilli(time).toString()
            writeText("""<TrainingCenterDatabase><Activities><Activity Sport="Other"><Id>$stamp</Id><Lap StartTime="$stamp"><TotalTimeSeconds>3500</TotalTimeSeconds><DistanceMeters>$distance</DistanceMeters><Track><Trackpoint><Time>$stamp</Time><Position><LatitudeDegrees>49</LatitudeDegrees><LongitudeDegrees>-123</LongitudeDegrees></Position><HeartRateBpm><Value>91</Value></HeartRateBpm></Trackpoint><Trackpoint><Time>${java.time.Instant.ofEpochMilli(time+3500000)}</Time><Position><LatitudeDegrees>49.01</LatitudeDegrees><LongitudeDegrees>-123.01</LongitudeDegrees></Position><HeartRateBpm><Value>92</Value></HeartRateBpm></Trackpoint></Track></Lap></Activity></Activities></TrainingCenterDatabase>""")
        }
        val repo=TrailRepository(d,context.contentResolver,context.filesDir)
        val same=tcx(start,5000);assertEquals(1L,repo.importActivityFile(android.net.Uri.fromFile(same),1,true))
        val retained=d.activity(1)!!;assertEquals(before.huaweiFactsJson,retained.huaweiFactsJson);assertEquals(sources,d.huaweiSources());assertEquals(listOf(start to 123),HuaweiHeartRate(db).load(1))
        val other=tcx(start+86400000L,7000);repo.importActivityFile(android.net.Uri.fromFile(other),1,true)
        val replaced=d.activity(1)!!;assertEquals("",replaced.huaweiFactsJson);assertFalse(d.huaweiSources().any {it.kind=="workouts" || it.kind=="rawChunks"});assertTrue(HuaweiHeartRate(db).load(1).isEmpty())
        assertEquals(before.comment,replaced.comment);assertEquals(before.questionnaireHistory,replaced.questionnaireHistory);assertEquals(before.sleepHours,replaced.sleepHours);assertEquals(before.sleepQuality,replaced.sleepQuality);assertEquals(before.feltRested,replaced.feltRested);assertEquals(before.descentEffort,replaced.descentEffort);assertEquals(listOf(companion),d.companionIdsOnce(1))
        assertEquals(start+86400000L,replaced.importedStartedAt);assertEquals(replaced.trackEncoded,replaced.originalTrackEncoded);assertEquals(7000.0,HuaweiWatchDisplay.activity(replaced).distanceM,0.0);assertEquals(92,HuaweiWatchDisplay.activity(replaced).maxHeartRateBpm)
        val export=com.mayushi.trailsense.network.AnalysisPayloadBuilder.activity(replaced,emptyList(),8.0,null).getJSONObject("activity")
        assertTrue(export.isNull("huawei_archive"));assertEquals(1,d.huaweiSources().count {it.kind=="sleeps"})
        // A later sleep replay must not reattach A's sleep or facts to B.
        val sleepOnly=file(emptyList(),listOf(sleep("other-sleep",1704153600000L,1704178800000L)));i.import(sleepOnly,i.preview(sleepOnly),emptyMap());assertEquals(replaced,d.activity(1))
        assertEquals(1,i.preview(f).count("NEW"))
    }}
    @Test fun replacementFailureRollsBackArchiveDetachmentAndPreservesQuestionnaire()=runBlocking {db().useRoom {db ->
        val i=HuaweiImporter(db);val f=file(listOf(workout()),listOf(sleep()));i.import(f,i.preview(f),emptyMap());val d=db.trailDao();val a=d.huaweiActivityIndex().single();val sources=d.huaweiSources()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_reject_update BEFORE UPDATE ON activities BEGIN SELECT RAISE(ABORT,'synthetic rollback'); END")
        assertTrue(runCatching {d.replaceWatchActivity(a,a.copy(importedStartedAt=0,huaweiFactsJson=""))}.isFailure)
        assertEquals(a,d.storedActivity(a.id));assertEquals(sources,d.huaweiSources())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_reject_update")
        assertTrue(runCatching {d.replaceWatchActivity(a.copy(updatedAt=a.updatedAt-1),a)}.isFailure);assertEquals(sources,d.huaweiSources())
    }}
    @Test fun mountedHeartRateObserverRefreshesSampleOnlyAndEmptyUpdateWithoutResubscription()=runBlocking {
        db().useRoom {db ->
            val i=HuaweiImporter(db);val d=db.trailDao();val start=1704103200000L
            fun sampled(bpm:Int?)=workout().put("heartRateSamples",JSONArray().also {a ->if(bpm!=null) a.put(JSONObject().put("timeMs",start).put("bpm",bpm))})
            val initial=file(listOf(sampled(101),workout("unrelated",start+86400000L)))
            i.import(initial,i.preview(initial),emptyMap());val id=d.huaweiSources().single {it.sourceId=="w1"}.activityId!!
            val other=d.huaweiActivityIndex().single {it.id!=id};val otherSource=d.huaweiSources().single {it.activityId==other.id}
            val a=d.activity(id)!!;val track=com.mayushi.trailsense.domain.TrackCodec.encode(listOf(com.mayushi.trailsense.domain.TrackPoint(49.0,-123.0,timeEpochMs=start,heartRateBpm=91)))
            d.updateActivity(a.copy(trackEncoded=track,originalTrackEncoded=track))
            val original=d.activity(id)!!;val summary=original.huaweiFactsJson
            val values=kotlinx.coroutines.channels.Channel<List<Pair<Long,Int>>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val job=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {HuaweiHeartRate(db).observe(id).collect { values.send(it) }}
            suspend fun next(expected:List<Pair<Long,Int>>) = kotlinx.coroutines.withTimeout(15000) {while(values.receive()!=expected) {}}
            try {
                next(listOf(start to 101))
                val update=file(listOf(sampled(155)));i.import(update,i.preview(update),mapOf("workouts:w1" to id.toString()))
                next(emptyList());next(listOf(start to 155));assertEquals(summary,d.activity(id)!!.huaweiFactsJson)
                val empty=file(listOf(sampled(null)));i.import(empty,i.preview(empty),mapOf("workouts:w1" to id.toString()))
                next(emptyList());assertTrue(HuaweiHeartRate(db).load(id).isEmpty());assertEquals(summary,d.activity(id)!!.huaweiFactsJson)
                assertEquals(listOf(start to 91),HuaweiHeartRate.withOriginalFallback(HuaweiHeartRate(db).load(id),original))
                assertEquals(other,d.storedActivity(other.id));assertTrue(d.huaweiSources().contains(otherSource))
            } finally {job.cancel();job.join();values.close()}
        }
    }
    @Test fun productionLifecycleDoesNotMaterializeEveryInlineRawPayload()=runBlocking {
        val queries=java.util.Collections.synchronizedList(mutableListOf<String>())
        Room.inMemoryDatabaseBuilder(context,TrailDatabase::class.java).allowMainThreadQueries()
            .setQueryCallback({sql,_ ->queries+=sql},java.util.concurrent.Executor {it.run()}).build().useRoom {db ->
            val d=db.trailDao();val i=HuaweiImporter(db);val f=file(listOf(workout()),listOf(sleep()))
            i.import(f,i.preview(f),emptyMap());val a=d.huaweiActivityIndex().single()
            repeat(200) {n -> d.putHuaweiSource(HuaweiSourceEntity("rawChunks","synthetic-unrelated-$n","f".repeat(64),null,"x".repeat(32000),"synthetic","+0000",n.toLong(),0,null,null,false))}
            queries.clear()
            assertEquals(2,i.preview(f).count("UNCHANGED"));assertEquals(0,i.import(f,i.preview(f),emptyMap()))
            assertTrue(HuaweiHeartRate(db).load(a.id).isEmpty())
            d.replaceWatchActivity(a,a.copy(importedStartedAt=0,updatedAt=a.updatedAt+1))
            assertEquals(201,d.huaweiSourceIdentities().size) // unrelated raw identities and sleep survive
            assertFalse(queries.any {it.trim().equals("SELECT * FROM huawei_sources",ignoreCase=true)})
            assertTrue(queries.any {it.contains("kind,sourceId,sourceHash,activityId")})
        }
    }
    @Test fun migration11To12PreservesExactRowsLinksPayloadCacheAndReopen()=runBlocking {
        val name="synthetic-stage4-upgrade.db";context.deleteDatabase(name)
        fun open()=Room.databaseBuilder(context,TrailDatabase::class.java,name).addMigrations(TrailDatabase.MIGRATION_11_12).allowMainThreadQueries().build()
        var db=open();val d=db.trailDao();val r=RouteEntity(id=1,title="Synthetic",notes=" exact ");d.insertRoute(r);val a=original();d.insertActivity(a);val c=CompanionEntity(id=1,name="Synthetic person");d.insertCompanion(c);d.linkCompanion(ActivityCompanionCrossRef(1,1))
        val job=AiJobEntity(id=1,entityType="ACTIVITY",entityId=1,analysisKind="POST_HIKE",inputJson="{ \"exact\": true }",inputHash="a".repeat(64));d.insertAiJob(job);db.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {it.remove041Stage4Schema();it.version=11}
        repeat(2) {db=open();val dao=db.trailDao();assertEquals(r,dao.route(1));assertEquals(a,dao.activity(1));assertEquals(c,dao.companion(1));assertEquals(listOf(1L),dao.companionIdsOnce(1));assertEquals(job,dao.aiJob(1));assertTrue(dao.huaweiSources().isEmpty());db.close()}
    }
}
