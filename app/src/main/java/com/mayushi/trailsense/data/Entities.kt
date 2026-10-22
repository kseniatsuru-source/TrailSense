package com.mayushi.trailsense.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "routes", indices = [Index(value = ["canonicalUrl"], unique = true)])
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sourceUrl: String? = null,
    val canonicalUrl: String? = null,
    val sourceLabel: String = "Вручную",
    val trackEncoded: String? = null,
    val distanceM: Double? = null,
    val elevationGainM: Double? = null,
    val elevationLossM: Double? = null,
    val sourceDifficulty: String? = null,
    val sourceRating: Double? = null,
    val overallDifficulty: Int? = null,
    val climbDifficulty: Int? = null,
    val technicality: Int? = null,
    val exposure: Int? = null,
    val beauty: Int? = null,
    val notes: String = "",
    val reviewExcerpts: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "activities",
    foreignKeys = [ForeignKey(entity = RouteEntity::class, parentColumns = ["id"], childColumns = ["routeId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("routeId")],
)
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Long? = null,
    val suggestedRouteId: Long? = null,
    val title: String,
    val sourceFileName: String,
    val sourceFormat: String,
    val trackEncoded: String,
    val originalTrackEncoded: String? = null,
    val screenshotPath: String? = null,
    val sleepScreenshotPath: String? = null,
    val startedAt: Long? = null,
    val importedStartedAt: Long? = null,
    val manualDateEpochDay: Long? = null,
    val originalMetricsJson: String? = null,
    @ColumnInfo(defaultValue = "''") val huaweiFactsJson: String = "",
    @ColumnInfo(defaultValue = "''") val customRatings: String = "",
    @ColumnInfo(defaultValue = "''") val pointsOfInterest: String = "",
    val distanceM: Double,
    val elevationGainM: Double,
    val elevationLossM: Double,
    val elapsedSeconds: Long? = null,
    val movingSeconds: Long? = null,
    val stoppedSeconds: Long? = null,
    val averagePaceMinutesPerKm: Double? = null,
    val averageHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val matchConfidence: Double? = null,
    val overallEffort: Int? = null,
    val climbEffort: Int? = null,
    val descentEffort: Int? = null,
    val technicality: Int? = null,
    val enjoyment: Int? = null,
    val sleepHours: Double? = null,
    val sleepOcrMinutes: Int? = null,
    val sleepOcrStatus: String? = null,
    @ColumnInfo(defaultValue = "''") val questionnaireHistory: String = "",
    val sleepQuality: Int? = null,
    val feltRested: Boolean? = null,
    val anomaly: String = "NORMAL",
    val paceSetter: String = "SELF",
    val paceConstraint: String = "FREE",
    @ColumnInfo(defaultValue = "0") val heavyBackpack: Boolean = false,
    val hadWaitingStops: Boolean = false,
    val representativeSpeed: Boolean = true,
    @ColumnInfo(defaultValue = "'NONE'") val trackCorrectionMode: String = "NONE",
    @ColumnInfo(defaultValue = "''") val trackCorrectionNote: String = "",
    val questionnaireCompletedAt: Long? = null,
    val comment: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "companions")
data class CompanionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val notes: String = "",
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

/** Small scalar index; full private source JSON lives in bounded immutable chunks. */
@Entity(tableName = "huawei_sources", primaryKeys = ["kind", "sourceId"])
data class HuaweiSourceEntity(
    val kind: String, val sourceId: String, val sourceHash: String,
    val activityId: Long?, val payloadRef: String,
    val deviceId: String, val sourceOffset: String, val startTimeMs: Long, val endTimeMs: Long,
    val wakeEpochDay: Long?, val durationMs: Long?, val nap: Boolean,
)

@Entity(
    tableName = "activity_companions",
    primaryKeys = ["activityId", "companionId"],
    foreignKeys = [
        ForeignKey(entity = ActivityEntity::class, parentColumns = ["id"], childColumns = ["activityId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("activityId"), Index("companionId")],
)
data class ActivityCompanionCrossRef(val activityId: Long, val companionId: Long)

@Entity(
    tableName = "pace_segments",
    foreignKeys = [ForeignKey(entity = ActivityEntity::class, parentColumns = ["id"], childColumns = ["activityId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("activityId")],
)
data class PaceSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    val startFraction: Double,
    val endFraction: Double,
    val paceSetterName: String,
    @ColumnInfo(defaultValue = "''") val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "calendar_context", indices = [Index("startEpochDay"), Index("endEpochDay")])
data class CalendarContextEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "0") val ongoing: Boolean = false,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val type: String,
    val severity: Int? = null,
    @ColumnInfo(defaultValue = "''") val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "pill_plans")
data class PillPlanEntity(
    @PrimaryKey val id: Int = 1,
    val anchorEpochDay: Long,
    val activeDays: Int = 84,
    val breakDays: Int = 7,
    val enabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val subBlockDays: Int = 0,
    @ColumnInfo(defaultValue = "0") val subBlockCount: Int = 0,
)

@Entity(tableName = "pill_plan_versions", indices = [Index("effectiveEpochDay")])
data class PillPlanVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val effectiveEpochDay: Long,
    val anchorEpochDay: Long,
    val activeDays: Int,
    val breakDays: Int,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val subBlockDays: Int = 0,
    @ColumnInfo(defaultValue = "0") val subBlockCount: Int = 0,
)

@Entity(tableName = "pill_events", indices = [Index("epochDay")])
data class PillEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val plannedEpochDay: Long? = null,
    val type: String,
    val actualTaken: Boolean? = null,
    val note: String = "",
    val deviationPolicy: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "ai_cache", indices = [Index(value = ["cacheKey"], unique = true)])
data class AiCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cacheKey: String,
    val entityType: String,
    val entityId: Long,
    val analysisKind: String,
    val inputHash: String,
    val response: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "ai_jobs", indices = [Index("status")])
data class AiJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val entityId: Long,
    val analysisKind: String,
    val inputJson: String,
    val inputHash: String,
    val imagePath: String? = null,
    val imageMimeType: String? = null,
    val status: String = "PENDING",
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val scopeKey: String = "",
    @ColumnInfo(defaultValue = "1") val inputRevision: Long = 1,
)

/** Durable latest result per exact object/recording set; independent of disposable caches. */
@Entity(tableName = "analysis_results")
data class AnalysisResultEntity(
    @PrimaryKey val resultKey: String,
    val entityType: String,
    val entityId: Long,
    val analysisKind: String,
    val scopeKey: String,
    val jobId: Long,
    val inputHash: String,
    val inputRevision: Long,
    val analysisCreatedAtMs: Long,
    val responseJson: String,
)
