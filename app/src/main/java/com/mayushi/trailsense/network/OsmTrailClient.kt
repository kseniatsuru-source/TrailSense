package com.mayushi.trailsense.network

import com.mayushi.trailsense.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.*

class OsmTrailClient(private val context: android.content.Context? = null) {
    data class Area(val south: Double,val west: Double,val north: Double,val east: Double) {
        fun query(): String = "[out:json][timeout:20];way[highway~\"^(path|footway|track|steps|bridleway)$\"]($south,$west,$north,$east);(._;>;);out body;"
        fun disclosure(): String = String.format(Locale.US,"%.2f–%.2f° N, %.2f–%.2f° E",south,north,west,east)
        companion object {
            fun from(start: TrackPoint,end: TrackPoint): Area {
                val area = Area(floor((min(start.latitude,end.latitude)-.005)*100)/100, floor((min(start.longitude,end.longitude)-.005)*100)/100, ceil((max(start.latitude,end.latitude)+.005)*100)/100, ceil((max(start.longitude,end.longitude)+.005)*100)/100)
                require(area.north-area.south <= .12 && area.east-area.west <= .16) { "Старт слишком далеко для небольшого запроса OSM. Выбери точку ближе к началу записи." }
                return area
            }
        }
    }
    private val http = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(35,TimeUnit.SECONDS).build()
    suspend fun fetch(area: Area): TrailGraph = withContext(Dispatchers.IO) {
        val cache = context?.let { ctx -> java.io.File(java.io.File(ctx.cacheDir,"osm-trails").apply { mkdirs() }, "${area.south}_${area.west}_${area.north}_${area.east}.json") }
        if(cache?.isFile == true && System.currentTimeMillis()-cache.lastModified() in 0..86_400_000L && cache.length() <= 12_000_000) {
            runCatching { TrailGraph.parse(cache.readText()) }.getOrNull()?.let { return@withContext it }
        }
        var lastError: Exception? = null
        for (endpoint in listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")) {
            try {
                val request = Request.Builder().url(endpoint)
                    .header("User-Agent","TrailSense/0.3.6 personal hiking route preview")
                    .post(FormBody.Builder().add("data",area.query()).build()).build()
                val graph = http.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "OSM/Overpass сейчас недоступен (HTTP ${response.code}). Повтори позже. Прямая линия не будет добавлена." }
                    val body = response.body ?: error("OSM вернул пустой ответ")
                    require(body.contentLength() <= 12_000_000) { "Ответ OSM слишком большой" }
                    val bytes = body.byteStream().use { input ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while(output.size() <= 12_000_000) { val n=input.read(buffer); if(n < 0) break; output.write(buffer,0,n) }
                        output.toByteArray()
                    }
                    require(bytes.size <= 12_000_000) { "Ответ OSM слишком большой" }
                    val text = String(bytes,Charsets.UTF_8)
                    val parsed = TrailGraph.parse(text)
                    cache?.let { file ->
                        runCatching { file.writeText(text); file.parentFile?.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() } }
                    }
                    parsed
                }
                return@withContext graph
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: Exception) { lastError=e }
        }
        throw IllegalArgumentException("Тропы не загружены: ${lastError?.message}. Повтори позже; прямой линии вместо троп не будет.",lastError)
    }
}
