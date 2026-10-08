package com.mayushi.trailsense.domain

enum class EventMetricSource { ROUTE, WATCH_FILE }

data class EventTrackMetrics(
    val distanceM: Double?,
    val elevationGainM: Double?,
    val elevationLossM: Double?,
    val source: EventMetricSource,
)

object EventMetricSelector {
    fun choose(
        routeDistanceM: Double?,
        routeElevationGainM: Double?,
        routeElevationLossM: Double?,
        watchDistanceM: Double?,
        watchElevationGainM: Double?,
        watchElevationLossM: Double?,
    ): EventTrackMetrics {
        val hasWatchMetrics = watchDistanceM != null || watchElevationGainM != null || watchElevationLossM != null
        return EventTrackMetrics(
            distanceM = watchDistanceM,
            elevationGainM = watchElevationGainM,
            elevationLossM = watchElevationLossM,
            source = if (hasWatchMetrics) EventMetricSource.WATCH_FILE else EventMetricSource.ROUTE,
        )
    }
}
