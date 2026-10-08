package com.mayushi.trailsense.data

import androidx.sqlite.db.SupportSQLiteDatabase
import com.mayushi.trailsense.domain.HikeDateResolver

/** Called by Room inside its upgrade transaction. Never reads a legacy full track row. */
object LargeTrackMigration {
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS track_chunks (trackKey TEXT NOT NULL, ordinal INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(trackKey, ordinal))")
        for ((table, columns) in listOf("routes" to listOf("trackEncoded"), "activities" to listOf("trackEncoded", "originalTrackEncoded"))) {
            val ids = mutableListOf<Long>()
            db.query("SELECT id FROM $table").use { cursor -> while (cursor.moveToNext()) ids += cursor.getLong(0) }
            for (id in ids) for (column in columns) {
                val value = readLegacy(db, table, column, id) ?: continue
                if (value.length <= TrackChunks.SIZE || value.startsWith(TrackChunks.PREFIX)) continue
                val chunks = TrackChunks.split(value)
                for (chunk in chunks) db.execSQL("INSERT OR IGNORE INTO track_chunks(trackKey, ordinal, payload) VALUES (?, ?, ?)", arrayOf(chunk.trackKey, chunk.ordinal, chunk.payload))
                // Verify persisted bytes BEFORE replacing the only legacy reference.
                check(load(db, TrackChunks.PREFIX + chunks.first().trackKey) == value)
                db.execSQL("UPDATE $table SET $column=? WHERE id=?", arrayOf(TrackChunks.PREFIX + chunks.first().trackKey, id))
            }
        }
        val rows = mutableListOf<Pair<Long, Long?>>()
        db.query("SELECT id, startedAt FROM activities WHERE sourceFormat IN ('TCX', 'GPX')").use { cursor ->
            while (cursor.moveToNext()) rows += cursor.getLong(0) to if (cursor.isNull(1)) null else cursor.getLong(1)
        }
        val now = System.currentTimeMillis()
        for ((id, oldDate) in rows) {
            val original = readLegacy(db, "activities", "originalTrackEncoded", id)?.let { load(db, it) }
            val current = readLegacy(db, "activities", "trackEncoded", id)?.let { load(db, it) }
            // Original recording takes precedence over cropped/corrected points and export headers.
            // Undated recordings retain existing metadata dates; no filename/import-time guessing.
            val recordedDate = HikeDateResolver.fromEncodedTrack(original) ?: HikeDateResolver.fromEncodedTrack(current)
            if (recordedDate != null && recordedDate != oldDate) {
                db.execSQL("UPDATE activities SET startedAt=?, updatedAt=CASE WHEN updatedAt>=? THEN updatedAt+1 ELSE ? END WHERE id=?", arrayOf(recordedDate, now, now, id))
                db.execSQL("DELETE FROM ai_cache WHERE (entityType='ACTIVITY' AND entityId=?) OR (entityType='ROUTE' AND entityId IN (SELECT routeId FROM activities WHERE id=?))", arrayOf(id, id))
                db.execSQL("UPDATE ai_jobs SET status='SUPERSEDED', lastError='Дата хайка восстановлена из исходной записи часов', updatedAt=? WHERE status='PENDING' AND ((entityType='ACTIVITY' AND entityId=?) OR (entityType='ROUTE' AND entityId IN (SELECT routeId FROM activities WHERE id=?)))", arrayOf(now, id, id))
            }
        }
    }

    internal fun readLegacy(db: SupportSQLiteDatabase, table: String, column: String, id: Long): String? {
        val length = db.query("SELECT length($column) FROM $table WHERE id=?", arrayOf(id)).use { c ->
            if (!c.moveToFirst() || c.isNull(0)) return null
            c.getInt(0)
        }
        return buildString(length) {
            var offset = 1
            while (offset <= length) {
                db.query("SELECT substr($column, ?, ?) FROM $table WHERE id=?", arrayOf(offset, TrackChunks.SIZE, id)).use { c ->
                    check(c.moveToFirst())
                    append(c.getString(0))
                }
                offset += TrackChunks.SIZE
            }
        }
    }
    private fun load(db: SupportSQLiteDatabase, value: String): String {
        if (!value.startsWith(TrackChunks.PREFIX)) return value
        val chunks = mutableListOf<TrackChunkEntity>()
        db.query("SELECT trackKey, ordinal, payload FROM track_chunks WHERE trackKey=? ORDER BY ordinal", arrayOf(value.removePrefix(TrackChunks.PREFIX))).use { c ->
            while (c.moveToNext()) chunks += TrackChunkEntity(c.getString(0), c.getInt(1), c.getString(2))
        }
        return TrackChunks.join(value, chunks)
    }
}
