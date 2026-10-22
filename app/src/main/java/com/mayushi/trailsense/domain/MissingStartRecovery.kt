package com.mayushi.trailsense.domain

import kotlin.math.*

/** Local tangent-plane projection, used only for matching; never creates a network edge. */
object PolylineMatch {
    data class Projection(val point: TrackPoint, val fraction: Double, val distanceM: Double)
    fun project(p: TrackPoint, a: TrackPoint, b: TrackPoint): Projection {
        val scale = cos(Math.toRadians(p.latitude))
        val dx = (b.longitude-a.longitude)*scale; val dy = b.latitude-a.latitude
        val f = if (dx*dx+dy*dy == 0.0) 0.0 else
            (((p.longitude-a.longitude)*scale*dx+(p.latitude-a.latitude)*dy)/(dx*dx+dy*dy)).coerceIn(0.0,1.0)
        val q = TrackPoint(a.latitude+(b.latitude-a.latitude)*f,a.longitude+(b.longitude-a.longitude)*f)
        return Projection(q,f,Geo.distanceM(p,q))
    }
    fun distance(p: TrackPoint, line: List<TrackPoint>): Double = line.zipWithNext().minOfOrNull { (a,b) -> project(p,a,b).distanceM }
        ?: line.firstOrNull()?.let { Geo.distanceM(p,it) } ?: Double.POSITIVE_INFINITY
    fun sample(line: List<TrackPoint>, stepM: Double = 20.0): List<TrackPoint> {
        require(stepM > 0 && stepM.isFinite())
        if(line.size < 2) return line
        val cumulative=TrackGeometry.cumulative(line)
        val result=mutableListOf(line.first()); var target=stepM; var i=1
        while(target < cumulative.last() - .001) {
            while(i < line.lastIndex && cumulative[i] < target) i++
            val f=(target-cumulative[i-1])/(cumulative[i]-cumulative[i-1]).coerceAtLeast(.000001)
            val a=line[i-1];val b=line[i]
            result.add(TrackPoint(a.latitude+(b.latitude-a.latitude)*f,a.longitude+(b.longitude-a.longitude)*f))
            target+=stepM
        }
        result.add(line.last());return result
    }
}

/** Inferred geometry only. Neither the watch's finish nor a map can prove the true start. */
object MissingStartRecovery {
    data class ReturnMatch(val joinIndex: Int, val corridor: List<TrackPoint>)
    data class ManualRoute(val points: List<TrackPoint>, val connectorM: Double, val endConnectorM: Double = 0.0) {
        val needsConfirmation: Boolean get() = connectorM > 5.0 || endConnectorM > 5.0
    }
    private data class Candidate(val edge: Int, val join: TrackPoint, val arc: Double, val error: Double)

    fun validateRecording(points: List<TrackPoint>) {
        require(points.size >= 2 && points.none { it.inferred } && points.all {
            it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
        }) { "Нужна исходная запись часов." }
        require(points.zipWithNext().none { (a,b) -> TrackGeometry.disconnected(a,b) || Geo.distanceM(a,b) > 250.0 }) {
            "В записи есть разрыв. Выбери другой участок: восстановление начала не исправит разрыв."
        }
    }

    private fun candidates(points: List<TrackPoint>): List<Candidate> {
        validateRecording(points)
        require(points.size >= 5) { "Недостаточно записи. Выбери старт вручную." }
        val cumulative = TrackGeometry.cumulative(points)
        require(cumulative.last() >= 1000.0) { "Недостаточно записанного пути для проверки возврата. Выбери старт вручную." }
        val forward = PolylineMatch.sample(clip(points, 300.0))
        return (0 until points.lastIndex).mapNotNull { i ->
            val projection = PolylineMatch.project(points.first(), points[i], points[i+1])
            val arc = cumulative[i] + Geo.distanceM(points[i], projection.point)
            if (arc < 600.0 || cumulative.last()-arc < 20.0 || projection.distanceM > 25.0) return@mapNotNull null
            val reverse = PolylineMatch.sample(clip(listOf(projection.point) + points.take(i+1).asReversed(), 300.0))
            val errors = forward.zip(reverse).map { (a,b) -> Geo.distanceM(a,b) }
            if (reverse.size != forward.size || errors.isEmpty() || errors.max() > 35.0) null
            else Candidate(i, projection.point, arc, errors.average() + projection.distanceM)
        }.sortedWith(compareBy<Candidate> { it.error }.thenBy { it.arc })
    }

