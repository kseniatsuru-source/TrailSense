package com.mayushi.trailsense.network

import com.mayushi.trailsense.data.ActivityEntity
import com.mayushi.trailsense.data.RouteEntity
import org.junit.Assert.*
import org.junit.Test

class AiResultMergerTest {
    @Test fun `merges imported route facts without overwriting user fields`() {
        val route = RouteEntity(id = 7, title = "Старое", overallDifficulty = 4, notes = "Моя заметка")
        val parsed = AiResponseParser.parse("""{"summary":"Итог","route_facts":{"title":"Новое","distance_m":15980,"gain_m":1312,"loss_m":1310.8,"difficulty":"Hard","rating":4.7},"activity_facts":null}""")

        val merged = AiResultMerger.mergeRoute(route, parsed, updatedAt = 999)

        assertEquals("Старое", merged.title)
        assertEquals(15980.0, merged.distanceM!!, 0.01)
        assertEquals(4, merged.overallDifficulty)
        assertEquals("Моя заметка", merged.notes)
        assertEquals(999L, merged.updatedAt)
    }

    @Test fun `merges imported activity facts without overwriting questionnaire`() {
        val activity = ActivityEntity(
            id = 8,
            title = "Старое",
            sourceFileName = "watch.tcx",
            sourceFormat = "TCX",
            trackEncoded = "[]",
            distanceM = 1.0,
            elevationGainM = 2.0,
            elevationLossM = 3.0,
            overallEffort = 5,
            comment = "Очень тяжело",
        )
        val parsed = AiResponseParser.parse("""{"summary":"Итог","route_facts":null,"activity_facts":{"title":"Поход","distance_m":7400,"gain_m":620,"loss_m":615,"elapsed_s":10800,"moving_s":9000,"stopped_s":1800,"avg_hr":132,"max_hr":166}}""")

        val merged = AiResultMerger.mergeActivity(activity, parsed, updatedAt = 1000)

        assertEquals(activity, merged)
    }
}
