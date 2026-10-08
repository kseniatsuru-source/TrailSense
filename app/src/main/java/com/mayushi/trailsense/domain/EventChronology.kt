package com.mayushi.trailsense.domain

import com.mayushi.trailsense.data.ActivityEntity
import com.mayushi.trailsense.data.RouteEntity

/** Chronology comes exclusively from timestamps embedded in watch files. */
object EventChronology {
    fun latestActivityByRoute(activities: List<ActivityEntity>): Map<Long, ActivityEntity> = activities
        .asSequence()
        .filter { it.routeId != null }
        .groupBy { it.routeId!! }
        .mapValues { (_, hikes) ->
            hikes.maxWithOrNull(
                compareBy<ActivityEntity> { it.startedAt ?: Long.MIN_VALUE }
                    .thenBy { it.id }
            )!!
        }

    fun watchDateForRoute(routeId: Long, activities: List<ActivityEntity>): Long? =
        latestActivityByRoute(activities)[routeId]?.startedAt

    fun sortRoutes(routes: List<RouteEntity>, activities: List<ActivityEntity>): List<RouteEntity> {
        val latest = latestActivityByRoute(activities)
        return routes.sortedWith { left, right ->
            val leftDate = latest[left.id]?.startedAt
            val rightDate = latest[right.id]?.startedAt
            when {
                leftDate != null && rightDate != null -> compareValues(rightDate, leftDate)
                    .takeIf { it != 0 } ?: compareValues(left.title.lowercase(), right.title.lowercase())
                leftDate != null -> -1
                rightDate != null -> 1
                else -> compareValues(left.title.lowercase(), right.title.lowercase())
                    .takeIf { it != 0 } ?: compareValues(left.id, right.id)
            }
        }
    }
}
