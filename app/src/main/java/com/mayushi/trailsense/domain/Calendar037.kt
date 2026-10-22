package com.mayushi.trailsense.domain

import com.mayushi.trailsense.data.CalendarContextEntity
import com.mayushi.trailsense.data.PillEventEntity
import java.time.LocalDate

/** Calendar-only presentation rules; no record or regimen history is rewritten. */
object Calendar037 {
    fun normalizedType(type: String): String = if (type == "HIGH_STRESS") "STRONG_STRESS" else type

    fun label(type: String): String = when (normalizedType(type)) {
        "SEVERE_SLEEP_DEBT" -> "Сильный недосып"
        "STRESS" -> "Стресс"
        "STRONG_STRESS" -> "Сильный стресс"
        "NOTE" -> "Заметка"
        "MENSTRUATION" -> "Менструация"
        else -> type
    }

    fun effectiveEnd(event: CalendarContextEntity, today: LocalDate): Long =
        if (event.ongoing) today.toEpochDay() else event.endEpochDay

    fun overlaps(event: CalendarContextEntity, date: LocalDate, today: LocalDate): Boolean =
        date.toEpochDay() >= event.startEpochDay && date.toEpochDay() <= effectiveEnd(event, today)

    fun contextsForDate(events: List<CalendarContextEntity>, date: LocalDate, today: LocalDate) =
        events.filter { overlaps(it, date, today) }

    fun latestFact(events: List<PillEventEntity>, date: LocalDate): PillEventEntity? =
        events.filter { it.epochDay == date.toEpochDay() }
            .maxWithOrNull(compareBy<PillEventEntity> { it.createdAt }.thenBy { it.id })

    fun validRegimen(active: Int?, breakDays: Int?): Boolean =
        active != null && breakDays != null && active > 0 && breakDays >= 0 &&
            active.toLong() + breakDays.toLong() <= Int.MAX_VALUE
}
