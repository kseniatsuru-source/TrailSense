package com.mayushi.trailsense.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class HikeDateResolverTest {
    @Test fun `existing encoded track is reindexed from its first embedded timestamp`() {
        val encoded = TrackCodec.encode(listOf(
            TrackPoint(49.0, -123.0, timeEpochMs = null),
            TrackPoint(49.1, -123.1, timeEpochMs = 1_780_423_400_000L),
            TrackPoint(49.2, -123.2, timeEpochMs = 1_780_423_460_000L),
        ))

        assertEquals(1_780_423_400_000L, HikeDateResolver.fromEncodedTrack(encoded))
    }

    @Test fun `track correction never replaces the imported hike date`() {
        val corrected = listOf(
            TrackPoint(49.1, -123.1, timeEpochMs = 1_780_500_000_000L),
            TrackPoint(49.2, -123.2, timeEpochMs = 1_780_500_060_000L),
        )

        assertEquals(1_780_423_400_000L, HikeDateResolver.afterTrackCorrection(1_780_423_400_000L, corrected))
    }
}