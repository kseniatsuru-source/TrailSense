package com.mayushi.trailsense.domain

import java.util.PriorityQueue
import org.json.JSONObject

/** OSM topology, not raster tiles. No invented edges or private/restricted ways. */
class TrailGraph(val nodes: Map<Long, TrackPoint>, val edges: Map<Long, List<Long>>) {
    fun route(start: TrackPoint, end: TrackPoint, snapM: Double = 75.0, corridor: List<TrackPoint>? = null): List<TrackPoint> {
        val routeNodes = nodes.toMutableMap()
        val routeEdges = edges.mapValues { it.value.toMutableSet() }.toMutableMap()
        var virtual = Long.MIN_VALUE
        fun snap(p: TrackPoint): Long {
            val nearNode = routeEdges.keys.filter { routeEdges[it].orEmpty().isNotEmpty() }
                .minByOrNull { Geo.distanceM(p,routeNodes.getValue(it)) }
            if(nearNode != null && Geo.distanceM(p,routeNodes.getValue(nearNode)) < .01) return nearNode
            val edge = routeEdges.flatMap { (a,targets) -> targets.map { b ->
                Triple(a,b,PolylineMatch.project(p,routeNodes.getValue(a),routeNodes.getValue(b)))
            } }.minByOrNull { it.third.distanceM }
                ?: throw IllegalArgumentException("В этой области OSM не вернул доступных троп. Выбери другую область или повтори позже.")
            require(edge.third.distanceM <= snapM) { "Выбранная точка дальше ${snapM.toInt()} м от тропы OSM. Передвинь маркер ближе к тропе." }
            val (a,b,projection)=edge
            if(projection.fraction < .000001) return a
            if(projection.fraction > .999999) return b
            while(virtual in routeNodes) virtual++
            val id=virtual++
            routeNodes[id]=projection.point
            routeEdges.getValue(a).remove(b)
            routeEdges.getValue(a).add(id)
            routeEdges[id]=mutableSetOf(b)
            if(routeEdges[b]?.remove(a) == true) {
                routeEdges.getValue(b).add(id);routeEdges.getValue(id).add(a)
            }
            return id
        }
        val source = snap(start); val target = snap(end)
        val sampledCorridor = corridor?.let { PolylineMatch.sample(it) }
        val distance = mutableMapOf(source to 0.0); val previous = mutableMapOf<Long,Long>()
        val queue = PriorityQueue<Pair<Long,Double>>(compareBy<Pair<Long,Double>> { it.second }.thenBy { it.first })
        queue.add(source to 0.0)
        while (queue.isNotEmpty()) {
            val (id,d) = queue.remove()
            if (d != distance[id]) continue
            if (id == target) break
            routeEdges[id].orEmpty().sorted().forEach { next ->
                val a=routeNodes.getValue(id);val b=routeNodes.getValue(next)
                val midpoint=TrackPoint((a.latitude+b.latitude)/2,(a.longitude+b.longitude)/2)
                val offset=sampledCorridor?.let { PolylineMatch.distance(midpoint,it) } ?: 0.0
                // Prefer the observed return branch, not an unrelated shorter OSM shortcut.
                val candidate = d + Geo.distanceM(a,b)*(1+offset*offset/225.0)
                if (candidate < (distance[next] ?: Double.POSITIVE_INFINITY)) { distance[next] = candidate; previous[next] = id; queue.add(next to candidate) }
            }
        }
        require(target in distance) { "Тропы OSM в выбранной области не соединены. Передвинь старт на ту же сеть или выбери другую область. Прямую линию приложение не добавляет." }
        val path = mutableListOf(target); var current = target
        while (current != source) { current = previous.getValue(current); path.add(current) }
        require(path.size >= 2) { "Старт совпал с началом записи. Выбери пропущенный старт дальше по тропе." }
        return path.asReversed().map { routeNodes.getValue(it).copy(inferred = true, timeEpochMs = null, heartRateBpm = null, watchDistanceM = null) }
    }
    companion object {
        fun parse(json: String): TrailGraph {
            val elements = JSONObject(json).getJSONArray("elements")
            require(elements.length() <= 100_000) { "Область слишком большая. Выбери старт ближе к началу записи." }
            val nodes = mutableMapOf<Long,TrackPoint>(); val ways = mutableListOf<Pair<List<Long>,Int>>()
            for (i in 0 until elements.length()) {
                val e = elements.getJSONObject(i)
                when(e.optString("type")) {
                    "node" -> nodes[e.getLong("id")] = TrackPoint(e.getDouble("lat"),e.getDouble("lon"))
                    "way" -> {
                        val tags = e.optJSONObject("tags") ?: continue
                        if (tags.optString("highway") !in setOf("path","footway","track","steps","bridleway")) continue
                        if (listOf("access","foot").any { tags.optString(it) in setOf("no","private","restricted","customers","permit") }) continue
                        if (tags.optString("highway") == "construction" || tags.optString("proposed").isNotBlank()) continue
                        val direction=when(tags.optString("oneway:foot")) { "yes","1","true" -> 1; "-1" -> -1; else -> 0 }
                        val ids = e.getJSONArray("nodes"); ways.add(List(ids.length()) { ids.getLong(it) } to direction)
                    }
                }
            }
            val edges = mutableMapOf<Long,MutableSet<Long>>()
            ways.forEach { (way,direction) -> way.zipWithNext().forEach { (a,b) -> if (a in nodes && b in nodes) {
                edges.getOrPut(a) { sortedSetOf() }; edges.getOrPut(b) { sortedSetOf() }
                if(direction >= 0) edges.getValue(a).add(b)
                if(direction <= 0) edges.getValue(b).add(a)
            } } }
            return TrailGraph(nodes,edges.mapValues { it.value.toList() })
        }
    }
}
