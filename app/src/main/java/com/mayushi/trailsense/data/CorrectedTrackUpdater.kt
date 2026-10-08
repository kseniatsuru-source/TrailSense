package com.mayushi.trailsense.data

import com.mayushi.trailsense.domain.*

/** Pure correction merge shared by trim, inferred start and reset. */
object CorrectedTrackUpdater {
    fun merge(activity: ActivityEntity, points: List<TrackPoint>, mode: String, note: String,
              now: Long = System.currentTimeMillis()): ActivityEntity {
        val metrics = TrackAnalyzer.analyze(points)
        return activity.copy(
            originalTrackEncoded = activity.originalTrackEncoded ?: activity.trackEncoded,
            trackEncoded = TrackCodec.encode(points),
            // Recover legacy dates BEFORE trimming or synthesizing earlier points.
            startedAt = HikeDateResolver.afterTrackCorrection(
                HikeDateResolver.fromStoredTracks(activity.startedAt, activity.originalTrackEncoded, activity.trackEncoded),
                points,
            ),
            distanceM = metrics.distanceM,
            elevationGainM = metrics.elevationGainM,
            elevationLossM = metrics.elevationLossM,
            elapsedSeconds = metrics.elapsedSeconds,
            movingSeconds = metrics.movingSeconds,
            stoppedSeconds = metrics.stoppedSeconds,
            averagePaceMinutesPerKm = metrics.averagePaceMinutesPerKm,
            averageHeartRateBpm = metrics.averageHeartRateBpm,
            maxHeartRateBpm = metrics.maxHeartRateBpm,
            trackCorrectionMode = mode,
            trackCorrectionNote = note,
            updatedAt = maxOf(now, activity.updatedAt + 1),
        )
    }
}
