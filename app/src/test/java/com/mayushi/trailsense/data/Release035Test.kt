package com.mayushi.trailsense.data

import android.content.Context
import androidx.room.Room
import com.mayushi.trailsense.domain.*
import com.mayushi.trailsense.network.*
import com.mayushi.trailsense.exchange.TelegramExchangeManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Release035Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val points = listOf(0.0, .00001, .00002, .008, .009, .01).mapIndexed { i, lat -> TrackPoint(49+lat,-123.0,100.0+i,1000L+i*1000,null) }
    private fun activity() = ActivityEntity(id=1,routeId=1,title="Synthetic",sourceFileName="synthetic.gpx",sourceFormat="GPX",trackEncoded=TrackCodec.encode(points),originalTrackEncoded=TrackCodec.encode(points),distanceM=1200.0,elevationGainM=5.0,elevationLossM=0.0,originalMetricsJson=OriginalWatchMetrics.encode(TrackAnalyzer.analyze(points)),sleepHours=8.0,comment="Preserved synthetic context",createdAt=100,updatedAt=100)
    @Test fun trimUsesCumulativeDistanceNotSamplingIndex() {
        val cumulative=TrackGeometry.cumulative(points)
        assertEquals(3,TrackGeometry.indexAtDistance(cumulative,cumulative.last()*.8))
        assertEquals(points.drop(3),TrackCorrection.trim(points,.8,1.0))
        val gap=points.mapIndexed { i,p -> p.copy(segmentStart=i==3) }
        assertEquals(Geo.distanceM(gap[3],gap[4])+Geo.distanceM(gap[4],gap[5])+Geo.distanceM(gap[0],gap[1])+Geo.distanceM(gap[1],gap[2]),TrackGeometry.cumulative(gap).last(),.000001)
    }
    @Test fun backpackPersistReopenResetReimportHashAndStaleJob()=runBlocking {
        val name="synthetic035.db";context.deleteDatabase(name)
        fun open()=Room.databaseBuilder(context,TrailDatabase::class.java,name).allowMainThreadQueries().build()
        var db=open();var dao=db.trailDao()
        dao.insertRoute(RouteEntity(id=1,title="Synthetic",updatedAt=100));dao.insertActivity(activity())
        var repo=TrailRepository(dao,context.contentResolver,context.filesDir)
        val prefs=AppPreferences(context);prefs.directHermesEnabled=false
        fun payload(a:ActivityEntity)=AnalysisPayloadBuilder.activity(a,emptyList(),prefs.sleepNormHours,null).put("algorithm_version",AnalysisPrompt.VERSION)
        val before=payload(dao.activity(1)!!).toString()
        val job=AiJobEntity(entityType="ACTIVITY",entityId=1,analysisKind="POST_HIKE",inputJson=before,inputHash=AnalysisInputHash.calculate(before,null,null).inputHash)
        val jobId=dao.insertAiJob(job)
        AiJobFreshness.requireCurrent(dao,prefs,job)
        repo.setHeavyBackpack(1,true)
        val enabled=dao.activity(1)!!
        assertTrue(enabled.heavyBackpack);assertEquals("PENDING",dao.aiJob(jobId)!!.status) // Existing jobs refresh in place; no automatic replacement jobs.
        try { AiJobFreshness.requireCurrent(dao,prefs,job);fail("Known old request must be stale") } catch (_:IllegalArgumentException) {}
        val after=payload(enabled);assertTrue(after.getJSONObject("activity").getBoolean("heavy_backpack"))
        assertFalse(after.getJSONObject("activity").getJSONObject("backpack_context").getBoolean("weight_is_measured"))
        assertNotEquals(job.inputHash,AnalysisInputHash.calculate(after.toString(),null,null).inputHash)
        repo.trimActivityTrack(1,.1,.9);repo.resetTrackCorrection(1)
        assertTrue(dao.activity(1)!!.heavyBackpack)
        val reset=dao.activity(1)!!
        val reimport=WatchActivityUpdater.merge(reset,1,"Synthetic","synthetic.gpx",TrackFormat.GPX,reset.originalTrackEncoded!!,null,TrackAnalyzer.analyze(points),System.currentTimeMillis())
        assertTrue(reimport.heavyBackpack);assertEquals(reset.sleepHours,reimport.sleepHours)
        dao.updateActivity(reimport);db.close();db=open();dao=db.trailDao()
        assertTrue(dao.activity(1)!!.heavyBackpack);assertEquals(points,TrackCodec.decode(dao.activity(1)!!.trackEncoded))
        repo=TrailRepository(dao,context.contentResolver,context.filesDir);repo.setHeavyBackpack(1,false)
        assertFalse(dao.activity(1)!!.heavyBackpack);db.close()
    }
    @Test fun migration7To8PreservesExactTrackAndContext()=runBlocking {
        val name="upgrade035.db";context.deleteDatabase(name)
        fun open()=Room.databaseBuilder(context,TrailDatabase::class.java,name).addMigrations(TrailDatabase.MIGRATION_7_8, TrailDatabase.MIGRATION_8_9, TrailDatabase.MIGRATION_9_10, TrailDatabase.MIGRATION_10_11, TrailDatabase.MIGRATION_11_12).allowMainThreadQueries().build()
        var db=open();db.trailDao().insertRoute(RouteEntity(id=1,title="Synthetic"));db.trailDao().insertActivity(activity());db.close()
        val native=android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
        native.remove037Schema()
        native.execSQL("PRAGMA foreign_keys=OFF")
        val schema=native.rawQuery("SELECT sql FROM sqlite_master WHERE name='activities'",null).use { it.moveToFirst();it.getString(0) }
        val fields=schema.substringAfter('(').substringBeforeLast(')').split(", ").filterNot { it.startsWith("`heavyBackpack` ") }
        native.execSQL("CREATE TABLE activities_v7 ("+fields.joinToString(", ")+")")
        val names=native.rawQuery("PRAGMA table_info(activities_v7)",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(1)) } }.joinToString(",") { "`$it`" }
        native.execSQL("INSERT INTO activities_v7 ($names) SELECT $names FROM activities")
        native.execSQL("DROP TABLE activities");native.execSQL("ALTER TABLE activities_v7 RENAME TO activities");native.execSQL("CREATE INDEX index_activities_routeId ON activities(routeId)")
        native.version=7;native.close();db=open()
        assertEquals(activity(),db.trailDao().activity(1));assertEquals(points,TrackCodec.decode(db.trailDao().activity(1)!!.trackEncoded));db.close()
        db=open();assertFalse(db.trailDao().activity(1)!!.heavyBackpack);db.close()
    }
    @Test fun confirmedOfficialAssociationPreservesEntireWatchActivity()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,TrailDatabase::class.java).allowMainThreadQueries().build()
        val dao=db.trailDao();dao.insertRoute(RouteEntity(id=1,title="Synthetic"));dao.insertActivity(activity())
        val before=dao.activity(1)!!
        val repo=TrailRepository(dao,context.contentResolver,context.filesDir)
        repo.saveRoute(dao.route(1)!!.copy(sourceUrl="https://www.alltrails.com/trail/canada/british-columbia/example",sourceLabel="AllTrails MCP:123"))
        assertEquals(before,dao.activity(1));assertEquals("AllTrails MCP:123",dao.route(1)!!.sourceLabel)
        db.close()
    }
    @Test fun scopedShareExcludesOtherHikesAndKinds()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,TrailDatabase::class.java).allowMainThreadQueries().build();val dao=db.trailDao()
        val prefs=AppPreferences(context);prefs.directHermesEnabled=false
        dao.insertRoute(RouteEntity(id=1,title="First",updatedAt=100));dao.insertRoute(RouteEntity(id=2,title="Other",updatedAt=101))
        for ((id,kind) in listOf(1L to "DEEP_ROUTE",1L to "SHORT_ROUTE",2L to "DEEP_ROUTE")) {
            val p=AnalysisPayloadBuilder.route(dao.route(id)!!,null,emptyList(),prefs.sleepNormHours,null).put("algorithm_version",AnalysisPrompt.VERSION).toString()
            dao.insertAiJob(AiJobEntity(entityType="ROUTE",entityId=id,analysisKind=kind,inputJson=p,inputHash=AnalysisInputHash.calculate(p,null,null).inputHash))
        }
        val request=TelegramExchangeManager(context,db,prefs).createRequestFile(1,"DEEP_ROUTE")
        assertEquals(1,request.taskCount)
        val tasks=JSONObject(request.file.readText()).getJSONArray("tasks")
        assertEquals(1,tasks.length());assertEquals(1,tasks.getJSONObject(0).getLong("entity_id"));assertEquals("DEEP_ROUTE",tasks.getJSONObject(0).getString("analysis_kind"));assertEquals(3,dao.pendingAiJobs().size)
        db.close()
    }
}
