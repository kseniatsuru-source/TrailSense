package com.mayushi.trailsense.data

import org.json.JSONObject

/** Presentation only: archive watch facts have priority; corrected geometry and immutable originals stay intact. */
object HuaweiWatchDisplay {
    fun activity(a: ActivityEntity): ActivityEntity {
        if(a.huaweiFactsJson.isBlank()) return a
        val f=runCatching {JSONObject(a.huaweiFactsJson)}.getOrNull() ?: return a
        return a.copy(
            distanceM=if(a.trackCorrectionMode=="NONE") f.optDouble("distanceM",a.distanceM) else a.distanceM,
            elevationGainM=if(a.trackCorrectionMode=="NONE") f.optDouble("gainM",a.elevationGainM) else a.elevationGainM,
            elevationLossM=if(a.trackCorrectionMode=="NONE") f.optDouble("lossM",a.elevationLossM) else a.elevationLossM,
            averageHeartRateBpm=f.optInt("avgHrBpm").takeIf {it>0} ?: a.averageHeartRateBpm,
            maxHeartRateBpm=f.optInt("maxHrBpm").takeIf {it>0} ?: a.maxHeartRateBpm,
            averagePaceMinutesPerKm=f.optDouble("paceSecondsPerKm").takeIf {it.isFinite() && it>0}?.div(60) ?: a.averagePaceMinutesPerKm,
            // totalTimeMs is deliberately not used as elapsedSeconds, movingSeconds or stoppedSeconds.
        )
    }
}
