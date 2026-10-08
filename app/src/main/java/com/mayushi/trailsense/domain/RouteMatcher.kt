package com.mayushi.trailsense.domain

import kotlin.math.abs
import kotlin.math.exp

object RouteMatcher {
    fun match(activity: List<TrackPoint>, route: List<TrackPoint>): RouteMatch {
        require(activity.size >= 2 && route.size >= 2)
        val activityDistance = polylineDistance(activity)
        val routeDistance = polylineDistance(route)
        val lengthDifference = abs(activityDistance - routeDistance) / routeDistance.coerceAtLeast(1.0)
        val sampledActivity = sample(activity, 80)
        val sampledRoute = sample(route, 160)
        val normalStartEnd = Geo.distanceM(activity.first(), route.first()) + Geo.distanceM(activity.last(), route.last())
        val reversedStartEnd = Geo.distanceM(activity.first(), route.last()) + Geo.distanceM(activity.last(), route.first())
        val reversed = reversedStartEnd < normalStartEnd
        val nearestMean = sampledActivity.map { point -> sampledRoute.minOf { Geo.distanceM(point, it) } }.average()
        val geometryScore = exp(-nearestMean / 120.0)
        val lengthScore = exp(-lengthDifference * 3.0)
        val endpointScore = exp(-minOf(normalStartEnd, reversedStartEnd) / 500.0)
        val confidence = (geometryScore * 0.55 + lengthScore * 0.25 + endpointScore * 0.20).coerceIn(0.0, 1.0)
        return RouteMatch(confidence, nearestMean, lengthDifference, reversed)
    }

    private fun polylineDistance(points: List<TrackPoint>) = points.zipWithNext().sumOf { Geo.distanceM(it.first, it.second) }
    private fun sample(points: List<TrackPoint>, max: Int): List<TrackPoint> {
        if (points.size <= max) return points
        val step = (points.size - 1).toDouble() / (max - 1)
        return List(max) { points[(it * step).toInt().coerceAtMost(points.lastIndex)] }
    }
}
