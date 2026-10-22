package com.mayushi.trailsense.domain

import com.mayushi.trailsense.data.ActivityEntity
import com.mayushi.trailsense.data.RouteEntity
import com.mayushi.trailsense.network.AllTrailsMcpClient
import org.junit.Assert.*
import org.junit.Test

class ConfirmedRoutePrioritizer037Test {
    private val url = "https://www.alltrails.com/trail/canada/british-columbia/grouse-grind"
    private val route = RouteEntity(id = 7, title = "Grouse Grind", sourceUrl = url, sourceLabel = "AllTrails + TCX с часов", distanceM = 2800.0, elevationGainM = 800.0)
    private fun shape(offset: Double = 0.0) = List(31) { i -> TrackPoint(49.35 + i * .0005, -123.1 + offset, timeEpochMs = i * 60_000L) }
    private fun activity(id: Long, points: List<TrackPoint> = shape(), routeId: Long? = null) = ActivityEntity(
        id = id, routeId = routeId, title = "Recorded hike", sourceFileName = "$id.tcx", sourceFormat = "TCX", trackEncoded = TrackCodec.encode(points),
        distanceM = 2800.0, elevationGainM = 800.0, elevationLossM = 20.0, elapsedSeconds = 4500, averageHeartRateBpm = 155,
    )
    private val nearby = AllTrailsMcpClient.Candidate(99, "BCMC", "https://www.alltrails.com/trail/canada/british-columbia/bcmc-trail", 2700.0, 820.0, "out-and-back", "nearby")

    @Test fun confirmedOmittedByMcpStillRanksFirstWithActualMetadata() {
        val current = activity(2, shape(.000015))
        val old = activity(1, routeId = 7)
        val result = ConfirmedRoutePrioritizer.rank(current, listOf(route), listOf(old, current), listOf(nearby))
        assertEquals(2, result.size)
        assertTrue(result.first().priorConfirmed)
        assertEquals(url, result.first().candidate.url)
        assertEquals("Grouse Grind", result.first().candidate.name)
        assertEquals(route.sourceLabel, result.first().sourceLabel)
        assertEquals(0L, result.first().candidate.id)
        assertEquals(2800.0, result.first().candidate.length!!, 0.0)
        assertEquals(155, current.averageHeartRateBpm); assertEquals(4500L, current.elapsedSeconds); assertEquals(2800.0, current.distanceM, 0.0)
        assertNull(current.routeId)
    }
    @Test fun nearbyBcmcEvenSharedEndpointsIsNotSameShape() {
        val nearbyShape = shape(.004).mapIndexed { i, p -> if (i == 0 || i == 30) shape()[i] else p }
        assertNull(ConfirmedRoutePrioritizer.similarity(shape(), nearbyShape))
        assertTrue(ConfirmedRoutePrioritizer.rank(activity(2, nearbyShape), listOf(route), listOf(activity(1, routeId = 7)), emptyList()).isEmpty())
    }
    @Test fun suggestionsSelfAndInvalidSourcesDoNotBecomeConfirmed() {
        val suggested = activity(1).copy(suggestedRouteId = 7)
        val current = activity(2, routeId = 7)
        assertTrue(ConfirmedRoutePrioritizer.rank(current, listOf(route), listOf(suggested, current), emptyList()).isEmpty())
        listOf(route.copy(sourceUrl = "https://alltrails.com.evil.example/trail/canada/bc/grind"), route.copy(sourceLabel = "Вручную"), route.copy(title = ""), route.copy(sourceUrl = "http://www.alltrails.com/trail/canada/bc/grind")).forEach {
            assertTrue(ConfirmedRoutePrioritizer.rank(current, listOf(it), listOf(activity(1, routeId = 7)), emptyList()).isEmpty())
        }
    }
    @Test fun freshCandidateMetricsAndIdPreservedAndDuplicateCacheMerged() {
        val fresh = AllTrailsMcpClient.Candidate(100, "Fresh name", url, 3000.0, 900.0, "loop", "Canada", "Curated description")
        val result = ConfirmedRoutePrioritizer.rank(activity(2), listOf(route), listOf(activity(1, routeId = 7)), listOf(nearby, fresh))
        assertEquals(2, result.size); assertEquals(fresh, result.first().candidate)
    }
    @Test fun arbitraryAscentWorksNotHardcodedToGrouseAndBadTracksUnknown() {
        val generic = route.copy(title = "Another ascent", sourceUrl = "https://alltrails.com/trail/country/province/another-ascent")
        assertEquals("Another ascent", ConfirmedRoutePrioritizer.rank(activity(2), listOf(generic), listOf(activity(1, routeId = 7)), emptyList()).single().candidate.name)
        val invalid = listOf(activity(1, routeId = 7).copy(trackCorrectionMode = "INFERRED_START"), activity(1, shape().map { it.copy(inferred = true) }, 7), activity(1, shape().take(2), 7))
        invalid.forEach { assertTrue(ConfirmedRoutePrioritizer.rank(activity(2), listOf(route), listOf(it), emptyList()).isEmpty()) }
    }
    @Test fun partialSubsetDoesNotQualifyAndLocalizedUrlDedupes() {
        assertNull(ConfirmedRoutePrioritizer.similarity(shape(), shape().take(10)))
        val localized = nearby.copy(id = 22, url = "https://alltrails.com/en-ca/trail/canada/british-columbia/grouse-grind")
        val ranked = ConfirmedRoutePrioritizer.rank(activity(2), listOf(route), listOf(activity(1, routeId = 7)), listOf(localized))
        assertEquals(1, ranked.size); assertEquals(localized, ranked.single().candidate)
    }
}