    fun returnMatch(points: List<TrackPoint>): ReturnMatch {
        val matches = candidates(points)
        require(matches.isNotEmpty()) { "Обратный участок не совпадает с началом записи. Выбери старт вручную." }
        val best = matches.first()
        require(matches.none { abs(it.arc-best.arc) > 100.0 && it.error <= best.error+5.0 }) {
            "Есть несколько совпадающих возвратов. Выбери старт вручную: автоматически достроить маршрут нельзя."
        }
        // Keep actual watch vertices, including the finish; do not replace them with OSM or a shortcut.
        val line = (points.drop(best.edge+1).asReversed() + best.join + points.first())
            .map(::geometryOnly).fold(mutableListOf<TrackPoint>()) { acc, p ->
                if (acc.isEmpty() || Geo.distanceM(acc.last(),p) > .01) acc.add(p)
                acc
            }
        require(line.size >= 2) { "Пропущенный участок слишком короткий." }
        return ReturnMatch(best.edge+1, line)
    }

    fun automatic(points: List<TrackPoint>): List<TrackPoint> = returnMatch(points).corridor

    /** Kept for source compatibility; automatic recovery deliberately does not use this graph. */
    @Suppress("UNUSED_PARAMETER")
    fun automatic(points: List<TrackPoint>, graph: TrailGraph): List<TrackPoint> = automatic(points)

    /** Clip the observed return corridor at the selected start, with an explicit approximate connector. */
    fun manual(points: List<TrackPoint>, start: TrackPoint): ManualRoute {
        require(start.latitude.isFinite() && start.longitude.isFinite() && start.latitude in -90.0..90.0 && start.longitude in -180.0..180.0)
        val line = returnMatch(points).corridor
        val cumulative = TrackGeometry.cumulative(line)
        val projections = line.zipWithNext().mapIndexed { i,(a,b) -> i to PolylineMatch.project(start,a,b) }
        val best = projections.minBy { it.second.distanceM }
        val arc = cumulative[best.first] + Geo.distanceM(line[best.first], best.second.point)
        require(projections.none { (i,p) ->
            p.distanceM <= best.second.distanceM+5.0 && abs(cumulative[i]+Geo.distanceM(line[i],p.point)-arc) > 100.0
        }) { "Выбранная точка подходит к нескольким участкам записи. Измени точку или построй по тропам." }
        val remainder = listOf(best.second.point) + line.drop(best.first+1)
        require(TrackGeometry.cumulative(remainder).last() >= 5.0) { "Старт совпал с началом записи. Выбери пропущенный старт дальше по треку." }
        val connector = best.second.distanceM
        val path = (listOf(geometryOnly(start)) + remainder).fold(mutableListOf<TrackPoint>()) { acc,p ->
            if (acc.isEmpty() || Geo.distanceM(acc.last(),p) > .01) acc.add(geometryOnly(p))
            acc
        }
        return ManualRoute(path, connector)
    }

    /** Graph route with explicit endpoint connectors; never silently snap away the user's start. */
    fun trails(points: List<TrackPoint>, start: TrackPoint, graph: TrailGraph): ManualRoute {
        validateRecording(points)
        val path = graph.route(start, points.first(), snapM = Double.MAX_VALUE)
        val startGap = Geo.distanceM(start, path.first())
        val endGap = Geo.distanceM(path.last(), points.first())
        val connected = (listOf(start) + path + points.first()).map(::geometryOnly)
            .fold(mutableListOf<TrackPoint>()) { acc,p ->
                if (acc.isEmpty() || Geo.distanceM(acc.last(),p) > .01) acc.add(p)
                acc
            }
        return ManualRoute(connected, startGap, endGap)
    }

    private fun geometryOnly(p: TrackPoint) = TrackPoint(p.latitude,p.longitude,inferred = true)

    private fun clip(line: List<TrackPoint>, lengthM: Double): List<TrackPoint> {
        val result = mutableListOf(line.first()); var length = 0.0
        for ((a,b) in line.zipWithNext()) {
            val d = Geo.distanceM(a,b)
            if (length+d >= lengthM && d > 0) {
                val f = (lengthM-length)/d
                result.add(TrackPoint(a.latitude+(b.latitude-a.latitude)*f,a.longitude+(b.longitude-a.longitude)*f))
                break
            }
            result.add(b); length += d
        }
        return result
    }
}
