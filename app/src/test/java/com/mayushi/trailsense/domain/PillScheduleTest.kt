package com.mayushi.trailsense.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PillScheduleTest {
    private val start = LocalDate.of(2026, 1, 15)
    private val plan = PillPlan(start.toEpochDay())

    @Test fun `84 active then 7 break and restart on day 92`() {
        assertEquals(PillPhase.ACTIVE, PillSchedule.day(plan, start.plusDays(83)).phase)
        assertEquals(84, PillSchedule.day(plan, start.plusDays(83)).dayInPhase)
        assertEquals(PillPhase.BREAK, PillSchedule.day(plan, start.plusDays(84)).phase)
        assertEquals(7, PillSchedule.day(plan, start.plusDays(90)).dayInPhase)
        assertEquals(PillPhase.ACTIVE, PillSchedule.day(plan, start.plusDays(91)).phase)
        assertEquals(1, PillSchedule.day(plan, start.plusDays(91)).dayInPhase)
    }

    @Test fun `builds four reminder dates`() {
        val dates = PillSchedule.reminderDates(plan, 0)
        assertEquals(start.plusDays(83), dates[PillReminderKind.LAST_ACTIVE])
        assertEquals(start.plusDays(84), dates[PillReminderKind.BREAK_START])
        assertEquals(start.plusDays(90), dates[PillReminderKind.RESTART_EVE])
        assertEquals(start.plusDays(91), dates[PillReminderKind.RESTART_DAY])
    }

    @Test fun `deviation policies keep recalculate or override one boundary`() {
        val planned = start.plusDays(91)
        val actual = planned.plusDays(2)
        assertEquals(plan, PillSchedule.applyBoundaryDeviation(plan, planned, actual, DeviationPolicy.KEEP_PLAN).plan)
        assertEquals(start.plusDays(2).toEpochDay(), PillSchedule.applyBoundaryDeviation(plan, planned, actual, DeviationPolicy.RECALCULATE_ALL).plan.anchorEpochDay)
        val one = PillSchedule.applyBoundaryDeviation(plan, planned, actual, DeviationPolicy.NEAREST_BOUNDARY_ONLY)
        assertEquals(plan, one.plan)
        assertEquals(actual.toEpochDay(), one.nearestBoundaryOverride?.actualEpochDay)
    }
}
