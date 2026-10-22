package com.mayushi.trailsense

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mayushi.trailsense.data.ActivityEntity
import com.mayushi.trailsense.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.*

private data class MapRecording(val activity: ActivityEntity,val segments: List<List<TrackPoint>>)
@Composable fun AllHikesMap(vm: AppViewModel,openActivity: (Long)->Unit = {},openRecording: ((Long,Long?)->Unit)? = null,open: (Long)->Unit) {
    val context=LocalContext.current
    val activities by vm.recordingIndex.collectAsStateWithLifecycle()
    val routes by vm.routeIndex.collectAsStateWithLifecycle()
    var latitude by rememberSaveable { mutableDoubleStateOf(49.35) }
    var longitude by rememberSaveable { mutableDoubleStateOf(-123.1) }
    var zoom by rememberSaveable { mutableDoubleStateOf(11.0) }
    var fitted by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var records by remember { mutableStateOf(emptyList<MapRecording>()) }
    var noGps by remember { mutableIntStateOf(0) }
    var scaleBucket by remember { mutableIntStateOf(zoom.toInt()/3) }
    val scope=rememberCoroutineScope()
    val map=remember { TrackMapView(context).apply {
        setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.OpenTopo)
        setMultiTouchControls(false)
        zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        controller.setZoom(zoom); controller.setCenter(GeoPoint(latitude,longitude))
        addMapListener(object: org.osmdroid.events.MapListener {
            override fun onScroll(e: org.osmdroid.events.ScrollEvent?):Boolean { latitude=mapCenter.latitude; longitude=mapCenter.longitude; return false }
            override fun onZoom(e: org.osmdroid.events.ZoomEvent?):Boolean { zoom=zoomLevelDouble; scaleBucket=zoom.toInt()/3; return false }
        })
    } }
    DisposableEffect(map) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
    LaunchedEffect(activities,scaleBucket) {
        val data=withContext(Dispatchers.Default) {
            activities.mapNotNull { activity ->
                val points=runCatching { TrackCodec.decode(vm.loadRecordedTrack(activity.trackEncoded)) }.getOrDefault(emptyList())
                val segments=TrackGeometry.split(points).filter { it.size>=2 }.map { p ->
                    AutomaticRepeatRoutes.sample(p, minOf(p.size,if(scaleBucket>=5) 1600 else 400))
                }
                if(segments.isEmpty()) null else MapRecording(activity,segments)
            }
        }
        records=data; noGps=activities.size-data.size
    }
    LaunchedEffect(records, selectedIds, zoom) {
        map.overlays.clear()
        // One tap handler for ALL recordings, including coincident repeated routes.
        map.overlays += MapEventsOverlay(object: org.osmdroid.events.MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint):Boolean {
                val snapshot=records
                scope.launchMapSelection(snapshot,p,map) { selectedIds=it }
                return true
            }
            override fun longPressHelper(p:GeoPoint):Boolean=false
        })
        records.forEach { record -> record.segments.forEach { segment ->
            map.overlays += Polyline().apply {
                setPoints(segment.map { GeoPoint(it.latitude,it.longitude) })
                outlinePaint.color=if(segment.first().inferred) android.graphics.Color.rgb(30,230,115) else android.graphics.Color.rgb(204,40,107)
                outlinePaint.strokeWidth=if(record.activity.id in selectedIds) 13f else if(segment.first().inferred) 12f else 6f
                if(segment.first().inferred) outlinePaint.pathEffect=android.graphics.DashPathEffect(floatArrayOf(24f,12f),0f)
                setOnClickListener { _,_,p -> scope.launchMapSelection(records,p,map) {selectedIds=it}; true }
            }
        } }
        val clusters = mutableListOf<MutableList<MapRecording>>()
        records.forEach { record ->
            val point = record.segments.first().first()
            val pixel = map.projection.toPixels(GeoPoint(point.latitude, point.longitude), null)
            val cluster = clusters.firstOrNull { group ->
                val other = group.first().segments.first().first()
                val xy = map.projection.toPixels(GeoPoint(other.latitude, other.longitude), null)
                kotlin.math.hypot((pixel.x - xy.x).toDouble(), (pixel.y - xy.y).toDouble()) < 40 * map.resources.displayMetrics.density
            }
            if(cluster == null) clusters.add(mutableListOf(record)) else cluster.add(record)
        }
        clusters.forEach { group ->
            val point = group.first().segments.first().first()
            map.overlays += Marker(map).apply {
                position = GeoPoint(point.latitude, point.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = mapRecordingMarker(map.resources, group.size)
                title = if(group.size > 1) "${group.size} прохождения" else group.first().activity.title
                setOnMarkerClickListener { _, _ -> selectedIds = group.map { it.activity.id }; true }
            }
        }
        if(!fitted && records.isNotEmpty()) {
            fitted=true
            val p=records.flatMap {it.segments.flatten()}
            map.post {map.zoomToBoundingBox(org.osmdroid.util.BoundingBox(p.maxOf{it.latitude},p.maxOf{it.longitude},p.minOf{it.latitude},p.minOf{it.longitude}),false,48)}
        }
        map.invalidate()
    }
    val selected=activities.filter {it.id in selectedIds}
    // Registered inside the map, after the app-level navigation handler: dismiss, do not navigate.
    BackHandler(enabled=selected.isNotEmpty()) { selectedIds=emptyList() }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            AndroidView(factory={map},modifier=Modifier.fillMaxSize().testTag("all-hikes-map"))
            if(selected.isNotEmpty()) key(selectedIds) {
                Box(Modifier.align(Alignment.BottomCenter)) {
                    MapRecordingPanel(selected, routes, onDismiss={selectedIds=emptyList()}) { activity ->
                        // Never fall back to opening a route's latest recording.
                        openMapRecording(activity, openRecording, openActivity)
                    }
                }
            }
        }
        // A separate layout row, outside the map/overlay and above the app's navigation.
        Text("© OpenStreetMap contributors · OpenTopoMap (CC-BY-SA) · без GPS: $noGps",
            modifier=Modifier.fillMaxWidth().testTag("map-attribution").background(MaterialTheme.colorScheme.surface).padding(horizontal=8.dp, vertical=6.dp),
            style=MaterialTheme.typography.labelSmall)
    }
}

