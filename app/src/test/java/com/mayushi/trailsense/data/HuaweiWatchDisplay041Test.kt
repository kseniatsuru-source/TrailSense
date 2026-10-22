package com.mayushi.trailsense.data

import org.junit.Assert.*
import org.junit.Test

class HuaweiWatchDisplay041Test {
    private fun activity()=ActivityEntity(title="Synthetic",sourceFileName="synthetic",sourceFormat="TCX",trackEncoded="",distanceM=1000.0,elevationGainM=100.0,elevationLossM=10.0,elapsedSeconds=600,movingSeconds=500,
        huaweiFactsJson="{\"distanceM\":1200,\"gainM\":150,\"lossM\":20,\"avgHrBpm\":130,\"paceSecondsPerKm\":300,\"totalTimeMs\":9999999}")
    @Test fun watchFactsPriorityDoesNotRewritePersistedOriginalOrInventElapsed() {
        val a=activity();val display=HuaweiWatchDisplay.activity(a)
        assertEquals(1200.0,display.distanceM,0.0);assertEquals(150.0,display.elevationGainM,0.0);assertEquals(5.0,display.averagePaceMinutesPerKm!!,0.0);assertEquals(130,display.averageHeartRateBpm)
        assertEquals(a.elapsedSeconds,display.elapsedSeconds);assertEquals(a.movingSeconds,display.movingSeconds);assertEquals(a.originalMetricsJson,display.originalMetricsJson);assertEquals(1000.0,a.distanceM,0.0)
    }
    @Test fun selectedTrackCorrectionKeepsItsGeometryMetricsAndWatchHeartRate() {
        val a=activity().copy(trackCorrectionMode="CROP_START");val display=HuaweiWatchDisplay.activity(a)
        assertEquals(a.distanceM,display.distanceM,0.0);assertEquals(a.elevationGainM,display.elevationGainM,0.0);assertEquals(a.elevationLossM,display.elevationLossM,0.0);assertEquals(130,display.averageHeartRateBpm)
    }
    @Test fun missingOrInvalidArchiveNeverCreatesZeros() {
        val a=activity().copy(huaweiFactsJson="");assertEquals(a,HuaweiWatchDisplay.activity(a));val invalid=a.copy(huaweiFactsJson="not-json");assertEquals(invalid,HuaweiWatchDisplay.activity(invalid))
    }
}
