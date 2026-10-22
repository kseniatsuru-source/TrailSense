package com.mayushi.trailsense.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class HuaweiSourceIdentity(val kind: String, val sourceId: String, val sourceHash: String, val activityId: Long?)

@Dao
interface TrailDao {
    @Query("SELECT * FROM huawei_sources") suspend fun huaweiSources(): List<HuaweiSourceEntity>
    // Source indexes must not materialize every inline raw-chunk payload on a 192 MiB device.
    @Query("SELECT kind,sourceId,sourceHash,activityId FROM huawei_sources") suspend fun huaweiSourceIdentities(): List<HuaweiSourceIdentity>
    @Query("SELECT * FROM huawei_sources WHERE kind IN ('workouts','sleeps')") suspend fun huaweiRecordingAndSleepSources(): List<HuaweiSourceEntity>
    @Query("SELECT * FROM huawei_sources WHERE kind='workouts' AND activityId=:id") suspend fun huaweiWorkoutsForActivity(id: Long): List<HuaweiSourceEntity>
    @Query("SELECT * FROM huawei_sources WHERE kind='workouts' AND activityId=:id") fun observeHuaweiWorkout(id: Long): Flow<List<HuaweiSourceEntity>>
    // Detach only the recording's bindings. Immutable historical track/payload chunks survive.
    @Query("DELETE FROM huawei_sources WHERE activityId=:id AND kind IN ('workouts','rawChunks')") suspend fun detachHuaweiRecording(id: Long)

    @Transaction
    suspend fun replaceWatchActivity(expected: ActivityEntity, incoming: ActivityEntity) {
        val current = requireNotNull(storedActivity(expected.id))
        require(current.updatedAt == expected.updatedAt) { "Запись обновилась. Повтори замену файла." }
        val bindings = huaweiWorkoutsForActivity(current.id)
        val sameIdentity = bindings.size == 1 && bindings.all { source ->
            val payload = org.json.JSONObject(requireNotNull(loadTrack(source.payloadRef)))
            incoming.importedStartedAt == source.startTimeMs &&
                kotlin.math.abs(incoming.distanceM - payload.getDouble("distanceM")) < 0.01 &&
                incoming.elapsedSeconds?.let { kotlin.math.abs(it * 1000 - payload.getLong("totalTimeMs")) <= 1000 } == true
        }
        if (!sameIdentity) detachHuaweiRecording(current.id)
        updateActivityAndInvalidate(incoming.copy(huaweiFactsJson = if (sameIdentity) current.huaweiFactsJson else ""))
    }
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putHuaweiSource(source: HuaweiSourceEntity)
    @Query("DELETE FROM huawei_sources WHERE kind='rawChunks' AND sourceId LIKE :prefix") suspend fun deleteHuaweiRawChunks(prefix: String)
    @Query("UPDATE huawei_sources SET activityId=:target WHERE kind='rawChunks' AND sourceId LIKE :prefix") suspend fun bindHuaweiRawChunks(prefix: String,target: Long)
    @Query("SELECT * FROM huawei_sources WHERE kind='rawChunks' AND activityId=:id ORDER BY startTimeMs") suspend fun huaweiRawChunks(id: Long): List<HuaweiSourceEntity>
    @Query("SELECT * FROM activities ORDER BY id") suspend fun huaweiActivityIndex(): List<ActivityEntity>
    @Query("SELECT * FROM companions WHERE id=:id") suspend fun companion(id: Long): CompanionEntity?
    @Query("SELECT * FROM companions ORDER BY name COLLATE NOCASE") suspend fun companionsOnce(): List<CompanionEntity>
    @Query("SELECT c.id FROM companions c INNER JOIN activity_companions h ON h.companionId=c.id WHERE c.deleted=0 GROUP BY c.id ORDER BY COUNT(*) DESC, c.name COLLATE NOCASE, c.id LIMIT 4")
    fun observeFrequentCompanionIds(): Flow<List<Long>>
    @Query("SELECT COUNT(*) FROM activity_companions WHERE companionId=:id") suspend fun companionUseCount(id: Long): Int
    @Query("SELECT a.id,a.title,a.startedAt,a.manualDateEpochDay,r.title AS routeTitle FROM activities a INNER JOIN activity_companions c ON a.id=c.activityId LEFT JOIN routes r ON r.id=a.routeId WHERE c.companionId=:id ORDER BY a.startedAt DESC LIMIT 3")
    suspend fun companionExamples(id: Long): List<CompanionExample>
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
    @Query("DELETE FROM analysis_results WHERE (entityType='ROUTE' AND entityId=:routeId) OR (entityType='ACTIVITY' AND entityId IN (SELECT id FROM activities WHERE routeId=:routeId))")
    suspend fun deleteOwnedAnalysisResults(routeId: Long)
    @Query("SELECT * FROM ai_jobs WHERE entityType='COMPARISON'") suspend fun comparisonJobs(): List<AiJobEntity>
    @Query("SELECT * FROM analysis_results WHERE entityType='COMPARISON'") suspend fun comparisonResults(): List<AnalysisResultEntity>
    @Query("DELETE FROM analysis_results WHERE entityType='COMPARISON' AND scopeKey=:scope") suspend fun deleteComparisonScopeResults(scope: String)

