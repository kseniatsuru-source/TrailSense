package com.mayushi.trailsense.network

import android.content.Context

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var hermesEndpoint: String
        get() = prefs.getString("hermes_endpoint", "") ?: ""
        set(value) { prefs.edit().putString("hermes_endpoint", value).apply() }
    var directHermesEnabled: Boolean
        get() = prefs.getBoolean("direct_hermes_enabled", false)
        set(value) { prefs.edit().putBoolean("direct_hermes_enabled", value).apply() }
    var shortAutoAnalysis: Boolean
        get() = prefs.getBoolean("short_auto", true)
        set(value) { prefs.edit().putBoolean("short_auto", value).apply() }
    var postHikeAutoAnalysis: Boolean
        get() = prefs.getBoolean("post_auto", true)
        set(value) { prefs.edit().putBoolean("post_auto", value).apply() }
    var monthlyTokenBudget: Int
        get() = prefs.getInt("monthly_budget", 300_000)
        set(value) { prefs.edit().putInt("monthly_budget", value.coerceAtLeast(0)).apply() }
    var sleepNormHours: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong("sleep_norm", java.lang.Double.doubleToRawLongBits(9.0)))
        set(value) { prefs.edit().putLong("sleep_norm", java.lang.Double.doubleToRawLongBits(value.coerceIn(1.0, 16.0))).apply() }
}
