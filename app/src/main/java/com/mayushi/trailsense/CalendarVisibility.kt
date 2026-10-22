package com.mayushi.trailsense

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.mayushi.trailsense.data.CalendarContextEntity

internal enum class CalendarCategory(val label:String,val color:Color) {
    HIKES("Хайки",Color(0xFF78B892)),
    ACUTE("Острый стресс",Color(0xFFEAA887)),
    ONGOING("Длящийся стресс",Color(0xFFC8A4E8)),
    SLEEP("Сон",Color(0xFFA4BDD5)),
    NOTES("Заметки",Color(0xFFAAAAAA)),
    ACTIVE("Приём противозачаточных",Color(0xFFAAAAAA)),
    BREAK("Перерыв в приёме",Color(0xFFEAB0C8)),
    MENSTRUATION("Менструации",Color(0xFFDCA4BD));
    companion object {
        fun context(event:CalendarContextEntity):CalendarCategory = when(event.type) {
            "STRESS","STRONG_STRESS","HIGH_STRESS" -> if(event.ongoing) ONGOING else ACUTE
            "SEVERE_SLEEP_DEBT" -> SLEEP
            "MENSTRUATION" -> MENSTRUATION
            else -> NOTES
        }
    }
}
internal class CalendarVisibility(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("calendar_visibility_039",Context.MODE_PRIVATE)
    fun visible():Set<CalendarCategory> = CalendarCategory.entries.filter {
        prefs.getBoolean(it.name, if(it == CalendarCategory.SLEEP) prefs.getBoolean("NOTES",true) else true)
    }.toSet()
    fun set(category:CalendarCategory,value:Boolean) {prefs.edit().putBoolean(category.name,value).commit()}
}
