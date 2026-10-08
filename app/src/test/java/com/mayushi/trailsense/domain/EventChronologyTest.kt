package com.mayushi.trailsense.domain

import com.mayushi.trailsense.data.ActivityEntity
import com.mayushi.trailsense.data.RouteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventChronologyTest {
    @Test fun `hikes are sorted only by timestamp recorded in watch file`() {
        val editedRecently = route(id = 1, title = "Old", createdAt = 9_000, updatedAt = 99_000)
        val newerHike = route(id = 2, title = "New", createdAt = 100, updatedAt = 100)
        val activities = listOf(
            activity(id = 11, routeId = 1, startedAt = 2_000, createdAt = 80_000),
            activity(id = 22, routeId = 2, startedAt = 3_000, createdAt = 200),
        )

        assertEquals(listOf(2L, 1L), EventChronology.sortRoutes(listOf(editedRecently, newerHike), activities).map { it.id })
    }

    @Test fun `activity without watch timestamp never supplies a hike date`() {
        val route = route(id = 1, title = "Undated", createdAt = 1, updatedAt = 1)
        val activity = activity(id = 10, routeId = 1, startedAt = null, createdAt = 999_999)

        assertEquals(activity, EventChronology.latestActivityByRoute(listOf(activity))[route.id])
        assertNull(EventChronology.watchDateForRoute(route.id, listOf(activity)))
    }

    @Test fun `undated routes stay below dated hikes and are not ordered by creation date`() {
        val zulu = route(id = 1, title = "Zulu", createdAt = 99_000, updatedAt = 99_000)
        val alpha = route(id = 2, title = "Alpha", createdAt = 1, updatedAt = 1)
        val dated = route(id = 3, title = "Dated", createdAt = 50, updatedAt = 50)
        val sorted = EventChronology.sortRoutes(
            listOf(zulu, alpha, dated),
            listOf(activity(id = 30, routeId = 3, startedAt = 5_000, createdAt = 999_999)),
        )

        assertEquals(listOf(3L, 2L, 1L), sorted.map { it.id })
    }

    @Test fun `latest activity per route ignores undated imports`() {
        val dated = activity(id = 1, routeId = 7, startedAt = 2_000, createdAt = 1)
        val undatedButAddedLater = activity(id = 2, routeId = 7, startedAt = null, createdAt = 999_999)

        assertEquals(1L, EventChronology.latestActivityByRoute(listOf(dated, undatedButAddedLater)).getValue(7).id)
    }

    private fun route(id: Long, title: String, createdAt: Long, updatedAt: Long) = RouteEntity(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun activity(id: Long, routeId: Long, startedAt: Long?, createdAt: Long) = ActivityEntity(
        id = id,
        routeId = routeId,
        title = "Hike $id",
        sourceFileName = "hike-$id.tcx",
        sourceFormat = "TCX",
        trackEncoded = "",
        startedAt = startedAt,
        distanceM = 10_000.0,
        elevationGainM = 500.0,
        elevationLossM = 500.0,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
