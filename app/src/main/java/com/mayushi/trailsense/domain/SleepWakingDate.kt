package com.mayushi.trailsense.domain

import java.time.LocalDate

/** Only a fully readable calendar date; never supplies a missing year or uses bedtime. */
object SleepWakingDate {
    fun read(text:String):LocalDate? {
        val dates=mutableSetOf<LocalDate>()
        Regex("\\b(20\\d{2})[-/.](\\d{1,2})[-/.](\\d{1,2})\\b").findAll(text).forEach { m ->
            runCatching { LocalDate.of(m.groupValues[1].toInt(),m.groupValues[2].toInt(),m.groupValues[3].toInt()) }.getOrNull()?.let(dates::add)
        }
        Regex("\\b(\\d{1,2})[./](\\d{1,2})[./](20\\d{2})\\b").findAll(text).forEach { m ->
            runCatching { LocalDate.of(m.groupValues[3].toInt(),m.groupValues[2].toInt(),m.groupValues[1].toInt()) }.getOrNull()?.let(dates::add)
        }
        return dates.singleOrNull()
    }
    fun mismatch(wakingDate:LocalDate?,hikeDate:LocalDate?)=wakingDate!=null && hikeDate!=null && wakingDate!=hikeDate
}
