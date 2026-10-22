package com.mayushi.trailsense.domain

/** Conservative parser of OCR evidence, not a bedtime/wake-time calculator. */
object SleepDurationReader {
    data class Result(val minutes: Int?, val confidence: Double, val reason: String, val wakingDate: java.time.LocalDate? = null)

    fun unknown(reason: String) = Result(null, 0.0, reason)

    private val label = Regex(
        "^(?:total sleep(?: time| duration)?|night sleep(?: time| duration)?|time asleep|actual sleep(?: duration)?|sleep duration|total time asleep|общая продолжительность сна|общее время сна|продолжительность сна)\\s*[:：]?\\s*(.*)$",
        RegexOption.IGNORE_CASE,
    )
    private val hours = Regex("^(\\d{1,2})\\s*(?:h|hr|hrs|hour|hours|ч|час|часа|часов)(?:\\s*(\\d{1,2})\\s*(?:m|min|mins|minute|minutes|мин|минут|минуты))?$", RegexOption.IGNORE_CASE)
    private val minutes = Regex("^(\\d{1,4})\\s*(?:m|min|mins|minute|minutes|мин|минут|минуты)$", RegexOption.IGNORE_CASE)
    private val colon = Regex("^(\\d{1,2}):(\\d{2})$")

    fun read(text: String): Result {
        val lines = text.replace('\u00a0', ' ').lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val found = mutableListOf<Pair<Int, Double>>()
        var unreadableLabel = false
        for (i in lines.indices) {
            val match = label.matchEntire(lines[i]) ?: continue
            val inline = match.groupValues[1]
            val valueIndex = if (inline.isBlank()) i + 1 else i
            val valueText = inline.ifBlank { lines.getOrNull(valueIndex).orEmpty() }
            // Huawei cards append a qualitative status to the explicitly labelled duration.
            // Strip only the known card status, never estimates or arbitrary trailing text.
            var value = if (lines[i].startsWith("night sleep", ignoreCase = true))
                valueText.replace(Regex("""\s+(?:Normal|Low|High)$""", RegexOption.IGNORE_CASE), "") else valueText
            // OCR may place the two units on adjacent lines; never silently drop minutes.
            val h = hours.matchEntire(value)
            val next = lines.getOrNull(valueIndex + 1).orEmpty()
            if (h != null && h.groupValues[2].isBlank() && minutes.matches(next)) value += " $next"
            val duration = parseDuration(value)
            if (duration == null) unreadableLabel = true
            else found += duration to if (match.groupValues[1].isBlank()) 0.85 else 0.95
        }
        if (unreadableLabel) return unknown("Явное общее время сна не удалось однозначно прочитать")
        if (found.isEmpty()) return unknown("Нет явной подписи фактического общего времени сна; интервал в постели не используется")
        if (found.map { it.first }.distinct().size != 1) return unknown("На изображении конфликтующие значения общего сна")
        return Result(found.first().first, found.minOf { it.second }, "Явно подписанная длительность общего сна; проверь перед сохранением")
    }

    private fun parseDuration(value: String): Int? {
        val h = hours.matchEntire(value)
        val c = colon.matchEntire(value)
        val m = minutes.matchEntire(value)
        val result = when {
            h != null -> {
                val mins = h.groupValues[2].ifBlank { "0" }.toInt()
                if (mins >= 60) return null
                h.groupValues[1].toInt() * 60 + mins
            }
            c != null -> {
                val mins = c.groupValues[2].toInt()
                if (mins >= 60) return null
                c.groupValues[1].toInt() * 60 + mins
            }
            m != null -> m.groupValues[1].toInt()
            else -> return null
        }
        return result.takeIf { it in 1..1440 }
    }
}