    @Transaction
    suspend fun deleteRouteEvent(routeId: Long) {
        val deletedIds = storedActivitiesForRoute(routeId).map { it.id }.toSet()
        val results = comparisonResults()
        val scopes = (comparisonJobs().map { it.scopeKey } + results.map { it.scopeKey }).distinct()
        scopes.filter { scope -> scope.substringAfterLast(':').split(',').mapNotNull { it.toLongOrNull() }.any { it in deletedIds } }.forEach { scope ->
            results.filter { it.scopeKey == scope }.forEach { deleteComparisonCache(it.inputHash) }
            deleteComparisonScopeResults(scope)
            deleteComparisonScopeCaches(scope)
            deleteComparisonJobs(scope)
        }
        deleteOwnedAnalysisResults(routeId)
        deleteAiCacheForRouteEvent(routeId)
        deleteAiJobsForRouteEvent(routeId)
        deleteCompanionLinksForRouteEvent(routeId)
        deletedIds.forEach { detachHuaweiRecording(it) }
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

    @Query("UPDATE ai_jobs SET status='SUPERSEDED', lastError='Контекст рюкзака изменился' WHERE status='PENDING' AND ((entityType='ACTIVITY' AND entityId=:activityId) OR (entityType='ROUTE' AND entityId IN (SELECT routeId FROM activities WHERE id=:activityId)))")
    suspend fun supersedeBackpackJobs(activityId: Long)
    @Transaction
    suspend fun updateBackpack(activity: ActivityEntity) {
        updateActivityAndInvalidate(activity)
    }

    @Query("SELECT * FROM companions ORDER BY name COLLATE NOCASE") fun observeCompanions(): Flow<List<CompanionEntity>>
    @Insert suspend fun insertCompanion(companion: CompanionEntity): Long
    @Transaction suspend fun createUniqueCompanion(name: String): Long {
        CompanionIdentity.validate(name)
        require(companionsOnce().none {CompanionIdentity.key(it.name)==CompanionIdentity.key(name)}) { "Имя уже есть в списке или истории. Выбери человека либо уточни имя." }
        return insertCompanion(CompanionEntity(name=name.trim()))
    }
    @Update suspend fun updateCompanion(companion: CompanionEntity)
    @Delete suspend fun deleteCompanion(companion: CompanionEntity)
    @Query("SELECT companionId FROM activity_companions WHERE activityId=:activityId") fun observeCompanionIds(activityId: Long): Flow<List<Long>>
    @Query("SELECT companionId FROM activity_companions WHERE activityId=:activityId") suspend fun companionIdsOnce(activityId: Long): List<Long>
    @Query("SELECT companions.name FROM companions INNER JOIN activity_companions ON companions.id=activity_companions.companionId WHERE activity_companions.activityId=:activityId ORDER BY companions.name COLLATE NOCASE")
    suspend fun companionNamesForActivity(activityId: Long): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun linkCompanion(link: ActivityCompanionCrossRef)
    @Query("DELETE FROM activity_companions WHERE activityId=:activityId") suspend fun clearActivityCompanions(activityId: Long)

    @Transaction
    suspend fun updateActivityWithCompanions(activity: ActivityEntity, companionIds: Set<Long>) {
        val previous=companionIdsOnce(activity.id).toSet()
        for(id in companionIds) require(companion(id)?.let { !it.deleted || id in previous }==true) { "Удалённого путника нужно сначала восстановить" }
        updateActivity(activity)
        clearActivityCompanions(activity.id)
        companionIds.forEach { linkCompanion(ActivityCompanionCrossRef(activity.id, it)) }
        invalidateAiForActivity(activity.id)
    }

    @Query("SELECT * FROM pace_segments WHERE activityId=:activityId ORDER BY startFraction, endFraction")
    fun observePaceSegments(activityId: Long): Flow<List<PaceSegmentEntity>>
    @Transaction
    suspend fun saveUnifiedQuestionnaire(route: RouteEntity, activity: ActivityEntity, companionIds: Set<Long>) {
        updateActivityWithCompanions(activity, companionIds)
        updateRouteAndInvalidate(route)
    }
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
    @Query("SELECT * FROM calendar_context WHERE startEpochDay <= :epochDay AND ((ongoing=0 AND endEpochDay >= :epochDay) OR (ongoing=1 AND :epochDay <= :today)) ORDER BY startEpochDay, createdAt")
    suspend fun calendarContextsForDay(epochDay: Long, today: Long = java.time.LocalDate.now().toEpochDay()): List<CalendarContextEntity>
    @Query("SELECT MAX(updatedAt) FROM calendar_context WHERE startEpochDay <= :epochDay AND ((ongoing=0 AND endEpochDay >= :epochDay) OR (ongoing=1 AND :epochDay <= :today))")
    suspend fun calendarContextRevisionForDay(epochDay: Long, today: Long = java.time.LocalDate.now().toEpochDay()): Long?
    @Insert suspend fun insertCalendarContext(context: CalendarContextEntity): Long
    @Update suspend fun updateCalendarContext(context: CalendarContextEntity)
    @Delete suspend fun deleteCalendarContext(context: CalendarContextEntity)
    @Query("UPDATE calendar_context SET updatedAt=:updatedAt WHERE startEpochDay <= :endEpochDay AND endEpochDay >= :startEpochDay")
    suspend fun touchCalendarContextsInRange(startEpochDay: Long, endEpochDay: Long, updatedAt: Long)

    @Query("SELECT * FROM pill_plans WHERE id=1") fun observePillPlan(): Flow<PillPlanEntity?>
    @Query("SELECT * FROM pill_plans WHERE id=1") suspend fun pillPlan(): PillPlanEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun savePillPlan(plan: PillPlanEntity)
    @Query("SELECT * FROM pill_events ORDER BY epochDay DESC, createdAt DESC") fun observePillEvents(): Flow<List<PillEventEntity>>
    @Query("SELECT * FROM pill_events ORDER BY epochDay DESC, createdAt DESC") suspend fun pillEvents(): List<PillEventEntity>
    @Insert suspend fun insertPillEvent(event: PillEventEntity): Long
    @Update suspend fun updatePillEvent(event: PillEventEntity)
    @Query("SELECT * FROM pill_events WHERE id=:id") suspend fun pillEvent(id: Long): PillEventEntity?
    @Query("SELECT * FROM calendar_context WHERE id=:id") suspend fun calendarContext(id: Long): CalendarContextEntity?
    @Query("SELECT * FROM pill_plan_versions ORDER BY effectiveEpochDay, id") fun observePillPlanVersions(): Flow<List<PillPlanVersionEntity>>
    @Query("SELECT * FROM pill_plan_versions ORDER BY effectiveEpochDay, id") suspend fun pillPlanVersions(): List<PillPlanVersionEntity>
    @Insert suspend fun insertPillPlanVersion(version: PillPlanVersionEntity): Long
    @Transaction suspend fun saveVersionedPillPlan(plan: PillPlanEntity, effectiveEpochDay: Long) {
        // Preserve a pre-versioned current plan before the first edit.
        val old = pillPlan()
        if (pillPlanVersions().isEmpty() && old != null) {
            insertPillPlanVersion(PillPlanVersionEntity(effectiveEpochDay = old.anchorEpochDay,
                anchorEpochDay = old.anchorEpochDay, activeDays = old.activeDays, breakDays = old.breakDays,
                enabled = old.enabled, createdAt = old.updatedAt, subBlockDays = old.subBlockDays, subBlockCount = old.subBlockCount))
        }
        insertPillPlanVersion(PillPlanVersionEntity(effectiveEpochDay = effectiveEpochDay, anchorEpochDay = plan.anchorEpochDay,
            activeDays = plan.activeDays, breakDays = plan.breakDays, enabled = plan.enabled, createdAt = plan.updatedAt,
            subBlockDays = plan.subBlockDays, subBlockCount = plan.subBlockCount))
        savePillPlan(plan)
    }

    @Query("SELECT * FROM ai_cache WHERE cacheKey=:key LIMIT 1") suspend fun aiCache(key: String): AiCacheEntity?
    @Query("SELECT c.* FROM ai_cache c LEFT JOIN analysis_results r ON r.entityType=c.entityType AND r.entityId=c.entityId AND r.analysisKind=c.analysisKind AND r.inputHash=c.inputHash WHERE c.entityType=:type AND c.entityId=:entityId AND c.analysisKind=:kind ORDER BY (r.resultKey IS NOT NULL) DESC, r.inputRevision DESC, c.createdAt DESC LIMIT 1") fun observeLatestAi(type: String, entityId: Long, kind: String): Flow<AiCacheEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAiCache(cache: AiCacheEntity): Long
    @Query("SELECT COALESCE(SUM(promptTokens + completionTokens), 0) FROM ai_cache WHERE createdAt >= :since") suspend fun aiTokensSince(since: Long): Int
    @Insert suspend fun insertAiJob(job: AiJobEntity): Long
    @Query("SELECT * FROM ai_jobs WHERE entityType=:type AND entityId=:entityId AND analysisKind=:kind AND inputHash=:hash AND status='PENDING' LIMIT 1") suspend fun pendingAiJob(type: String, entityId: Long, kind: String, hash: String): AiJobEntity?
    @Query("UPDATE ai_jobs SET status='SUPERSEDED', lastError='Заменено новой версией задания', updatedAt=:updatedAt WHERE entityType=:type AND entityId=:entityId AND analysisKind=:kind AND inputHash != :hash AND status='PENDING'")
    suspend fun supersedePendingAiJobs(type: String, entityId: Long, kind: String, hash: String, updatedAt: Long): Int

    @Query("SELECT * FROM ai_jobs WHERE entityType=:type AND entityId=:entityId AND analysisKind=:kind AND scopeKey=:scope AND status='PENDING' ORDER BY id DESC LIMIT 1")
    suspend fun pendingAiJobForScope(type: String, entityId: Long, kind: String, scope: String): AiJobEntity?
    @Query("SELECT MAX(inputRevision) FROM ai_jobs WHERE entityType=:type AND entityId=:entityId AND analysisKind=:kind AND scopeKey=:scope")
    suspend fun latestInputRevision(type: String, entityId: Long, kind: String, scope: String): Long?
    @Update suspend fun updateAiJob(job: AiJobEntity)
    @Query("UPDATE ai_jobs SET lastError=:reason WHERE id=:id AND inputRevision=:revision AND status='PENDING'")
    suspend fun notePendingRefreshFailure(id: Long, revision: Long, reason: String)
    @Transaction
    suspend fun refreshPendingAiJob(expected: AiJobEntity, replacement: AiJobEntity): Boolean {
        val current = aiJob(expected.id) ?: return false
        if (current.status != "PENDING" || current.inputRevision != expected.inputRevision || current.inputHash != expected.inputHash) return false
        if (current.inputHash == replacement.inputHash) return false
        require(current.scopeKey == replacement.scopeKey && current.entityType == replacement.entityType && current.entityId == replacement.entityId)
        updateAiJob(replacement.copy(id = current.id, createdAt = current.createdAt, inputRevision = Math.addExact(current.inputRevision, 1)))
        return true
    }
    @Query("SELECT * FROM ai_jobs WHERE id=:id") suspend fun analysisJobSnapshot(id: Long): AiJobEntity?
    @Query("DELETE FROM analysis_results WHERE resultKey=:key AND entityType='COMPARISON'") suspend fun deleteComparisonResultRow(key: String)
    @Query("DELETE FROM ai_cache WHERE entityType='COMPARISON' AND inputHash=:hash") suspend fun deleteComparisonCache(hash: String)
    @Query("DELETE FROM ai_jobs WHERE entityType='COMPARISON' AND scopeKey=:scope") suspend fun deleteComparisonJobs(scope: String)
    @Query("DELETE FROM ai_cache WHERE entityType='COMPARISON' AND inputHash IN (SELECT inputHash FROM ai_jobs WHERE entityType='COMPARISON' AND scopeKey=:scope)")
    suspend fun deleteComparisonScopeCaches(scope: String)
    @Transaction suspend fun deleteComparisonAnalysis(key: String) {
        val result = analysisResult(key) ?: return
        require(result.entityType == "COMPARISON")
        deleteComparisonResultRow(key)
        deleteComparisonCache(result.inputHash)
        deleteComparisonScopeCaches(result.scopeKey)
        deleteComparisonJobs(result.scopeKey)
    }
    @Query("SELECT * FROM analysis_results WHERE resultKey=:key") suspend fun analysisResult(key: String): AnalysisResultEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAnalysisResult(result: AnalysisResultEntity)
    @Query("SELECT * FROM analysis_results WHERE entityType=:type AND entityId=:entityId AND analysisKind=:kind ORDER BY analysisCreatedAtMs DESC")
    fun observeAnalysisResults(type: String, entityId: Long, kind: String): Flow<List<AnalysisResultEntity>>

    @Transaction
    suspend fun enqueuePendingAiJob(job: AiJobEntity): Long {
        val pending = pendingAiJobForScope(job.entityType, job.entityId, job.analysisKind, job.scopeKey)
        if (pending?.inputHash == job.inputHash) return -1
        val resultKey = "${job.entityType}:${job.entityId}:${job.analysisKind}:${job.scopeKey}"
        val revision = Math.addExact(maxOf(latestInputRevision(job.entityType, job.entityId, job.analysisKind, job.scopeKey) ?: 0,
            analysisResult(resultKey)?.inputRevision ?: 0), 1)
        if (pending != null) {
            // Preserve queue identity/order, but invalidate all older exported hashes.
            updateAiJob(job.copy(id = pending.id, createdAt = pending.createdAt, inputRevision = revision))
            return pending.id
        }
        return insertAiJob(job.copy(inputRevision = revision))
    }

    @Query("SELECT * FROM ai_jobs WHERE id=:id") suspend fun aiJob(id: Long): AiJobEntity?
    @Query("SELECT * FROM ai_jobs WHERE status='PENDING' ORDER BY createdAt LIMIT 1") suspend fun nextAiJob(): AiJobEntity?
    @Query("SELECT * FROM ai_jobs WHERE status='PENDING' ORDER BY createdAt") suspend fun pendingAiJobs(): List<AiJobEntity>
    @Query("UPDATE ai_jobs SET status='SUPERSEDED', lastError=:reason, updatedAt=:updatedAt WHERE id=:id AND inputHash=:hash AND inputRevision=:revision AND status='PENDING'")
    suspend fun supersedePendingAiJob(id: Long, hash: String, reason: String, updatedAt: Long, revision: Long): Int
    @Query("UPDATE ai_jobs SET status=:claimedStatus, updatedAt=:updatedAt WHERE id=:id AND inputHash=:hash AND inputRevision=:revision AND status='PENDING'")
    suspend fun claimPendingAiJob(id: Long, hash: String, claimedStatus: String, updatedAt: Long, revision: Long): Int
    @Query("UPDATE ai_jobs SET status='DONE', attempts=attempts+1, lastError=NULL, updatedAt=:updatedAt WHERE id=:id AND inputHash=:hash AND inputRevision=:revision AND status=:claimedStatus")
    suspend fun finishClaimedAiJob(id: Long, hash: String, claimedStatus: String, updatedAt: Long, revision: Long): Int
    @Query("UPDATE ai_jobs SET status=:status, attempts=attempts+1, lastError=:lastError, updatedAt=:updatedAt WHERE id=:id AND inputHash=:hash AND inputRevision=:revision AND status='PENDING'")
    suspend fun recordPendingAiJobFailure(id: Long, hash: String, status: String, lastError: String?, updatedAt: Long, revision: Long): Int
    @Query("SELECT COUNT(*) FROM ai_jobs WHERE status='PENDING'") fun observePendingAiCount(): Flow<Int>
    @Query("DELETE FROM ai_jobs WHERE status='DONE'") suspend fun clearDoneAiJobs()
}
