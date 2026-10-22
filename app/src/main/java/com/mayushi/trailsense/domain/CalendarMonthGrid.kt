package com.mayushi.trailsense.domain

import com.mayushi.trailsense.data.CalendarContextEntity
import java.time.LocalDate
import java.time.YearMonth

data class CalendarDayCell(
    val date: LocalDate,
    val inDisplayedMonth: Boolean,
)

object CalendarMonthGrid {
    /** Only complete Monday-first weeks needed for this month. */
    fun cells(month: YearMonth): List<CalendarDayCell> {
        val first = month.atDay(1)
        val gridStart = first.minusDays((first.dayOfWeek.value - 1).toLong())
        val days = ((first.dayOfWeek.value - 1 + month.lengthOfMonth() + 6) / 7) * 7L
        return (0L until days).map { offset ->
            val date = gridStart.plusDays(offset)
            CalendarDayCell(date, YearMonth.from(date) == month)
        }
    }

    fun contextTypes(date: LocalDate, contexts: List<CalendarContextEntity>): List<String> = contexts
        .asSequence()
        .filter { date.toEpochDay() in it.startEpochDay..it.endEpochDay }
        .map { it.type }
        .distinct()
        .toList()
}

object HikeQuestionnairePresentation {
    @Suppress("UNUSED_PARAMETER")
    fun initiallyExpanded(questionnaireCompletedAt: Long?): Boolean = false
}
