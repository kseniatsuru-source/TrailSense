package com.mayushi.trailsense.domain

import org.junit.Assert.*
import org.junit.Test

class TrackAnalyzerTest {
    @Test fun `calculates moving and stopped time with elevation noise filter`() {
        val p = listOf(
            TrackPoint(49.0, -123.0, 100.0, 0, 100),
            TrackPoint(49.0009, -123.0, 102.0, 100_000, 110),
            TrackPoint(49.0009, -123.0, 102.0, 160_000, 105),
            TrackPoint(49.0018, -123.0, 110.0, 260_000, 130),
        )
        val m = TrackAnalyzer.analyze(p)
        assertEquals(200.0, m.distanceM, 5.0)
        assertEquals(10.0, m.elevationGainM, 0.1)
        assertEquals(260L, m.elapsedSeconds)
        assertEquals(200L, m.movingSeconds)
        assertEquals(60L, m.stoppedSeconds)
        assertEquals(111, m.averageHeartRateBpm)
        assertEquals(130, m.maxHeartRateBpm)
    }

    @Test fun `Huawei summary overrides GPS-derived distance gain and elapsed time`() {
        val points = listOf(
            TrackPoint(49.0, -123.0, 100.0, 0),
            TrackPoint(49.01, -123.0, 200.0, 30_000),
        )
        val summary = TrackSummary(
            distanceM = 15_980.0,
            elevationGainM = 1_312.0,
            elevationLossM = 1_310.8,
            elapsedSeconds = 32_826,
        )

        val metrics = TrackAnalyzer.analyze(points, summary)

        assertEquals(15_980.0, metrics.distanceM, 0.01)
        assertEquals(1_312.0, metrics.elevationGainM, 0.01)
        assertEquals(1_310.8, metrics.elevationLossM, 0.01)
        assertEquals(32_826L, metrics.elapsedSeconds)
    }

    @Test fun `slow continuous hiking is not classified as stopped`() {
        val points = mutableListOf<TrackPoint>()
        var latitude = 49.0
        for (second in 0..600) {
            val isResting = second in 241..360
            if (!isResting && second > 0) latitude += 0.2 / 111_111.0
            points += TrackPoint(latitude, -123.0, 100.0, second * 1_000L)
        }

        val metrics = TrackAnalyzer.analyze(points, TrackSummary(elapsedSeconds = 600))

        assertTrue("slow hiking should remain mostly moving", metrics.movingSeconds!! >= 450)
        assertTrue("the sustained two-minute rest should still be detected", metrics.stoppedSeconds!! in 60..150)
    }
}
