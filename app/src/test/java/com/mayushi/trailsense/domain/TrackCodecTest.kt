package com.mayushi.trailsense.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackCodecTest {
    @Test fun `round trips normalized points`() {
        val points = listOf(TrackPoint(49.1, -123.2, 10.0, 42, 130))
        assertEquals(points, TrackCodec.decode(TrackCodec.encode(points)))
    }
}
