package com.mayushi.trailsense.network

import org.junit.Assert.*
import org.junit.Test

class AiResponseValidationTest {
    @Test fun `strict parser rejects impossible route values`() {
        assertFails("distance_m") {
            AiResponseParser.parseStrict("""{"summary":"x","route_facts":{"distance_m":-1}}""")
        }
        assertFails("rating") {
            AiResponseParser.parseStrict("""{"summary":"x","route_facts":{"rating":8}}""")
        }
        assertFails("title") {
            AiResponseParser.parseStrict("""{"summary":"x","route_facts":{"title":"${"x".repeat(201)}"}}""")
        }
    }

    @Test fun `strict parser rejects inconsistent activity values`() {
        assertFails("moving_s") {
            AiResponseParser.parseStrict("""{"summary":"x","activity_facts":{"elapsed_s":100,"moving_s":101}}""")
        }
        assertFails("avg_hr") {
            AiResponseParser.parseStrict("""{"summary":"x","activity_facts":{"avg_hr":260}}""")
        }
        assertFails("avg_hr") {
            AiResponseParser.parseStrict("""{"summary":"x","activity_facts":{"avg_hr":180,"max_hr":170}}""")
        }
    }

    @Test fun `strict parser accepts plausible facts`() {
        val parsed = AiResponseParser.parseStrict("""{"summary":"ok","activity_facts":{"distance_m":7400,"elapsed_s":10800,"moving_s":9000,"stopped_s":1800,"avg_hr":132,"max_hr":166}}""")
        assertEquals(7400.0, parsed.activityFacts!!.distanceM!!, 0.01)
    }

    private fun assertFails(field: String, block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains(field))
        }
    }
}
