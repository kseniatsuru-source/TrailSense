package com.mayushi.trailsense.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AnalysisMigration041Test {
    @Test fun additiveMigrationPreservesPayloadHashCachesQuestionnairesAndReopen() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val name = "synthetic-analysis-v10.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, TrailDatabase::class.java, name)
            .addMigrations(TrailDatabase.MIGRATION_10_11, TrailDatabase.MIGRATION_11_12).allowMainThreadQueries().build()
        var db = open()
        val route = RouteEntity(id = 1, title = "Synthetic route", notes = "Exact route notes", updatedAt = 20)
        val activity = ActivityEntity(id = 1, routeId = 1, title = "Synthetic recording", sourceFileName = "synthetic", sourceFormat = "SYNTHETIC",
            trackEncoded = "[]", distanceM = 1000.0, elevationGainM = 100.0, elevationLossM = 100.0, comment = " Exact notes\n", updatedAt = 30)
        val jobs = listOf("PENDING", "DONE").mapIndexed { i, status -> AiJobEntity(id = (i + 4).toLong(), entityType = "ACTIVITY", entityId = 1,
            analysisKind = "POST_HIKE", inputJson = "{ \"exact\": true }", inputHash = "a".repeat(64), status = status, createdAt = 10, updatedAt = 15) }
        val cache = AiCacheEntity(id = 8, cacheKey = "synthetic", entityType = "ACTIVITY", entityId = 1, analysisKind = "POST_HIKE", inputHash = "b".repeat(64), response = "Exact old result", createdAt = 12)
        db.trailDao().apply { insertRoute(route); insertActivity(activity); jobs.forEach { insertAiJob(it) }; insertAiCache(cache) }
        db.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.remove041AnalysisSchema(); it.version = 10
        }
        db = open()
        suspend fun verify() {
            val dao = db.trailDao()
            assertEquals(route, dao.route(1)); assertEquals(activity, dao.activity(1)); assertEquals(cache, dao.aiCache(cache.cacheKey))
            jobs.forEach { assertEquals(it.copy(inputRevision = it.id), dao.aiJob(it.id)) }
            assertNull(dao.analysisResult("ACTIVITY:1:POST_HIKE:"))
        }
        verify(); db.close(); db = open(); verify(); db.close()
    }
}
