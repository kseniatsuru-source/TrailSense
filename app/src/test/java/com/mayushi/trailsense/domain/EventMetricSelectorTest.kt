package com.mayushi.trailsense.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class EventMetricSelectorTest {
    @Test fun `watch file distance gain and loss override AllTrails values`() {
        val metrics = EventMetricSelector.choose(
            routeDistanceM = 10_000.0,
            routeElevationGainM = 500.0,
            routeElevationLossM = 480.0,
            watchDistanceM = 10_850.0,
            watchElevationGainM = 640.0,
            watchElevationLossM = 625.0,
        )

        assertEquals(10_850.0, metrics.distanceM)
        assertEquals(640.0, metrics.elevationGainM)
        assertEquals(625.0, metrics.elevationLossM)
        assertEquals(EventMetricSource.WATCH_FILE, metrics.source)
    }

    @Test fun `route values remain until a watch file is attached`() {
        val metrics = EventMetricSelector.choose(
            routeDistanceM = 10_000.0,
            routeElevationGainM = 500.0,
            routeElevationLossM = 480.0,
            watchDistanceM = null,
            watchElevationGainM = null,
            watchElevationLossM = null,
        )

        assertEquals(null, metrics.distanceM)
        assertEquals(null, metrics.elevationGainM)
        assertEquals(null, metrics.elevationLossM)
        assertEquals(EventMetricSource.ROUTE, metrics.source)
    }
}
