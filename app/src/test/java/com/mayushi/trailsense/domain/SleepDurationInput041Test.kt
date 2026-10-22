package com.mayushi.trailsense.domain

import org.junit.Assert.*
import org.junit.Test

class SleepDurationInput041Test {
    @Test fun minutesDoNotNeedColonAndHoursUseTwoDigits() {
        assertEquals("06" to "55", SleepDurationInput.fields(6 + 55 / 60.0))
        assertEquals(6 + 55 / 60.0, SleepDurationInput.value("6", "55")!!, 0.0)
        assertEquals("" to "", SleepDurationInput.fields(null))
    }
    @Test fun noSilentMinuteOrDayOverflow() {
        for ((h, m) in listOf("6" to "60", "25" to "00", "24" to "01", "-1" to "00", "1" to "5.5", "1" to "", "" to "30")) {
            assertFalse("$h:$m", SleepDurationInput.valid(h, m))
            assertTrue(runCatching { SleepDurationInput.value(h, m) }.isFailure)
        }
        assertEquals(24.0, SleepDurationInput.value("24", "00")!!, 0.0)
        assertEquals(0.0, SleepDurationInput.value("0", "0")!!, 0.0)
    }
    @Test fun blankPairClearsOptionalSleep() {
        assertTrue(SleepDurationInput.valid("", ""))
        assertNull(SleepDurationInput.value("", ""))
    }
    @Test fun numericOnlyBoundedFields() {
        for (value in listOf("", "0", "06", "59")) assertTrue(SleepDurationInput.accepts(value))
        for (value in listOf("006", ":", "6:55", "-1", " 6", "６")) assertFalse(SleepDurationInput.accepts(value))
    }
    @Test fun automaticFocusWaitsForAmbiguousOneOrTwo() {
        for (value in listOf("0", "3", "6", "9", "10", "12", "24")) assertTrue(SleepDurationInput.advanceFromHours(value))
        for (value in listOf("", "1", "2")) assertFalse(SleepDurationInput.advanceFromHours(value))
    }
}
