package com.mayushi.trailsense.network

import com.mayushi.trailsense.data.ActivityEntity
import com.mayushi.trailsense.data.RouteEntity

object AiResultMerger {
    fun mergeRoute(route: RouteEntity, decoded: ParsedAiResponse, updatedAt: Long = System.currentTimeMillis()): RouteEntity {
        val facts = decoded.routeFacts ?: return route
        return route.copy(
            title = route.title, // User-entered event title is not an AI write target.
            distanceM = facts.distanceM ?: route.distanceM,
            elevationGainM = facts.gainM ?: route.elevationGainM,
            elevationLossM = facts.lossM ?: route.elevationLossM,
            sourceDifficulty = facts.difficulty ?: route.sourceDifficulty,
            sourceRating = facts.rating ?: route.sourceRating,
            updatedAt = updatedAt,
        )
    }

    fun mergeActivity(activity: ActivityEntity, decoded: ParsedAiResponse, updatedAt: Long = System.currentTimeMillis()): ActivityEntity {
        // Imported measurements and subjective context are never AI write targets.
        if (activity.sourceFormat != "HUAWEI_SCREENSHOT") return activity
        val facts = decoded.activityFacts ?: return activity
        return activity.copy(
            title = facts.title?.takeIf { it.isNotBlank() } ?: activity.title,
            distanceM = facts.distanceM ?: activity.distanceM,
            elevationGainM = facts.gainM ?: activity.elevationGainM,
            elevationLossM = facts.lossM ?: activity.elevationLossM,
            elapsedSeconds = facts.elapsedSeconds ?: activity.elapsedSeconds,
            movingSeconds = facts.movingSeconds ?: activity.movingSeconds,
            stoppedSeconds = facts.stoppedSeconds ?: activity.stoppedSeconds,
            averageHeartRateBpm = facts.averageHeartRateBpm ?: activity.averageHeartRateBpm,
            maxHeartRateBpm = facts.maxHeartRateBpm ?: activity.maxHeartRateBpm,
            updatedAt = updatedAt,
        )
    }
}
