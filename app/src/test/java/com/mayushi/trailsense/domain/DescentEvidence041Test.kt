package com.mayushi.trailsense.domain

import org.junit.Assert.*
import org.junit.Test

class DescentEvidence041Test {
    private fun profile(vararg heights: Double)=heights.mapIndexed {i,h ->TrackPoint(0.0,i*0.001,elevationM=h)}
    @Test fun monotonicFlatAndRecoveredMinorJitterUseExistingNoisePolicy() {
        for (p in listOf(profile(10.0,10.0,20.0), profile(10.0,10.0,10.0),
            profile(10.0,9.999,20.0), profile(10.0,9.0,10.0,9.5,10.0),
            profile(10.0,11.0,10.0,12.0,11.0,13.0))) {
            assertEquals(DescentEvidence.Profile.PROVEN_NO_DESCENT,DescentEvidence.profile(p))
            assertEquals(0.0,TrackAnalyzer.analyze(p).elevationLossM,0.0)
        }
    }
    @Test fun realAndShortSteepDescentInsideOverallUphillCannotDisappear() {
        for (p in listOf(profile(1000.0,500.0,0.0), profile(0.0,50.0,40.0,100.0),
            profile(0.0,50.0,47.0,100.0), profile(0.0,50.0,49.0,48.0,47.0,100.0),
            profile(50.0,51.0,49.0,50.0,48.0,49.0,47.0,100.0))) {
            assertEquals(DescentEvidence.Profile.UNKNOWN,DescentEvidence.profile(p))
        }
    }
    @Test fun ambiguousSmallSustainedOrUnrecoveredDeclineStaysUnknown() {
        for(p in listOf(profile(10.0,9.0),profile(0.0,10.0,9.9),
            profile(10.0,9.5,9.0,10.0),profile(10.0,9.5,9.5,9.0,10.0),
            profile(10.0,20.0,10.0,20.0)))
            assertEquals(DescentEvidence.Profile.UNKNOWN,DescentEvidence.profile(p))
    }
    @Test fun duplicatedSamplesAndVariableDensityDoNotHideDescentOrCreateJitter() {
        for (heights in listOf(listOf(10.0,9.0,10.0,11.0),listOf(10.0,20.0,10.0,30.0))) {
            val p=profile(*heights.toDoubleArray())
            val dense=p.flatMap { listOf(it,it,it) }
            assertEquals(DescentEvidence.profile(p),DescentEvidence.profile(dense))
            assertEquals(DescentEvidence.profile(p),DescentEvidence.profile(p.mapIndexed {i,t -> t.copy(longitude=i*i*0.00001) }))
        }
        val gradual=(0..100).map {i ->TrackPoint(0.0,i*0.00001,elevationM=50.0-i*0.05)}
        assertEquals(DescentEvidence.Profile.UNKNOWN,DescentEvidence.profile(gradual))
    }
    @Test fun legacyPolicyRemainsStrictUntilExplicitSaveAndHistoryIsPreserved() {
        val old="exact audit\nq041|descent-policy|conservative-no-decrease-v1"
        val p=profile(10.0,9.999,20.0)
        assertEquals("UNRATED_OR_UNKNOWN",DescentEvidence.blankMeaning(10,old,p))
        val saved=DescentEvidence.savedHistory(old)
        assertTrue(saved.startsWith(old+"\n"))
        assertEquals(saved,DescentEvidence.savedHistory(saved))
        assertEquals("RECORDED_PROFILE_NO_DESCENT",DescentEvidence.blankMeaning(10,saved,p))
        assertEquals("NO_DATA",DescentEvidence.blankMeaning(null,saved,p))
    }
    @Test fun missingInvalidElevationCoordinatesAndTimedOutagesRemainUnknown() {
        val p=profile(0.0,1.0,2.0)
        for (bad in listOf(p.mapIndexed {i,t -> if(i==1)t.copy(elevationM=null) else t},
            p.map {it.copy(elevationM=Double.NaN)},p.map {it.copy(elevationM=Double.POSITIVE_INFINITY)},
            p.map {it.copy(latitude=91.0)},p.map {it.copy(longitude=-181.0)},
            p.mapIndexed {i,t ->t.copy(timeEpochMs=if(i==0)0L else 600000L+i)},
            p.mapIndexed {i,t ->t.copy(timeEpochMs=1000L-i)}))
            assertEquals(DescentEvidence.Profile.UNKNOWN,DescentEvidence.profile(bad))
    }
    @Test fun missingInferredDiscontinuousAndEmptyRemainUnknown() {
        assertEquals(DescentEvidence.Profile.UNKNOWN,DescentEvidence.profile(emptyList()))
        for(p in listOf(profile(0.0,1.0).map {it.copy(inferred=true)},profile(0.0,1.0).map {it.copy(elevationM=null)},profile(0.0,1.0).map {it.copy(segmentStart=true)})) assertEquals(DescentEvidence.Profile.UNKNOWN,DescentEvidence.profile(p))
    }
    @Test fun legacyBlankAndNeverSavedNeverReinterpreted() {
        val p=profile(0.0,1.0)
        assertEquals("NO_DATA",DescentEvidence.blankMeaning(null,"",p));assertEquals("NO_DATA",DescentEvidence.blankMeaning(10,"q038|rating-state|Спуск|NA",p))
        val h=DescentEvidence.savedHistory("old exact\n")
        assertTrue(h.startsWith("old exact\n"));assertEquals(h,DescentEvidence.savedHistory(h));assertEquals("RECORDED_PROFILE_NO_DESCENT",DescentEvidence.blankMeaning(10,h,p))
        assertEquals("UNRATED_OR_UNKNOWN",DescentEvidence.blankMeaning(10,h,profile(10.0,0.0)))
    }
    @Test fun clearedExposureAndTechnicalityRemainUnansweredNotAbsent() {
        val h=RatingApplicability.save("exact",mapOf("Экспозиция" to "UNANSWERED","Техничность" to "UNANSWERED"))
        assertEquals("UNANSWERED",RatingApplicability.states(h)["Экспозиция"]);assertEquals("UNANSWERED",RatingApplicability.states(h)["Техничность"])
    }
    @Test fun invalidOrStationaryGeometryCannotProveAbsence() {
        assertEquals(DescentEvidence.Profile.UNKNOWN,DescentEvidence.profile(profile(0.0,1.0).map {it.copy(latitude=Double.NaN)}))
        assertEquals(DescentEvidence.Profile.UNKNOWN,DescentEvidence.profile(profile(0.0,1.0).map {it.copy(latitude=0.0,longitude=0.0)}))
    }
    @Test fun correctedOrPartialProfileIsNeverExportedAsNoDescent() {
        val a=com.mayushi.trailsense.data.ActivityEntity(title="Synthetic",sourceFileName="synthetic",sourceFormat="TCX",trackEncoded=TrackCodec.encode(profile(0.0,1.0)),distanceM=1000.0,elevationGainM=1.0,elevationLossM=0.0,questionnaireCompletedAt=10,questionnaireHistory=DescentEvidence.savedHistory(""))
        fun meaning(recording: com.mayushi.trailsense.data.ActivityEntity)=com.mayushi.trailsense.network.AnalysisPayloadBuilder.activity(recording,emptyList(),8.0,null).getJSONObject("activity").getString("blank_descent_meaning")
        assertEquals("RECORDED_PROFILE_NO_DESCENT",meaning(a));assertEquals("UNRATED_OR_UNKNOWN",meaning(a.copy(trackCorrectionMode="CROP_START")));assertEquals("UNRATED_OR_UNKNOWN",meaning(a.copy(trackEncoded="invalid")))
        assertEquals("UNRATED_OR_UNKNOWN",meaning(a.copy(trackEncoded=TrackCodec.encode(profile(0.0,50.0,40.0,100.0)))))
        assertEquals("UNRATED_OR_UNKNOWN",meaning(a.copy(trackEncoded=TrackCodec.encode(profile(0.0,1.0).map {it.copy(elevationM=null)}))))
        assertEquals("RATED",meaning(a.copy(descentEffort=1)))
    }
}
