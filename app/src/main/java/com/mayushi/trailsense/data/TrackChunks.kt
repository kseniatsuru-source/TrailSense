package com.mayushi.trailsense.data

import androidx.room.Entity
import java.security.MessageDigest

/** Immutable content-addressed chunks: references remain valid even across Flow emissions.
 * Kept inside SQLite so track writes and entity writes commit/rollback together.
 * Never concatenate chunks in SQL: that would recreate the oversized CursorWindow row.
 */
@Entity(tableName = "track_chunks", primaryKeys = ["trackKey", "ordinal"])
data class TrackChunkEntity(val trackKey: String, val ordinal: Int, val payload: String)

object TrackChunks {
    const val SIZE = 64 * 1024
    const val PREFIX = "ts-chunks-v1:"
    fun key(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun split(value: String): List<TrackChunkEntity> {
        val key = key(value)
        return value.chunked(SIZE).mapIndexed { i, chunk -> TrackChunkEntity(key, i, chunk) }
    }
    fun join(reference: String, chunks: List<TrackChunkEntity>): String {
        require(reference.startsWith(PREFIX))
        val key = reference.removePrefix(PREFIX)
        require(chunks.isNotEmpty() && chunks.all { it.trackKey == key }) { "Missing track chunks" }
        require(chunks.withIndex().all { (i, chunk) -> chunk.ordinal == i && chunk.payload.length <= SIZE }) { "Invalid track chunk order" }
        val value = chunks.joinToString("") { it.payload }
        require(key(value) == key) { "Track checksum mismatch" }
        return value
    }
}
