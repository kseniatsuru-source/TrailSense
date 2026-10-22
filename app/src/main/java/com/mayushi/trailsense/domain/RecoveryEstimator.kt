package com.mayushi.trailsense.domain

import kotlin.math.*

/** Geometry/elevation estimate only. Restored geometry has no measured time, HR, distance or cadence. */
object RecoveryEstimator {
    // seconds retained for persisted/caller compatibility, always unknown for a restored prefix.
    data class Estimate(val points: List<TrackPoint>,val distanceM: Double,val gainM: Double?,val lossM: Double?,val seconds: Long?)
    fun estimate(route: List<TrackPoint>, recorded: List<TrackPoint>): Estimate {
        require(route.size >= 2)
        val cell = .0003
        fun key(p: TrackPoint) = floor(p.latitude/cell).toInt() to floor(p.longitude/cell).toInt()
        val buckets = recorded.filter { !it.inferred && it.elevationM?.isFinite() == true }.groupBy(::key)
        fun nearest(p: TrackPoint): TrackPoint? {
            val (x,y)=key(p)
            val near = (-1..1).flatMap { dx -> (-1..1).flatMap { dy -> buckets[x+dx to y+dy].orEmpty() } }.minByOrNull { Geo.distanceM(p,it) }
            return near?.takeIf { Geo.distanceM(p,it) <= 25 }
        }
        val elevated = route.map {
            TrackPoint(it.latitude,it.longitude,elevationM = nearest(it)?.elevationM,inferred = true)
        }
        val covered = elevated.all { it.elevationM != null } && route.zipWithNext().all { (a,b) ->
            val length = Geo.distanceM(a,b)
            val steps = ceil(length/20).toInt().coerceAtLeast(1)
            (0..steps).all { i -> val f=i.toDouble()/steps; nearest(TrackPoint(a.latitude+(b.latitude-a.latitude)*f,a.longitude+(b.longitude-a.longitude)*f)) != null }
        }
        val metrics = TrackAnalyzer.analyze(elevated)
        return Estimate(elevated,metrics.distanceM,metrics.elevationGainM.takeIf { covered },metrics.elevationLossM.takeIf { covered },null)
    }
    fun summary(e: Estimate): String = "Добавлено ≈ %.2f км · %s · время неизвестно".format(e.distanceM/1000, e.gainM?.let { "+${it.roundToInt()} м" } ?: "набор неизвестен")
}
