package com.mayushi.trailsense.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {
    @Test fun `one degree latitude is about 111 km`() {
        assertEquals(111_195.0, Geo.distanceM(TrackPoint(49.0, -123.0), TrackPoint(50.0, -123.0)), 100.0)
    }
}
