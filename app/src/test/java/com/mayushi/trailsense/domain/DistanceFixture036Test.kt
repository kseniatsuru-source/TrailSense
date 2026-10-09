package com.mayushi.trailsense.domain

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DistanceFixture036Test {
    @Test fun actualTcxWatchAxisAndSelectedPortions() {
        val paths=System.getenv("TRAILSENSE036_TCX")?.split(File.pathSeparator).orEmpty()
        assumeTrue("Private TCX fixture paths were not provided",paths.isNotEmpty())
        paths.forEach { path ->
            val parsed=File(path).inputStream().use { TrackParser.parse(it,"activity.tcx") }
            val metrics=TrackAnalyzer.analyze(parsed.points,parsed.summary)
            val axis=TrackDistanceAxis.build(parsed.points,metrics.distanceM)
            assertEquals(metrics.distanceM,axis.meters.last(),.001)
            val encoded=TrackCodec.encode(parsed.points)
            assertEquals(parsed.points,TrackCodec.decode(encoded))
            val selected=TrackCorrection.trim(parsed.points,.1,.85,metrics.distanceM)
            assertTrue(selected.size < parsed.points.size)
            assertEquals(parsed.points.subList(parsed.points.indexOf(selected.first()),parsed.points.indexOf(selected.last())+1),selected)
            assertEquals(encoded,TrackCodec.encode(parsed.points))
            println("036 private fixture points=${parsed.points.size}, watch=${metrics.distanceM}, gps=${TrackGeometry.cumulative(parsed.points).last()}, pointDistanceSamples=${parsed.points.count { it.watchDistanceM != null }}, provenance=${axis.provenance}")
        }
    }
}
