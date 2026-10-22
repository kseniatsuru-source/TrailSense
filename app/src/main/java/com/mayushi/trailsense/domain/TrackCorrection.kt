package com.mayushi.trailsense.domain

import kotlin.math.roundToInt

/** Non-destructive track transformations. Callers should always feed the original watch track. */
object TrackCorrection {
    fun trim(points: List<TrackPoint>, startFraction: Double, endFraction: Double, recordedDistanceM: Double? = null): List<TrackPoint> {
        require(points.size >= 2) { "Трек слишком короткий для обрезки" }
        require(startFraction in 0.0..1.0 && endFraction in 0.0..1.0 && endFraction > startFraction) {
            "Некорректные границы трека"
        }
        val last = points.lastIndex
        val cumulative = TrackDistanceAxis.build(points, recordedDistanceM).meters
        val start = TrackGeometry.indexAtDistance(cumulative, cumulative.last() * startFraction).coerceIn(0, last - 1)
        val end = TrackGeometry.indexAtDistance(cumulative, cumulative.last() * endFraction).coerceIn(start + 1, last)
        return points.subList(start, end + 1).toList()
    }

    fun inferMissingOutAndBackStart(points: List<TrackPoint>, maxJoinDistanceM: Double = 150.0, manualJoinIndex: Int? = null): List<TrackPoint> {
        require(points.size >= 5) { "Для восстановления начала нужен трек туда-обратно" }
        require(points.zipWithNext().none { (a,b) -> TrackGeometry.disconnected(a,b) }) { "Нельзя зеркально восстановить трек с разрывами" }
        val first = points.first()
        require(points.maxOf { Geo.distanceM(first, it) } >= 500.0) {
            "Трек слишком короткий, чтобы надёжно восстановить пропущенное начало"
        }
        val joinIndex = manualJoinIndex?.also { require(it in 1 until points.lastIndex) { "Выбери точку на обратном участке" } }
            ?: MissingStartRecovery.returnMatch(points).joinIndex
        val joinDistance = Geo.distanceM(first, points[joinIndex])
        require(joinDistance <= maxJoinDistanceM) {
            "Точка возврата дальше 150 м от начала записи. Передвинь её ближе или выбери настоящий пропущенный старт на карте."
        }
        require(joinIndex < points.lastIndex) { "После точки возврата нет участка для восстановления" }

        val mirrored = points.subList(joinIndex, points.size).asReversed().map { source ->
            source.copy(timeEpochMs = null, heartRateBpm = null, inferred = true, segmentStart = false, watchDistanceM = null)
        }
        return mirrored + points
    }
}
