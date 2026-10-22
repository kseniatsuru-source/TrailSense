package com.mayushi.trailsense.domain

import com.mayushi.trailsense.data.ActivityEntity
import com.mayushi.trailsense.data.RouteEntity
import com.mayushi.trailsense.network.AllTrailsMcpClient
import java.net.URI

/** Pure suggestions. Does not attach routes, change watch metrics, or make network requests. */
object ConfirmedRoutePrioritizer {
    data class RankedCandidate(
        val candidate: AllTrailsMcpClient.Candidate,
        val sourceLabel: String,
        val priorConfirmed: Boolean,
        val similarity: Double?,
        val routeId: Long?,
    )

    fun rank(
        activity: ActivityEntity,
        routes: List<RouteEntity>,
        activities: List<ActivityEntity>,
        candidates: List<AllTrailsMcpClient.Candidate>,
    ): List<RankedCandidate> {
        val current = actualPoints(activity)
        val routesById = routes.associateBy { it.id }
        val priors = if (current == null) emptyList() else activities.mapNotNull { previous ->
            if (previous.id == activity.id) return@mapNotNull null
            // suggestedRouteId is deliberately not consulted: a machine suggestion is not consent.
            val route = previous.routeId?.let(routesById::get) ?: return@mapNotNull null
            if (route.title.isBlank() || !route.sourceLabel.contains("AllTrails", ignoreCase = true)) return@mapNotNull null
            val url = sequenceOf(route.sourceUrl, route.canonicalUrl).filterNotNull().firstOrNull { AllTrailsMcpClient.validRouteUrl(it) }
                ?: return@mapNotNull null
            val score = similarity(current, actualPoints(previous) ?: return@mapNotNull null) ?: return@mapNotNull null
            RankedCandidate(
                AllTrailsMcpClient.Candidate(0, route.title, url, route.distanceM, route.elevationGainM, "", ""),
                route.sourceLabel, true, score, route.id,
            )
        }.sortedByDescending { it.similarity }.distinctBy { key(it.candidate.url) }
        val priorsByUrl = priors.associateBy { key(it.candidate.url) }
        val fresh = candidates.filter { it.name.isNotBlank() && AllTrailsMcpClient.validRouteUrl(it.url) }
            .distinctBy { key(it.url) }.map { candidate ->
                val prior = priorsByUrl[key(candidate.url)]
                if (prior == null) RankedCandidate(candidate, "AllTrails MCP", false, null, null)
                else prior.copy(candidate = candidate) // Preserve fresh MCP id, description, lengths and gains.
            }
        val freshKeys = fresh.map { key(it.candidate.url) }.toSet()
        // Strong confirmed cache entries omitted by MCP still appear, with their real saved name/URL/source.
        return (fresh + priors.filter { key(it.candidate.url) !in freshKeys })
            .sortedWith(compareByDescending<RankedCandidate> { it.priorConfirmed }.thenByDescending { it.similarity ?: -1.0 })
    }

    /** Shape in BOTH directions, length AND endpoints; shared region/termini alone cannot qualify. */
    fun similarity(a: List<TrackPoint>, b: List<TrackPoint>): Double? {
        if (!valid(a) || !valid(b)) return null
        val forward = RouteMatcher.match(a, b)
        val backward = RouteMatcher.match(b, a)
        val endpoints = if (forward.directionReversed) listOf(Geo.distanceM(a.first(), b.last()), Geo.distanceM(a.last(), b.first()))
            else listOf(Geo.distanceM(a.first(), b.first()), Geo.distanceM(a.last(), b.last()))
        val score = minOf(forward.confidence, backward.confidence)
        return score.takeIf {
            it >= 0.92 && forward.averageNearestDistanceM <= 35.0 && backward.averageNearestDistanceM <= 35.0 &&
                forward.lengthDifferenceRatio <= 0.15 && backward.lengthDifferenceRatio <= 0.15 && endpoints.all { distance -> distance <= 100.0 }
        }
    }

    private fun actualPoints(activity: ActivityEntity): List<TrackPoint>? {
        if (activity.trackCorrectionMode != "NONE") return null
        return runCatching { TrackCodec.decode(activity.trackEncoded) }.getOrNull()?.takeIf(::valid)
    }

    private fun valid(points: List<TrackPoint>): Boolean = points.size >= 5 && points.all {
        !it.inferred && it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
    } && points.drop(1).none { it.segmentStart } && points.zipWithNext().sumOf { Geo.distanceM(it.first, it.second) } >= 150.0

    private fun key(url: String): String = URI(url).path.replace(Regex("^/[a-z]{2}(?:-[a-z]{2})?/trail/"), "/trail/")
}
