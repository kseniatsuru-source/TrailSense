package com.mayushi.trailsense.domain

object TrackGeometry {
    /** Explicit segment or timed outage; never draw/count an invented straight connector. */
    fun disconnected(a: TrackPoint, b: TrackPoint): Boolean = b.segmentStart ||
        (a.timeEpochMs != null && b.timeEpochMs != null && b.timeEpochMs - a.timeEpochMs > 300_000)
    fun cumulative(points: List<TrackPoint>): DoubleArray {
        val result = DoubleArray(points.size)
        for (i in 1 until points.size) result[i] = result[i-1] + if (disconnected(points[i-1], points[i])) 0.0 else Geo.distanceM(points[i-1], points[i])
        return result
    }
    fun split(points: List<TrackPoint>): List<List<TrackPoint>> {
        if (points.isEmpty()) return emptyList()
        val result = mutableListOf<List<TrackPoint>>()
        var start = 0
        for (i in 1 until points.size) if (disconnected(points[i-1], points[i]) || points[i].inferred != points[i-1].inferred) {
            result += points.subList(start, if (disconnected(points[i-1], points[i])) i else i+1)
            start = i
        }
        result += points.subList(start, points.size)
        return result
    }
}
