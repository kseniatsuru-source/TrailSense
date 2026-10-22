package com.mayushi.trailsense.domain

import com.mayushi.trailsense.data.ActivityEntity
import com.mayushi.trailsense.data.RouteEntity
import kotlin.math.abs

/** Bounded ordered geometry, complete-link groups, recorded timestamps only. Run on Default. */
object AutomaticRepeatRoutes {
    data class Section(val activity: ActivityEntity, val points: List<TrackPoint>, val start: Int, val end: Int, val ascent: Boolean, val signature:List<TrackPoint>)
    data class Row(val activity: ActivityEntity, val seconds: Long, val start: Int, val end: Int)
    data class Group(val id: String, val title: String, val rows: List<Row>, val ascent: Boolean)
    fun sample(points: List<TrackPoint>, count: Int = 96): List<TrackPoint> {
        if(points.size < 2) return points
        val axis = DoubleArray(points.size)
        for(i in 1 until points.size) axis[i] = axis[i-1] + Geo.distanceM(points[i-1],points[i])
        if(axis.last() <= 0) return listOf(points.first(),points.last())
        var j = 0
        return (0 until count).map { n ->
            val d = axis.last() * n / (count-1)
            while(j < points.lastIndex && axis[j] < d) j++
            points[j]
        }
    }
    private fun section(a: ActivityEntity, knownAscent: Boolean): Section? {
        if(a.trackCorrectionMode != "NONE") return null
        val p = runCatching { TrackCodec.decode(a.trackEncoded) }.getOrNull() ?: return null
        if(p.size < 10 || p.any { it.inferred || it.timeEpochMs == null } || p.drop(1).any { it.segmentStart } ||
            p.zipWithNext().any { (x,y) -> y.timeEpochMs!! <= x.timeEpochMs!! }) return null
        // Largest recorded rise; flat approaches and following descents are outside an ascent.
        var low = 0; var start = 0; var end = p.lastIndex; var rise = 0.0
        if(p.all { it.elevationM != null }) {
            p.indices.forEach { i ->
                if(p[i].elevationM!! < p[low].elevationM!!) low = i
                val gain = p[i].elevationM!! - p[low].elevationM!!
                if(gain > rise) { rise = gain; start = low; end = i }
            }
        }
        val ascent = rise >= 300 && start < end && (knownAscent || (p.last().elevationM!!-p.first().elevationM!!) >= rise*.65)
        if(!ascent) { start = 0; end = p.lastIndex }
        return Section(a,p,start,end,ascent,sample(p.subList(start,end+1)))
    }
    private fun compatible(a: Section,b: Section): Boolean {
        if(a.ascent != b.ascent) return false
        val pa = a.signature; val pb = b.signature
        if(Geo.distanceM(pa.first(),pb.first()) > 100 || Geo.distanceM(pa.last(),pb.last()) > 100) return false
        val distances = pa.zip(pb).map { (x,y) -> Geo.distanceM(x,y) }
        return distances.count { it <= 55 } >= distances.size * .90 && distances.max() <= 100
    }
    fun discover(activities: List<ActivityEntity>, routes: List<RouteEntity>, excluded: Set<Long> = emptySet()): List<Group> {
        val buckets = mutableMapOf<Pair<Int,Int>,MutableList<MutableList<Section>>>()
        val groups = mutableListOf<MutableList<Section>>()
        val routesById=routes.associateBy {it.id}
        activities.asSequence().filter { it.id !in excluded }.mapNotNull {a -> section(a,routesById[a.routeId]?.sourceUrl.orEmpty().contains("grouse-grind",true)) }.sortedBy { it.activity.id }.forEach { candidate ->
            val first = candidate.points[candidate.start]
            val x = kotlin.math.floor(first.latitude * 500).toInt(); val y = kotlin.math.floor(first.longitude * 500).toInt()
            val near = (-1..1).flatMap { dx -> (-1..1).flatMap { dy -> buckets[x+dx to y+dy].orEmpty() } }.distinct()
            val group = near.firstOrNull { it.all { member -> compatible(member,candidate) } }
            if(group != null) group += candidate else {
                val new = mutableListOf(candidate); groups += new
                buckets.getOrPut(x to y) { mutableListOf() }.add(new)
            }
        }
        return groups.filter { it.size >= 2 }.mapNotNull { members ->
            // Use common recorded boundaries, not watch elapsed/moving totals or generated geometry.
            val reference = members.first()
            val starts = members.map { m -> (reference.start..reference.end).minBy { Geo.distanceM(reference.points[it],m.points[m.start]) } }
            val ends = members.map { m -> (reference.end downTo reference.start).minBy { Geo.distanceM(reference.points[it],m.points[m.end]) } }
            val commonStart = if(reference.ascent) starts.max() else reference.start
            val commonEnd = if(reference.ascent) ends.min() else reference.end
            if(commonStart >= commonEnd) return@mapNotNull null
            val rows = members.mapNotNull { m ->
                val range = m.start..m.end
                val start = if(m.ascent) range.minBy { Geo.distanceM(m.points[it],reference.points[commonStart]) } else m.start
                val end = if(m.ascent) (m.end downTo m.start).minBy { Geo.distanceM(m.points[it],reference.points[commonEnd]) } else m.end
                if(start >= end || Geo.distanceM(m.points[start],reference.points[commonStart]) > 55 || Geo.distanceM(m.points[end],reference.points[commonEnd]) > 55) return@mapNotNull null
                val seconds = (m.points[end].timeEpochMs!! - m.points[start].timeEpochMs!!) / 1000
                if(seconds <= 0) null else Row(m.activity,seconds,start,end)
            }.sortedWith(compareBy<Row> { RepeatRouteDynamics.date(it.activity) }.thenBy { it.activity.startedAt }.thenBy { it.activity.id })
            if(rows.size < 2) return@mapNotNull null
            val titles = rows.map { DisplayTitle.resolve(it.activity,routes) }
            Group(members.minOf { it.activity.id }.toString(),titles.first(),rows,reference.ascent)
        }
    }
}
