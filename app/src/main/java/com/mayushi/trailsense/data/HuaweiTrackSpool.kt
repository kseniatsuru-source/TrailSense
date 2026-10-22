package com.mayushi.trailsense.data

import com.mayushi.trailsense.domain.TrackCodec
import com.mayushi.trailsense.domain.TrackPoint
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.Base64

/** Owned temporary binary spool; memory is bounded by one input chunk, not hike length. */
internal class HuaweiTrackSpool(private val directory: File): java.io.Closeable {
    private data class Entry(val file: File, var count: Int = 0)
    private val entries = mutableMapOf<String,Entry>()
    fun accept(j: JSONObject) {
        val entry=entries.getOrPut(j.getString("sourceId")) { Entry(File.createTempFile("owned-huawei-track-", ".bin",directory)) }
        val array=j.getJSONArray("points")
        val points=(0 until array.length()).map { i -> val p=array.getJSONObject(i)
            TrackPoint(p.getDouble("latitude"),p.getDouble("longitude"),if(p.isNull("elevationM")) null else p.getDouble("elevationM"),p.getLong("timeMs"),
                if(p.isNull("heartRateBpm")) null else p.getInt("heartRateBpm"),p.optBoolean("segmentStart",false),false,null,
                if(p.isNull("cadenceSpm")) null else p.getInt("cadenceSpm"))
        }
        val bytes=Base64.getDecoder().decode(TrackCodec.encode(points,true))
        FileOutputStream(entry.file,true).use { it.write(bytes,8,bytes.size-8) }
        entry.count+=points.size
    }
    suspend fun store(id: String, dao: TrailDao): String? {
        val entry=entries[id] ?: return null
        val encoded=File.createTempFile("owned-huawei-encoded-", ".txt",directory)
        try {
            Base64.getEncoder().wrap(encoded.outputStream().buffered()).use { out ->
                DataOutputStream(out).apply { writeInt(4);writeInt(entry.count);flush() }
                entry.file.inputStream().buffered().use { it.copyTo(out,65536) }
            }
            val digest=MessageDigest.getInstance("SHA-256")
            encoded.inputStream().buffered().use { input -> val buffer=ByteArray(65536);while(true) {val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }
            val key=digest.digest().joinToString("") { "%02x".format(it) }
            encoded.reader(Charsets.US_ASCII).buffered().use { input ->
                val buffer=CharArray(TrackChunks.SIZE);var ordinal=0
                while(true) {var count=0;while(count<buffer.size) {val n=input.read(buffer,count,buffer.size-count);if(n<0)break;count+=n};if(count==0)break
                    dao.insertTrackChunks(listOf(TrackChunkEntity(key,ordinal++,String(buffer,0,count))))
                }
            }
            return TrackChunks.PREFIX+key
        } finally { encoded.delete() }
    }
    override fun close() { entries.values.forEach { it.file.delete() };entries.clear() }
}
