package com.mayushi.trailsense.domain

import com.mayushi.trailsense.data.CalendarContextEntity
import com.mayushi.trailsense.data.PillEventEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class Calendar037Test {
    private val today = LocalDate.of(2026, 4, 10)
    private fun event(id: Long, start: LocalDate, end: LocalDate, ongoing: Boolean = false, type: String = "NOTE") =
        CalendarContextEntity(id = id, startEpochDay = start.toEpochDay(), endEpochDay = end.toEpochDay(),
            type = type, ongoing = ongoing, note = "constructed", createdAt = 1L, updatedAt = 2L)

    @Test fun `all overlapping contexts retain full original periods and ids`() {
        val period = event(1, today.minusDays(5), today.plusDays(3))
        val single = event(2, today, today)
        val excluded = event(3, today.plusDays(1), today.plusDays(2))
        val selected = Calendar037.contextsForDate(listOf(period, single, excluded), today, today)
        assertEquals(listOf(period, single), selected)
        assertEquals(today.minusDays(5).toEpochDay(), selected.first().startEpochDay)
        assertEquals(today.plusDays(3).toEpochDay(), selected.first().endEpochDay)
    }

    @Test fun `closed periods include both endpoints`() {
        val period = event(1, today.minusDays(2), today)
        assertTrue(Calendar037.overlaps(period, today.minusDays(2), today))
        assertTrue(Calendar037.overlaps(period, today, today))
        assertFalse(Calendar037.overlaps(period, today.plusDays(1), today))
        assertFalse(Calendar037.overlaps(period, today.minusDays(3), today))
    }

    @Test fun `ongoing ignores legacy end but never includes future or pre-start days`() {
        val period = event(1, today.minusDays(5), today.minusDays(5), ongoing = true)
        assertEquals(today.toEpochDay(), Calendar037.effectiveEnd(period, today))
        assertTrue(Calendar037.overlaps(period, today, today))
        assertFalse(Calendar037.overlaps(period, today.plusDays(1), today))
        assertFalse(Calendar037.overlaps(period, today.minusDays(6), today))
        assertTrue(Calendar037.overlaps(period, today.plusDays(1), today.plusDays(1)))
        assertFalse(Calendar037.overlaps(event(2, today.plusDays(1), today.plusDays(1), true), today, today))
    }

    @Test fun `legacy high stress has strong label without rewriting record`() {
        val legacy = event(1, today, today, type = "HIGH_STRESS")
        assertEquals("STRONG_STRESS", Calendar037.normalizedType(legacy.type))
        assertEquals(Calendar037.label("STRONG_STRESS"), Calendar037.label(legacy.type))
        assertNotEquals(Calendar037.label("STRESS"), Calendar037.label(legacy.type))
        assertEquals("HIGH_STRESS", legacy.type)
        assertEquals("CUSTOM_LEGACY", Calendar037.label("CUSTOM_LEGACY"))
    }

    @Test fun `latest fact independent of query order and stable on same timestamp`() {
        val early = PillEventEntity(id = 1, epochDay = today.toEpochDay(), type = "TAKEN", actualTaken = true, createdAt = 10)
        val latest = early.copy(id = 2, actualTaken = false, createdAt = 20)
        val tie = latest.copy(id = 3, note = "correction")
        assertEquals(tie, Calendar037.latestFact(listOf(tie, latest, early), today))
        assertEquals(tie, Calendar037.latestFact(listOf(early, latest, tie), today))
        assertNull(Calendar037.latestFact(listOf(tie), today.plusDays(1)))
    }

    @Test fun `version effective date is independent of anchor and leaves historical regimen intact`() {
        val old = com.mayushi.trailsense.data.PillPlanVersionEntity(id=1, effectiveEpochDay=today.minusDays(100).toEpochDay(),
            anchorEpochDay=today.minusDays(100).toEpochDay(), activeDays=24, breakDays=4, createdAt=10)
        val next = old.copy(id=2, effectiveEpochDay=today.plusDays(2).toEpochDay(),
            anchorEpochDay=today.minusDays(47).toEpochDay(), activeDays=84, breakDays=7,
            subBlockDays=21, subBlockCount=4, createdAt=20)
        val versions = listOf(next, old)
        assertEquals(old.toCurrentPlan(), PillPlanVersions.forDate(versions, next.toCurrentPlan(), today.plusDays(1)))
        assertEquals(next.toCurrentPlan(), PillPlanVersions.forDate(versions, next.toCurrentPlan(), today.plusDays(2)))
        assertEquals(24, old.toDomainPlan().subBlockDays)
        assertEquals(1, old.toDomainPlan().subBlockCount)
        assertEquals(6, PillSchedule.day(next.toDomainPlan(), today).dayInSubBlock)
        assertEquals(today, PillPlanVersions.effectiveDate(today.minusDays(50), today))
        assertEquals(today.plusDays(2), PillPlanVersions.effectiveDate(today.plusDays(2), today))
        assertEquals(24, PillPlanVersions.normalizedPlan(today,24,4).subBlockDays)
        assertEquals(1, PillPlanVersions.normalizedPlan(today,24,4).subBlockCount)
        assertEquals(3, PillPlanVersions.normalizedPlan(today,30,5,10,3).subBlockCount)
        assertNull(PillPlanVersions.forDate(versions,next.toCurrentPlan(),today.minusDays(101)))
    }

    @Test fun `actual menstruation period has no predicted extension`() {
        val actual = event(1,today.minusDays(4),today.minusDays(1),type="MENSTRUATION")
        assertTrue(Calendar037.overlaps(actual,today.minusDays(4),today))
        assertTrue(Calendar037.overlaps(actual,today.minusDays(1),today))
        assertFalse(Calendar037.overlaps(actual,today,today))
        assertEquals(actual,Calendar037.contextsForDate(listOf(actual),today.minusDays(2),today).single())
    }

    @Test fun `regimen validates positive whole days and block overflow`() {
        assertTrue(Calendar037.validRegimen(84, 7))
        assertTrue(Calendar037.validRegimen(21, 7))
        assertFalse(Calendar037.validRegimen(null, 7))
        assertFalse(Calendar037.validRegimen(84, null))
        assertFalse(Calendar037.validRegimen(0, 7))
        assertTrue(Calendar037.validRegimen(21, 0))
        assertFalse(Calendar037.validRegimen(21, -1))
        assertFalse(Calendar037.validRegimen(-1, 7))
        assertFalse(Calendar037.validRegimen(Int.MAX_VALUE, 1))
    }

    @Test fun `fact correction preserves id timestamp and deviation policy`() {
        val original = PillEventEntity(id = 7, epochDay = today.toEpochDay(), plannedEpochDay = today.minusDays(1).toEpochDay(),
            type = "BOUNDARY", deviationPolicy = "RECALCULATE_ALL", note = "constructed", createdAt = 100)
        val edited = original.copy(epochDay = today.plusDays(1).toEpochDay(), actualTaken = false, note = "corrected")
        assertEquals(original.id, edited.id)
        assertEquals(original.createdAt, edited.createdAt)
        assertEquals(original.deviationPolicy, edited.deviationPolicy)
        assertEquals(original.type, edited.type)
        assertEquals(original.plannedEpochDay, edited.plannedEpochDay)
    }
}
