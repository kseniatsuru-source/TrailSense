package com.mayushi.trailsense.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface TrailDao {
    @Query("SELECT * FROM track_chunks WHERE trackKey=:key ORDER BY ordinal") suspend fun trackChunks(key: String): List<TrackChunkEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTrackChunks(chunks: List<TrackChunkEntity>)

    suspend fun storeTrack(value: String?): String? {
        if (value == null || value.length <= TrackChunks.SIZE) return value
        require(!value.startsWith(TrackChunks.PREFIX)) { "Expected hydrated track" }
        val chunks = TrackChunks.split(value)
        insertTrackChunks(chunks)
        return TrackChunks.PREFIX + chunks.first().trackKey
    }
    suspend fun loadTrack(value: String?): String? = if (value?.startsWith(TrackChunks.PREFIX) == true)
        TrackChunks.join(value, trackChunks(value.removePrefix(TrackChunks.PREFIX))) else value
    suspend fun hydrateRoute(row: RouteEntity): RouteEntity = row.copy(trackEncoded = loadTrack(row.trackEncoded))
    suspend fun hydrateActivity(row: ActivityEntity): ActivityEntity = row.copy(
        trackEncoded = requireNotNull(loadTrack(row.trackEncoded)), originalTrackEncoded = loadTrack(row.originalTrackEncoded))

    @Query("SELECT * FROM routes ORDER BY updatedAt DESC") fun observeStoredRoutes(): Flow<List<RouteEntity>>
    fun observeRoutes(): Flow<List<RouteEntity>> = observeStoredRoutes().map { rows -> rows.map { hydrateRoute(it) } }
    @Query("SELECT * FROM routes WHERE id=:id") fun observeStoredRoute(id: Long): Flow<RouteEntity?>
    fun observeRoute(id: Long): Flow<RouteEntity?> = observeStoredRoute(id).map { it?.let { row -> hydrateRoute(row) } }
    @Query("SELECT * FROM routes WHERE id=:id") suspend fun storedRoute(id: Long): RouteEntity?
    suspend fun route(id: Long): RouteEntity? = storedRoute(id)?.let { hydrateRoute(it) }
    @Query("SELECT * FROM routes WHERE canonicalUrl=:url LIMIT 1") suspend fun storedRouteByUrl(url: String): RouteEntity?
    suspend fun routeByUrl(url: String): RouteEntity? = storedRouteByUrl(url)?.let { hydrateRoute(it) }
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertStoredRoute(route: RouteEntity): Long
    @Transaction suspend fun insertRoute(route: RouteEntity): Long = insertStoredRoute(route.copy(trackEncoded = storeTrack(route.trackEncoded)))
    @Update suspend fun updateStoredRoute(route: RouteEntity)
    @Transaction suspend fun updateRoute(route: RouteEntity) { updateStoredRoute(route.copy(trackEncoded = storeTrack(route.trackEncoded))) }
    @Delete suspend fun deleteRoute(route: RouteEntity)
    @Query("DELETE FROM ai_cache WHERE entityType='ROUTE' AND entityId=:routeId")
    suspend fun invalidateAiForRoute(routeId: Long)
    @Query("DELETE FROM ai_cache WHERE (entityType='ACTIVITY' AND entityId=:activityId) OR (entityType='ROUTE' AND entityId IN (SELECT routeId FROM activities WHERE id=:activityId AND routeId IS NOT NULL))")
    suspend fun invalidateAiForActivity(activityId: Long)
    @Query("DELETE FROM ai_cache") suspend fun invalidateAllAiCache()

    @Transaction
    suspend fun updateRouteAndInvalidate(route: RouteEntity) {
        updateRoute(route)
        invalidateAiForRoute(route.id)
    }
    @Query("DELETE FROM ai_cache WHERE (entityType='ROUTE' AND entityId=:routeId) OR (entityType='ACTIVITY' AND entityId IN (SELECT id FROM activities WHERE routeId=:routeId))")
    suspend fun deleteAiCacheForRouteEvent(routeId: Long)
    @Query("DELETE FROM ai_jobs WHERE (entityType='ROUTE' AND entityId=:routeId) OR (entityType='ACTIVITY' AND entityId IN (SELECT id FROM activities WHERE routeId=:routeId))")
    suspend fun deleteAiJobsForRouteEvent(routeId: Long)
    @Query("DELETE FROM activity_companions WHERE activityId IN (SELECT id FROM activities WHERE routeId=:routeId)")
    suspend fun deleteCompanionLinksForRouteEvent(routeId: Long)
    @Query("DELETE FROM routes WHERE id=:routeId") suspend fun deleteRouteById(routeId: Long)

    @Transaction
    suspend fun deleteRouteEvent(routeId: Long) {
        deleteAiCacheForRouteEvent(routeId)
        deleteAiJobsForRouteEvent(routeId)
        deleteCompanionLinksForRouteEvent(routeId)
        deleteActivitiesForRoute(routeId)
        deleteRouteById(routeId)
    }

    @Query("SELECT * FROM activities ORDER BY CASE WHEN startedAt IS NULL THEN 1 ELSE 0 END, startedAt DESC, id DESC") fun observeStoredActivities(): Flow<List<ActivityEntity>>
    fun observeActivities(): Flow<List<ActivityEntity>> = observeStoredActivities().map { rows -> rows.map { hydrateActivity(it) } }
    @Query("SELECT * FROM activities WHERE routeId=:routeId ORDER BY CASE WHEN startedAt IS NULL THEN 1 ELSE 0 END, startedAt DESC, id DESC") fun observeStoredActivitiesForRoute(routeId: Long): Flow<List<ActivityEntity>>
    fun observeActivitiesForRoute(routeId: Long): Flow<List<ActivityEntity>> = observeStoredActivitiesForRoute(routeId).map { rows -> rows.map { hydrateActivity(it) } }
    @Query("SELECT * FROM activities WHERE routeId=:routeId") suspend fun storedActivitiesForRoute(routeId: Long): List<ActivityEntity>
    suspend fun activitiesForRoute(routeId: Long): List<ActivityEntity> = storedActivitiesForRoute(routeId).map { hydrateActivity(it) }
    @Query("SELECT * FROM activities WHERE routeId=:routeId ORDER BY CASE WHEN startedAt IS NULL THEN 1 ELSE 0 END, startedAt DESC, id DESC LIMIT 1") suspend fun storedLatestActivityForRoute(routeId: Long): ActivityEntity?
    suspend fun latestActivityForRoute(routeId: Long): ActivityEntity? = storedLatestActivityForRoute(routeId)?.let { hydrateActivity(it) }
    @Query("SELECT * FROM activities WHERE id=:id") fun observeStoredActivity(id: Long): Flow<ActivityEntity?>
    fun observeActivity(id: Long): Flow<ActivityEntity?> = observeStoredActivity(id).map { it?.let { row -> hydrateActivity(row) } }
    @Query("SELECT * FROM activities WHERE id=:id") suspend fun storedActivity(id: Long): ActivityEntity?
    suspend fun activity(id: Long): ActivityEntity? = storedActivity(id)?.let { hydrateActivity(it) }
    @Insert suspend fun insertStoredActivity(activity: ActivityEntity): Long
    @Transaction suspend fun insertActivity(activity: ActivityEntity): Long = insertStoredActivity(activity.copy(
        trackEncoded = requireNotNull(storeTrack(activity.trackEncoded)), originalTrackEncoded = storeTrack(activity.originalTrackEncoded)))
    @Update suspend fun updateStoredActivity(activity: ActivityEntity)
    @Transaction suspend fun updateActivity(activity: ActivityEntity) { updateStoredActivity(activity.copy(
        trackEncoded = requireNotNull(storeTrack(activity.trackEncoded)), originalTrackEncoded = storeTrack(activity.originalTrackEncoded))) }
    @Delete suspend fun deleteActivity(activity: ActivityEntity)
    @Query("DELETE FROM activities WHERE routeId=:routeId") suspend fun deleteActivitiesForRoute(routeId: Long)

    @Transaction
    suspend fun updateCorrectedTrack(activity: ActivityEntity, route: RouteEntity) {
        updateActivity(activity)
        updateRoute(route)
        invalidateAiForActivity(activity.id)
    }

    @Transaction
    suspend fun updateActivityAndInvalidate(activity: ActivityEntity) {
        updateActivity(activity)
        invalidateAiForActivity(activity.id)
    }

    @Query("SELECT * FROM companions ORDER BY name COLLATE NOCASE") fun observeCompanions(): Flow<List<CompanionEntity>>
    @Insert suspend fun insertCompanion(companion: CompanionEntity): Long
    @Update suspend fun updateCompanion(companion: CompanionEntity)
    @Delete suspend fun deleteCompanion(companion: CompanionEntity)
    @Query("SELECT companionId FROM activity_companions WHERE activityId=:activityId") fun observeCompanionIds(activityId: Long): Flow<List<Long>>
    @Query("SELECT companions.name FROM companions INNER JOIN activity_companions ON companions.id=activity_companions.companionId WHERE activity_companions.activityId=:activityId ORDER BY companions.name COLLATE NOCASE")
    suspend fun companionNamesForActivity(activityId: Long): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun linkCompanion(link: ActivityCompanionCrossRef)
    @Query("DELETE FROM activity_companions WHERE activityId=:activityId") suspend fun clearActivityCompanions(activityId: Long)

    @Transaction
    suspend fun updateActivityWithCompanions(activity: ActivityEntity, companionIds: Set<Long>) {
        updateActivity(activity)
        clearActivityCompanions(activity.id)
        companionIds.forEach { linkCompanion(ActivityCompanionCrossRef(activity.id, it)) }
        invalidateAiForActivity(activity.id)
    }

    @Query("SELECT * FROM pace_segments WHERE activityId=:activityId ORDER BY startFraction, endFraction")
    fun observePaceSegments(activityId: Long): Flow<List<PaceSegmentEntity>>
    @Query("SELECT * FROM pace_segments WHERE activityId=:activityId ORDER BY startFraction, endFraction")
    suspend fun paceSegments(activityId: Long): List<PaceSegmentEntity>
    @Query("DELETE FROM pace_segments WHERE activityId=:activityId") suspend fun clearPaceSegments(activityId: Long)
    @Insert suspend fun insertPaceSegments(segments: List<PaceSegmentEntity>)
    @Query("UPDATE activities SET updatedAt=:updatedAt WHERE id=:activityId") suspend fun touchActivity(activityId: Long, updatedAt: Long)

    @Transaction
    suspend fun replacePaceSegments(activityId: Long, segments: List<PaceSegmentEntity>, updatedAt: Long) {
        clearPaceSegments(activityId)
        if (segments.isNotEmpty()) insertPaceSegments(segments)
        touchActivity(activityId, updatedAt)
        invalidateAiForActivity(activityId)
    }

    @Query("SELECT * FROM calendar_context ORDER BY startEpochDay DESC, createdAt DESC")
    fun observeCalendarContexts(): Flow<List<CalendarContextEntity>>
    @Query("SELECT * FROM calendar_context WHERE startEpochDay <= :epochDay AND endEpochDay >= :epochDay ORDER BY startEpochDay, createdAt")
    suspend fun calendarContextsForDay(epochDay: Long): List<CalendarContextEntity>
    @Query("SELECT MAX(updatedAt) FROM calendar_context WHERE startEpochDay <= :epochDay AND endEpochDay >= :epochDay")
    suspend fun calendarContextRevisionForDay(epochDay: Long): Long?
    @Insert suspend fun insertCalendarContext(context: CalendarContextEntity): Long
    @Delete suspend fun deleteCalendarContext(context: CalendarContextEntity)
    @Query("UPDATE calendar_context SET updatedAt=:updatedAt WHERE startEpochDay <= :endEpochDay AND endEpochDay >= :startEpochDay")
    suspend fun touchCalendarContextsInRange(startEpochDay: Long, endEpochDay: Long, updatedAt: Long)

    @Query("SELECT * FROM pill_plans WHERE id=1") fun observePillPlan(): Flow<PillPlanEntity?>
    @Query("SELECT * FROM pill_plans WHERE id=1") suspend fun pillPlan(): PillPlanEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun savePillPlan(plan: PillPlanEntity)
    @Query("SELECT * FROM pill_events ORDER BY epochDay DESC, createdAt DESC") fun observePillEvents(): Flow<List<PillEventEntity>>
    @Query("SELECT * FROM pill_events ORDER BY epochDay DESC, createdAt DESC") suspend fun pillEvents(): List<PillEventEntity>
    @Insert suspend fun insertPillEvent(event: PillEventEntity): Long

    @Query("SELECT * FROM ai_cache WHERE cacheKey=:key LIMIT 1") suspend fun aiCache(key: String): AiCacheEntity?
    @Query("SELECT * FROM ai_cache WHERE entityType=:type AND entityId=:entityId AND analysisKind=:kind ORDER BY createdAt DESC LIMIT 1") fun observeLatestAi(type: String, entityId: Long, kind: String): Flow<AiCacheEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAiCache(cache: AiCacheEntity): Long
    @Query("SELECT COALESCE(SUM(promptTokens + completionTokens), 0) FROM ai_cache WHERE createdAt >= :since") suspend fun aiTokensSince(since: Long): Int
    @Insert suspend fun insertAiJob(job: AiJobEntity): Long
    @Query("SELECT * FROM ai_jobs WHERE entityType=:type AND entityId=:entityId AND analysisKind=:kind AND inputHash=:hash AND status='PENDING' LIMIT 1") suspend fun pendingAiJob(type: String, entityId: Long, kind: String, hash: String): AiJobEntity?
    @Query("UPDATE ai_jobs SET status='SUPERSEDED', lastError='Заменено новой версией задания', updatedAt=:updatedAt WHERE entityType=:type AND entityId=:entityId AND analysisKind=:kind AND inputHash != :hash AND status='PENDING'")
    suspend fun supersedePendingAiJobs(type: String, entityId: Long, kind: String, hash: String, updatedAt: Long): Int

    @Transaction
    suspend fun enqueuePendingAiJob(job: AiJobEntity): Long {
        if (pendingAiJob(job.entityType, job.entityId, job.analysisKind, job.inputHash) != null) return -1
        supersedePendingAiJobs(job.entityType, job.entityId, job.analysisKind, job.inputHash, System.currentTimeMillis())
        return insertAiJob(job)
    }

    @Query("SELECT * FROM ai_jobs WHERE id=:id") suspend fun aiJob(id: Long): AiJobEntity?
    @Query("SELECT * FROM ai_jobs WHERE status='PENDING' ORDER BY createdAt LIMIT 1") suspend fun nextAiJob(): AiJobEntity?
    @Query("SELECT * FROM ai_jobs WHERE status='PENDING' ORDER BY createdAt") suspend fun pendingAiJobs(): List<AiJobEntity>
    @Query("UPDATE ai_jobs SET status='SUPERSEDED', lastError=:reason, updatedAt=:updatedAt WHERE id=:id AND inputHash=:hash AND status='PENDING'")
    suspend fun supersedePendingAiJob(id: Long, hash: String, reason: String, updatedAt: Long): Int
    @Query("UPDATE ai_jobs SET status=:claimedStatus, updatedAt=:updatedAt WHERE id=:id AND inputHash=:hash AND status='PENDING'")
    suspend fun claimPendingAiJob(id: Long, hash: String, claimedStatus: String, updatedAt: Long): Int
    @Query("UPDATE ai_jobs SET status='DONE', attempts=attempts+1, lastError=NULL, updatedAt=:updatedAt WHERE id=:id AND inputHash=:hash AND status=:claimedStatus")
    suspend fun finishClaimedAiJob(id: Long, hash: String, claimedStatus: String, updatedAt: Long): Int
    @Query("UPDATE ai_jobs SET status=:status, attempts=attempts+1, lastError=:lastError, updatedAt=:updatedAt WHERE id=:id AND inputHash=:hash AND status='PENDING'")
    suspend fun recordPendingAiJobFailure(id: Long, hash: String, status: String, lastError: String?, updatedAt: Long): Int
    @Query("SELECT COUNT(*) FROM ai_jobs WHERE status='PENDING'") fun observePendingAiCount(): Flow<Int>
    @Query("DELETE FROM ai_jobs WHERE status='DONE'") suspend fun clearDoneAiJobs()
}
