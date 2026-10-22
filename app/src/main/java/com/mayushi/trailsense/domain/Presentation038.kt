package com.mayushi.trailsense.domain

import com.mayushi.trailsense.data.ActivityEntity
import com.mayushi.trailsense.data.RouteEntity
import kotlin.math.roundToInt

/** Resolve against the live binding, never rewrite recorded watch titles or metrics. */
object DisplayTitle {
    fun resolve(activity: ActivityEntity, routes: List<RouteEntity>): String =
        routes.firstOrNull { it.id == activity.routeId }?.title ?: activity.title
    fun boundTitle(route: RouteEntity, candidate: String?): String =
        if (route.sourceLabel.contains("CUSTOM_TITLE") || route.sourceLabel == "Вручную" || candidate.isNullOrBlank()) route.title else candidate
}
object SleepClock {
    fun format(hours: Double?): String = hours?.takeIf { it.isFinite() && it >= 0 }?.let {
        val minutes = (it * 60).roundToInt()
        "%d:%02d".format(minutes / 60, minutes % 60)
    } ?: ""
    fun parse(text: String): Double? {
        val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(text.trim()) ?: return null
        val hours = match.groupValues[1].toInt()
        val minutes = match.groupValues[2].toInt()
        return if (minutes < 60 && hours <= 24 && (hours < 24 || minutes == 0)) (hours * 60 + minutes) / 60.0 else null
    }
}
