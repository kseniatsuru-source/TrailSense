package com.mayushi.trailsense.data

import android.content.Context
import androidx.room.Room
import com.mayushi.trailsense.domain.TrackCodec
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
@Config(sdk=[28],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HuaweiGeometry041Test {
    private val context: Context get()=RuntimeEnvironment.getApplication()
    private fun packageFile(count: Int=50000, first: Boolean=true, corrupt: Boolean=false): File {
        val root=JSONObject(requireNotNull(javaClass.getResourceAsStream("/huawei-valid-v1.json")).bufferedReader().use {it.readText()})
        val w=root.getJSONArray("workouts").getJSONObject(0);val start=w.getLong("startTimeMs")
        val chunks=(0 until count step 1000).mapIndexed { ordinal,from -> JSONObject().put("sourceId",w.getString("sourceId")).put("ordinal",ordinal).put("points",JSONArray().apply {
            for(i in from until minOf(count,from+1000)) put(JSONObject().put("latitude",1.0).put("longitude",i*.000001).put("timeMs",start+i*60L).put("elevationM",i*.01).put("heartRateBpm",120+i%30).put("cadenceSpm",90+i%10))
        }) }
        val hash=HuaweiPackage.sha(chunks.joinToString("") {HuaweiPackage.canonical(it)+"\n"})
        w.put("track",JSONObject().put("chunkCount",chunks.size).put("pointCount",count).put("sha256",if(corrupt) "0".repeat(64) else hash))
        val raw=JSONObject().put("sourceId",w.getString("sourceId")).put("ordinal",0).put("lines",JSONArray().put("synthetic original channel line\n"))
            .put("heartRateSamples",JSONArray().put(JSONObject().put("timeMs",start).put("bpm",125)))
        w.put("detail",JSONObject().put("chunkCount",1).put("pointCount",1).put("sha256",HuaweiPackage.sha(HuaweiPackage.canonical(raw)+"\n")))
        val fields="\"trackChunks\":"+JSONArray(chunks)+",\"rawChunks\":"+JSONArray().put(raw)
        val rest="\"workouts\":"+root.getJSONArray("workouts")+",\"sleeps\":[],\"schemaVersion\":1,\"protocol\":\"trailsense-huawei\""
        return File.createTempFile("synthetic-geometry-",".json",context.cacheDir).apply {writeText("{"+if(first) fields+","+rest+"}" else rest+","+fields+"}")}
    }
    @Test fun publishedExtendedSampleUsesProductionParserAndActualGeometry()=runBlocking {
        val f=File.createTempFile("synthetic-contract-",".json",context.cacheDir)
        f.writeText(requireNotNull(javaClass.getResourceAsStream("/huawei-valid-geometry-v1.json")).bufferedReader().use {it.readText()})
        Room.inMemoryDatabaseBuilder(context,TrailDatabase::class.java).allowMainThreadQueries().build().useRoom {db ->
            val i=HuaweiImporter(db);assertEquals(2,i.import(f,i.preview(f),emptyMap()))
            val a=db.trailDao().huaweiActivityIndex().single();assertEquals(2,TrackCodec.decode(db.trailDao().activity(a.id)!!.trackEncoded).size)
        };f.delete();Unit
    }
    @Test fun fiftyThousandPointsStreamToChunkStoreWithoutTruncationAndReopen()=runBlocking {
        val f=packageFile();assertTrue(f.length()>2*1024*1024)
        val name="synthetic-geometry-stage5.db";context.deleteDatabase(name)
        fun open()=Room.databaseBuilder(context,TrailDatabase::class.java,name).allowMainThreadQueries().build()
        var db=open()
        try {
            val importer=HuaweiImporter(db);assertEquals(1,importer.import(f,importer.preview(f),emptyMap()))
            val dao=db.trailDao();val a=dao.huaweiActivityIndex().single();assertTrue(a.trackEncoded.startsWith(TrackChunks.PREFIX));assertEquals(a.trackEncoded,a.originalTrackEncoded)
            val points=TrackCodec.decode(dao.activity(a.id)!!.trackEncoded);assertEquals(50000,points.size);assertEquals(120+49999%30,points.last().heartRateBpm);assertEquals(99,points.last().cadencePerMinute)
            val raw=dao.huaweiRawChunks(a.id).single();assertEquals("synthetic original channel line\n",JSONObject(dao.loadTrack(raw.payloadRef)!!).getJSONArray("lines").getString(0))
            db.openHelper.readableDatabase.query("SELECT MAX(length(payload)) FROM track_chunks").use {it.moveToFirst();assertTrue(it.getInt(0)<=65536)}
            db.close();db=open();assertEquals(points,TrackCodec.decode(db.trailDao().activity(a.id)!!.trackEncoded))
            val replay=HuaweiImporter(db);assertEquals(1,replay.preview(f).count("UNCHANGED"));assertEquals(0,replay.import(f,replay.preview(f),emptyMap()))
        } finally {db.close();context.deleteDatabase(name);f.delete()}
    }
    @Test fun rootOrderIndependentAndExistingOriginalCorrectedTracksFieldExact()=runBlocking {
        val f=packageFile(2000,false)
        Room.inMemoryDatabaseBuilder(context,TrailDatabase::class.java).allowMainThreadQueries().build().useRoom {db ->
            val d=db.trailDao();val r=RouteEntity(title="Synthetic route",trackEncoded=TrackCodec.encode(emptyList()),notes="exact route")
            val rid=d.insertRoute(r);val w=JSONObject(requireNotNull(javaClass.getResourceAsStream("/huawei-valid-v1.json")).bufferedReader().use {it.readText()}).getJSONArray("workouts").getJSONObject(0)
            val old=ActivityEntity(routeId=rid,title="Synthetic",sourceFileName="synthetic",sourceFormat="TCX",startedAt=w.getLong("startTimeMs"),distanceM=5000.0,elevationGainM=300.0,elevationLossM=300.0,elapsedSeconds=3500,trackEncoded=TrackCodec.encode(emptyList()),originalTrackEncoded=TrackCodec.encode(emptyList()),trackCorrectionMode="TRIMMED",comment=" personal ",updatedAt=1)
            val id=d.insertActivity(old);val importer=HuaweiImporter(db);val p=importer.preview(f);assertEquals(1,p.count("ENRICH"));importer.import(f,p,emptyMap())
            val saved=d.activity(id)!!;assertEquals(old.copy(id=id),saved.copy(huaweiFactsJson="",updatedAt=1));assertEquals(r.copy(id=rid),d.route(rid))
            val archive=JSONObject(saved.huaweiFactsJson).getString("archiveTrackRef");assertEquals(2000,TrackCodec.decode(d.loadTrack(archive)!!).size)
        };f.delete();Unit
    }
    @Test fun corruptMissingOrOrphanGeometryFailsClosedAndRollsBack()=runBlocking {
        Room.inMemoryDatabaseBuilder(context,TrailDatabase::class.java).allowMainThreadQueries().build().useRoom {db ->
            val importer=HuaweiImporter(db);val good=packageFile(2000);val p=importer.preview(good)
            val bad=packageFile(2000,corrupt=true);assertTrue(runCatching {importer.preview(bad)}.isFailure)
            val mutable=JSONObject(good.readText());mutable.remove("trackChunks");good.writeText(mutable.toString());assertTrue(runCatching {importer.preview(good)}.isFailure)
            val corruptHash=java.security.MessageDigest.getInstance("SHA-256").digest(bad.readBytes()).joinToString("") {"%02x".format(it)}
            // Exercise in-transaction rejection after raw chunk insertion, not only preflight SHA refusal.
            assertTrue(runCatching {importer.import(bad,p.copy(fileHash=corruptHash),emptyMap())}.isFailure);assertTrue(db.trailDao().huaweiActivityIndex().isEmpty());assertTrue(db.trailDao().huaweiSources().isEmpty())
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM track_chunks").use {it.moveToFirst();assertEquals(0,it.getInt(0))}
            good.delete();bad.delete();Unit
        }
    }
}
