package com.mayushi.trailsense.domain

import kotlin.math.roundToInt

/** Sleep editor values stay local until explicit Done. Blank pair clears the optional duration. */
object SleepDurationInput {
    fun fields(hours: Double?): Pair<String, String> {
        if (hours == null) return "" to ""
        val minutes = (hours * 60).roundToInt()
        return "%02d".format(minutes / 60) to "%02d".format(minutes % 60)
    }
    fun accepts(text: String) = text.length <= 2 && text.all { it in '0'..'9' }
    fun valid(hours: String, minutes: String): Boolean {
        if (hours.isBlank() && minutes.isBlank()) return true
        if (!accepts(hours) || !accepts(minutes)) return false
        val h = hours.toIntOrNull() ?: return false
        val m = minutes.toIntOrNull() ?: return false
        return h in 0..24 && m in 0..59 && h * 60 + m <= 24 * 60
    }
    fun value(hours: String, minutes: String): Double? {
        require(valid(hours, minutes)) { "Часы 0–24, минуты 0–59; не больше 24 часов" }
        if (hours.isBlank() && minutes.isBlank()) return null
        return (hours.toInt() * 60 + minutes.toInt()) / 60.0
    }
    fun advanceFromHours(text: String): Boolean = accepts(text) &&
        (text.length == 2 || (text.length == 1 && (text == "0" || text[0] in '3'..'9')))
}
