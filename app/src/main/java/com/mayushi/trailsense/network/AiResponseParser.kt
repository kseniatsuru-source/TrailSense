package com.mayushi.trailsense.network

import org.json.JSONObject

data class RouteFacts(
    val title: String?,
    val distanceM: Double?,
    val gainM: Double?,
    val lossM: Double?,
    val difficulty: String?,
    val rating: Double?,
)

data class ActivityFacts(
    val title: String?,
    val distanceM: Double?,
    val gainM: Double?,
    val lossM: Double?,
    val elapsedSeconds: Long?,
    val movingSeconds: Long?,
    val stoppedSeconds: Long?,
    val averageHeartRateBpm: Int?,
    val maxHeartRateBpm: Int?,
)

data class ParsedAiResponse(
    val summary: String,
    val routeFacts: RouteFacts?,
    val activityFacts: ActivityFacts? = null,
)

object AiResponseParser {
    fun parse(raw: String): ParsedAiResponse {
        val cleaned = clean(raw)
        return try {
            parseJson(cleaned)
        } catch (_: Exception) {
            ParsedAiResponse(cleaned.ifBlank { "Mayushi не вернула текст анализа" }, null)
        }
    }

    fun parseStrict(raw: String): ParsedAiResponse {
        val cleaned = clean(raw)
        val parsed = try {
            parseJson(cleaned)
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: Exception) {
            throw IllegalArgumentException("Mayushi вернула повреждённый JSON анализа")
        }
        require(parsed.summary.isNotBlank()) { "В анализе нет summary" }
        return parsed
    }

    private fun clean(raw: String): String = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    private fun parseJson(cleaned: String): ParsedAiResponse {
        val json = JSONObject(cleaned)
        val summary = json.stringOrNull("summary", MAX_SUMMARY_LENGTH)
            ?: throw IllegalArgumentException("В анализе нет summary")
        val routeFacts = json.objectOrNull("route_facts")?.let {
            RouteFacts(
                title = it.stringOrNull("title", MAX_TITLE_LENGTH),
                distanceM = it.doubleInRangeOrNull("distance_m", 0.0, MAX_DISTANCE_M),
                gainM = it.doubleInRangeOrNull("gain_m", 0.0, MAX_ELEVATION_M),
                lossM = it.doubleInRangeOrNull("loss_m", 0.0, MAX_ELEVATION_M),
                difficulty = it.stringOrNull("difficulty", MAX_DIFFICULTY_LENGTH),
                rating = it.doubleInRangeOrNull("rating", 0.0, 5.0),
            )
        }
        val activityFacts = json.objectOrNull("activity_facts")?.let {
            ActivityFacts(
                title = it.stringOrNull("title", MAX_TITLE_LENGTH),
                distanceM = it.doubleInRangeOrNull("distance_m", 0.0, MAX_DISTANCE_M),
                gainM = it.doubleInRangeOrNull("gain_m", 0.0, MAX_ELEVATION_M),
                lossM = it.doubleInRangeOrNull("loss_m", 0.0, MAX_ELEVATION_M),
                elapsedSeconds = it.longInRangeOrNull("elapsed_s", 0, MAX_DURATION_SECONDS),
                movingSeconds = it.longInRangeOrNull("moving_s", 0, MAX_DURATION_SECONDS),
                stoppedSeconds = it.longInRangeOrNull("stopped_s", 0, MAX_DURATION_SECONDS),
                averageHeartRateBpm = it.intInRangeOrNull("avg_hr", MIN_HEART_RATE, MAX_HEART_RATE),
                maxHeartRateBpm = it.intInRangeOrNull("max_hr", MIN_HEART_RATE, MAX_HEART_RATE),
            ).also(::validateActivityConsistency)
        }
        return ParsedAiResponse(summary, routeFacts, activityFacts)
    }

    private fun validateActivityConsistency(facts: ActivityFacts) {
        val elapsed = facts.elapsedSeconds
        val moving = facts.movingSeconds
        val stopped = facts.stoppedSeconds
        if (elapsed != null && moving != null) require(moving <= elapsed) { "Некорректное поле moving_s: больше elapsed_s" }
        if (elapsed != null && stopped != null) require(stopped <= elapsed) { "Некорректное поле stopped_s: больше elapsed_s" }
        if (elapsed != null && moving != null && stopped != null) {
            require(moving + stopped <= elapsed + TIME_TOLERANCE_SECONDS) { "Некорректные поля moving_s/stopped_s" }
        }
        val average = facts.averageHeartRateBpm
        val maximum = facts.maxHeartRateBpm
        if (average != null && maximum != null) require(average <= maximum) { "Некорректное поле avg_hr: больше max_hr" }
    }

    private fun JSONObject.objectOrNull(name: String): JSONObject? {
        if (!has(name) || isNull(name)) return null
        return optJSONObject(name) ?: throw IllegalArgumentException("Некорректное поле $name")
    }

    private fun JSONObject.stringOrNull(name: String, maxLength: Int): String? {
        if (!has(name) || isNull(name)) return null
        val value = opt(name)
        require(value is String) { "Некорректное поле $name" }
        val cleaned = value.trim()
        if (cleaned.isEmpty()) return null
        require(cleaned.length <= maxLength) { "Некорректное поле $name: слишком длинное" }
        return cleaned
    }

    private fun JSONObject.doubleInRangeOrNull(name: String, min: Double, max: Double): Double? {
        if (!has(name) || isNull(name)) return null
        val number = opt(name) as? Number ?: throw IllegalArgumentException("Некорректное поле $name")
        val value = number.toDouble()
        require(value.isFinite() && value in min..max) { "Некорректное поле $name" }
        return value
    }

    private fun JSONObject.longInRangeOrNull(name: String, min: Long, max: Long): Long? {
        if (!has(name) || isNull(name)) return null
        val number = opt(name) as? Number ?: throw IllegalArgumentException("Некорректное поле $name")
        val double = number.toDouble()
        val value = number.toLong()
        require(double.isFinite() && double == value.toDouble() && value in min..max) { "Некорректное поле $name" }
        return value
    }

    private fun JSONObject.intInRangeOrNull(name: String, min: Int, max: Int): Int? {
        val value = longInRangeOrNull(name, min.toLong(), max.toLong()) ?: return null
        return value.toInt()
    }

    private const val MAX_SUMMARY_LENGTH = 20_000
    private const val MAX_TITLE_LENGTH = 200
    private const val MAX_DIFFICULTY_LENGTH = 100
    private const val MAX_DISTANCE_M = 1_000_000.0
    private const val MAX_ELEVATION_M = 100_000.0
    private const val MAX_DURATION_SECONDS = 7L * 24 * 60 * 60
    private const val MIN_HEART_RATE = 20
    private const val MAX_HEART_RATE = 250
    private const val TIME_TOLERANCE_SECONDS = 300
}
