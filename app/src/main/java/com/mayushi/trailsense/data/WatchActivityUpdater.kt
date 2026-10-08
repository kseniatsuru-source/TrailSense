package com.mayushi.trailsense.data

import com.mayushi.trailsense.domain.TrackFormat
import com.mayushi.trailsense.domain.TrackMetrics

/** Replaces watch-derived fields without discarding the user's event notes and ratings. */
object WatchActivityUpdater {
    fun merge(
        existing: ActivityEntity?,
        routeId: Long,
        routeTitle: String,
        fileName: String,
        format: TrackFormat,
        encodedTrack: String,
        startedAt: Long?,
        metrics: TrackMetrics,
        now: Long = System.currentTimeMillis(),
    ): ActivityEntity {
        val watchComment = "Расстояние, набор, спуск и общее время взяты из файла часов."
        return if (existing == null) {
            ActivityEntity(
                routeId = routeId,
                title = routeTitle,
                sourceFileName = fileName,
                sourceFormat = format.name,
                trackEncoded = encodedTrack,
                originalTrackEncoded = encodedTrack,
                importedStartedAt = startedAt,
                originalMetricsJson = OriginalWatchMetrics.encode(metrics),
                startedAt = startedAt,
                distanceM = metrics.distanceM,
                elevationGainM = metrics.elevationGainM,
                elevationLossM = metrics.elevationLossM,
                elapsedSeconds = metrics.elapsedSeconds,
                movingSeconds = metrics.movingSeconds,
                stoppedSeconds = metrics.stoppedSeconds,
                averagePaceMinutesPerKm = metrics.averagePaceMinutesPerKm,
                averageHeartRateBpm = metrics.averageHeartRateBpm,
                maxHeartRateBpm = metrics.maxHeartRateBpm,
                matchConfidence = 1.0,
                comment = watchComment,
                createdAt = now,
                updatedAt = now,
            )
        } else {
            existing.copy(
                routeId = routeId,
                suggestedRouteId = null,
                title = routeTitle,
                sourceFileName = fileName,
                sourceFormat = format.name,
                trackEncoded = encodedTrack,
                originalTrackEncoded = encodedTrack,
                screenshotPath = existing.screenshotPath,
                trackCorrectionMode = "NONE",
                trackCorrectionNote = "",
                startedAt = existing.manualDateEpochDay?.let { java.time.LocalDate.ofEpochDay(it).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() } ?: startedAt,
                importedStartedAt = startedAt,
                originalMetricsJson = OriginalWatchMetrics.encode(metrics),
                distanceM = metrics.distanceM,
                elevationGainM = metrics.elevationGainM,
                elevationLossM = metrics.elevationLossM,
                elapsedSeconds = metrics.elapsedSeconds,
                movingSeconds = metrics.movingSeconds,
                stoppedSeconds = metrics.stoppedSeconds,
                averagePaceMinutesPerKm = metrics.averagePaceMinutesPerKm,
                averageHeartRateBpm = metrics.averageHeartRateBpm,
                maxHeartRateBpm = metrics.maxHeartRateBpm,
                matchConfidence = 1.0,
                updatedAt = now,
            )
        }
    }
}
