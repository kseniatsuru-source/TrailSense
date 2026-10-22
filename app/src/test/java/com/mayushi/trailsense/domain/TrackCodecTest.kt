package com.mayushi.trailsense.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackCodecTest {
    @Test fun `cadence preserves new measurements without changing legacy bytes`() {
        val legacy=listOf(TrackPoint(49.1,-123.2,10.0,42,130))
        val oldBytes=TrackCodec.encode(legacy)
        assertEquals(3,java.io.DataInputStream(java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(oldBytes))).readInt())
        assertEquals(oldBytes,TrackCodec.encode(TrackCodec.decode(oldBytes)))
        val measured=legacy.map{it.copy(cadencePerMinute=82)}
        assertEquals(measured,TrackCodec.decode(TrackCodec.encode(measured)))
    }
    @Test fun `round trips normalized points`() {
        val points = listOf(TrackPoint(49.1, -123.2, 10.0, 42, 130))
        assertEquals(points, TrackCodec.decode(TrackCodec.encode(points)))
    }
}
