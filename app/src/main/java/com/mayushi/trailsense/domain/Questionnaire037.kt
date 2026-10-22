package com.mayushi.trailsense.domain

/** Pure migration/edit policies. No watch metrics or Android dependencies. */
object Questionnaire037 {
    val paceLabels = listOf("Обычный", "Чуть медленнее", "Медленнее")
    fun paceLabel(stored: String): String? = when (stored) {
        "FREE" -> paceLabels[0]
        "SLIGHT" -> paceLabels[1]
        "NOTABLE", "MUCH" -> paceLabels[2]
        else -> null
    }
    /** Clicking the already-selected merged label is NOT a migration from MUCH to NOTABLE. */
    fun selectPace(stored: String, label: String): String {
        require(label in paceLabels)
        if (paceLabel(stored) == label) return stored
        return when (label) { paceLabels[0] -> "FREE"; paceLabels[1] -> "SLIGHT"; else -> "NOTABLE" }
    }
    data class Notes(val comment: String, val ratings: String, val pois: String)
    private const val RATING = "[rating] "
    private const val POI = "[poi] "
    private val noteLabels = linkedMapOf(
        "[rating] " to "Дополнительная оценка: ", "[poi] " to "Ориентир: ",
        "[legacy-rating] " to "Прежняя оценка: ", "[legacy-poi] " to "Прежний ориентир: "
    )
    private const val literalNote = "Текст заметки: "
    /** Friendly editor labels; storage markers remain private and round-trip exactly. */
    fun notesForDisplay(text: String): String = text.lines().joinToString("\n") { line ->
        val marker=noteLabels.keys.firstOrNull{line.startsWith(it)}
        when {
            marker!=null -> noteLabels.getValue(marker)+line.removePrefix(marker)
            line.startsWith(literalNote) || noteLabels.values.any{line.startsWith(it)} -> literalNote+line
            else -> line
        }
    }
    fun notesForStorage(text: String): String = text.lines().joinToString("\n") { line ->
        if(line.startsWith(literalNote)) line.removePrefix(literalNote)
        else {
            val entry=noteLabels.entries.firstOrNull{line.startsWith(it.value)}
            if(entry==null) line else entry.key+line.removePrefix(entry.value)
        }
    }

    fun parseNotes(text: String): Notes {
        val prose = mutableListOf<String>()
        val ratings = linkedSetOf<String>()
        val pois = linkedSetOf<String>()
        text.lines().forEach { line ->
            when {
                line.startsWith(RATING) -> {
                    val raw = line.removePrefix(RATING)
                    require(raw.isNotBlank()) { "Критерий: название | оценка | максимум" }
                    HikeAnnotations.ratings(raw) // fail visibly, never discard malformed structured input
                    ratings += raw
                }
                line.startsWith(POI) -> {
                    val raw = line.removePrefix(POI)
                    require(raw.isNotBlank()) { "POI: название | широта | долгота" }
                    HikeAnnotations.pois(raw)
                    pois += raw
                }
                else -> prose += line
            }
        }
        val result = Notes(prose.joinToString("\n").trim(), ratings.joinToString("\n"), pois.joinToString("\n"))
        HikeAnnotations.validate(result.ratings, result.pois)
        return result
    }
    /** Exact block dedup only for prose: never erase repeated lines within a user's story. */
    fun mergeNotes(comment: String, routeNotes: String, ratings: String, pois: String): String {
        val blocks = linkedSetOf<String>()
        listOf(comment, routeNotes).filter { it.isNotBlank() }.forEach { blocks += it.trim() }
        val lines = blocks.flatMap { it.lines() }.toMutableList()
        fun migrate(raw: String, prefix: String, valid: (String) -> Unit, legacy: String) {
            raw.lines().filter { it.isNotBlank() }.forEach { line ->
                val rendered = if (runCatching { valid(line) }.isSuccess) prefix + line else "$legacy $line"
                if (rendered !in lines) lines += rendered
            }
        }
        migrate(ratings, RATING, { HikeAnnotations.ratings(it) }, "[legacy-rating]")
        migrate(pois, POI, { HikeAnnotations.pois(it) }, "[legacy-poi]")
        return lines.joinToString("\n")
    }
    fun climbConflict(route: Int?, activity: Int?) = route != null && activity != null && route != activity
    fun initialClimb(route: Int?, activity: Int?): Int? = if (climbConflict(route, activity)) null else activity ?: route
    /** Versioned exact-value audit, idempotent across reopen/save. */
    fun appendHistory(history: String, entry: String): String {
        require('\n' !in entry && '\r' !in entry)
        val line = "q037|$entry"
        return if (line in history.lines()) history else listOf(history.trimEnd(), line).filter { it.isNotEmpty() }.joinToString("\n")
    }
    fun climbHistory(history: String, route: Int?, activity: Int?, chosen: Int?): String =
        if (route == activity && route == chosen) history
        else appendHistory(history, "climb|route=${route ?: "null"}|activity=${activity ?: "null"}|chosen=${chosen ?: "null"}")
    fun effectiveSleep(latest: Double?, draft: Double?, dirty: Boolean) = if (dirty) draft else latest
    fun sleepConflict(manual: Double?, suggested: Double?) = manual != null && suggested != null && manual != suggested
}
