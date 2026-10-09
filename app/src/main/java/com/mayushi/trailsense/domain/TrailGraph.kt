package com.mayushi.trailsense.domain

import java.util.PriorityQueue
import org.json.JSONObject

/** OSM topology, not raster tiles. No invented edges or private/restricted ways. */
class TrailGraph(val nodes: Map<Long, TrackPoint>, val edges: Map<Long, List<Long>>) {
    fun route(start: TrackPoint, end: TrackPoint, snapM: Double = 75.0): List<TrackPoint> {
        fun snap(p: TrackPoint): Long {
            val id = edges.keys.minByOrNull { Geo.distanceM(p, nodes.getValue(it)) }
                ?: throw IllegalArgumentException("В этой области OSM не вернул доступных троп. Выбери другую область или повтори позже.")
            require(Geo.distanceM(p,nodes.getValue(id)) <= snapM) { "Выбранная точка дальше 75 м от тропы OSM. Передвинь маркер ближе к тропе." }
            return id
        }
        val source = snap(start); val target = snap(end)
        val distance = mutableMapOf(source to 0.0); val previous = mutableMapOf<Long,Long>()
        val queue = PriorityQueue<Pair<Long,Double>>(compareBy<Pair<Long,Double>> { it.second }.thenBy { it.first })
        queue.add(source to 0.0)
        while (queue.isNotEmpty()) {
            val (id,d) = queue.remove()
            if (d != distance[id]) continue
            if (id == target) break
            edges[id].orEmpty().sorted().forEach { next ->
                val candidate = d + Geo.distanceM(nodes.getValue(id),nodes.getValue(next))
                if (candidate < (distance[next] ?: Double.POSITIVE_INFINITY)) { distance[next] = candidate; previous[next] = id; queue.add(next to candidate) }
            }
        }
        require(target in distance) { "Тропы OSM в выбранной области не соединены. Передвинь старт на ту же сеть или выбери другую область. Прямую линию приложение не добавляет." }
        val path = mutableListOf(target); var current = target
        while (current != source) { current = previous.getValue(current); path.add(current) }
        require(path.size >= 2) { "Старт совпал с началом записи. Выбери пропущенный старт дальше по тропе." }
        return path.asReversed().map { nodes.getValue(it).copy(inferred = true) }
    }
    companion object {
        fun parse(json: String): TrailGraph {
            val elements = JSONObject(json).getJSONArray("elements")
            require(elements.length() <= 100_000) { "Область слишком большая. Выбери старт ближе к началу записи." }
            val nodes = mutableMapOf<Long,TrackPoint>(); val ways = mutableListOf<List<Long>>()
            for (i in 0 until elements.length()) {
                val e = elements.getJSONObject(i)
                when(e.optString("type")) {
                    "node" -> nodes[e.getLong("id")] = TrackPoint(e.getDouble("lat"),e.getDouble("lon"))
                    "way" -> {
                        val tags = e.optJSONObject("tags") ?: continue
                        if (tags.optString("highway") !in setOf("path","footway","track","steps","bridleway")) continue
                        if (listOf("access","foot").any { tags.optString(it) in setOf("no","private","restricted","customers","permit") }) continue
                        if (tags.optString("highway") == "construction" || tags.optString("proposed").isNotBlank()) continue
                        val ids = e.getJSONArray("nodes"); ways.add(List(ids.length()) { ids.getLong(it) })
                    }
                }
            }
            val edges = mutableMapOf<Long,MutableSet<Long>>()
            ways.forEach { way -> way.zipWithNext().forEach { (a,b) -> if (a in nodes && b in nodes) { edges.getOrPut(a) { sortedSetOf() }.add(b); edges.getOrPut(b) { sortedSetOf() }.add(a) } } }
            return TrailGraph(nodes,edges.mapValues { it.value.toList() })
        }
    }
}
