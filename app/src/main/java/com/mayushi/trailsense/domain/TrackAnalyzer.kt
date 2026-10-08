package com.mayushi.trailsense.domain

import kotlin.math.abs
import kotlin.math.roundToInt

object TrackAnalyzer {
    private const val SPARSE_TRACK_MOVING_SPEED_MPS = 0.5
    private const val DENSE_SAMPLE_MAX_SECONDS = 10.0
    private const val PAUSE_WINDOW_SECONDS = 60L
    private const val PAUSE_DISPLACEMENT_M = 5.0
    private const val ELEVATION_NOISE_M = 3.0
    private const val SEGMENT_TARGET_M = 500.0

    fun analyze(points: List<TrackPoint>, summary: TrackSummary? = null): TrackMetrics {
        require(points.size >= 2) { "Для анализа нужно хотя бы две точки трека" }
        var gpsDistance = 0.0
        var calculatedGain = 0.0
        var calculatedLoss = 0.0
        var elevationAnchor = points.first().elevationM
        var pointElapsedSeconds = 0L
        val cumulative = DoubleArray(points.size)
        val legs = DoubleArray(points.size)

        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val current = points[i]
            val broken = TrackGeometry.disconnected(previous, current)
            if (broken) elevationAnchor = current.elevationM
            val leg = (if (broken) 0.0 else Geo.distanceM(previous, current)).takeIf { it.isFinite() } ?: 0.0
            legs[i] = leg
            gpsDistance += leg
            cumulative[i] = gpsDistance

            val elevation = current.elevationM
            if (elevation != null && elevationAnchor != null) {
                val delta = elevation - elevationAnchor
                if (abs(delta) >= ELEVATION_NOISE_M) {
                    if (delta > 0) calculatedGain += delta else calculatedLoss += -delta
                    elevationAnchor = elevation
                }
            } else if (elevation != null) {
                elevationAnchor = elevation
            }

            val t1 = previous.timeEpochMs
            val t2 = current.timeEpochMs
            if (!broken && t1 != null && t2 != null && t2 > t1) {
                pointElapsedSeconds += (t2 - t1) / 1000
            }
        }

        val hasTimes = points.first().timeEpochMs != null && points.last().timeEpochMs != null
        val elapsed = summary?.elapsedSeconds ?: pointElapsedSeconds.takeIf { hasTimes }
        val movingEstimate = if (hasTimes) estimateMovingSeconds(points, legs) else null
        val moving = if (elapsed != null && movingEstimate != null) movingEstimate.coerceAtMost(elapsed) else movingEstimate
        val stopped = if (elapsed != null && moving != null) (elapsed - moving).coerceAtLeast(0) else null
        val distance = summary?.distanceM?.takeIf { it > 0 } ?: gpsDistance
        val gain = summary?.elevationGainM?.takeIf { it >= 0 } ?: calculatedGain
        val loss = summary?.elevationLossM?.takeIf { it >= 0 } ?: calculatedLoss
        val heartRates = points.mapNotNull { it.heartRateBpm }.filter { it in 20..250 }
        val segments = buildSegments(points, cumulative)

        return TrackMetrics(
            distanceM = distance,
            elevationGainM = gain,
            elevationLossM = loss,
            elapsedSeconds = elapsed,
            movingSeconds = moving,
            stoppedSeconds = stopped,
            averagePaceMinutesPerKm = if (moving != null && distance > 0) moving / 60.0 / (distance / 1000.0) else null,
            averageHeartRateBpm = heartRates.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            maxHeartRateBpm = heartRates.maxOrNull(),
            segments = segments,
        )
    }

    private fun estimateMovingSeconds(points: List<TrackPoint>, legs: DoubleArray): Long {
        val intervals = (1 until points.size).mapNotNull { i ->
            if (TrackGeometry.disconnected(points[i-1], points[i])) return@mapNotNull null
            val start = points[i - 1].timeEpochMs ?: return@mapNotNull null
            val end = points[i].timeEpochMs ?: return@mapNotNull null
            val seconds = (end - start) / 1000.0
            seconds.takeIf { it > 0 }
        }
        if (intervals.isEmpty()) return 0L
        val medianInterval = intervals.sorted()[intervals.size / 2]
        if (medianInterval > DENSE_SAMPLE_MAX_SECONDS) {
            return (1 until points.size).sumOf { i ->
                val t1 = points[i - 1].timeEpochMs
                val t2 = points[i].timeEpochMs
                if (!TrackGeometry.disconnected(points[i-1], points[i]) && t1 != null && t2 != null && t2 > t1) {
                    val seconds = (t2 - t1) / 1000
                    if (legs[i] / seconds >= SPARSE_TRACK_MOVING_SPEED_MPS) seconds else 0L
                } else 0L
            }
        }

        var movingSeconds = 0L
        var anchor = 0
        for (i in 1 until points.size) {
            if (TrackGeometry.disconnected(points[i-1], points[i])) { anchor = i; continue }
            val previousTime = points[i - 1].timeEpochMs ?: continue
            val currentTime = points[i].timeEpochMs ?: continue
            if (currentTime <= previousTime) continue
            val targetTime = currentTime - PAUSE_WINDOW_SECONDS * 1000
            while (anchor + 1 < i && (points[anchor + 1].timeEpochMs ?: Long.MAX_VALUE) <= targetTime) anchor++
            val anchorTime = points[anchor].timeEpochMs ?: continue
            val observedSeconds = ((currentTime - anchorTime) / 1000.0).coerceAtLeast(1.0)
            val requiredDisplacement = PAUSE_DISPLACEMENT_M * (observedSeconds / PAUSE_WINDOW_SECONDS).coerceAtMost(1.0)
            val displacement = Geo.distanceM(points[anchor], points[i])
            if (displacement >= requiredDisplacement) movingSeconds += (currentTime - previousTime) / 1000
        }
        return movingSeconds
    }

    private fun buildSegments(points: List<TrackPoint>, cumulative: DoubleArray): List<TrackSegment> {
        if (cumulative.last() <= 0) return emptyList()
        val result = mutableListOf<TrackSegment>()
        var start = 0
        while (start < points.lastIndex) {
            val boundary = cumulative[start] + SEGMENT_TARGET_M
            var end = start + 1
            while (end < points.lastIndex && cumulative[end] < boundary) end++
            val segmentDistance = cumulative[end] - cumulative[start]
            if (segmentDistance <= 0) { start = end; continue }
            val e1 = points[start].elevationM
            val e2 = points[end].elevationM
            val elevationDelta = if (e1 != null && e2 != null) e2 - e1 else 0.0
            val gain = elevationDelta.coerceAtLeast(0.0)
            val t1 = points[start].timeEpochMs
            val t2 = points[end].timeEpochMs
            val elapsed = if (t1 != null && t2 != null && t2 > t1) (t2 - t1) / 1000 else null
            val hrs = points.subList(start, end + 1).mapNotNull { it.heartRateBpm }.filter { it in 20..250 }
            result += TrackSegment(
                index = result.size,
                startDistanceM = cumulative[start],
                endDistanceM = cumulative[end],
                distanceM = segmentDistance,
                elevationGainM = gain,
                averageGradePercent = elevationDelta / segmentDistance * 100.0,
                elapsedSeconds = elapsed,
                paceMinutesPerKm = elapsed?.let { it / 60.0 / (segmentDistance / 1000.0) },
                averageHeartRateBpm = hrs.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            )
            start = end
        }
        return result
    }
}
