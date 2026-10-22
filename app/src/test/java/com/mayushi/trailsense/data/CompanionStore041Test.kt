package com.mayushi.trailsense.data

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CompanionStore041Test {
    private fun db()=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),TrailDatabase::class.java).allowMainThreadQueries().build()
    @Test fun softDeleteRetainsHistoryIdsAndRestoreSameEntity()=runBlocking {db().useRoom {db ->
        val d=db.trailDao();d.insertRoute(RouteEntity(id=1,title="Synthetic linked route"));val a=ActivityEntity(id=1,routeId=1,title="Synthetic recording",sourceFileName="synthetic",sourceFormat="TCX",trackEncoded="[]",distanceM=1.0,elevationGainM=0.0,elevationLossM=0.0,startedAt=1704103200000)
        d.insertActivity(a);var refreshes=0;val store=CompanionStore(db) {refreshes++};val id=store.create("Synthetic Person");d.linkCompanion(ActivityCompanionCrossRef(1,id));val before=d.companion(id)!!
        store.deleted(id,true);assertTrue(d.companion(id)!!.deleted);assertEquals(listOf(id),d.companionIdsOnce(1));assertEquals(listOf("Synthetic Person"),d.companionNamesForActivity(1));assertEquals("Synthetic linked route",d.companionExamples(id).single().routeTitle);assertEquals(a.startedAt,d.companionExamples(id).single().startedAt)
        d.updateActivityWithCompanions(a,setOf(id));assertTrue(runCatching {store.create(" synthetic   person ")}.isFailure)
        store.deleted(id,false);assertEquals(before,d.companion(id));assertEquals(2,refreshes);assertTrue(d.pendingAiJobs().isEmpty());assertEquals(a,d.activity(1))
    }}
    @Test fun newPersonRequiresDistinctNameNoHistoryMergeAndRenameIsExactId()=runBlocking {db().useRoom {db ->val s=CompanionStore(db);val first=s.create("Synthetic");s.deleted(first,true);assertTrue(runCatching {s.create("SYNTHETIC")}.isFailure);val second=s.create("Synthetic other person");assertNotEquals(first,second);assertTrue(db.trailDao().companion(first)!!.deleted);s.rename(second,"Distinct new name");assertEquals("Synthetic",db.trailDao().companion(first)!!.name);assertEquals("Distinct new name",db.trailDao().companion(second)!!.name)}}
    @Test fun deletedPersonCannotBeNewlySelectedButExistingLinkSurvives()=runBlocking {db().useRoom {db ->val d=db.trailDao();val a=ActivityEntity(id=1,title="Synthetic",sourceFileName="s",sourceFormat="S",trackEncoded="[]",distanceM=0.0,elevationGainM=0.0,elevationLossM=0.0);d.insertActivity(a);val s=CompanionStore(db);val id=s.create("Synthetic");s.deleted(id,true);assertTrue(runCatching {d.updateActivityWithCompanions(a,setOf(id))}.isFailure);assertTrue(d.companionIdsOnce(1).isEmpty());assertEquals(a,d.activity(1))}}
    @Test fun multipleLegacyDeletedNamesNeverAutoMergeAndActiveCollisionRefused()=runBlocking {db().useRoom {db ->val d=db.trailDao();val first=d.insertCompanion(CompanionEntity(name="Synthetic",deleted=true));val second=d.insertCompanion(CompanionEntity(name="Synthetic",deleted=true));val s=CompanionStore(db);assertTrue(runCatching {s.create("Synthetic")}.isFailure);s.deleted(second,false);assertTrue(d.companion(first)!!.deleted);assertFalse(d.companion(second)!!.deleted);assertTrue(runCatching {s.deleted(first,false)}.isFailure);assertEquals(2,d.companionsOnce().size)}}
    @Test fun frequentFlowTracksHistoryOnlyChangesAndRestoredIdentity()=runBlocking {db().useRoom {db ->
        val d=db.trailDao();val store=CompanionStore(db)
        val a=ActivityEntity(id=1,title="Synthetic",sourceFileName="s",sourceFormat="S",trackEncoded="[]",distanceM=0.0,elevationGainM=0.0,elevationLossM=0.0)
        d.insertActivity(a);d.insertActivity(a.copy(id=2))
        val ids=listOf("Alpha","Beta","Gamma","Delta","Epsilon").map {store.create(it)}
        val emissions=Channel<List<Long>>(Channel.UNLIMITED)
        val observer=launch(Dispatchers.IO) {d.observeFrequentCompanionIds().collect {emissions.send(it)}}
        suspend fun await(expected:List<Long>)=withTimeout(10000) {while(emissions.receive()!=expected) {}}
        try {
            await(emptyList());val people=d.companionsOnce()
            ids.forEach {d.linkCompanion(ActivityCompanionCrossRef(1,it))}
            await(listOf(ids[0],ids[1],ids[3],ids[4]));assertEquals(people,d.companionsOnce())
            d.linkCompanion(ActivityCompanionCrossRef(2,ids[2]))
            await(listOf(ids[2],ids[0],ids[1],ids[3]));assertEquals(people,d.companionsOnce())
            store.deleted(ids[2],true);await(listOf(ids[0],ids[1],ids[3],ids[4]))
            assertEquals(ids,d.companionIdsOnce(1));assertEquals(listOf(ids[2]),d.companionIdsOnce(2))
            store.rename(ids[2],"Renamed");store.deleted(ids[2],false)
            await(listOf(ids[2],ids[0],ids[1],ids[3]));assertEquals("Renamed",d.companion(ids[2])!!.name)
            d.clearActivityCompanions(2);await(listOf(ids[0],ids[1],ids[3],ids[4]))
            assertEquals(ids,d.companionIdsOnce(1));assertEquals(a,d.activity(1));assertTrue(d.pendingAiJobs().isEmpty())
        } finally {observer.cancelAndJoin();emissions.close()}
    }}
}
