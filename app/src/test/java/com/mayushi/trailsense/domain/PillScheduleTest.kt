package com.mayushi.trailsense.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PillScheduleTest {
    private val start = LocalDate.of(2026, 1, 15)
    private val plan = PillPlan(start.toEpochDay())

    @Test fun `84 active then 7 break and restart on day 92`() {
        for (index in 0 until 84) {
            val day = PillSchedule.day(plan, start.plusDays(index.toLong()))
            assertEquals(PillPhase.ACTIVE, day.phase)
            assertEquals(index % 21 + 1, day.dayInSubBlock)
            assertEquals(index / 21 + 1, day.subBlockIndex)
            assertEquals(4, day.subBlockCount)
            assertEquals(0L, day.blockNumber)
        }
        assertEquals(PillPhase.ACTIVE, PillSchedule.day(plan, start.plusDays(83)).phase)
        assertEquals(84, PillSchedule.day(plan, start.plusDays(83)).dayInPhase)
        assertEquals(PillPhase.BREAK, PillSchedule.day(plan, start.plusDays(84)).phase)
        assertEquals(0, PillSchedule.day(plan, start.plusDays(84)).subBlockIndex)
        assertEquals(0, PillSchedule.day(plan, start.plusDays(84)).dayInSubBlock)
        assertEquals(7, PillSchedule.day(plan, start.plusDays(90)).dayInPhase)
        assertEquals(PillPhase.ACTIVE, PillSchedule.day(plan, start.plusDays(91)).phase)
        assertEquals(1, PillSchedule.day(plan, start.plusDays(91)).dayInPhase)
        assertEquals(1, PillSchedule.day(plan, start.plusDays(91)).dayInSubBlock)
        assertEquals(1L, PillSchedule.day(plan, start.plusDays(91)).blockNumber)
    }

    @Test fun `custom contiguous blocks and negative cycle retain old cycle semantics`() {
        val custom = PillPlan(start.toEpochDay(), 30, 5, 10, 3)
        assertEquals(10, PillSchedule.day(custom, start.plusDays(29)).dayInSubBlock)
        assertEquals(3, PillSchedule.day(custom, start.plusDays(29)).subBlockIndex)
        assertEquals(PillPhase.BREAK, PillSchedule.day(custom, start.plusDays(30)).phase)
        assertEquals(5, PillSchedule.day(custom, start.plusDays(34)).dayInPhase)
        assertEquals(1, PillSchedule.day(custom, start.plusDays(35)).dayInSubBlock)
        assertEquals(-1L, PillSchedule.day(custom, start.minusDays(1)).blockNumber)
        assertEquals(5, PillSchedule.day(custom, start.minusDays(1)).dayInPhase)
        assertEquals(start.plusDays(35), PillSchedule.reminderDates(custom, 0)[PillReminderKind.RESTART_DAY])
        assertTrue(runCatching { PillPlan(start.toEpochDay(), 30, 5, 21, 4) }.isFailure)
        assertTrue(runCatching { PillPlan(start.toEpochDay(), 30, 5, 0, 3) }.isFailure)
        assertTrue(runCatching { PillPlan(start.toEpochDay(), Int.MAX_VALUE, 1, Int.MAX_VALUE, 1) }.isFailure)
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
