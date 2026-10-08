package com.mayushi.trailsense.domain

import com.mayushi.trailsense.data.CalendarContextEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CalendarMonthGridTest {
    @Test fun `month grid always has six Monday-first weeks`() {
        val cells = CalendarMonthGrid.cells(YearMonth.of(2026, 8))

        assertEquals(42, cells.size)
        assertEquals(LocalDate.of(2026, 7, 27), cells.first().date)
        assertEquals(LocalDate.of(2026, 9, 6), cells.last().date)
        assertEquals(31, cells.count { it.inDisplayedMonth })
    }

    @Test fun `context markers include ranges covering selected day`() {
        val day = LocalDate.of(2026, 8, 17)
        val contexts = listOf(
            context(1, LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 18), "HIGH_STRESS"),
            context(2, day, day, "NOTE"),
            context(3, LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 19), "SEVERE_SLEEP_DEBT"),
        )

        assertEquals(listOf("HIGH_STRESS", "NOTE"), CalendarMonthGrid.contextTypes(day, contexts))
    }

    @Test fun `questionnaire starts collapsed whether completed or not`() {
        assertFalse(HikeQuestionnairePresentation.initiallyExpanded(questionnaireCompletedAt = null))
        assertFalse(HikeQuestionnairePresentation.initiallyExpanded(questionnaireCompletedAt = 123L))
    }

    @Test fun `current month flag excludes adjacent month cells`() {
        val cells = CalendarMonthGrid.cells(YearMonth.of(2026, 8))
        assertFalse(cells.first().inDisplayedMonth)
        assertTrue(cells.first { it.date == LocalDate.of(2026, 8, 1) }.inDisplayedMonth)
    }

    private fun context(id: Long, start: LocalDate, end: LocalDate, type: String) = CalendarContextEntity(
        id = id,
        startEpochDay = start.toEpochDay(),
        endEpochDay = end.toEpochDay(),
        type = type,
    )
}
