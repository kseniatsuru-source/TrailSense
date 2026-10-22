package com.mayushi.trailsense.domain

import com.mayushi.trailsense.data.ActivityEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/** Constructed GPS, not a user's Grouse recording. */
class RepeatRouteDynamics037Test {
    private fun activity(id: Long, seconds: Long = 500, format: String = "TCX") = ActivityEntity(
        id = id, title = "Constructed ascent $id", sourceFileName = "$id.tcx", sourceFormat = format,
        trackEncoded = TrackCodec.encode(List(6) { TrackPoint(49.0 + it * .001, -123.0, elevationM = 100.0 + it * 100,
            timeEpochMs = id * 86_400_000 + it * seconds * 1000 / 5) }),
        startedAt = id * 86_400_000, distanceM = 9999.0, elevationGainM = 1234.0, elevationLossM = 0.0, elapsedSeconds = 99999)
    private fun member(id: Long, complete: Boolean = true, start: Int = 0, end: Int = 5) = RepeatRouteDynamics.Member(id, true, complete, start, end)
    private fun summary(members: List<RepeatRouteDynamics.Member>, vararg activities: ActivityEntity) = RepeatRouteDynamics.summarize(
        RepeatRouteDynamics.Group("manual", "Constructed", "TCX", members), activities.toList(), ZoneId.of("UTC"))
    @Test fun legacyFullRecordingConfirmationDoesNotInventSection() {
        assertTrue(summary(emptyList(), activity(1)).rows.isEmpty())
        assertNull(summary(listOf(RepeatRouteDynamics.Member(1, true, true)), activity(1)).bestSeconds)
    }
    @Test fun previousAndBestUseGpsSectionsNotWatchTotals() {
        val s = summary(listOf(member(3), member(1), member(2)), activity(3, 900), activity(1, 1100), activity(2, 700))
        assertEquals(listOf(1L,2L,3L), s.rows.map { it.activity.id })
        assertEquals(700L, s.bestSeconds); assertEquals(2L, s.bestActivityId)
        assertNull(s.rows[0].previousSeconds)
        assertEquals(1100L, s.rows[1].previousSeconds); assertEquals(700L, s.rows[2].previousSeconds)
        assertEquals(700L, s.rows[2].priorBestSeconds)
    }
    @Test fun incompleteMissingStartDifferentEndpointAndSourceExcluded() {
        val missing = activity(3).copy(trackEncoded = TrackCodec.encode(TrackCodec.decode(activity(3).trackEncoded).drop(2)))
        val s = summary(listOf(member(1), member(2,false), member(3,end=3), member(4)), activity(1), activity(2,10), missing, activity(4,20,"GPX"))
        assertEquals(1L,s.bestActivityId); assertEquals(listOf(true,false,false,false),s.rows.map { it.eligible })
    }
    @Test fun timestampGapInferredCorrectionAndUnknownElevationExcluded() {
        val baseline = activity(1)
        val points = TrackCodec.decode(baseline.trackEncoded)
        val variants = listOf(points.map { it.copy(inferred=true) }, points.map { it.copy(timeEpochMs=null) },
            points.mapIndexed { i,p -> p.copy(segmentStart=i==3) }, points.map { it.copy(elevationM=null) },
            points.map { it.copy(timeEpochMs=1) })
        variants.forEach { assertNull(summary(listOf(member(1)),baseline.copy(trackEncoded=TrackCodec.encode(it))).bestSeconds) }
        assertNull(summary(listOf(member(1)),baseline.copy(trackCorrectionMode="TRIM")).bestSeconds)
        assertNull(summary(listOf(member(1)),baseline.copy(trackEncoded="invalid")).bestSeconds)
    }
    @Test fun loopWithTwoAscentsExcludesDownhillAndWholeLoopButIncludesBothAscents() {
        val p = TrackCodec.decode(activity(1,500).trackEncoded)
        val loop = p + p.reversed().drop(1) + p.drop(1)
        val timed = loop.mapIndexed { i,point -> point.copy(timeEpochMs=86_400_000L+i*100_000) }
        val hike = activity(1).copy(trackEncoded=TrackCodec.encode(timed))
        val s = summary(listOf(member(1,end=5),member(1,start=10,end=15),member(1,start=5,end=10),member(1,end=15)),hike)
        assertEquals(4,s.rows.size); assertEquals(2,s.rows.count { it.eligible })
        assertEquals(500L,s.bestSeconds)
        assertEquals(500L,s.rows.first { it.member.startIndex==10 }.previousSeconds)
    }
    @Test fun gapOutsideSelectedSectionDoesNotExcludeActualAscent() {
        val hike = activity(1).let { it.copy(trackEncoded=TrackCodec.encode(TrackCodec.decode(it.trackEncoded).mapIndexed { i,p -> p.copy(segmentStart=i==1) })) }
        assertTrue(summary(listOf(member(1,start=2)),hike).rows.single().eligible)
    }
    @Test fun gpsNoiseWithinToleranceDoesNotRequireExactLine() {
        val noisy = activity(2).let { it.copy(trackEncoded=TrackCodec.encode(TrackCodec.decode(it.trackEncoded).map { p -> p.copy(longitude=p.longitude+.0003) })) }
        assertEquals(2,summary(listOf(member(1),member(2)),activity(1),noisy).rows.count { it.eligible })
    }
    @Test fun changedTrackRequiresReconfirmationAndContextsNeverMutateSource() {
        val one = activity(1).copy(averageHeartRateBpm=144,sleepHours=8.0,heavyBackpack=true,overallEffort=9)
        val fingerprint = RepeatRouteDynamics.fingerprint(one)
        val original = one.copy()
        assertTrue(summary(listOf(member(1).copy(trackFingerprint=fingerprint)),one).rows.single().eligible)
        val changed = one.copy(trackEncoded=TrackCodec.encode(TrackCodec.decode(one.trackEncoded).map { it.copy(longitude=it.longitude+.00001) }))
        assertFalse(summary(listOf(member(1).copy(trackFingerprint=fingerprint)),changed).rows.single().eligible)
        assertEquals(original,one)
        assertNull(summary(listOf(member(1)),one).rows.single().averageHeartRateBpm)
    }
    @Test fun sectionHeartRateUsesOnlyActualSectionPointsAndMissingStaysMissing() {
        val hike = activity(1).let { it.copy(trackEncoded=TrackCodec.encode(TrackCodec.decode(it.trackEncoded).mapIndexed { i,p -> p.copy(heartRateBpm=if(i>=2) 120 else 200) })) }
        assertEquals(120,summary(listOf(member(1,start=2)),hike).rows.single().averageHeartRateBpm)
    }
    @Test fun missingDatesDoNotInventPriorAndMissingMembersAbsent() {
        val unknown=activity(2).copy(startedAt=null,importedStartedAt=null)
        val s=summary(listOf(member(1),member(2),member(999)),activity(1),unknown)
        assertEquals(2,s.rows.size); assertNull(s.rows.last().previousSeconds)
        assertEquals(42L,RepeatRouteDynamics.date(unknown.copy(manualDateEpochDay=42))?.toEpochDay())
    }
    @Test fun missingOrContradictoryWatchElapsedNeverOverridesGpsSectionTime() {
        assertEquals(500L,summary(listOf(member(1)),activity(1).copy(elapsedSeconds=null,movingSeconds=1)).bestSeconds)
        assertEquals(500L,summary(listOf(member(1)),activity(1).copy(elapsedSeconds=10)).bestSeconds)
    }
}
