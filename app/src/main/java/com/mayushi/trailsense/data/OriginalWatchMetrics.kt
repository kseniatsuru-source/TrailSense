package com.mayushi.trailsense.data

import com.mayushi.trailsense.domain.TrackMetrics
import org.json.JSONObject

/** Immutable metric snapshot separate from geometry. Null legacy snapshots are never guessed. */
object OriginalWatchMetrics {
    fun encode(m: TrackMetrics): String = JSONObject().apply {
        put("distanceM", m.distanceM); put("elevationGainM", m.elevationGainM); put("elevationLossM", m.elevationLossM)
        put("elapsedSeconds", m.elapsedSeconds); put("movingSeconds", m.movingSeconds); put("stoppedSeconds", m.stoppedSeconds)
        put("averagePaceMinutesPerKm", m.averagePaceMinutesPerKm)
        put("averageHeartRateBpm", m.averageHeartRateBpm); put("maxHeartRateBpm", m.maxHeartRateBpm)
    }.toString()
    fun decode(value: String): TrackMetrics {
        val j = JSONObject(value)
        fun l(k: String) = if (j.has(k) && !j.isNull(k)) j.getLong(k) else null
        fun d(k: String) = if (j.has(k) && !j.isNull(k)) j.getDouble(k) else null
        return TrackMetrics(j.getDouble("distanceM"), j.getDouble("elevationGainM"), j.getDouble("elevationLossM"),
            l("elapsedSeconds"), l("movingSeconds"), l("stoppedSeconds"), d("averagePaceMinutesPerKm"),
            l("averageHeartRateBpm")?.toInt(), l("maxHeartRateBpm")?.toInt(), emptyList())
    }
}
