package com.mayushi.trailsense

import android.content.Context
import com.mayushi.trailsense.domain.PillReminderKind

internal enum class MedicationMode { PILLS, MENSTRUATION }

internal class MedicationPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("medication_preferences", Context.MODE_PRIVATE)
    var mode: MedicationMode
        get() = runCatching { MedicationMode.valueOf(prefs.getString("mode", null).orEmpty()) }.getOrDefault(MedicationMode.PILLS)
        set(value) { check(prefs.edit().putString("mode", value.name).commit()) }
    var remindersEnabled: Boolean
        get() = prefs.getBoolean("reminders_enabled", false)
        set(value) { check(prefs.edit().putBoolean("reminders_enabled", value).commit()) }
    @set:JvmName("assignReminderTime")
    var reminderTime: String
        get() = prefs.getString("reminder_time", "09:00") ?: "09:00"
        set(value) { setReminderTime(value) }
    fun option(kind: PillReminderKind): Boolean = prefs.getBoolean("option_${kind.name}", true)
    fun setOption(kind: PillReminderKind, value: Boolean) {
        check(prefs.edit().putBoolean("option_${kind.name}", value).commit())
    }
    fun setReminderTime(value: String): Boolean {
        if (!validReminderTime(value)) return false
        return prefs.edit().putString("reminder_time", value).commit()
    }
    internal fun snapshot(): String = listOf(mode.name, remindersEnabled.toString(), reminderTime,
        PillReminderKind.values().joinToString { "${it.name}:${option(it)}" }).joinToString("|")
}

internal fun validReminderTime(value: String): Boolean =
    Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(value)