/** Keep recording identity even for coincident repeats on one bound route. */
internal fun openMapRecording(
    activity: ActivityEntity,
    openRecording: ((Long, Long?) -> Unit)?,
    openActivity: (Long) -> Unit,
) {
    if (openRecording != null) openRecording(activity.id, activity.routeId) else openActivity(activity.id)
}

/** Unconsumed downward drag only: the child's scroll must reach its top first. */
internal fun mapPanelDismissDistance(previous: Float, atTop: Boolean, deltaY: Float): Float =
    if (!atTop || deltaY < 0f) 0f else previous + deltaY

@Composable
private fun MapRecordingPanel(
    selected: List<ActivityEntity>,
    routes: List<com.mayushi.trailsense.data.RouteEntity>,
    onDismiss: () -> Unit,
    onOpen: (ActivityEntity) -> Unit,
) {
    val ordered = remember(selected) {
        selected.sortedWith(compareByDescending<ActivityEntity> { it.startedAt }.thenByDescending { it.id })
    }
    val pager = rememberPagerState(pageCount = { ordered.size })
    val dismissThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    val dismiss = rememberUpdatedState(onDismiss)
    // Source: render-map-mint-background-equal-padding.py. Keep the mock's RGBA
    // colors, 650/720 card width and 20/720 gap, with equal 6dp top/bottom gutters.
    BoxWithConstraints(
        Modifier.fillMaxWidth().testTag("map-recording-panel")
            .background(Color(0xCBA5D6B8))
            .pointerInput(dismissThreshold) {
                var downwardDistance = 0f
                var dismissed = false
                detectVerticalDragGestures(
                    onDragStart = { downwardDistance = 0f; dismissed = false },
                    onDragEnd = { downwardDistance = 0f },
                    onDragCancel = { downwardDistance = 0f },
                    onVerticalDrag = { change, delta ->
                        // The carousel has no vertical list to rewind; horizontal swipes
                        // belong to the pager and do not enter this vertical-slop detector.
                        downwardDistance = mapPanelDismissDistance(downwardDistance, true, delta)
                        if (delta > 0f) change.consume()
                        if (!dismissed && downwardDistance >= dismissThreshold) {
                            dismissed = true
                            dismiss.value()
                        }
                    },
                )
            },
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().testTag("map-recording-carousel"),
            contentPadding = PaddingValues(horizontal = maxWidth * (35f / 720f), vertical = 6.dp),
            pageSpacing = maxWidth * (20f / 720f),
            key = { ordered[it].id },
        ) { index ->
            val activity = ordered[index]
            Card(
                onClick = { onOpen(activity) },
                modifier = Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("map-open-${activity.id}"),
                shape = RoundedCornerShape(9.dp),
                colors = CardDefaults.cardColors(containerColor=Color(0xE11D2F24), contentColor=Color(0xFFE0E8E2)),
                border = BorderStroke(.5.dp, Color(0xA07C9785)),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal=10.5.dp, vertical=8.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(DisplayTitle.resolve(activity,routes), modifier=Modifier.fillMaxWidth().testTag("map-title-${activity.id}"),
                        fontWeight=FontWeight.Bold, style=MaterialTheme.typography.titleSmall,
                        maxLines=1, overflow=TextOverflow.Ellipsis)
                    Text("${km(activity.distanceM)} · +${activity.elevationGainM.toInt()} м", style=MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.Bottom, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("${recordingDate(activity)} · ${activity.elapsedSeconds?.let(::recordingDuration) ?: "время неизвестно"}",
                            modifier=Modifier.weight(1f), style=MaterialTheme.typography.labelSmall)
                        Text("${index + 1}/${ordered.size}", modifier=Modifier.testTag("map-counter-${activity.id}"),
                            style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** 20dp visible circle (~40px on the 720px mock), independently of its 48dp hit rectangle. */
private fun mapRecordingMarker(resources: android.content.res.Resources, count: Int): android.graphics.drawable.Drawable {
    val density = resources.displayMetrics.density
    val size = (48 * density).toInt().coerceAtLeast(1)
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val center = size / 2f
    val radius = 10 * density
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.rgb(218, 235, 221) // #DAEBDD
    canvas.drawCircle(center, center, radius, paint)
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = .75f * density
    paint.color = android.graphics.Color.rgb(141, 175, 154) // #8DAF9A
    canvas.drawCircle(center, center, radius - paint.strokeWidth / 2, paint)
    paint.color = android.graphics.Color.rgb(52, 83, 66) // #345342
    if (count > 1) {
        paint.style = android.graphics.Paint.Style.FILL
        paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.textSize = 11 * density
        val label = count.toString()
        val width = paint.measureText(label)
        if (width > 15 * density) paint.textSize *= 15 * density / width
        canvas.drawText(label, center, center - (paint.ascent() + paint.descent()) / 2, paint)
    } else {
        paint.strokeWidth = 1.2f * density
        paint.strokeJoin = android.graphics.Paint.Join.ROUND
        val mountain = android.graphics.Path().apply {
            moveTo(center - 6 * density, center + 4 * density)
            lineTo(center - 1 * density, center - 4 * density)
            lineTo(center + 2 * density, center + 1 * density)
            lineTo(center + 4 * density, center - 1 * density)
            lineTo(center + 7 * density, center + 4 * density)
            close()
            moveTo(center - 3 * density, center - 1 * density)
            lineTo(center - 1 * density, center)
            lineTo(center + .5f * density, center - 1.5f * density)
        }
        canvas.drawPath(mountain, paint)
    }
    return android.graphics.drawable.BitmapDrawable(resources, bitmap)
}
private fun kotlinx.coroutines.CoroutineScope.launchMapSelection(records:List<MapRecording>,tap:GeoPoint,map:TrackMapView,selected:(List<Long>)->Unit) = launch {
    val tolerance=map.projection.metersToEquatorPixels(1f).let { if(it>0) 24*map.resources.displayMetrics.density/it else 50f }.toDouble().coerceIn(5.0,2000.0)
    val ids=withContext(Dispatchers.Default) {
        val point=TrackPoint(tap.latitude,tap.longitude)
        records.filter {record -> record.segments.any {s -> s.zipWithNext().any { (a,b) ->
            val latScale=111320.0; val lonScale=latScale*kotlin.math.cos(Math.toRadians(point.latitude))
            val ax=(a.longitude-point.longitude)*lonScale;val ay=(a.latitude-point.latitude)*latScale
            val bx=(b.longitude-point.longitude)*lonScale;val by=(b.latitude-point.latitude)*latScale
            val dx=bx-ax;val dy=by-ay
            val t=(-(ax*dx+ay*dy)/(dx*dx+dy*dy).coerceAtLeast(.0001)).coerceIn(0.0,1.0)
            kotlin.math.hypot(ax+t*dx,ay+t*dy)<=tolerance
        } } }.map{it.activity.id}
    }
    selected(ids)
}
