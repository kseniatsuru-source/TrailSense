package com.mayushi.trailsense

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mayushi.trailsense.data.ActivityEntity
import com.mayushi.trailsense.data.PaceSegmentEntity
import com.mayushi.trailsense.data.RouteEntity
import com.mayushi.trailsense.domain.EventChronology
import com.mayushi.trailsense.domain.EventMetricSelector
import com.mayushi.trailsense.domain.HikeQuestionnairePresentation
import com.mayushi.trailsense.domain.TrackCodec
import com.mayushi.trailsense.domain.TrackCorrection
import kotlin.math.roundToInt

private val watchFileTypes = arrayOf(
    "application/gpx+xml",
    "application/vnd.garmin.tcx+xml",
    "application/xml",
    "text/xml",
    "application/octet-stream",
)
const val HIKE_LONG_PRESS_MS = 2000L

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EventsScreen(vm: AppViewModel, open: (Long) -> Unit) {
    val routes by vm.routes.collectAsStateWithLifecycle()
    val activities by vm.activities.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<RouteEntity?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var watchTargetId by remember { mutableStateOf<Long?>(null) }
    var missingLinkRoute by remember { mutableStateOf<RouteEntity?>(null) }
    val pickWatch = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val routeId = watchTargetId
        watchTargetId = null
        if (uri != null && routeId != null) vm.importWatchFile(routeId, uri)
    }
    val latestByRoute = remember(activities) { EventChronology.latestActivityByRoute(activities) }
    val chronologicalRoutes = remember(routes, activities) { EventChronology.sortRoutes(routes, activities) }
    val busy by vm.busy.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Button(onClick = { showCreate = true }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Добавить хайк")
            }
        }
        if (routes.isEmpty()) item { EmptyState("Пока нет хайков", "Добавь ссылку AllTrails и файл с часов в одной карточке.") }
        items(chronologicalRoutes, key = { it.id }) { route ->
            val watch = latestByRoute[route.id]
            val metrics = EventMetricSelector.choose(
                route.distanceM,
                route.elevationGainM,
                route.elevationLossM,
                watch?.distanceM,
                watch?.elevationGainM,
                watch?.elevationLossM,
            )
            val baseConfiguration = androidx.compose.ui.platform.LocalViewConfiguration.current
            val slowConfiguration = remember(baseConfiguration) { object : androidx.compose.ui.platform.ViewConfiguration by baseConfiguration { override val longPressTimeoutMillis: Long = HIKE_LONG_PRESS_MS } }
            CompositionLocalProvider(androidx.compose.ui.platform.LocalViewConfiguration provides slowConfiguration) {
            Card(Modifier.fillMaxWidth().combinedClickable(onClick = { open(route.id) }, onLongClickLabel = "Действия с хайком", onLongClick = { deleteTarget = route; confirmDelete = false })) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(route.title, fontWeight = FontWeight.Bold)
                            Text(
                                watch?.startedAt?.let(::dateFromMs) ?: "—",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (watch?.startedAt != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                listOfNotNull(
                                    metrics.distanceM?.let(::km),
                                    metrics.elevationGainM?.let { "+${it.roundToInt()} м" },
                                    watch?.elapsedSeconds?.let(::duration),
                                ).joinToString(" · ").ifBlank { "Показатели пока не добавлены" },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        val impression = watch?.enjoyment
                        Column(horizontalAlignment = Alignment.End) {
                            Text(impression?.let { "$it / 5" } ?: "—", color = Forest, fontWeight = FontWeight.Bold)
                            Text("Мои впечатления", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (watch == null || route.sourceUrl.isNullOrBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (watch == null) AssistChip(
                                onClick = { watchTargetId = route.id; pickWatch.launch(watchFileTypes) },
                                label = { Text("Добавить часы") },
                                leadingIcon = { Icon(Icons.Default.Watch, null) },
                            )
                            if (route.sourceUrl.isNullOrBlank()) AssistChip(
                                onClick = { missingLinkRoute = route },
                                label = { Text("Добавить AllTrails") },
                                leadingIcon = { Icon(Icons.Default.Link, null) },
                            )
                        }
                    }
                }
            }
        }
    }

    }
    deleteTarget?.let { target ->
        DeleteHikeDialog({ deleteTarget = null }) { vm.deleteEvent(target.id) {}; deleteTarget = null }
    }
    if (showCreate) CreateEventDialog(
        onDismiss = { showCreate = false },
        busy = busy,
        onCreate = { title, link, file -> vm.createEvent(title, link, file) { showCreate = false } },
    )
    missingLinkRoute?.let { route ->
        val activity = latestByRoute[route.id] ?: ActivityEntity(title=route.title, sourceFileName="", sourceFormat="", trackEncoded="", distanceM=0.0, elevationGainM=0.0, elevationLossM=0.0)
        RouteSearchDialog(route, activity, vm) { missingLinkRoute = null }
    }
}

