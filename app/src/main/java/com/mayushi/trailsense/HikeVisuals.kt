package com.mayushi.trailsense

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mayushi.trailsense.domain.TrackPoint
import com.mayushi.trailsense.domain.TrackGeometry
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import kotlin.math.roundToInt

@Composable
fun TopographicTrackMap(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
    highlightedRange: ClosedFloatingPointRange<Float>? = null,
    mapHeight: Dp = 260.dp,
    pois: List<com.mayushi.trailsense.domain.HikeAnnotations.Poi> = emptyList(),
) {
    if (points.size < 2) return
    val context = LocalContext.current
    val map = remember { MapView(context).apply {
        setTileSource(TileSourceFactory.OpenTopo)
        setMultiTouchControls(true)
        controller.setZoom(13.0)
    } }
    DisposableEffect(map) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    LaunchedEffect(points, highlightedRange, pois) {
        map.overlays.clear()
        TrackGeometry.split(points).filter { it.size >= 2 }.forEach { segment ->
            map.overlays += Polyline().apply {
                setPoints(segment.map { GeoPoint(it.latitude, it.longitude) })
                outlinePaint.color = if (segment.first().inferred) AndroidColor.rgb(240,173,145) else AndroidColor.rgb(164,216,184)
                outlinePaint.strokeWidth = 8f
            }
        }
        highlightedRange?.let { range ->
            val start = (points.lastIndex * range.start).roundToInt().coerceIn(0, points.lastIndex - 1)
            val end = (points.lastIndex * range.endInclusive).roundToInt().coerceIn(start + 1, points.lastIndex)
            TrackGeometry.split(points.subList(start,end+1)).filter { it.size >= 2 }.forEach { segment ->
                map.overlays += Polyline().apply {
                    setPoints(segment.map { GeoPoint(it.latitude, it.longitude) })
                    outlinePaint.color = AndroidColor.rgb(240,173,145)
                    outlinePaint.strokeWidth = 12f
                }
            }
        }
        pois.forEach { poi -> map.overlays += org.osmdroid.views.overlay.Marker(map).apply {
            position = GeoPoint(poi.latitude,poi.longitude); title = poi.name; setAnchor(.5f,1f)
        } }
        val north = points.maxOf { it.latitude }
        val south = points.minOf { it.latitude }
        val east = points.maxOf { it.longitude }
        val west = points.minOf { it.longitude }
        map.post { map.zoomToBoundingBox(BoundingBox(north, east, south, west), true, 48) }
        map.invalidate()
    }
    Column(modifier.clipToBounds(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(mapHeight)
                .clip(RoundedCornerShape(16.dp))
                .clipToBounds(),
        ) {
            AndroidView(
                factory = { map },
                modifier = Modifier.matchParentSize().clipToBounds(),
            )
        }
        Text(
            (if (points.any { it.inferred }) "Зелёный — запись · персиковый — оценка начала. " else "") + "Топографическая карта: водоёмы, рельеф и горизонтали · © OpenStreetMap contributors · OpenTopoMap",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ElevationProfile(points: List<TrackPoint>, modifier: Modifier = Modifier) {
    val cumulative = remember(points) { TrackGeometry.cumulative(points) }
    val elevations = points.mapNotNull { it.elevationM }
    if (elevations.size < 2) return
    val min = elevations.min()
    val max = elevations.max()
    val span = (max - min).takeIf { it > 0 } ?: 1.0
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Профиль высоты", style = MaterialTheme.typography.labelMedium)
            Text("${min.roundToInt()}–${max.roundToInt()} м", style = MaterialTheme.typography.labelMedium)
        }
        Canvas(Modifier.fillMaxWidth().height(72.dp)) {
            val path = Path()
            val inferredPath = Path()
            fun position(index: Int): Offset = Offset(
                (size.width * cumulative[index] / cumulative.last().coerceAtLeast(1.0)).toFloat(),
                size.height - (((points[index].elevationM ?: min)-min)/span*size.height).toFloat())
            for (index in 1 until points.size) {
                val previous = points[index-1]; val current = points[index]
                if (previous.elevationM == null || current.elevationM == null || TrackGeometry.disconnected(previous,current)) continue
                val target = if (previous.inferred || current.inferred) inferredPath else path
                val a=position(index-1); val b=position(index)
                target.moveTo(a.x,a.y); target.lineTo(b.x,b.y)
            }
            drawPath(path, color = Forest, style = Stroke(width = 4f))
            drawPath(inferredPath, color = Coral, style = Stroke(width = 4f))
            drawLine(axisColor, Offset(0f, size.height), Offset(size.width, size.height), 1f)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("0 км"); Text("%.2f км по GPS".format(cumulative.last()/1000.0)) }
    }
}

@Composable
fun RichAnalysisText(text: String) {
    val lines = text.lines()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var index = 0
        while (index < lines.size) {
            val raw = lines[index].trim()
            when {
                raw.isBlank() -> Unit
                raw.startsWith("## ") -> Text(raw.removePrefix("## "), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                raw.startsWith("# ") -> Text(raw.removePrefix("# "), style = MaterialTheme.typography.titleLarge)
                raw.startsWith("- ") || raw.startsWith("• ") -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", color = MaterialTheme.colorScheme.secondary)
                    Text(raw.drop(2), Modifier.weight(1f))
                }
                raw.startsWith("|") -> {
                    val rows = mutableListOf<List<String>>()
                    while (index < lines.size && lines[index].trim().startsWith("|")) {
                        val cells = lines[index].trim().trim('|').split('|').map(String::trim)
                        if (cells.none { it.all { ch -> ch == '-' || ch == ':' } }) rows += cells
                        index++
                    }
                    AnalysisTable(rows)
                    index--
                }
                else -> Text(raw)
            }
            index++
        }
    }
}

@Composable
private fun AnalysisTable(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { rowIndex, cells ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                cells.forEach { cell ->
                    Text(
                        cell,
                        Modifier.weight(1f).padding(4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (rowIndex == 0) androidx.compose.ui.text.font.FontWeight.Bold else null,
                    )
                }
            }
        }
    }
}
