package com.mayushi.trailsense.domain

import org.junit.Assert.*
import org.junit.Test

class MissingStartRecoveryTest {
    private fun p(x: Int, y: Int = 0) = TrackPoint(49.0+x*.0001,-123.0+y*.0001)
    private fun recorded() = (20..100).map { p(it) }+(99 downTo 0).map { p(it) }
    private fun graph() = TrailGraph(mapOf(1L to p(0),2L to p(100)),mapOf(1L to listOf(2L),2L to listOf(1L)))
    @Test fun continuousEdgeProjectionDoesNotSnapToDistantVertices() {
        val route=graph().route(p(20),p(80))
        assertEquals(2,route.size);assertTrue(Geo.distanceM(p(20),route.first())<.01);assertTrue(Geo.distanceM(p(80),route.last())<.01)
        assertTrue(route.all { it.inferred && it.timeEpochMs==null && it.heartRateBpm==null && it.watchDistanceM==null })
    }
    @Test fun snappingDoesNotMutateGraph() {
        val g=graph();val original=g.edges.toMap();g.route(p(20),p(80));assertEquals(original,g.edges);assertEquals(2,g.nodes.size)
    }
    @Test fun automaticReversesWatchTailIncludingFinishNotOsmTrailhead() {
        val base=recorded();val parked=base+(1..8).map { p(-it,it) }
        val path=MissingStartRecovery.automatic(parked)
        assertTrue(Geo.distanceM(parked.last(),path.first())<.01)
        assertTrue(path.any { Geo.distanceM(p(0),it)<.01 })
        assertTrue(Geo.distanceM(base.first(),path.last())<.01)
        assertEquals(parked, (path+parked).drop(path.size))
        assertTrue(path.all { it.inferred && it.timeEpochMs==null && it.heartRateBpm==null && it.watchDistanceM==null && it.cadencePerMinute==null })
    }
    @Test fun returnJoinNotRestrictedToSecondSamplingHalf() {
        // Dense return pauses change point-count midpoint without changing return direction.
        val points=recorded().flatMapIndexed { index,point -> List(if(index>160)100 else 1){point} }
        val match=MissingStartRecovery.returnMatch(points)
        assertTrue(match.joinIndex < points.size/2);assertTrue(Geo.distanceM(points.first(),points[match.joinIndex])<=25)
    }
    @Test fun internalExplicitGapRefused() {
        val points=recorded().mapIndexed { i,it -> it.copy(segmentStart=i==80) }
        assertTrue(runCatching { MissingStartRecovery.automatic(points,graph()) }.isFailure)
    }
    @Test fun timedOutageRefused() {
        val points=recorded().mapIndexed { i,it -> it.copy(timeEpochMs=i*1000L+if(i>=80)400000L else 0L) }
        assertTrue(runCatching { MissingStartRecovery.returnMatch(points) }.isFailure)
    }
    @Test fun oneWayWithoutReturnRefused() {
        assertTrue(runCatching { MissingStartRecovery.returnMatch((20..200).map { p(it) }) }.isFailure)
    }
    @Test fun repeatedLoopReturnAmbiguityRefused() {
        val points=recorded()+recorded()
        assertTrue(runCatching { MissingStartRecovery.returnMatch(points) }.isFailure)
    }
    @Test fun automaticGraphCompatibilityOverloadIgnoresTerminalHypotheses() {
        val g=TrailGraph(mapOf(1L to p(0),2L to p(100),3L to p(8)),mapOf(1L to listOf(2L),2L to listOf(1L,3L),3L to listOf(2L)))
        assertEquals(MissingStartRecovery.automatic(recorded()),MissingStartRecovery.automatic(recorded(),g))
    }
    @Test fun automaticWorksWithoutAnyOsmGraph() {
        assertEquals(MissingStartRecovery.automatic(recorded()),MissingStartRecovery.automatic(recorded(),TrailGraph(emptyMap(),emptyMap())))
    }
    @Test fun wrongShorterBranchNotChosenOverRecordedReturn() {
        val nodes=mapOf(1L to p(0),2L to p(50,15),3L to p(100),4L to p(50,-30))
        val g=TrailGraph(nodes,mapOf(1L to listOf(2L,4L),2L to listOf(1L,3L),3L to listOf(2L,4L),4L to listOf(1L,3L)))
        val corridor=listOf(p(0),p(50,-30),p(100))
        val path=g.route(p(0),p(100),corridor=corridor)
        assertTrue(path.any { Geo.distanceM(it,p(50,-30))<.01 });assertFalse(path.any { Geo.distanceM(it,p(50,15))<.01 })
    }
    @Test fun joinProjectsInsideSparseReturnEdgeWithoutDroppingMissedDistance() {
        val points = (20..100).map { p(it) } + (99 downTo 0 step 3).map { p(it) }
        val prefix = MissingStartRecovery.automatic(points)
        assertTrue(Geo.distanceM(points.last(),prefix.first()) < .01)
        assertTrue(Geo.distanceM(points.first(),prefix.last()) < .01)
        assertEquals(Geo.distanceM(points.last(),points.first()),TrackGeometry.cumulative(prefix).last(),.1)
    }
    @Test fun manualOnTrackClipsReturnAtSelectedPoint() {
        val route = MissingStartRecovery.manual(recorded(),p(10))
        assertFalse(route.needsConfirmation)
        assertTrue(Geo.distanceM(p(10),route.points.first()) < .01)
        assertTrue(Geo.distanceM(p(20),route.points.last()) < .01)
        assertEquals(Geo.distanceM(p(10),p(20)),TrackGeometry.cumulative(route.points).last(),.1)
    }
    @Test fun manualOffTrackPreservesSelectedPointAndApproximateConnectorDistance() {
        val chosen = p(10,6)
        val route = MissingStartRecovery.manual(recorded(),chosen)
        assertTrue(route.needsConfirmation)
        assertEquals(Geo.distanceM(chosen,p(10)),route.connectorM,.1)
        assertTrue(Geo.distanceM(chosen,route.points.first()) < .01)
        assertEquals(route.connectorM + Geo.distanceM(p(10),p(20)),TrackGeometry.cumulative(route.points).last(),.1)
    }
    @Test fun manualAtRecordingStartDoesNotFabricatePrefix() {
        assertTrue(runCatching { MissingStartRecovery.manual(recorded(),p(20)) }.isFailure)
    }
    @Test fun manualWithoutReturnIsImpossibleAllowingTrailFallback() {
        assertTrue(runCatching { MissingStartRecovery.manual((20..200).map { p(it) },p(0)) }.isFailure)
    }
    @Test fun manualGapAndAmbiguousReturnRefused() {
        assertTrue(runCatching { MissingStartRecovery.manual(recorded().mapIndexed { i,p -> p.copy(segmentStart=i==80) },p(0)) }.isFailure)
        assertTrue(runCatching { MissingStartRecovery.manual(recorded()+recorded(),p(0)) }.isFailure)
    }
    @Test fun unmarkedSpatialJumpRefused() {
        val points = recorded().toMutableList(); points[80] = p(200,200)
        assertTrue(runCatching { MissingStartRecovery.automatic(points) }.isFailure)
    }
    @Test fun restoredPrefixHasNoWatchMetricsAndOriginalRemainsFieldExact() {
        val points = recorded().mapIndexed { i,p -> p.copy(elevationM=i.toDouble(),timeEpochMs=i*10_000L,heartRateBpm=120,watchDistanceM=i*10.0,cadencePerMinute=80) }
        val snapshot = points.toList()
        val prefix = MissingStartRecovery.automatic(points)
        val e = RecoveryEstimator.estimate(prefix,points)
        assertNull(e.seconds)
        assertTrue(e.points.all { it.inferred && it.timeEpochMs==null && it.heartRateBpm==null && it.watchDistanceM==null && it.cadencePerMinute==null && !it.segmentStart })
        assertEquals(snapshot,points)
        assertEquals(snapshot,(e.points+points).drop(e.points.size))
        assertEquals(TrackAnalyzer.analyze(snapshot),TrackAnalyzer.analyze(points))
    }
    @Test fun estimatorNeverUsesSameGradePaceOrMirroredReturnTime() {
        val points = (0..100).map { i -> p(i).copy(elevationM=i.toDouble(),timeEpochMs=i*10_000L,heartRateBpm=130,cadencePerMinute=90) }
        val up = RecoveryEstimator.estimate(points.take(50),points)
        val down = RecoveryEstimator.estimate(points.take(50).asReversed(),points)
        assertNull(up.seconds); assertNull(down.seconds)
        assertNotNull(up.gainM); assertNotNull(down.lossM)
        assertTrue(RecoveryEstimator.summary(up).contains("время неизвестно"))
    }
    @Test fun offTrackElevationAndTimeRemainUnknown() {
        val points = recorded().map { it.copy(elevationM=100.0,timeEpochMs=1000L) }
        val route = MissingStartRecovery.manual(points,p(10,10))
        val e = RecoveryEstimator.estimate(route.points,points)
        assertNull(e.gainM); assertNull(e.lossM); assertNull(e.seconds)
        assertNull(e.points.first().elevationM)
    }
    @Test fun explicitTrailFallbackDoesNotDropOffTrailStart() {
        val points = (20..200).map { p(it) }
        val selected = p(0,8)
        val route = MissingStartRecovery.trails(points,selected,graph())
        assertTrue(route.needsConfirmation)
        assertEquals(Geo.distanceM(selected,p(0)),route.connectorM,.1)
        assertTrue(Geo.distanceM(selected,route.points.first())<.01)
        assertTrue(Geo.distanceM(points.first(),route.points.last())<.01)
        assertTrue(route.points.all { it.inferred && it.timeEpochMs==null && it.heartRateBpm==null })
    }
    @Test fun disconnectedTrailFallbackDoesNotInventGraphConnection() {
        val nodes = mapOf(1L to p(0),2L to p(10),3L to p(20),4L to p(30))
        val g = TrailGraph(nodes,mapOf(1L to listOf(2L),2L to listOf(1L),3L to listOf(4L),4L to listOf(3L)))
        assertTrue(runCatching { MissingStartRecovery.trails((20..200).map { p(it) },p(0),g) }.isFailure)
    }
    @Test fun footOneWayHonoredIncludingVirtualSnaps() {
        val json="""{"elements":[{"type":"node","id":1,"lat":49,"lon":-123},{"type":"node","id":2,"lat":49.01,"lon":-123},{"type":"way","id":3,"nodes":[1,2],"tags":{"highway":"path","oneway:foot":"yes"}}]}"""
        val g=TrailGraph.parse(json)
        assertEquals(2,g.route(p(20),p(80)).size)
        assertTrue(runCatching { g.route(p(80),p(20)) }.isFailure)
    }
}
