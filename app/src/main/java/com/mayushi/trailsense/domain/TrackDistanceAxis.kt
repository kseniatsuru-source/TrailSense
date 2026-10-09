package com.mayushi.trailsense.domain

/** Display/selection axis only. Never changes coordinates or original watch samples. */
object TrackDistanceAxis {
    data class Axis(val meters: DoubleArray, val provenance: String)
    fun build(points: List<TrackPoint>, recordedDistanceM: Double? = null): Axis {
        if (points.isEmpty()) return Axis(doubleArrayOf(), "Нет точек")
        val gps = TrackGeometry.cumulative(points)
        val recorded = points.indices.filter { !points[it].inferred }
        if (recorded.isEmpty()) return Axis(gps, "Шкала: геометрия оценённого участка")
        val start = recorded.firstOrNull() ?: 0
        val end = recorded.lastOrNull() ?: points.lastIndex
        val raw = recorded.map { points[it].watchDistanceM }
        val span = if (raw.all { it != null && it.isFinite() && it >= 0 } && raw.zipWithNext().all { (a,b) -> b!! >= a!! }) raw.last()!! - raw.first()!! else 0.0
        val target = recordedDistanceM?.takeIf { it.isFinite() && it > 0 } ?: span.takeIf { it > 0 } ?: (gps[end] - gps[start])
        val sane = span > 0 && span / target in 0.8..1.2
        val axis = gps.copyOf()
        for (i in start..end) axis[i] = gps[start] + if (sane) (points[i].watchDistanceM!! - raw.first()!!) * target / span else (gps[i] - gps[start]) * target / (gps[end] - gps[start]).coerceAtLeast(1.0)
        for (i in end+1 until points.size) axis[i] = axis[end] + gps[i] - gps[end]
        return Axis(axis, if (sane) "Шкала: накопленная дистанция часов, согласована с итогом записи" else "Шкала: GPS пропорционально дистанции часов; расстояния отдельных точек не измерены часами")
    }
}
