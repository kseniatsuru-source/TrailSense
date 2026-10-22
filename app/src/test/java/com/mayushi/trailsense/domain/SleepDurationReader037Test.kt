package com.mayushi.trailsense.domain

import org.junit.Assert.*
import org.junit.Test

class SleepDurationReader037Test {
    @Test fun wakingDateKnownMismatchAndUnknownSilent() {
        val hike=java.time.LocalDate.of(2026,6,11)
        assertEquals(hike, SleepWakingDate.read("2026-06-11\nTotal sleep 9 h 4 min"))
        assertFalse(SleepWakingDate.mismatch(SleepWakingDate.read("2026-06-11"),hike))
        assertTrue(SleepWakingDate.mismatch(SleepWakingDate.read("12.06.2026"),hike))
        listOf("Jun 12", "Unreadable", "2026-13-40", "2026-06-11 – 2026-06-12", "").forEach {
            assertNull(SleepWakingDate.read(it)); assertFalse(SleepWakingDate.mismatch(SleepWakingDate.read(it),hike))
        }
    }
    @Test fun explicitActualTotalNotBedtimeSpan() {
        val result = SleepDurationReader.read("Sleep\nBedtime 22:00\nWake up 07:00\nTime in bed 9 h\nTotal sleep 7 h 25 min\nDeep sleep 1 h")
        assertEquals(445, result.minutes)
        assertEquals(0.95, result.confidence, 0.001)
    }
    @Test fun explicitNightSleepLabelAndRepeatedCardNotBedtime() {
        assertEquals(427, SleepDurationReader.read("Night sleep\n7 h 7 min\nBedtime 21:00\nWake up 06:00\nNight sleep\n7 h 7 min").minutes)
        assertNull(SleepDurationReader.read("Night sleep score 80\nTime in bed 9 h").minutes)
        assertNull(SleepDurationReader.read("Night sleep\n7 h\nTotal sleep\n8 h").minutes)
    }
    @Test fun nextLineAndDurationColon() {
        assertEquals(425, SleepDurationReader.read("Time asleep\n7 hr 5 min").minutes)
        assertEquals(0.85, SleepDurationReader.read("Time asleep\n7 hr 5 min").confidence, 0.001)
        assertEquals(450, SleepDurationReader.read("Sleep duration: 07:30").minutes)
    }
    @Test fun noGuessFromBedtimeStagesScoreOrGenericSleep() {
        listOf("22:00–07:00", "Time in bed 9 h", "Sleep\n8 h", "Deep sleep 2 h\nREM 2 h\nLight 4 h", "Sleep score 89", "Total sleep score 90", "7h 45min").forEach {
            val r = SleepDurationReader.read(it)
            assertNull(it, r.minutes); assertEquals(0.0, r.confidence, 0.0)
        }
    }
    @Test fun conflictingOrMalformedTotalsUnknown() {
        listOf("Total sleep 7 h\nTime asleep 8 h", "Total sleep 7 h\nTime asleep unreadable", "Total sleep 7 h 65 min", "Total sleep 07:70", "Total sleep 22:00 - 07:00", "Total sleep 0 min", "Total sleep 25 h", "Total sleep 7 h (estimated)").forEach { assertNull(it, SleepDurationReader.read(it).minutes) }
    }
    @Test fun duplicatesUnitsAndLocalizedExplicitLabels() {
        assertEquals(480, SleepDurationReader.read("Total sleep 8h\nTotal sleep 8h").minutes)
        assertEquals(475, SleepDurationReader.read("TOTAL SLEEP TIME: 475 minutes").minutes)
        assertEquals(465, SleepDurationReader.read("Общее время сна: 7 ч 45 мин").minutes)
        assertEquals(480, SleepDurationReader.read("Total sleep 8\u00a0h").minutes)
    }
}
