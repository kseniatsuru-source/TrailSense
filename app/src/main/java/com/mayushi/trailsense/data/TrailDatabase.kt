package com.mayushi.trailsense.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [HuaweiSourceEntity::class, TrackChunkEntity::class, RouteEntity::class, ActivityEntity::class, CompanionEntity::class, ActivityCompanionCrossRef::class, PaceSegmentEntity::class, CalendarContextEntity::class, PillPlanEntity::class, PillPlanVersionEntity::class, PillEventEntity::class, AiCacheEntity::class, AiJobEntity::class, AnalysisResultEntity::class],
    version = 12,
    exportSchema = false,
)
abstract class TrailDatabase : RoomDatabase() {
    abstract fun trailDao(): TrailDao

    companion object {
        @Volatile private var instance: TrailDatabase? = null
        fun get(context: Context): TrailDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TrailDatabase::class.java, "trailsense.db")
                .addMigrations(MIGRATION_3_4, MIGRATION_4_6, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                .build().also { instance = it }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activities ADD COLUMN huaweiFactsJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE companions ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS huawei_sources (kind TEXT NOT NULL, sourceId TEXT NOT NULL, sourceHash TEXT NOT NULL, activityId INTEGER, payloadRef TEXT NOT NULL, deviceId TEXT NOT NULL, sourceOffset TEXT NOT NULL, startTimeMs INTEGER NOT NULL, endTimeMs INTEGER NOT NULL, wakeEpochDay INTEGER, durationMs INTEGER, nap INTEGER NOT NULL, PRIMARY KEY(kind,sourceId))")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_jobs ADD COLUMN scopeKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE ai_jobs ADD COLUMN inputRevision INTEGER NOT NULL DEFAULT 1")
                // Order old jobs locally; never reinterpret existing payload bytes or hashes.
                db.execSQL("UPDATE ai_jobs SET inputRevision=id")
                db.execSQL("CREATE TABLE IF NOT EXISTS analysis_results (resultKey TEXT NOT NULL PRIMARY KEY, entityType TEXT NOT NULL, entityId INTEGER NOT NULL, analysisKind TEXT NOT NULL, scopeKey TEXT NOT NULL, jobId INTEGER NOT NULL, inputHash TEXT NOT NULL, inputRevision INTEGER NOT NULL, analysisCreatedAtMs INTEGER NOT NULL, responseJson TEXT NOT NULL)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Zero means derive the legacy subdivision without changing old fields.
                for (table in listOf("pill_plans", "pill_plan_versions")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN subBlockDays INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN subBlockCount INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activities ADD COLUMN sleepOcrMinutes INTEGER")
                db.execSQL("ALTER TABLE activities ADD COLUMN sleepOcrStatus TEXT")
                db.execSQL("ALTER TABLE activities ADD COLUMN questionnaireHistory TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE calendar_context ADD COLUMN ongoing INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS pill_plan_versions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, effectiveEpochDay INTEGER NOT NULL, anchorEpochDay INTEGER NOT NULL, activeDays INTEGER NOT NULL, breakDays INTEGER NOT NULL, enabled INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pill_plan_versions_effectiveEpochDay ON pill_plan_versions(effectiveEpochDay)")
                // Copy the known old regimen; no past cycle rewritten by later starts.
                db.execSQL("INSERT INTO pill_plan_versions(effectiveEpochDay,anchorEpochDay,activeDays,breakDays,enabled,createdAt) SELECT anchorEpochDay,anchorEpochDay,activeDays,breakDays,enabled,updatedAt FROM pill_plans WHERE id=1")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activities ADD COLUMN heavyBackpack INTEGER NOT NULL DEFAULT 0")
                db.execSQL("DELETE FROM ai_cache")
                db.execSQL("UPDATE ai_jobs SET status='SUPERSEDED', lastError='Добавлен контекст рюкзака' WHERE status='PENDING'")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activities ADD COLUMN importedStartedAt INTEGER")
                db.execSQL("ALTER TABLE activities ADD COLUMN manualDateEpochDay INTEGER")
                db.execSQL("ALTER TABLE activities ADD COLUMN originalMetricsJson TEXT")
                db.execSQL("ALTER TABLE activities ADD COLUMN customRatings TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE activities ADD COLUMN pointsOfInterest TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE activities SET importedStartedAt = startedAt")
                // Read only bounded scalar fields, never select legacy track blobs.
                db.query("SELECT id,distanceM,elevationGainM,elevationLossM,elapsedSeconds,movingSeconds,stoppedSeconds,averagePaceMinutesPerKm,averageHeartRateBpm,maxHeartRateBpm FROM activities WHERE trackCorrectionMode='NONE'").use { c ->
                    while (c.moveToNext()) {
                        val j = org.json.JSONObject()
                        for (i in 1 until c.columnCount) if (!c.isNull(i)) j.put(c.getColumnName(i), if (i in 1..3 || i == 7) c.getDouble(i) else c.getLong(i))
                        db.execSQL("UPDATE activities SET originalMetricsJson=? WHERE id=?", arrayOf(j.toString(), c.getLong(0)))
                    }
                }
                db.execSQL("DELETE FROM ai_cache")
                db.execSQL("UPDATE ai_jobs SET status='SUPERSEDED', lastError='Новая версия защиты фактов' WHERE status='PENDING'")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activities ADD COLUMN originalTrackEncoded TEXT")
                db.execSQL("UPDATE activities SET originalTrackEncoded = trackEncoded WHERE sourceFormat != 'HUAWEI_SCREENSHOT'")
                db.execSQL("ALTER TABLE activities ADD COLUMN sleepScreenshotPath TEXT")
                db.execSQL("ALTER TABLE activities ADD COLUMN trackCorrectionMode TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE activities ADD COLUMN trackCorrectionNote TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE activities ADD COLUMN questionnaireCompletedAt INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS pace_segments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, activityId INTEGER NOT NULL, startFraction REAL NOT NULL, endFraction REAL NOT NULL, paceSetterName TEXT NOT NULL, note TEXT NOT NULL DEFAULT '', createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(activityId) REFERENCES activities(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pace_segments_activityId ON pace_segments(activityId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS calendar_context (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, startEpochDay INTEGER NOT NULL, endEpochDay INTEGER NOT NULL, type TEXT NOT NULL, severity INTEGER, note TEXT NOT NULL DEFAULT '', createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_calendar_context_startEpochDay ON calendar_context(startEpochDay)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_calendar_context_endEpochDay` ON `calendar_context` (`endEpochDay`)")
                db.execSQL("DELETE FROM ai_cache")
                db.execSQL("UPDATE ai_jobs SET status='SUPERSEDED', lastError='Задание создано старой версией TrailSense' WHERE status='PENDING'")
                }
        }

        // Bypass the shipped 4->5 full-column cursor, which itself crashes on large rows.
        val MIGRATION_4_6 = object : Migration(4, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = LargeTrackMigration.migrate(db)
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = LargeTrackMigration.migrate(db)
        }

    }
}
