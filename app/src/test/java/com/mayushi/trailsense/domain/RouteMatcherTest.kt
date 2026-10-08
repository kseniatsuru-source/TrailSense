package com.mayushi.trailsense.domain

import org.junit.Assert.*
import org.junit.Test

class RouteMatcherTest {
    @Test fun `suggests same route even when reversed`() {
        val route = (0..20).map { TrackPoint(49.0 + it * 0.001, -123.0) }
        val match = RouteMatcher.match(route.reversed(), route)
        assertTrue(match.shouldSuggest)
        assertTrue(match.directionReversed)
    }

    @Test fun `does not suggest distant track`() {
        val route = listOf(TrackPoint(49.0, -123.0), TrackPoint(49.01, -123.0))
        val activity = listOf(TrackPoint(50.0, -122.0), TrackPoint(50.01, -122.0))
        assertFalse(RouteMatcher.match(activity, route).shouldSuggest)
    }
}
