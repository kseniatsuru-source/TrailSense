package com.mayushi.trailsense.domain

object HikeDateResolver {
    fun fromParsedTrack(track: ParsedTrack): Long? = track.points.firstNotNullOfOrNull { it.timeEpochMs }
        ?: track.startedAtEpochMs

    fun fromEncodedTrack(encodedTrack: String?): Long? {
        if (encodedTrack.isNullOrBlank()) return null
        return runCatching {
            TrackCodec.decode(encodedTrack).firstNotNullOfOrNull { it.timeEpochMs }
        }.getOrNull()
    }

    fun fromStoredTracks(startedAt: Long?, originalTrack: String?, currentTrack: String?): Long? =
        startedAt ?: fromEncodedTrack(originalTrack) ?: fromEncodedTrack(currentTrack)

    fun afterTrackCorrection(importedStartedAt: Long?, correctedPoints: List<TrackPoint>): Long? =
        importedStartedAt ?: correctedPoints.firstNotNullOfOrNull { it.timeEpochMs }
}
