package com.mayushi.trailsense

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.mayushi.trailsense.data.ActivityEntity
import com.mayushi.trailsense.domain.TrackCodec
import kotlinx.coroutines.flow.catch
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
fun WatchDetails041(a: ActivityEntity,vm: AppViewModel) {
    WatchDetails041(a,remember(a.id,vm) { vm.observeHuaweiHeartRate(a.id) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatchDetails041(a: ActivityEntity,heartRate: kotlinx.coroutines.flow.Flow<List<Pair<Long,Int>>>) {
    var open by remember(a.id) {mutableStateOf(false)}
    val facts=remember(a.huaweiFactsJson) {runCatching {JSONObject(a.huaweiFactsJson)}.getOrDefault(JSONObject())}
    // Subscription remains mounted while open and follows bound source/payload revisions.
    val archived by remember(a.id,open,heartRate) {
        if(open) heartRate.catch { emit(emptyList()) }
        else kotlinx.coroutines.flow.flowOf(emptyList<Pair<Long,Int>>())
    }.collectAsState(initial=emptyList())
    val samples=remember(archived,a.originalTrackEncoded,a.trackEncoded) {
        com.mayushi.trailsense.data.HuaweiHeartRate.withOriginalFallback(archived,a)
    }
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(horizontal=14.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable {open=!open},verticalAlignment=Alignment.CenterVertically) {
            Text("Подробнее о треке",Modifier.weight(1f)); Icon(if(open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,if(open) "Свернуть данные часов" else "Раскрыть данные часов")
        }
        if(open) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                (facts.optInt("avgHrBpm").takeIf {it>0} ?: a.averageHeartRateBpm)?.let {Metric("Средний пульс","$it уд/мин")}
                (facts.optInt("maxHrBpm").takeIf {it>0} ?: a.maxHeartRateBpm)?.let {Metric("Максимальный пульс","$it уд/мин")}
                if(!facts.isNull("steps")) Metric("Шаги",facts.getLong("steps").toString())
                val cadence=facts.optDouble("cadenceSpm").takeIf {it.isFinite()} ?: remember(a.originalTrackEncoded,a.trackEncoded) {runCatching {TrackCodec.decode(a.originalTrackEncoded ?: a.trackEncoded).mapNotNull {it.cadencePerMinute}.takeIf {it.isNotEmpty()}?.average()}.getOrNull()}
                cadence?.let {Metric("Каденс","${it.roundToInt()} /мин")}
                val pace=facts.optDouble("paceSecondsPerKm").takeIf {it.isFinite() && it>0} ?: a.averagePaceMinutesPerKm?.times(60)
                pace?.let {val s=it.roundToInt();Metric("Средний темп","${s/60}:${(s%60).toString().padStart(2,'0')} /км")}
            }
            if(samples.isNotEmpty()) HeartRateChart041(samples)
            facts.optJSONObject("sleep")?.let {s ->
                val minutes=s.getLong("durationMs")/60000
                Text("Сон перед хайком · ${minutes/60} ч ${minutes%60} мин")
                InfoHint("Длительность сна часов","Вычислена по полностью покрытым именованным стадиям, без бодрствования. Это данные часов, не оценка качества сна и не ответ «выспалась».")
            }
            if(facts.length()==0 && a.averageHeartRateBpm==null && a.maxHeartRateBpm==null && a.averagePaceMinutesPerKm==null && samples.isEmpty()) Text("Дополнительных данных часов нет")
        }
    } }
}

@Composable
private fun HeartRateChart041(samples: List<Pair<Long,Int>>) {
    Text("Пульс · уд/мин",style=MaterialTheme.typography.labelMedium)
    val min=samples.minOf {it.second}; val max=samples.maxOf {it.second}; val start=samples.first().first;val end=samples.last().first
    Canvas(Modifier.fillMaxWidth().height(140.dp).padding(8.dp)) {
        val path=Path()
        samples.forEachIndexed {i,p -> val x=((p.first-start).toDouble()/(end-start).coerceAtLeast(1)*size.width).toFloat();val y=(size.height-(p.second-min).toFloat()/(max-min).coerceAtLeast(1)*size.height)
            if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
        }
        if(samples.size==1) drawCircle(Color(0xFFCC7285),4.dp.toPx(),Offset(0f,size.height)) else drawPath(path,Color(0xFFCC7285),style=Stroke(2.dp.toPx()))
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text("$min–$max уд/мин",style=MaterialTheme.typography.labelSmall);Text(duration((end-start)/1000),style=MaterialTheme.typography.labelSmall)}
}