@Composable
private fun DeleteHikeDialog(dismiss: () -> Unit, delete: () -> Unit) {
    ImeAlertDialog(onDismissRequest = dismiss, title = { Text("Удалить хайк?") },
        text = { Text("Трек, анкета и анализы этого хайка будут удалены.") },
        confirmButton = { Button(onClick = delete, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}

@Composable
private fun CreateEventDialog(onDismiss: () -> Unit, busy: Boolean = false, onCreate: (String, String, Uri?) -> Unit) {
    var title by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var watchFile by remember { mutableStateOf<Uri?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    val pickWatch = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { watchFile = it }
    ImeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый хайк") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true)
                OutlinedTextField(link, { link = it; localError = null }, Modifier.fillMaxWidth(), label = { Text("Ссылка AllTrails") }, singleLine = true)
                OutlinedButton(onClick = { pickWatch.launch(watchFileTypes) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Watch, null)
                    Spacer(Modifier.width(8.dp))
                    Text(watchFile?.lastPathSegment?.substringAfterLast('/') ?: "Добавить GPX / TCX с часов")
                }
                InfoHint("Файл часов", "Дата и фактические показатели будут взяты только из файла часов.")
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (link.isBlank() && watchFile == null) localError = "Выбери файл часов или ссылку"
                else if (link.isNotBlank() && com.mayushi.trailsense.data.AllTrailsImport.parseSharedText(link) == null) localError = "Некорректная ссылка AllTrails"
                else onCreate(title, link, watchFile)
            }, enabled = !busy) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun AddAllTrailsDialog(route: RouteEntity, onDismiss: () -> Unit, save: (RouteEntity) -> Unit) {
    var link by remember { mutableStateOf(route.sourceUrl.orEmpty()) }
    ImeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Маршрут на AllTrails") },
        text = { OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("Ссылка") }, singleLine = true) },
        confirmButton = { Button(onClick = { save(route.copy(sourceUrl = link.trim())) }, enabled = link.contains("alltrails.com", true)) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun AttachSharedFileDialog(uri: Uri, routes: List<RouteEntity>, vm: AppViewModel) {
    var selectedRouteId by remember { mutableStateOf<Long?>(routes.firstOrNull()?.id) }
    ImeAlertDialog(
        onDismissRequest = vm::dismissSharedFile,
        title = { Text("К какому хайку добавить файл?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(uri.lastPathSegment?.substringAfterLast('/') ?: "Файл с часов")
                OutlinedButton(onClick = { vm.createEvent("", "", uri) { vm.dismissSharedFile() } }) { Text("Создать отдельный хайк из файла") }
                if (routes.isNotEmpty()) RouteChoice(routes, selectedRouteId) { selectedRouteId = it }
            }
        },
        confirmButton = { Button(onClick = { selectedRouteId?.let(vm::consumeSharedFile) }, enabled = selectedRouteId != null) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = vm::dismissSharedFile) { Text("Отмена") } },
    )
}

@Composable
fun RouteEventDetail(
    id: Long,
    vm: AppViewModel,
    onDeleted: () -> Unit,
    editRequested: Boolean = false,
    onEditRequestConsumed: () -> Unit = {},
    deleteRequested: Boolean = false,
    onDeleteRequestConsumed: () -> Unit = {},
    selectedRecordingId: Long? = null,
) {
    val route by vm.route(id).collectAsStateWithLifecycle(null)
    val linkedActivities by vm.activitiesForRoute(id).collectAsStateWithLifecycle(emptyList())
    val currentRoute = route ?: return EmptyState("Загрузка", "")
    val watchActivity = if(selectedRecordingId!=null) linkedActivities.firstOrNull{it.id==selectedRecordingId} else linkedActivities.firstOrNull { it.startedAt != null } ?: linkedActivities.firstOrNull()
    val aiShort by vm.latestAi(if(watchActivity != null) "ACTIVITY" else "ROUTE", watchActivity?.id ?: id, "SHORT_ROUTE").collectAsStateWithLifecycle(null)
    val aiDeep by vm.latestAi(if(watchActivity != null) "ACTIVITY" else "ROUTE", watchActivity?.id ?: id, "DEEP_ROUTE").collectAsStateWithLifecycle(null)
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showTrackTools by remember { mutableStateOf(false) }
    var searchRoute by remember { mutableStateOf(false) }
    var autoSearch by remember { mutableStateOf(false) }
    var showAnalysisChoice by remember { mutableStateOf(false) }
    val pickWatch = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importWatchFile(id, it) } }
    val context = LocalContext.current
    LaunchedEffect(deleteRequested) { if (deleteRequested) { showDeleteConfirmation = true; onDeleteRequestConsumed() } }
    LaunchedEffect(editRequested) {
        if (editRequested) {
            showEditDialog = true
            onEditRequestConsumed()
        }
    }

    val detailScroll = rememberScrollState()
    var viewportTop by remember { mutableFloatStateOf(0f) }
    // This viewport already sits below Scaffold's topbar. One eager page keeps all drafts composed.
    Box(Modifier.fillMaxSize().onGloballyPositioned { viewportTop = it.positionInRoot().y }) {
    Column(
        Modifier.fillMaxSize().verticalScroll(detailScroll).padding(16.dp).testTag("recording-detail-${watchActivity?.id ?: 0}"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(currentRoute.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        watchActivity?.let { ManualHikeDate(it, vm) }

        if (watchActivity == null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Добавь файл часов", fontWeight = FontWeight.Bold)
                    InfoHint("Файл часов", "Дата, трек, расстояние, набор и время будут рассчитаны локально.")
                    Button(onClick = { pickWatch.launch(watchFileTypes) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Watch, null); Spacer(Modifier.width(8.dp)); Text("Выбрать GPX / TCX") }
                }
            }
        } else {
            val points = remember(watchActivity.trackEncoded) { TrackCodec.decode(watchActivity.trackEncoded) }
            if (points.size >= 2) {
                TopographicTrackMap(points, Modifier.fillMaxWidth(), mapHeight = 260.dp, pois = remember(watchActivity.pointsOfInterest) { runCatching { com.mayushi.trailsense.domain.HikeAnnotations.pois(watchActivity.pointsOfInterest) }.getOrDefault(emptyList()) })
                ElevationProfile(points, Modifier.fillMaxWidth(), displayedRecordedDistance(watchActivity,points))
            }
            FlowMetrics(watchActivity)
            OutlinedButton(onClick = { showTrackTools = !showTrackTools }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Text("Редактировать трек", fontWeight = FontWeight.Normal)
                Icon(if(showTrackTools) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Коррекция записи")
            }
            if (showTrackTools) {
                TrackCorrectionPanel(watchActivity, vm)
                OutlinedButton(onClick = { pickWatch.launch(watchFileTypes) }) { Text("Заменить файл часов", fontWeight = FontWeight.Normal) }
            }
            if (watchActivity.trackCorrectionMode == "INFERRED_START") {
                Text(watchActivity.trackCorrectionNote.substringBefore("\n"), color = Forest)
                InfoHint("О восстановлении", "Исходная запись и показатели часов сохранены отдельно. Добавленное начало — оценка. Неизвестные время и набор не подставляются; итог может быть неполным. Коррекцию можно отменить кнопкой «Вернуть исходный трек».")
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                val url = currentRoute.sourceUrl
                if (url.isNullOrBlank()) { searchRoute = true; autoSearch = false }
                else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }, modifier = Modifier.weight(1f)) {
                Text(if (currentRoute.sourceUrl.isNullOrBlank()) "Маршрут на AllTrails" else "AllTrails · ${currentRoute.title} ↗", fontWeight = FontWeight.Normal)
            }
            IconButton(onClick = { searchRoute = true; autoSearch = false }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Edit, "Изменить маршрут AllTrails") }
        }
        if (watchActivity != null) {
            WatchDetails041(watchActivity,vm)
            HikeQuestionnaire037(currentRoute, watchActivity, vm, history = watchActivity.questionnaireHistory,
                listScrollState = detailScroll, viewportTopInRoot = viewportTop,
                suggestedSleepHours = watchActivity.sleepOcrMinutes?.div(60.0),
                onSave = { r, a, ids, history, callback -> vm.saveHikeQuestionnaire(r, a.copy(questionnaireHistory=history), ids, emptyList(), callback) })
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Анализ Mayushi", Modifier.weight(1f))
            InfoHint("Анализ Mayushi", "Отправь показатели этого хайка и выбранный контекст на анализ, когда захочешь. Готовые ответы остаются здесь. Новые задания не создаются автоматически.")
        }
        val analysis = aiDeep ?: aiShort
        if (analysis != null) AiCard(analysis.response, analysis.promptTokens, analysis.completionTokens)
        OutlinedButton(onClick = { showAnalysisChoice = true }, Modifier.fillMaxWidth(), enabled = watchActivity != null) {
            Icon(Icons.Default.AutoAwesome, null)
            Spacer(Modifier.width(8.dp))
            Text("Отправить на анализ", fontWeight = FontWeight.Normal)
        }


    }

    }
    if (showAnalysisChoice && watchActivity != null) HikeAnalysisChoice(watchActivity.id, vm) { showAnalysisChoice = false }
    if (searchRoute && watchActivity != null) RouteSearchDialog(currentRoute, watchActivity, vm, autoStart = autoSearch) { searchRoute = false }
    if (searchRoute && watchActivity == null) RouteSearchDialog(currentRoute, ActivityEntity(title=currentRoute.title, sourceFileName="", sourceFormat="", trackEncoded="", distanceM=0.0, elevationGainM=0.0, elevationLossM=0.0), vm) { searchRoute = false }
    if (showEditDialog) EditHikeDialog(currentRoute, vm, { showEditDialog = false })
    if (showDeleteConfirmation) ImeAlertDialog(
        onDismissRequest = { showDeleteConfirmation = false },
        icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Удалить хайк?") },
        text = { Text("Будут удалены трек, анкета, календарные привязки к анализу и готовые анализы этого хайка.") },
        confirmButton = { Button(onClick = { showDeleteConfirmation = false; vm.deleteEvent(id, onDeleted) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("Отмена") } },
    )
}

