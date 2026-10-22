package com.mayushi.trailsense.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.mayushi.trailsense.domain.PillPlanVersions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Release037PersistenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun migration8To9PreservesAllOriginalFieldsAndRegimenHistory() = runBlocking {
        val name="upgrade037.db"; context.deleteDatabase(name)
        fun open()=Room.databaseBuilder(context,TrailDatabase::class.java,name).addMigrations(TrailDatabase.MIGRATION_8_9, TrailDatabase.MIGRATION_9_10, TrailDatabase.MIGRATION_10_11, TrailDatabase.MIGRATION_11_12).allowMainThreadQueries().build()
        var db=open(); val dao=db.trailDao()
        val r=RouteEntity(id=1,title="Constructed",climbDifficulty=2,notes="Original route note")
        val a=ActivityEntity(id=1,routeId=1,title="Constructed",sourceFileName="constructed.tcx",sourceFormat="TCX",trackEncoded="[]",originalTrackEncoded="[]",distanceM=1200.0,elevationGainM=400.0,elevationLossM=200.0,climbEffort=5,comment="Original comment",sleepHours=7.0,heavyBackpack=true)
        val c=CalendarContextEntity(id=1,startEpochDay=20,endEpochDay=30,type="HIGH_STRESS",note="Original context")
        dao.insertRoute(r);dao.insertActivity(a);dao.insertCalendarContext(c)
        dao.saveVersionedPillPlan(PillPlanEntity(anchorEpochDay=10,activeDays=21,breakDays=7),10)
        db.close()
        val native=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE)
        native.remove037Schema();native.version=8;native.close()
        db=open();assertEquals(r,db.trailDao().route(1));assertEquals(a,db.trailDao().activity(1))
        assertEquals(c,db.trailDao().calendarContext(1))
        val repo=TrailRepository(db.trailDao(),context.contentResolver,context.filesDir)
        val versionsBefore=repo.pillPlanVersions.first();assertEquals(1,versionsBefore.size)
        val before=PillPlanVersions.forDate(versionsBefore,repo.pillPlanOnce(),LocalDate.ofEpochDay(15))
        repo.savePillRegimen(LocalDate.ofEpochDay(40),24,4)
        assertEquals(before,PillPlanVersions.forDate(repo.pillPlanVersions.first(),repo.pillPlanOnce(),LocalDate.ofEpochDay(15)))
        db.close();db=open();assertEquals(a,db.trailDao().activity(1));assertEquals(2,db.trailDao().observePillPlanVersions().first().size);db.close()
    }
    @Test fun ocrConflictAndStaleFormNeverOverwriteWatchOrManualSleep() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,TrailDatabase::class.java).allowMainThreadQueries().build();val dao=db.trailDao()
        val r=RouteEntity(id=1,title="Constructed")
        val a=ActivityEntity(id=1,routeId=1,title="Constructed",sourceFileName="constructed.tcx",sourceFormat="TCX",trackEncoded="[]",distanceM=1300.0,elevationGainM=200.0,elevationLossM=180.0,sleepHours=7.0,sleepScreenshotPath="owned-synthetic-image",updatedAt=10)
        dao.insertRoute(r);dao.insertActivity(a)
        val repo=TrailRepository(dao,context.contentResolver,context.filesDir)
        repo.applySleepOcr(1,"owned-synthetic-image",390,"READ")
        val read=dao.activity(1)!!;assertEquals(7.0,read.sleepHours!!,0.0);assertEquals(390,read.sleepOcrMinutes);assertTrue(read.sleepOcrStatus!!.startsWith("CONFLICT"))
        assertTrue(runCatching { repo.saveQuestionnaire(a.copy(comment="Stale"),emptySet(),r) }.isFailure)
        assertEquals(read,dao.activity(1))
        repo.saveQuestionnaire(read.copy(comment="Chosen",sleepHours=6.5,questionnaireHistory="q037|sleep|previous=7.0|chosen=6.5",heavyBackpack=true),emptySet(),r)
        val saved=dao.activity(1)!!;assertEquals(1300.0,saved.distanceM,0.0);assertEquals(a.trackEncoded,saved.trackEncoded);assertEquals(390,saved.sleepOcrMinutes);assertEquals(6.5,saved.sleepHours!!,0.0);assertTrue(saved.heavyBackpack);assertTrue(saved.questionnaireHistory.contains("previous=7.0"))
        db.close()
    }
}
