package com.mayushi.trailsense.domain

import kotlin.math.*

/** Conservative estimate: elevations only where recorded geometry covers the same path. */
object RecoveryEstimator {
    data class Estimate(val points: List<TrackPoint>,val distanceM: Double,val gainM: Double?,val lossM: Double?,val seconds: Long?)
    private data class Sample(val distance: Double,val grade: Double,val secondsPerM: Double)
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
        val elevated = route.map { it.copy(elevationM = nearest(it)?.elevationM,timeEpochMs = null,heartRateBpm = null,watchDistanceM = null,inferred = true) }
        val covered = elevated.all { it.elevationM != null } && route.zipWithNext().all { (a,b) ->
            val length = Geo.distanceM(a,b)
            val steps = ceil(length/20).toInt().coerceAtLeast(1)
            (0..steps).all { i -> val f=i.toDouble()/steps; nearest(TrackPoint(a.latitude+(b.latitude-a.latitude)*f,a.longitude+(b.longitude-a.longitude)*f)) != null }
        }
        val metrics = TrackAnalyzer.analyze(elevated)
        if (!covered) return Estimate(elevated,metrics.distanceM,null,null,null)
        val samples = mutableListOf<Sample>()
        var anchor = 0; var distance = 0.0; var seconds = 0.0
        for(i in 1 until recorded.size) {
            val a = recorded[i-1]; val b = recorded[i]
            val dt = if(a.timeEpochMs != null && b.timeEpochMs != null) (b.timeEpochMs-a.timeEpochMs)/1000.0 else 0.0
            val d = Geo.distanceM(a,b)
            if (a.inferred || b.inferred || TrackGeometry.disconnected(a,b) || dt <= 0 || dt > 30 || d/dt !in .3..3.0 || a.elevationM == null || b.elevationM == null) { anchor=i; distance=0.0; seconds=0.0; continue }
            distance += d; seconds += dt
            if(distance >= 100) {
                val ele = recorded[anchor].elevationM
                if(ele != null) samples.add(Sample(distance,(b.elevationM-ele)/distance*100,seconds/distance))
                anchor=i; distance=0.0; seconds=0.0
            }
        }
        var eta = 0.0; var eligible = true
        var start = 0; var length = 0.0
        for (i in 1 until elevated.size) {
            length += Geo.distanceM(elevated[i-1],elevated[i])
            if(length >= 100 || i == elevated.lastIndex) {
                val grade = (elevated[i].elevationM!!-elevated[start].elevationM!!)/length.coerceAtLeast(1.0)*100
                val similar = samples.filter { abs(it.grade-grade) <= 4 && (abs(grade) < 2 || it.grade*grade > 0) }
                if(similar.size < 3 || similar.sumOf { it.distance } < 300) eligible = false
                else { val paces = similar.map { it.secondsPerM }.sorted(); eta += length*paces[paces.size/2] }
                start=i; length=0.0
            }
        }
        return Estimate(elevated,metrics.distanceM,metrics.elevationGainM,metrics.elevationLossM,eta.roundToLong().takeIf { eligible })
    }
    fun summary(e: Estimate): String = "Добавлено ≈ %.2f км · %s · %s".format(e.distanceM/1000, e.gainM?.let { "+${it.roundToInt()} м" } ?: "набор неизвестен", e.seconds?.let { "≈${(it/60.0).roundToInt()} мин" } ?: "время неизвестно")
}
