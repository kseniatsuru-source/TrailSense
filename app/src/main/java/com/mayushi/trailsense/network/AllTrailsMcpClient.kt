package com.mayushi.trailsense.network

import com.mayushi.trailsense.domain.TrackPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

/** Official MCP transport. No track, timestamps or personal questionnaire is uploaded. */
class AllTrailsMcpClient(private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()) {
    data class Area(val south: Double, val west: Double, val north: Double, val east: Double) {
        fun input() = JSONObject().put("southwest_bounds", JSONObject().put("latitude", south).put("longitude", west))
            .put("northeast_bounds", JSONObject().put("latitude", north).put("longitude", east)).put("limit",20).put("locale","en-CA")
        companion object {
            fun from(points: List<TrackPoint>): Area {
                require(points.size >= 2) { "Недостаточно GPS для поиска" }
                require(points.all { it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 })
                return Area((floor(points.minOf { it.latitude } * 100)/100-.02).coerceAtLeast(-90.0),
                    (floor(points.minOf { it.longitude } * 100)/100-.02).coerceAtLeast(-180.0),
                    (ceil(points.maxOf { it.latitude } * 100)/100+.02).coerceAtMost(90.0),
                    (ceil(points.maxOf { it.longitude } * 100)/100+.02).coerceAtMost(180.0))
            }
        }
    }
    data class Candidate(val id: Long, val name: String, val url: String, val length: Double?, val gain: Double?, val type: String, val location: String, val description: String = "")
    class Unavailable(message: String): IOException(message)
    companion object {
        fun validRouteUrl(url: String): Boolean = runCatching { val u=URI(url); u.scheme=="https" && u.host in setOf("www.alltrails.com","alltrails.com") && u.userInfo==null && (u.port == -1 || u.port == 443) && Regex("/(?:[a-z]{2}(?:-[a-z]{2})?/)?trail/[^/]+/[^/]+/[^/]+").matches(u.path) }.getOrDefault(false)
        fun decode(text: String): JSONObject {
            val raw = if (text.trimStart().startsWith("{")) JSONObject(text) else text.lineSequence().filter { it.startsWith("data:") }.mapNotNull { runCatching { JSONObject(it.substringAfter("data:").trim()) }.getOrNull() }.firstOrNull { it.has("result") || it.has("error") } ?: throw Unavailable("Некорректный ответ MCP")
            if(raw.has("error")) throw Unavailable("Ошибка протокола MCP")
            val result=raw.optJSONObject("result") ?: throw Unavailable("Нет результата MCP")
            if(result.optBoolean("isError")) throw Unavailable("AllTrails не выполнил поиск")
            result.optJSONObject("structuredContent")?.let { return it }
            val content=result.optJSONArray("content")
            if(content!=null) for(i in 0 until content.length()) { val item=content.optJSONObject(i) ?: continue; if(item.optString("type")=="text") runCatching { JSONObject(item.optString("text")) }.getOrNull()?.let { return it } }
            return result
        }
        fun candidates(result: JSONObject): List<Candidate> {
            val rows=result.optJSONArray("trails") ?: return emptyList()
            return (0 until rows.length()).mapNotNull { i ->
                val r=rows.optJSONObject(i) ?: return@mapNotNull null
                val id=r.optLong("id"); val url=r.optString("alltrails_web_url"); val name=r.optString("name")
                if(id<=0 || name.isBlank() || !validRouteUrl(url)) null else Candidate(id,name,url,r.number("length_meters"),r.number("elevation_gain_meters"),r.optString("route_type"),r.optString("location_label"))
            }.distinctBy { it.id }
        }
        private fun JSONObject.number(key: String): Double? = if(isNull(key)) null else optDouble(key).takeIf { it.isFinite() && it>=0 }
    }
    suspend fun search(area: Area, distance: Double, gain: Double): List<Candidate> = withTimeout(110_000) {
        var session: String?=null; var protocol="2025-03-26"; var id=0
        suspend fun rpc(method: String, params: JSONObject?=null, notification: Boolean=false): JSONObject {
            val body=JSONObject().put("jsonrpc","2.0").put("method",method)
            if(!notification) body.put("id",++id)
            if(params!=null) body.put("params",params)
            val request=Request.Builder().url("https://www.alltrails.com/mcp").header("Accept","application/json, text/event-stream").header("User-Agent","TrailSense/0.3.5").header("MCP-Protocol-Version",protocol)
            session?.let { request.header("Mcp-Session-Id",it) }
            val call=http.newCall(request.post(body.toString().toRequestBody("application/json".toMediaType())).build())
            val response=suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object: Callback {
                    override fun onFailure(call: Call, e: IOException) { if(continuation.isActive) continuation.resumeWithException(Unavailable("Сеть недоступна или истекло время ожидания")) }
                    override fun onResponse(call: Call, response: Response) { if(continuation.isActive) continuation.resume(response) else response.close() }
                })
            }
            response.use { r ->
                if(r.code==429) throw Unavailable("Лимит AllTrails. Повтори позже (Retry-After: ${r.header("Retry-After") ?: "не указан"})")
                if(!r.isSuccessful) throw Unavailable("AllTrails недоступен: HTTP ${r.code}")
                r.header("Mcp-Session-Id")?.let { session=it }
                if(notification) return JSONObject()
                val text=r.body?.string() ?: throw Unavailable("Пустой ответ MCP")
                return decode(text)
            }
        }
        val init=rpc("initialize",JSONObject().put("protocolVersion",protocol).put("capabilities",JSONObject()).put("clientInfo",JSONObject().put("name","TrailSense").put("version","0.3.5")))
        protocol=init.optString("protocolVersion",protocol)
        rpc("notifications/initialized",notification=true)
        suspend fun tool(name: String, input: JSONObject) = rpc("tools/call",JSONObject().put("name",name).put("arguments",JSONObject().put("input",input)))
        val all=candidates(tool("find_trails_within_bounds",area.input())).toMutableList()
        if(distance.isFinite() && distance>0) {
            val approximateDistance = (distance / 1000.0).roundToInt() * 1000.0
            val band=max(7000.0,ceil(approximateDistance*.3/1000.0)*1000.0)
            all+=candidates(tool("find_trails_within_bounds",area.input().put("filters",JSONObject().put("length",JSONObject().put("min",max(0.0,approximateDistance-band)).put("max",approximateDistance+band)))))
        }
        if(all.isEmpty()) all+=candidates(tool("find_trails_near_location",JSONObject().put("latitude",(area.south+area.north)/2).put("longitude",(area.west+area.east)/2).put("max_radius_meters",20000).put("limit",10).put("locale","en-CA")))
        val ranked=all.distinctBy { it.id }.sortedBy { c -> (c.length?.let { abs(it-distance)/max(distance,1000.0) } ?: 1.0) + .15*(c.gain?.let { abs(it-gain)/max(gain,100.0) } ?: 1.0) }.take(20).toMutableList()
        for(i in 0 until min(2,ranked.size)) {
            val detail=tool("get_trail_details",JSONObject().put("trail_id",ranked[i].id).put("locale","en-CA")).optJSONObject("trail")
            ranked[i]=ranked[i].copy(description=detail?.optString("curated_description").orEmpty().take(1800))
        }
        ranked
    }
}
