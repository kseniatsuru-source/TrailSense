package com.mayushi.trailsense.domain

/** Evidence about the recorded profile, never a replacement for original watch metrics. */
object DescentEvidence {
    enum class Profile { PROVEN_NO_DESCENT, UNKNOWN }

    // Reuse TrackAnalyzer's existing ELEVATION_NOISE_M policy (3 m), not a user-selected
    // meaningful-descent threshold. More conservative than its anchor accumulator: measure
    // every drawdown from the running peak, so sampling density cannot hide a downhill leg.
    private const val ELEVATION_NOISE_M = 3.0
    private const val LEGACY_POLICY = "q041|descent-policy|conservative-no-decrease-v1"
    private const val POLICY = "q041|descent-policy|recovered-sub-noise-drawdown-v2"

    fun profile(points: List<TrackPoint>): Profile {
        if (!reliable(points)) return Profile.UNKNOWN
        var peak = points.first().elevationM!!
        var falling = false
        for (i in 1 until points.size) {
            val height = points[i].elevationM!!
            if (peak - height >= ELEVATION_NOISE_M) return Profile.UNKNOWN
            // A sustained decline below the noise floor is ambiguous, not proof of jitter.
            // Repeated equal samples do not interrupt a declining run.
            val delta = height - points[i - 1].elevationM!!
            if (delta < 0) {
                if (falling) return Profile.UNKNOWN
                falling = true
            } else if (delta > 0) falling = false
            peak = maxOf(peak, height)
        }
        // Only recovered, isolated sub-noise dips can be ignored. A terminal decline may
        // be a genuine small downhill; don't infer its continuation from net uphill gain.
        return if (points.last().elevationM!! >= peak) Profile.PROVEN_NO_DESCENT else Profile.UNKNOWN
    }

    private fun reliable(points: List<TrackPoint>): Boolean {
        if (points.size < 2 || points.any { it.inferred || it.elevationM?.isFinite() != true }) return false
        if (points.any { !it.latitude.isFinite() || !it.longitude.isFinite() || it.latitude !in -90.0..90.0 || it.longitude !in -180.0..180.0 }) return false
        if (points.none { it.latitude != points.first().latitude || it.longitude != points.first().longitude }) return false
        return (1 until points.size).none { i ->
            val a = points[i - 1]; val b = points[i]
            TrackGeometry.disconnected(a, b) ||
                (a.timeEpochMs != null && b.timeEpochMs != null && b.timeEpochMs < a.timeEpochMs)
        }
    }

    fun blankMeaning(completedAt: Long?, history: String, points: List<TrackPoint>): String {
        // No retrospective reinterpretation: v1 retains its strict rule until an explicit
        // questionnaire save adds v2; old and never-saved blanks still mean no data.
        val policies = history.lineSequence().toSet()
        if (completedAt == null || (POLICY !in policies && LEGACY_POLICY !in policies)) return "NO_DATA"
        val absent = if (POLICY in policies) profile(points) == Profile.PROVEN_NO_DESCENT
            else reliable(points) && points.zipWithNext().all { (a, b) -> b.elevationM!! >= a.elevationM!! }
        return if (absent) "RECORDED_PROFILE_NO_DESCENT" else "UNRATED_OR_UNKNOWN"
    }

    fun savedHistory(history: String): String = if (history.lineSequence().any { it == POLICY }) history
        else listOf(history, POLICY).filter { it.isNotBlank() }.joinToString("\n")
}
