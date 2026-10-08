package com.mayushi.trailsense.domain

import kotlin.math.roundToInt

/** Non-destructive track transformations. Callers should always feed the original watch track. */
object TrackCorrection {
    fun trim(points: List<TrackPoint>, startFraction: Double, endFraction: Double): List<TrackPoint> {
        require(points.size >= 2) { "Трек слишком короткий для обрезки" }
        require(startFraction in 0.0..1.0 && endFraction in 0.0..1.0 && endFraction > startFraction) {
            "Некорректные границы трека"
        }
        val last = points.lastIndex
        val start = (last * startFraction).roundToInt().coerceIn(0, last - 1)
        val end = (last * endFraction).roundToInt().coerceIn(start + 1, last)
        return points.subList(start, end + 1).toList()
    }

    fun inferMissingOutAndBackStart(points: List<TrackPoint>, maxJoinDistanceM: Double = 150.0, manualJoinIndex: Int? = null): List<TrackPoint> {
        require(points.size >= 5) { "Для восстановления начала нужен трек туда-обратно" }
        require(points.zipWithNext().none { (a,b) -> TrackGeometry.disconnected(a,b) }) { "Нельзя зеркально восстановить трек с разрывами" }
        val first = points.first()
        require(points.maxOf { Geo.distanceM(first, it) } >= 500.0) {
            "Трек слишком короткий, чтобы надёжно восстановить пропущенное начало"
        }
        val searchStart = (points.size / 2).coerceAtMost(points.lastIndex - 2)
        val joinIndex = manualJoinIndex?.also { require(it in searchStart until points.lastIndex) { "Выбери точку на обратном участке" } } ?: (searchStart until points.lastIndex)
            .minByOrNull { Geo.distanceM(first, points[it]) }
            ?: throw IllegalArgumentException("Не удалось найти обратный участок возле точки старта записи")
        val joinDistance = Geo.distanceM(first, points[joinIndex])
        require(joinDistance <= maxJoinDistanceM) {
            "Трек не возвращается к месту, где была включена запись; безопасно восстановить начало нельзя"
        }
        require(joinIndex < points.lastIndex) { "После точки возврата нет участка для восстановления" }

        val recordedStart = first.timeEpochMs
        val joinTime = points[joinIndex].timeEpochMs
        val endTime = points.last().timeEpochMs
        val mirrored = points.subList(joinIndex, points.size).asReversed().map { source ->
            val inferredTime = if (recordedStart != null && joinTime != null && endTime != null && source.timeEpochMs != null) {
                recordedStart - (source.timeEpochMs - joinTime)
            } else null
            source.copy(timeEpochMs = inferredTime, heartRateBpm = null, inferred = true, segmentStart = false)
        }
        return mirrored.dropLast(1) + points
    }
}
