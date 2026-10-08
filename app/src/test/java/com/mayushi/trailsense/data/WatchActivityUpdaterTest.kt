package com.mayushi.trailsense.data

import com.mayushi.trailsense.domain.TrackFormat
import com.mayushi.trailsense.domain.TrackMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchActivityUpdaterTest {
    @Test fun `reimport updates metrics in place and preserves personal fields`() {
        val existing = ActivityEntity(
            id = 42,
            routeId = 7,
            title = "Golden Ears",
            sourceFileName = "old.tcx",
            sourceFormat = "TCX",
            trackEncoded = "old-track",
            distanceM = 14_900.0,
            elevationGainM = 1_290.0,
            elevationLossM = 1_280.0,
            overallEffort = 5,
            sleepHours = 7.5,
            comment = "Очень крутой подъём",
            createdAt = 100,
            updatedAt = 100,
        )
        val metrics = TrackMetrics(
            distanceM = 15_980.0,
            elevationGainM = 1_312.0,
            elevationLossM = 1_310.8,
            elapsedSeconds = 32_826,
            movingSeconds = 22_103,
            stoppedSeconds = 10_723,
            averagePaceMinutesPerKm = 23.1,
            averageHeartRateBpm = 134,
            maxHeartRateBpm = 171,
            segments = emptyList(),
        )

        val updated = WatchActivityUpdater.merge(
            existing = existing,
            routeId = 7,
            routeTitle = "Golden Ears",
            fileName = "new.tcx",
            format = TrackFormat.TCX,
            encodedTrack = "new-track",
            startedAt = 200,
            metrics = metrics,
            now = 300,
        )

        assertEquals(42, updated.id)
        assertEquals("new.tcx", updated.sourceFileName)
        assertEquals("new-track", updated.trackEncoded)
        assertEquals(15_980.0, updated.distanceM, 0.01)
        assertEquals(1_312.0, updated.elevationGainM, 0.01)
        assertEquals(1_310.8, updated.elevationLossM, 0.01)
        assertEquals(32_826L, updated.elapsedSeconds)
        assertEquals(5, updated.overallEffort)
        assertEquals(7.5, updated.sleepHours)
        assertEquals("Очень крутой подъём", updated.comment)
        assertEquals(100L, updated.createdAt)
        assertEquals(300L, updated.updatedAt)
    }

    @Test fun `first import creates linked activity`() {
        val metrics = TrackMetrics(1_000.0, 100.0, 90.0, 600, 500, 100, 8.3, null, null, emptyList())

        val created = WatchActivityUpdater.merge(null, 7, "Route", "watch.tcx", TrackFormat.TCX, "track", 123, metrics, 999)

        assertEquals(0L, created.id)
        assertEquals(7L, created.routeId)
        assertEquals(90.0, created.elevationLossM, 0.01)
        assertTrue(created.comment.contains("спуск"))
    }
}
