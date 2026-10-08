package com.mayushi.trailsense.domain

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationM: Double? = null,
    val timeEpochMs: Long? = null,
    val heartRateBpm: Int? = null,
    val segmentStart: Boolean = false,
    val inferred: Boolean = false,
)

data class TrackSegment(
    val index: Int,
    val startDistanceM: Double,
    val endDistanceM: Double,
    val distanceM: Double,
    val elevationGainM: Double,
    val averageGradePercent: Double,
    val elapsedSeconds: Long?,
    val paceMinutesPerKm: Double?,
    val averageHeartRateBpm: Int?,
)

data class TrackMetrics(
    val distanceM: Double,
    val elevationGainM: Double,
    val elevationLossM: Double,
    val elapsedSeconds: Long?,
    val movingSeconds: Long?,
    val stoppedSeconds: Long?,
    val averagePaceMinutesPerKm: Double?,
    val averageHeartRateBpm: Int?,
    val maxHeartRateBpm: Int?,
    val segments: List<TrackSegment>,
)

data class TrackSummary(
    val distanceM: Double? = null,
    val elevationGainM: Double? = null,
    val elevationLossM: Double? = null,
    val elapsedSeconds: Long? = null,
)

data class ParsedTrack(
    val name: String?,
    val points: List<TrackPoint>,
    val sourceFormat: TrackFormat,
    val summary: TrackSummary? = null,
    /** Workout start embedded in TCX/GPX; this is the hike date used by TrailSense. */
    val startedAtEpochMs: Long? = null,
)

enum class TrackFormat { GPX, TCX }

data class RouteMatch(
    val confidence: Double,
    val averageNearestDistanceM: Double,
    val lengthDifferenceRatio: Double,
    val directionReversed: Boolean,
) {
    val shouldSuggest: Boolean get() = confidence >= 0.70
}
