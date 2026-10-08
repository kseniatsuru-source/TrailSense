package com.mayushi.trailsense.domain

import kotlin.math.*

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceM(a: TrackPoint, b: TrackPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
