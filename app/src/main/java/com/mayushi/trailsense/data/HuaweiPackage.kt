package com.mayushi.trailsense.data

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset

/** One-item streaming parse: private input never enters an unbounded JSONObject or CursorWindow. */
object HuaweiPackage {
    const val MAX_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_CHUNK_POINTS = 1000
    const val MAX_CHUNK_BYTES = 512 * 1024
    data class Item(val kind: String, val json: JSONObject) {
        val id: String = json.getString("sourceId")
        val start: Long = json.getLong("startTimeMs")
        val end: Long = json.getLong("endTimeMs")
        val device: String = json.getString("deviceId")
        val offset: String = json.getString("sourceOffset")
        val hash: String = sha(legacyCanonical(json))
        fun localDay(time: Long) = Instant.ofEpochMilli(time).atOffset(ZoneOffset.of(offset)).toLocalDate().toEpochDay()
        fun summary(): JSONObject = JSONObject().apply {
            put("sourceId", id); put("source", "HUAWEI_ARCHIVE"); put("startTimeMs", start); put("endTimeMs", end); put("sourceOffset", offset)
            if (kind == "workouts") {
                for (key in listOf("distanceM", "avgHrBpm", "maxHrBpm", "steps", "cadenceSpm", "paceSecondsPerKm", "totalTimeMs"))
                    if (!json.isNull(key)) put(key, json.get(key))
                put("gainM", json.getDouble("gainDm") / 10); put("lossM", json.getDouble("lossDm") / 10)
                put("timeMeaning", "PROVIDER_TRACK_TIME_NOT_ELAPSED_OR_MOVING")
            }
        }
    }
    data class Sleep(val id: String, val device: String, val offset: String, val start: Long, val end: Long, val wakeDay: Long,
        val durationMs: Long?, val nap: Boolean, val hash: String)
    fun sleep(item: Item): Sleep {
        val stages = item.json.getJSONArray("stages")
        val ordered = (0 until stages.length()).map { stages.getJSONObject(it) }.sortedBy { it.getLong("startTimeMs") }
        var cursor = item.start; var valid = ordered.isNotEmpty(); var duration = 0L; var nap = false
        for (s in ordered) {
            val start = s.getLong("startTimeMs"); val end = s.getLong("endTimeMs"); val key = s.getString("key")
            if (start != cursor) valid = false
            cursor = end
            if (key == "PROFESSIONAL_SLEEP_NOON") nap = true
            if (key != "PROFESSIONAL_SLEEP_WAKE") duration += end - start
        }
        if (cursor != item.end) valid = false
        return Sleep(item.id,item.device,item.offset,item.start,item.end,item.localDay(item.end),duration.takeIf { valid },nap,item.hash)
    }
    fun matchingSleep(start: Long, day: Long, sessions: List<Sleep>): Sleep? {
        // Naps and sessions ending after the hike starts are not candidates; incomplete prior nights still make it ambiguous.
        val sameDay = sessions.filter { it.wakeDay == day && it.end<=start && !it.nap }
        if (sameDay.size != 1) return null
        val s = sameDay.single()
        if (s.end > start || s.nap || s.durationMs == null) return null
        if (sessions.any { it.id != s.id && it.start < s.end && it.end > s.start }) return null
        return s
    }
    suspend fun read(file: File, chunk: suspend (String, JSONObject) -> Unit = { _, _ -> }, consume: suspend (Item) -> Unit) {
        require(file.length() in 1..MAX_BYTES) { "Размер Huawei JSON вне допустимых границ" }
        val bounded=ItemReader(file.reader(Charsets.UTF_8).buffered())
        JsonReader(bounded).use { r ->
            r.isLenient = false
            var protocol: String? = null; var version: Int? = null
            val roots = mutableSetOf<String>(); val ids = mutableSetOf<String>()
            val expected = mutableMapOf<String, JSONObject>()
            val streams = mutableMapOf<String, ChunkAudit>()
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName(); require(roots.add(name)) { "Повтор поля пакета" }
                when (name) {
                    "protocol" -> { require(r.peek()==JsonToken.STRING); protocol = r.nextString() }
                    "schemaVersion" -> { require(r.peek()==JsonToken.NUMBER); version = r.nextInt() }
                    "workouts", "sleeps", "trackChunks", "rawChunks" -> {
                        r.beginArray(); var count = 0
                        while (r.hasNext()) {
                            require(++count <= if (name.endsWith("Chunks")) 200000 else if (name == "workouts") 10000 else 20000) { "Слишком много записей" }
                            bounded.resetItem(if(name.endsWith("Chunks")) MAX_CHUNK_BYTES else 2*1024*1024)
                            val budget = intArrayOf(0)
                            val obj = value(r,0,budget) as? JSONObject ?: error("Ожидается объект")
                            require(obj.toString().toByteArray(Charsets.UTF_8).size<=2*1024*1024) { "Слишком большая запись" }
                            if(name.endsWith("Chunks")) {
                                require(obj.toString().toByteArray(Charsets.UTF_8).size <= MAX_CHUNK_BYTES)
                                val id=obj.getString("sourceId"); require(id.length in 1..240)
                                val streamKey="$name:$id"
                                if(streamKey !in streams) require(streams.size<20000) { "Слишком много источников chunks" }
                                val audit=streams.getOrPut(streamKey) { ChunkAudit() }
                                audit.accept(name,obj)
                                chunk(name,obj)
                                continue
                            }
                            validate(name,obj)
                            val item = Item(name,obj)
                            require(ids.add("$name:${item.id}")) { "Повтор sourceId" }
                            if(name=="workouts") expected[item.id]=JSONObject().put("startTimeMs",item.start).put("endTimeMs",item.end).apply {
                                for(k in listOf("track","detail")) if(obj.has(k)) put(k,obj.getJSONObject(k))
                            }
                            consume(item)
                        }
                        r.endArray(); bounded.resetItem()
                    }
                    else -> error("Неизвестное поле пакета")
                }
            }
            r.endObject(); require(r.peek() == JsonToken.END_DOCUMENT) { "Лишние данные" }
            require(protocol == "trailsense-huawei" && version == 1 && roots.containsAll(listOf("workouts","sleeps"))) { "Неподдерживаемый Huawei JSON" }
            for((key,audit) in streams) {
                val kind=key.substringBefore(':'); val id=key.substringAfter(':')
                val workout=requireNotNull(expected[id]) { "Chunk без тренировки" }
                val declaration=workout.getJSONObject(if(kind=="trackChunks") "track" else "detail")
                require(integer(declaration,"chunkCount",1,200000)==audit.chunks.toLong() && integer(declaration,"pointCount",1,100000000)==audit.points)
                require(declaration.getString("sha256")==audit.hash()) { "Chunk checksum mismatch" }
                if(kind=="trackChunks") require(audit.minTime>=workout.getLong("startTimeMs") && audit.lastTime<=workout.getLong("endTimeMs"))
            }
            for((id,w) in expected) for((field,kind) in listOf("track" to "trackChunks","detail" to "rawChunks")) {
                if(w.has(field)) require(streams.containsKey("$kind:$id")) { "Missing declared chunks" }
            }
        }
    }
    private class ChunkAudit {
        var chunks=0; var points=0L; var minTime=Long.MAX_VALUE; var lastTime=0L
        private val digest=MessageDigest.getInstance("SHA-256")
        fun accept(kind: String,j: JSONObject) {
            require(integer(j,"ordinal",0,200000)==chunks.toLong()) { "Нарушен порядок chunks" }; chunks++
            if(kind=="trackChunks") {
                val p=j.getJSONArray("points"); require(p.length() in 1..MAX_CHUNK_POINTS)
                for(i in 0 until p.length()) {
                    val v=p.getJSONObject(i); number(v,"latitude",-90.0,90.0); number(v,"longitude",-180.0,180.0)
                    val t=integer(v,"timeMs",lastTime,4102444800000L); minTime=minOf(minTime,t);lastTime=t
                    if(!v.isNull("elevationM")) number(v,"elevationM",-15000.0,100000.0)
                    if(!v.isNull("heartRateBpm")) integer(v,"heartRateBpm",1,300)
                    if(!v.isNull("cadenceSpm")) integer(v,"cadenceSpm",0,400)
                    if(v.has("segmentStart")) require(v.get("segmentStart") is Boolean)
                }
                points+=p.length()
                require(points<=2_000_000)
            } else {
                val lines=j.getJSONArray("lines");require(lines.length() in 1..1000)
                for(i in 0 until lines.length()) require(lines.get(i) is String)
                points+=lines.length()
                val hr=j.optJSONArray("heartRateSamples") ?: JSONArray();require(hr.length()<=1000)
                for(i in 0 until hr.length()) {val s=hr.getJSONObject(i);integer(s,"timeMs",0,4102444800000L);integer(s,"bpm",1,300)}
                for((field,value,max) in listOf(Triple("cadenceSamples","spm",400.0),Triple("elevationSamples","meters",100000.0))) {
                    val samples=j.optJSONArray(field) ?: JSONArray();require(samples.length()<=1000)
                    for(i in 0 until samples.length()) {val s=samples.getJSONObject(i);integer(s,"timeMs",0,4102444800000L);number(s,value,if(field=="elevationSamples") -15000.0 else 0.0,max)}
                }
            }
            digest.update((canonical(j)+"\n").toByteArray(Charsets.UTF_8))
        }
        fun hash()=digest.digest().joinToString("") { "%02x".format(it) }
    }
    private class ItemReader(private val input: java.io.Reader): java.io.Reader() {
        private var count=0
        private var limit=2*1024*1024
        fun resetItem(max: Int=2*1024*1024) { count=0;limit=max }
        override fun read(b: CharArray,offset: Int,length: Int): Int {
            val n=input.read(b,offset,length.coerceAtMost(1024))
            if(n>0) {count+=n;require(count<=limit+2048) { "Слишком большая запись" }}
            return n
        }
        override fun close()=input.close()
    }
    private fun value(r: JsonReader, depth: Int, budget: IntArray): Any {
        require(depth <= 32 && ++budget[0] <= 2*1024*1024) { "Слишком большая запись" }
        return when (r.peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().apply {
                r.beginObject(); while (r.hasNext()) { val key = text(r.nextName(),budget); require(!has(key)) { "Повтор поля записи" }; put(key,value(r,depth+1,budget)) }; r.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JSONArray().apply { r.beginArray(); while(r.hasNext()) put(value(r,depth+1,budget)); r.endArray() }
            JsonToken.STRING -> text(r.nextString(),budget)
            JsonToken.NUMBER -> { val n = text(r.nextString(),budget); n.toLongOrNull() ?: java.math.BigDecimal(n).also { require(it.toDouble().isFinite()) } }
            JsonToken.BOOLEAN -> r.nextBoolean()
            JsonToken.NULL -> { r.nextNull(); JSONObject.NULL }
            else -> error("Некорректный JSON")
        }
    }
    private fun text(s: String,b: IntArray): String { require(s.length<=262144); b[0]+=s.length; require(b[0]<=2*1024*1024); return s }
    private fun validate(kind: String,j: JSONObject) {
        for(k in listOf("sourceId","deviceId")) require(j.get(k) is String && j.getString(k).length in 1..240)
        val start = integer(j,"startTimeMs",0,4102444800000L); val end=integer(j,"endTimeMs",start,4102444800000L)
        val offset=j.getString("sourceOffset"); require(Regex("[+-][0-9]{4}").matches(offset)); ZoneOffset.of(offset)
        j.getJSONObject("raw")
        if(kind=="workouts") {
            val sub=integer(j,"subType",0,1000); val sport=integer(j,"sportType",0,1000)
            require((sub==282L && sport==282L)||(sub==2L && sport==260L)) { "Неподдерживаемый вид тренировки" }
            for(k in listOf("distanceM","gainDm","lossDm")) number(j,k,0.0,100000000.0)
            integer(j,"totalTimeMs",0,31536000000L)
            if(!j.isNull("title")) require(j.get("title") is String && j.getString("title").length<=400)
            for(k in listOf("avgHrBpm","maxHrBpm")) if(!j.isNull(k)) integer(j,k,1,300)
            if(!j.isNull("steps")) integer(j,"steps",0,10000000)
            if(!j.isNull("cadenceSpm")) number(j,"cadenceSpm",0.0,400.0)
            if(!j.isNull("paceSecondsPerKm")) number(j,"paceSecondsPerKm",0.000001,10000000.0)
            if(!j.isNull("heartRateSamples")) require(j.get("heartRateSamples") is JSONArray)
            val samples=j.optJSONArray("heartRateSamples") ?: JSONArray(); require(samples.length()<=20000)
            var last=start
            for(i in 0 until samples.length()) { val s=samples.getJSONObject(i); last=integer(s,"timeMs",last,end); integer(s,"bpm",1,300) }
        } else {
            val stages=j.getJSONArray("stages"); require(stages.length()<=3000)
            for(i in 0 until stages.length()) { val s=stages.getJSONObject(i); val t=integer(s,"startTimeMs",start,end); integer(s,"endTimeMs",t+1,end)
                require(s.getString("key") in listOf("PROFESSIONAL_SLEEP_SHALLOW","PROFESSIONAL_SLEEP_DEEP","PROFESSIONAL_SLEEP_DREAM","PROFESSIONAL_SLEEP_NOON","PROFESSIONAL_SLEEP_WAKE")) }
        }
    }
    private fun integer(j: JSONObject,k: String,min: Long,max: Long): Long {
        val v=j.get(k); require(v is Number && v.toDouble().isFinite() && v.toDouble()==v.toLong().toDouble() && v.toLong() in min..max) { "Некорректное целое поле $k" }; return v.toLong()
    }
    private fun number(j: JSONObject,k: String,min: Double,max: Double): Double { val v=j.get(k); require(v is Number && v.toDouble().isFinite() && v.toDouble() in min..max) { "Некорректное число $k" }; return v.toDouble() }
    fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    // Existing v1 source identities retain their original hash algorithm.
    private fun legacyCanonical(v: Any): String = when(v) {
        is JSONObject -> v.keys().asSequence().toList().sorted().joinToString(",","{","}") { JSONObject.quote(it)+":"+legacyCanonical(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",","[","]") { legacyCanonical(v.get(it)) }
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }
    fun canonical(v: Any): String = when(v) {
        is JSONObject -> v.keys().asSequence().toList().sorted().joinToString(",","{","}") { JSONObject.quote(it)+":"+canonical(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",","[","]") { canonical(v.get(it)) }
        is String -> JSONObject.quote(v)
        is Number -> java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
        else -> v.toString()
    }
}
