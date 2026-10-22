package com.mayushi.trailsense.domain

import org.json.JSONArray
import org.json.JSONObject

/** Voluntary conditions encountered during the hike, without inferred order/duration. */
data class WeatherContext(val felt: Set<String> = emptySet(), val conditions: Set<String> = emptySet(), val temperatureC: Double? = null) {
    fun json(): JSONObject = JSONObject().put("felt", JSONArray(FELT.filter { it in felt }))
        .put("conditions", JSONArray(CONDITIONS.filter { it in conditions })).put("temperature_c", temperatureC ?: JSONObject.NULL)
    fun save(history: String): String {
        require(felt.all { it in FELT } && conditions.all { it in CONDITIONS })
        require(temperatureC == null || temperatureC.isFinite())
        if (read(history) == this) return history
        return history.trimEnd() + (if(history.isBlank()) "" else "\n") + PREFIX + json().toString()
    }
    companion object {
        val FELT = listOf("Жарко", "Комфортно", "Холодно")
        val CONDITIONS = listOf("Солнечно", "Облачно", "Дождь", "Снег", "Ветер", "Туман")
        private const val PREFIX = "q041|weather|"
        fun temperature(text: String): Double? {
            if(text.isBlank()) return null
            return text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: throw IllegalArgumentException("Введи температуру числом")
        }
        fun read(history: String): WeatherContext {
            val line = history.lines().lastOrNull { it.startsWith(PREFIX) } ?: return WeatherContext()
            return runCatching {
                val json = JSONObject(line.removePrefix(PREFIX))
                fun values(key: String, allowed: List<String>): Set<String> {
                    val array = json.getJSONArray(key)
                    return (0 until array.length()).map { array.getString(it) }.filter { it in allowed }.toSet()
                }
                WeatherContext(values("felt", FELT), values("conditions", CONDITIONS), if(json.isNull("temperature_c")) null else json.getDouble("temperature_c"))
            }.getOrDefault(WeatherContext())
        }
    }
}
