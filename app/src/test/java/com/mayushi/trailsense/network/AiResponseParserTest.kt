package com.mayushi.trailsense.network

import org.junit.Assert.*
import org.junit.Test

class AiResponseParserTest {
    @Test fun `extracts summary and route facts from fenced json`() {
        val parsed = AiResponseParser.parse("""```json
          {"summary":"Тяжёлый подъём.","route_facts":{"title":"Phelix","distance_m":10000,"gain_m":1148.8,"loss_m":null,"difficulty":"Hard","rating":4.5}}
        ```""")
        assertEquals("Тяжёлый подъём.", parsed.summary)
        assertEquals("Phelix", parsed.routeFacts?.title)
        assertEquals(1148.8, parsed.routeFacts?.gainM!!, 0.01)
        assertNull(parsed.routeFacts?.lossM)
    }

    @Test fun `keeps plain text when response is not json`() {
        val parsed = AiResponseParser.parse("Короткий ответ")
        assertEquals("Короткий ответ", parsed.summary)
        assertNull(parsed.routeFacts)
    }

    @Test fun `extracts Huawei screenshot activity facts`() {
        val parsed = AiResponseParser.parse("""{"summary":"Проверь время вручную.","route_facts":null,"activity_facts":{"title":"Hike","distance_m":7400,"gain_m":620,"loss_m":615,"elapsed_s":10800,"moving_s":9000,"stopped_s":1800,"avg_hr":132,"max_hr":166}}""")
        assertEquals("Проверь время вручную.", parsed.summary)
        assertEquals(7400.0, parsed.activityFacts?.distanceM!!, 0.01)
        assertEquals(10800L, parsed.activityFacts?.elapsedSeconds)
        assertEquals(166, parsed.activityFacts?.maxHeartRateBpm)
    }
}
