package com.mayushi.trailsense.domain

import org.junit.Assert.*
import org.junit.Test

class Questionnaire037Test {
    @Test fun friendlyNotesHideStorageMarkersAndRoundTripEveryOriginalLine() {
        val raw=listOf("Спутники, рюкзак и остановки", "[rating] Views | 8 | 10", "[poi] Lake | 49 | -123", "[legacy-rating] invalid old value", "[legacy-poi] invalid old location", "Дополнительная оценка: original prose", "Прежний ориентир: original prose", "Текст заметки: original prose").joinToString("\n")
        val shown=Questionnaire037.notesForDisplay(raw)
        assertFalse(shown.contains("[rating]"));assertFalse(shown.contains("[poi]"));assertFalse(shown.contains("[legacy-"))
        assertEquals(raw,Questionnaire037.notesForStorage(shown))
        val parsed=Questionnaire037.parseNotes(Questionnaire037.notesForStorage(Questionnaire037.notesForDisplay("[rating] Views | 8 | 10\n[poi] Lake | 49 | -123")))
        assertEquals("Views | 8 | 10",parsed.ratings);assertEquals("Lake | 49 | -123",parsed.pois)
    }

    @Test fun exactPaceLabels() { assertEquals(listOf("Обычный", "Чуть медленнее", "Медленнее"), Questionnaire037.paceLabels) }
    @Test fun legacyPaceDoesNotCollapseOnReselect() {
        for (value in listOf("FREE", "SLIGHT", "NOTABLE", "MUCH"))
            assertEquals(value, Questionnaire037.selectPace(value, Questionnaire037.paceLabel(value)!!))
        assertEquals("opaque-legacy", "opaque-legacy".also { assertNull(Questionnaire037.paceLabel(it)) })
        assertEquals("FREE", Questionnaire037.selectPace("MUCH", "Обычный"))
    }
    @Test fun conflictRequiresChoiceNotAverage() {
        assertTrue(Questionnaire037.climbConflict(2, 5)); assertNull(Questionnaire037.initialClimb(2, 5))
        assertEquals(4, Questionnaire037.initialClimb(null, 4)); assertEquals(2, Questionnaire037.initialClimb(2, null))
    }
    @Test fun historyKeepsBothOriginalClimbValues() {
        val audit = Questionnaire037.climbHistory("older metadata", 2, 5, 4)
        assertTrue(audit.contains("route=2|activity=5|chosen=4"))
        assertTrue(audit.startsWith("older metadata\n"))
        assertEquals(audit, Questionnaire037.climbHistory(audit, 2, 5, 4))
    }
    @Test fun identicalProseDeduplicatedWithoutRemovingRepeatedStoryLines() {
        val text = "Again\nAgain"
        assertEquals(text, Questionnaire037.mergeNotes(text, text, "", ""))
        assertEquals("A\nB", Questionnaire037.mergeNotes("A", "B", "", ""))
    }
    @Test fun structuredNotesRoundTripAndMapPoiParsing() {
        val merged = Questionnaire037.mergeNotes("Story", "Route detail", "Lake | 8 | 10", "Lake | 49.2 | -123.1")
        val parsed = Questionnaire037.parseNotes(merged)
        assertEquals("Story\nRoute detail", parsed.comment)
        assertEquals(8, HikeAnnotations.ratings(parsed.ratings).single().value)
        assertEquals(49.2, HikeAnnotations.pois(parsed.pois).single().latitude, 0.0)
        assertEquals(merged, Questionnaire037.mergeNotes(parsed.comment, "", parsed.ratings, parsed.pois))
    }
    @Test fun duplicateStructuredLinesDeduplicate() {
        val parsed = Questionnaire037.parseNotes("[rating] Lake | 8 | 10\n[rating] Lake | 8 | 10\n[poi] Lake | 49.2 | -123.1\n[poi] Lake | 49.2 | -123.1")
        assertEquals(1, HikeAnnotations.ratings(parsed.ratings).size)
        assertEquals(1, HikeAnnotations.pois(parsed.pois).size)
    }
    @Test fun malformedLegacyAnnotationsAreNotDiscarded() {
        val merged = Questionnaire037.mergeNotes("", "", "unknown old rating", "unknown old poi")
        val parsed = Questionnaire037.parseNotes(merged)
        assertTrue(parsed.comment.contains("[legacy-rating] unknown old rating"))
        assertTrue(parsed.comment.contains("[legacy-poi] unknown old poi"))
        assertEquals("", parsed.ratings); assertEquals("", parsed.pois)
    }
    @Test fun malformedNewAnnotationsFailVisibly() {
        for (input in listOf("[rating] ", "[poi] ", "[rating] Lake | 20 | 10", "[poi] Lake | 100 | -123")) {
            assertTrue(input, runCatching { Questionnaire037.parseNotes(input) }.isFailure)
        }
    }
    @Test fun nonDirtySleepRebasesOnConcurrentOcr() {
        assertEquals(7.25, Questionnaire037.effectiveSleep(7.25, 9.0, false)!!, 0.0)
        assertEquals(9.0, Questionnaire037.effectiveSleep(7.25, 9.0, true)!!, 0.0)
        assertNull(Questionnaire037.effectiveSleep(7.25, null, true))
    }
    @Test fun sleepConflictIsDurationOnlyAndHistoryKeepsOverride() {
        assertTrue(Questionnaire037.sleepConflict(9.0, 7.25))
        assertFalse(Questionnaire037.sleepConflict(null, 7.25))
        assertFalse(Questionnaire037.sleepConflict(7.25, 7.25))
        val audit = Questionnaire037.appendHistory("", "sleep|previous=9.0|suggested=7.25|chosen=7.25")
        assertTrue(audit.contains("previous=9.0")); assertTrue(audit.contains("chosen=7.25"))
    }
    @Test fun plainNotesNeedNoSyntax() {
        val parsed = Questionnaire037.parseNotes("A lake | no coordinates\nA beautiful day")
        assertEquals("A lake | no coordinates\nA beautiful day", parsed.comment)
        assertEquals("", parsed.ratings); assertEquals("", parsed.pois)
    }
}