@Composable
private fun EditHikeDialog(route: RouteEntity, vm: AppViewModel, dismiss: () -> Unit) {
    var edit by remember(route.updatedAt) { mutableStateOf(route) }
    ImeAlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Изменить хайк") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(edit.title, { edit = edit.copy(title = it) }, Modifier.fillMaxWidth(), label = { Text("Название") })
                OutlinedTextField(edit.sourceUrl.orEmpty(), { edit = edit.copy(sourceUrl = it) }, Modifier.fillMaxWidth(), label = { Text("Ссылка AllTrails") })
            }
        },
        confirmButton = { Button(onClick = { vm.saveRoute(edit.copy(sourceLabel=edit.sourceLabel + if(edit.title != route.title) "; CUSTOM_TITLE" else "")); dismiss() }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ManualHikeDate(activity: ActivityEntity, vm: AppViewModel) {
    var choosing by remember { mutableStateOf(false) }
    var selected by remember(activity.startedAt) { mutableStateOf(activity.startedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() } ?: java.time.LocalDate.now()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(activity.startedAt?.let(::dateFromMs) ?: "—", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = { choosing = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.EditCalendar, "Исправить дату", Modifier.size(18.dp)) }
    }
    if (choosing) ImeAlertDialog(onDismissRequest = { choosing = false }, title = { Text("Настоящий день похода") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { DateButton(selected) { selected = it }; TextButton(onClick = { vm.setManualDate(activity.id, null); choosing = false }) { Text("Вернуть дату файла") } } },
        confirmButton = { Button(onClick = { vm.setManualDate(activity.id, selected); choosing = false }) { Text("Подтвердить дату") } },
        dismissButton = { TextButton(onClick = { choosing = false }) { Text("Отмена") } })
}

@Composable
private fun CompanionSummary(activity: ActivityEntity, vm: AppViewModel) {
    val companions by vm.companions.collectAsStateWithLifecycle()
    val ids by vm.companionIds(activity.id).collectAsStateWithLifecycle(emptyList())
    val names = companions.filter { it.id in ids }.map { it.name }
    var editing by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { editing = true }) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Путники", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                IconButton(onClick = { editing = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Edit, "Изменить путников", Modifier.size(18.dp)) }
            }
            Text(if (names.isEmpty()) "Шла одна" else names.joinToString(" · "))
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().toggleable(value = activity.heavyBackpack, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = { vm.setHeavyBackpack(activity.id, it) }), verticalAlignment = Alignment.CenterVertically) {
                Text("Тяжёлый рюкзак ≈15 кг", Modifier.weight(1f))
                Switch(activity.heavyBackpack, onCheckedChange = null)
            }
        }
    }
    if (editing) CompanionPicker041(vm,ids.toSet(),{editing=false}) { chosen -> vm.saveCompanions(activity.id,chosen);editing=false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackCorrectionPanel(activity: ActivityEntity, vm: AppViewModel) {
    val original = remember(activity.originalTrackEncoded, activity.trackEncoded) {
        TrackCodec.decode(activity.originalTrackEncoded ?: activity.trackEncoded)
    }
    val trimBaseline = remember(activity.trackEncoded) { TrackCodec.decode(activity.trackEncoded) }
    val cumulative = remember(trimBaseline, activity.distanceM) { com.mayushi.trailsense.domain.TrackDistanceAxis.build(trimBaseline, displayedRecordedDistance(activity,trimBaseline)).meters }
    val totalKm = (cumulative.lastOrNull() ?: 0.0).div(1000).toFloat()
    if (totalKm <= 0f || original.size < 2) return
    var range by remember(activity.id, totalKm) { mutableStateOf(0f..totalKm) }
    var showRouting by remember { mutableStateOf(false) }
    if (showRouting) RecoveryRouteDialog(original, recordedDistance(activity), { vm.recoverAlongTrails(activity.id, it) }) { showRouting = false }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TopographicTrackMap(trimBaseline, Modifier.fillMaxWidth(), range.start / totalKm..range.endInclusive / totalKm, mapHeight = 210.dp, distanceM = displayedRecordedDistance(activity,trimBaseline))
            RangeSlider(value = range, onValueChange = { if (it.endInclusive > it.start) range = it }, valueRange = 0f..totalKm)
            Text("Оставить %.2f–%.2f км (всего %.2f км)".format(range.start, range.endInclusive, totalKm))
            Text("Будет обрезано: %.2f км".format(range.start + totalKm - range.endInclusive))
            Button(onClick = { vm.trimTrack(activity.id, range.start.toDouble() / totalKm, range.endInclusive.toDouble() / totalKm) }, Modifier.fillMaxWidth(), enabled = range.start > 0f || range.endInclusive < totalKm) {
                Icon(Icons.Default.ContentCut, null); Spacer(Modifier.width(8.dp)); Text("Обрезать и пересчитать")
            }
            OutlinedButton(onClick = { showRouting = true }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Restore, null); Spacer(Modifier.width(8.dp)); Text("Восстановить начало по тропам")
            }
            InfoHint("Коррекция записи", "Обрезка меняет показанный участок и пересчитывает его показатели. Восстановленное начало — оценка, а не запись часов; неизвестные время и высота не выдумываются. Исходные точки и показатели часов сохраняются: «Вернуть исходный трек» отменяет коррекцию.")
            if (activity.trackCorrectionMode != "NONE") TextButton(onClick = { vm.resetTrackCorrection(activity.id) }, Modifier.fillMaxWidth()) { Text("Вернуть исходный трек") }
        }
    }
}

@Composable
private fun StringDropdown(label: String, values: List<String>, selected: String, set: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { expanded = true }, Modifier.fillMaxWidth()) { Text(selected.ifBlank { "Выбрать" }) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { set(value); expanded = false }) }
        }
    }
}

private fun paceSetterLabel(value: String): String = when (value) {
    "SELF" -> "Я"
    "MIXED" -> "Смешанный"
    "COMPANION" -> "Путник"
    else -> value
}
