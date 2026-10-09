package com.mayushi.trailsense.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackCorrectionTest {
    @Test fun `missing beginning of out and back is reconstructed from recorded return`() {
        val points = listOf(
            point(1, 1_000),
            point(2, 2_000),
            point(3, 3_000),
            point(2, 4_000),
            point(1, 5_000),
            point(0, 6_000),
        )

        val corrected = TrackCorrection.inferMissingOutAndBackStart(points, maxJoinDistanceM = 5.0)

        assertEquals(8, corrected.size)
        assertEquals(-123.0, corrected.first().longitude, 0.000001)
        assertEquals(null, corrected.first().timeEpochMs)
        assertEquals(null, corrected[1].timeEpochMs)
        assertEquals(points, corrected.drop(2))
        assertEquals(-123.0, corrected.last().longitude, 0.000001)
        assertTrue(TrackAnalyzer.analyze(corrected).distanceM > TrackAnalyzer.analyze(points).distanceM)
    }

    @Test fun `trim keeps selected point interval and recalculates endpoint`() {
        val points = (0..9).map { point(it, it * 1_000L) }

        val corrected = TrackCorrection.trim(points, startFraction = 0.0, endFraction = 0.6)

        assertEquals(6, corrected.size)
        assertEquals(-123.0, corrected.first().longitude, 0.000001)
        assertEquals(-122.95, corrected.last().longitude, 0.000001)
        assertEquals(5_000L, corrected.last().timeEpochMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing start inference rejects a track that never returns near its start`() {
        val points = (0..5).map { point(it, it * 1_000L) }
        TrackCorrection.inferMissingOutAndBackStart(points, maxJoinDistanceM = 0.5)
    }

    private fun point(x: Int, time: Long) = TrackPoint(
        latitude = 49.0,
        longitude = -123.0 + x * 0.01,
        elevationM = 100.0 + x,
        timeEpochMs = time,
    )
}
