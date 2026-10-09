package com.mayushi.trailsense.domain

import org.junit.Assert.*
import org.junit.Test

class Release036Test {
    private val points = listOf(TrackPoint(49.0,-123.0,watchDistanceM=0.0),TrackPoint(49.001,-123.0,watchDistanceM=900.0),TrackPoint(49.002,-123.0,watchDistanceM=1000.0))
    @Test fun nonuniformWatchAxisNotUniformGps() {
        val axis=TrackDistanceAxis.build(points,1000.0)
        assertArrayEquals(doubleArrayOf(0.0,900.0,1000.0),axis.meters,.001)
        assertEquals(points.drop(1),TrackCorrection.trim(points,.8,1.0,1000.0))
        assertEquals(points,TrackCodec.decode(TrackCodec.encode(points)))
    }
    @Test fun invalidResetMissingAndNanFallbacks() {
        listOf(listOf(0.0,900.0,100.0),listOf(0.0,Double.NaN,1000.0),listOf(0.0,0.0,0.0)).forEach { values ->
            val p=points.mapIndexed { i,it -> it.copy(watchDistanceM=values[i]) }
            val a=TrackDistanceAxis.build(p,1000.0)
            assertEquals(500.0,a.meters[1],.1);assertEquals(1000.0,a.meters.last(),.001)
            assertTrue(a.provenance.contains("пропорционально"))
        }
        assertEquals(500.0,TrackDistanceAxis.build(points.map { it.copy(watchDistanceM=null) },1000.0).meters[1],.1)
    }
    @Test fun inferredPrefixDoesNotRescaleRecordedWatch() {
        val prefix=listOf(TrackPoint(48.999,-123.0,inferred=true),TrackPoint(49.0,-123.0,inferred=true))
        val axis=TrackDistanceAxis.build(prefix+points,1000.0).meters
        assertEquals(1000.0,axis.last()-axis[2],.001)
        assertEquals(900.0,axis[3]-axis[2],.001)
    }
    @Test fun commentsMergeIdempotentlyPreservesHistoricalTexts() {
        val merged=HikeComments.merge("Первое","Второе")
        assertEquals("Первое\n\nВторое",merged)
        assertEquals(merged,HikeComments.merge(merged,"Второе"))
        assertEquals("Первое",HikeComments.merge("Первое","Первое"))
        assertEquals(merged,HikeComments.merge(merged,""))
    }
    @Test fun noInventedConnectionAndUnknownElevationTime() {
        val path=listOf(TrackPoint(48.99,-123.0,inferred=true),TrackPoint(48.995,-123.0,inferred=true))
        val e=RecoveryEstimator.estimate(path,points)
        assertNull(e.gainM);assertNull(e.lossM);assertNull(e.seconds)
        assertEquals(e.distanceM,TrackGeometry.cumulative(path+points)[2],.001)
    }
    @Test fun shortestGraphRouteRestrictedAndDisconnected() {
        val nodes=mapOf(1L to TrackPoint(49.0,-123.0),2L to TrackPoint(49.001,-123.0),3L to TrackPoint(49.001,-123.001),4L to TrackPoint(49.002,-123.0))
        val graph=TrailGraph(nodes,mapOf(1L to listOf(2L),2L to listOf(1L,3L),3L to listOf(2L),4L to emptyList()))
        assertEquals(listOf(nodes[1L],nodes[2L],nodes[3L]).map { it!!.copy(inferred=true) },graph.route(nodes[1L]!!,nodes[3L]!!))
        assertTrue(runCatching { graph.route(nodes[1L]!!,nodes[4L]!!) }.isFailure)
        val json="""{"elements":[{"type":"node","id":1,"lat":49,"lon":-123},{"type":"node","id":2,"lat":49.001,"lon":-123},{"type":"way","id":8,"nodes":[1,2],"tags":{"highway":"path","access":"private"}}]}"""
        assertTrue(TrailGraph.parse(json).edges.isEmpty())
    }
    @Test fun estimatorUsesSameDirectionSimilarGradesNotMirroredReturnTime() {
        val recorded=(0..100).map { i -> TrackPoint(49+i*.0001,-123.0,elevationM=i*1.0,timeEpochMs=i*10_000L) }
        val up=recorded.take(50).map { it.copy(inferred=true,timeEpochMs=null) }
        val downhill=up.asReversed()
        assertNotNull(RecoveryEstimator.estimate(up,recorded).seconds)
        assertNull(RecoveryEstimator.estimate(downhill,recorded).seconds)
        assertNotNull(RecoveryEstimator.estimate(downhill,recorded).gainM)
    }
}
