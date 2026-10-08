package com.mayushi.trailsense.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TrackChunkEntity::class, RouteEntity::class, ActivityEntity::class, CompanionEntity::class, ActivityCompanionCrossRef::class, PaceSegmentEntity::class, CalendarContextEntity::class, PillPlanEntity::class, PillEventEntity::class, AiCacheEntity::class, AiJobEntity::class],
    version = 7,
    exportSchema = false,
)
abstract class TrailDatabase : RoomDatabase() {
    abstract fun trailDao(): TrailDao

    companion object {
        @Volatile private var instance: TrailDatabase? = null
        fun get(context: Context): TrailDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TrailDatabase::class.java, "trailsense.db")
                .addMigrations(MIGRATION_3_4, MIGRATION_4_6, MIGRATION_5_6, MIGRATION_6_7)
                .build().also { instance = it }
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
