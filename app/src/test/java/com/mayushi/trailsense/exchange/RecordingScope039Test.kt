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
@Config(sdk=[28],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RecordingScope039Test {
    @Test fun twoRecordingsOneRouteExportAndResponseNeverTouchOtherRecordingOrRoute()=runBlocking {
        val context:Context=RuntimeEnvironment.getApplication()
        runCatching {WorkManager.initialize(context,Configuration.Builder().build())}
        val db=Room.inMemoryDatabaseBuilder(context,TrailDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao=db.trailDao();val prefs=AppPreferences(context).apply{directHermesEnabled=false}
            val routeId=dao.insertRoute(RouteEntity(title="Same route",updatedAt=1000))
            fun a(start:Long,dist:Double,note:String)=ActivityEntity(routeId=routeId,title="Same route",sourceFileName="constructed.tcx",sourceFormat="TCX",trackEncoded="",startedAt=start,distanceM=dist,elevationGainM=dist/2,elevationLossM=0.0,elapsedSeconds=dist.toLong(),sleepHours=dist/1000,overallEffort=if(dist==1000.0)1 else 5,comment=note,updatedAt=start)
            val oldId=dao.insertActivity(a(1700000000000,1000.0,"historical only"));val newId=dao.insertActivity(a(1750000000000,9000.0,"newest only"))
            val old=dao.activity(oldId)!!;val newest=dao.activity(newId)!!;val route=dao.route(routeId)
            val manager=AiAnalysisManager(context,dao,prefs)
            listOf(old,newest).forEach {manager.enqueue("ACTIVITY",it.id,"DEEP_ROUTE",AnalysisPayloadBuilder.activity(it,emptyList(),prefs.sleepNormHours,null),allowDirect=false)}
            val exchange=TelegramExchangeManager(context,db,prefs)
            val exported=exchange.createRequestFile(analysisKind="DEEP_ROUTE",activityId=oldId)
            assertEquals(1,exported.taskCount)
            val request=JSONObject(exported.file.readText());val task=request.getJSONArray("tasks").getJSONObject(0)
            val oldJob=dao.pendingAiJobs().single{it.entityId==oldId};assertEquals(oldJob.id,task.getLong("job_id"))
            val input=JSONObject(oldJob.inputJson).getJSONObject("activity")
            assertEquals(oldId,input.getLong("id"));assertEquals(1000.0,input.getDouble("distance_m"),0.0);assertEquals(1,input.getInt("effort"));assertEquals(old.startedAt,input.getLong("started_at_ms"))
            val result=JSONObject().put("job_id",oldJob.id).put("input_hash",oldJob.inputHash).put("status","ok").put("response",JSONObject().put("summary","Constructed historical result").put("route_facts",JSONObject.NULL).put("activity_facts",JSONObject.NULL))
            val response=JSONObject().put("protocol",TelegramExchangeProtocol.PROTOCOL).put("schema_version",1).put("direction","analysis_response").put("request_id",request.getString("request_id")).put("results",JSONArray().put(result))
            val file=File(context.cacheDir,"constructed-scope039-response.json").apply{writeText(response.toString())}
            assertEquals(1,exchange.importResponse(Uri.fromFile(file)).imported)
            assertEquals(old,dao.activity(oldId));assertEquals(newest,dao.activity(newId));assertEquals(route,dao.route(routeId))
            assertNotNull(dao.observeLatestAi("ACTIVITY",oldId,"DEEP_ROUTE").first());assertNull(dao.observeLatestAi("ACTIVITY",newId,"DEEP_ROUTE").first());assertNull(dao.observeLatestAi("ROUTE",routeId,"DEEP_ROUTE").first())
            assertEquals(setOf(newId),dao.pendingAiJobs().map{it.entityId}.toSet())
        } finally {db.close()}
    }
}
