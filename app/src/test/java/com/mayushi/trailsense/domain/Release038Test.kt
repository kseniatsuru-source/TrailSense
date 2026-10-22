package com.mayushi.trailsense.domain

import com.mayushi.trailsense.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File

class Release038Test {
    private fun activity(id:Long, offset:Double=0.0,seconds:Long=900):ActivityEntity {
        val points=(0..200).map {i -> TrackPoint(49.0+i*.00003,-123.0+offset,elevationM=200.0+i*3,timeEpochMs=1700000000000+id*86400000+i*seconds*5) }
        return ActivityEntity(id=id,title="recorded",sourceFileName="synthetic",sourceFormat="TCX",trackEncoded=TrackCodec.encode(points),distanceM=1000.0,elevationGainM=600.0,elevationLossM=0.0,startedAt=points.first().timeEpochMs)
    }
    @Test fun clockRoundTripAndValidation() {
        assertEquals("9:04",SleepClock.format(544/60.0));assertEquals("8:00",SleepClock.format(8.0))
        assertEquals(544/60.0,SleepClock.parse("9:04")!!,0.0000001)
        listOf("9:99","-1:00","9.0","25:00").forEach {assertNull(SleepClock.parse(it))}
    }
    @Test fun liveBindingTitleDoesNotMutateWatch() {
        val watch=activity(1).copy(routeId=5)
        assertEquals("Route",DisplayTitle.resolve(watch,listOf(RouteEntity(id=5,title="Route"))))
        assertEquals("recorded",watch.title)
        assertEquals("custom",DisplayTitle.boundTitle(RouteEntity(title="custom",sourceLabel="CUSTOM_TITLE"),"Route"))
    }
    @Test fun automaticGroupsRejectNeighbourExcludeAndCorrections() {
        val a=activity(1);val b=activity(2);val c=activity(3,.003)
        val groups=AutomaticRepeatRoutes.discover(listOf(a,b,c),emptyList())
        assertEquals(1,groups.size);assertEquals(listOf(1L,2L),groups.single().rows.map{it.activity.id})
        assertTrue(AutomaticRepeatRoutes.discover(listOf(a,b,c),emptyList(),setOf(2)).isEmpty())
        assertTrue(AutomaticRepeatRoutes.discover(listOf(a,b.copy(trackCorrectionMode="INFERRED_START")),emptyList()).isEmpty())
    }
    @Test fun completeLinkDoesNotTransitiveChain() {
        val groups=AutomaticRepeatRoutes.discover(listOf(activity(1),activity(2,.0005),activity(3,.001)),emptyList())
        assertTrue(groups.all {it.rows.size <= 2})
    }
    @Test fun missingTimestampAndGapExcluded() {
        val a=activity(1);val p=TrackCodec.decode(a.trackEncoded)
        assertTrue(AutomaticRepeatRoutes.discover(listOf(a,activity(2).copy(trackEncoded=TrackCodec.encode(p.mapIndexed{i,x -> if(i==50) x.copy(timeEpochMs=null) else x}))),emptyList()).isEmpty())
        assertTrue(AutomaticRepeatRoutes.discover(listOf(a,activity(2).copy(trackEncoded=TrackCodec.encode(p.mapIndexed{i,x -> if(i==50) x.copy(segmentStart=true) else x}))),emptyList()).isEmpty())
    }
    @Test fun privateAuthorizedThreeRecordingsAndNeighbour() {
        val paths=(1..4).map {System.getenv("TS038_ROUTE_$it")}
        assumeTrue("Authorized real repeat fixtures not configured",paths.all{it!=null && File(it).isFile})
        val activities=paths.mapIndexed {i,path ->
            val parsed=File(path!!).inputStream().use {TrackParser.parse(it,"fixture.tcx")}
            val metrics=TrackAnalyzer.analyze(parsed.points,parsed.summary)
            ActivityEntity(id=(i+1).toLong(),title="private",sourceFileName="private",sourceFormat="TCX",trackEncoded=TrackCodec.encode(parsed.points),distanceM=metrics.distanceM,elevationGainM=metrics.elevationGainM,elevationLossM=metrics.elevationLossM,startedAt=parsed.points.first().timeEpochMs)
        }
        val groups=AutomaticRepeatRoutes.discover(activities,emptyList())
        assertEquals(1,groups.size);assertEquals(setOf(1L,2L,3L),groups.single().rows.map{it.activity.id}.toSet())
        assertTrue(groups.single().rows.all{it.seconds>0 && it.start<it.end})
        println("Real repeat geometry: three recordings grouped, neighbouring route rejected")
    }
    @Test fun constructedFourLargeRecordingsBoundedSignatures() {
        val points=(0 until 52000).map{i->TrackPoint(49.0+i*.0000001,-123.0,elevationM=200.0+i*600.0/51999,timeEpochMs=1700000000000L+i*1000L)}
        val encoded=TrackCodec.encode(points)
        val records=(1L..4L).map{id->activity(id).copy(trackEncoded=encoded)}
        val start=System.nanoTime()
        val groups=AutomaticRepeatRoutes.discover(records,emptyList())
        val elapsed=(System.nanoTime()-start)/1000000
        assertEquals(4,groups.single().rows.size);assertEquals(96,AutomaticRepeatRoutes.sample(points).size)
        assertTrue("Bounded 4 x 52k constructed grouping took $elapsed ms",elapsed<20000)
        println("Constructed repeat performance: 4 x 52000 points, $elapsed ms, 96-point pair signatures")
    }
    @Test fun explicitNaIsDistinctFromUnansweredAndAnsweredAgain() {
        assertTrue(RatingApplicability.states("").isEmpty())
        val cleared=RatingApplicability.save("legacy history",mapOf("descent" to "NA"))
        assertEquals("NA",RatingApplicability.states(cleared)["descent"])
        val answered=RatingApplicability.save(cleared,mapOf("descent" to "ANSWERED"))
        assertEquals("ANSWERED",RatingApplicability.states(answered)["descent"])
        assertTrue(answered.startsWith("legacy history"))
    }
}
