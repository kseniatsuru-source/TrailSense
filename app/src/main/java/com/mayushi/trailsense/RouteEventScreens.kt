package com.mayushi.trailsense

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
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

@Composable
fun EventsScreen(vm: AppViewModel, open: (Long) -> Unit) {
    val routes by vm.routes.collectAsStateWithLifecycle()
    val activities by vm.activities.collectAsStateWithLifecycle()
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
            Card(Modifier.fillMaxWidth().clickable { open(route.id) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(route.title, fontWeight = FontWeight.Bold)
                            Text(
                                watch?.startedAt?.let { dateFromMs(it) + if (watch.manualDateEpochDay != null) " · вручную" else " · файл" } ?: if (watch != null) "Файл без даты · можно задать вручную" else "Дата появится из файла часов",
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

    if (showCreate) CreateEventDialog(
        onDismiss = { showCreate = false },
        onCreate = { title, link, file -> vm.createEvent(title, link, file); showCreate = false },
    )
    missingLinkRoute?.let { route ->
        AddAllTrailsDialog(route, onDismiss = { missingLinkRoute = null }) { updated ->
            vm.saveRoute(updated)
            missingLinkRoute = null
        }
    }
}

@Composable
private fun CreateEventDialog(onDismiss: () -> Unit, onCreate: (String, String, Uri?) -> Unit) {
    var title by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var watchFile by remember { mutableStateOf<Uri?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    val pickWatch = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { watchFile = it }
    AlertDialog(
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
                Text("Дата и фактические показатели будут взяты только из файла часов.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (link.isBlank() && watchFile == null) localError = "Выбери файл часов или ссылку"
                else if (link.isNotBlank() && com.mayushi.trailsense.data.AllTrailsImport.parseSharedText(link) == null) localError = "Некорректная ссылка AllTrails"
                else onCreate(title, link, watchFile)
            }) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun AddAllTrailsDialog(route: RouteEntity, onDismiss: () -> Unit, save: (RouteEntity) -> Unit) {
    var link by remember { mutableStateOf(route.sourceUrl.orEmpty()) }
    AlertDialog(
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
    AlertDialog(
        onDismissRequest = vm::dismissSharedFile,
        title = { Text("К какому хайку добавить файл?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(uri.lastPathSegment?.substringAfterLast('/') ?: "Файл с часов")
                OutlinedButton(onClick = { vm.createEvent("", "", uri); vm.dismissSharedFile() }) { Text("Создать отдельный хайк из файла") }
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
) {
    val route by vm.route(id).collectAsStateWithLifecycle(null)
    val linkedActivities by vm.activitiesForRoute(id).collectAsStateWithLifecycle(emptyList())
    val aiShort by vm.latestAi("ROUTE", id, "SHORT_ROUTE").collectAsStateWithLifecycle(null)
    val aiDeep by vm.latestAi("ROUTE", id, "DEEP_ROUTE").collectAsStateWithLifecycle(null)
    val currentRoute = route ?: return EmptyState("Загрузка", "")
    val watchActivity = linkedActivities.firstOrNull { it.startedAt != null } ?: linkedActivities.firstOrNull()
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showTrackTools by remember { mutableStateOf(false) }
    var searchRoute by remember { mutableStateOf(false) }
    val pickWatch = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importWatchFile(id, it) } }
    val context = LocalContext.current
    LaunchedEffect(editRequested) {
        if (editRequested) {
            showEditDialog = true
            onEditRequestConsumed()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(currentRoute.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        if (watchActivity == null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Добавь файл часов", fontWeight = FontWeight.Bold)
                    Text("Дата, трек, расстояние, набор и время будут рассчитаны локально.")
                    Button(onClick = { pickWatch.launch(watchFileTypes) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Watch, null); Spacer(Modifier.width(8.dp)); Text("Выбрать GPX / TCX") }
                }
            }
        } else {
            val points = remember(watchActivity.trackEncoded) { TrackCodec.decode(watchActivity.trackEncoded) }
            if (points.size >= 2) {
                TopographicTrackMap(points, Modifier.fillMaxWidth(), mapHeight = 260.dp, pois = remember(watchActivity.pointsOfInterest) { runCatching { com.mayushi.trailsense.domain.HikeAnnotations.pois(watchActivity.pointsOfInterest) }.getOrDefault(emptyList()) })
                ElevationProfile(points, Modifier.fillMaxWidth())
            }
            FlowMetrics(watchActivity)
            watchActivity.startedAt?.let { Text("Дата похода: ${dateFromMs(it)} · ${if (watchActivity.manualDateEpochDay != null) "задана вручную" else "из файла (не подтверждена)"}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
            ManualHikeDate(watchActivity, vm)
            if (watchActivity.trackCorrectionMode == "INFERRED_START") watchActivity.originalMetricsJson?.let { snapshot ->
                val recorded = com.mayushi.trailsense.data.OriginalWatchMetrics.decode(snapshot)
                Text("Записано часами: ${km(recorded.distanceM)} · +${recorded.elevationGainM.roundToInt()} м · −${recorded.elevationLossM.roundToInt()} м · ${recorded.elapsedSeconds?.let(::duration) ?: "—"}")
                Text("Добавлено приблизительно: ${km(watchActivity.distanceM-recorded.distanceM)} · +${(watchActivity.elevationGainM-recorded.elevationGainM).roundToInt()} м · итог выше — запись + оценка", color = Coral)
            }
            if (watchActivity.trackCorrectionMode != "NONE") {
                AssistChip(onClick = {}, label = { Text(watchActivity.trackCorrectionNote) }, leadingIcon = { Icon(Icons.Default.Tune, null) })
            }
        }

        currentRoute.sourceUrl?.takeIf { it.isNotBlank() }?.let { url ->
            Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                Spacer(Modifier.width(8.dp))
                Text("Маршрут на AllTrails")
            }
        }

        if (watchActivity != null) {
            OutlinedButton(onClick = { searchRoute = true }, Modifier.fillMaxWidth()) { Text("Найти и подтвердить AllTrails") }
            CompanionSummary(watchActivity, vm)
            OutlinedButton(onClick = { showTrackTools = !showTrackTools }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Tune, null)
                Spacer(Modifier.width(8.dp))
                Text(if (showTrackTools) "Скрыть настройку трека" else "Дополнительная настройка трека")
            }
            if (showTrackTools) {
                TrackCorrectionPanel(watchActivity, vm)
                OutlinedButton(onClick = { pickWatch.launch(watchFileTypes) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Sync, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Заменить файл часов")
                }
            }
            HikeQuestionnaire(currentRoute, watchActivity, vm)
        }

        SectionTitle("Анализ Mayushi")
        val analysis = aiDeep ?: aiShort
        if (analysis != null) AiCard(analysis.response, analysis.promptTokens, analysis.completionTokens)
        else Card(colors = CardDefaults.cardColors(containerColor = Coral.copy(alpha = .12f))) {
            Text("Анализ появится здесь после отправки очереди Mayushi.", Modifier.padding(16.dp))
        }
        OutlinedButton(onClick = { vm.requestRouteAnalysis(id, "DEEP_ROUTE") }, Modifier.fillMaxWidth(), enabled = watchActivity != null) {
            Icon(Icons.Default.AutoAwesome, null)
            Spacer(Modifier.width(8.dp))
            Text("Обновить персональный анализ")
        }

        HorizontalDivider()
        OutlinedButton(
            onClick = { showDeleteConfirmation = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Icon(Icons.Default.DeleteForever, null); Spacer(Modifier.width(8.dp)); Text("Удалить хайк") }
    }

    if (searchRoute && watchActivity != null) RouteSearchDialog(currentRoute, watchActivity, vm) { searchRoute = false }
    if (showEditDialog) EditHikeDialog(currentRoute, vm, { showEditDialog = false })
    if (showDeleteConfirmation) AlertDialog(
        onDismissRequest = { showDeleteConfirmation = false },
        icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Удалить хайк?") },
        text = { Text("Будут удалены трек, анкета, календарные привязки к анализу и готовые анализы этого хайка.") },
        confirmButton = { Button(onClick = { showDeleteConfirmation = false; vm.deleteEvent(id, onDeleted) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("Отмена") } },
    )
}

@Composable
private fun RouteSearchDialog(route: RouteEntity, activity: ActivityEntity, vm: AppViewModel, dismiss: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var link by remember { mutableStateOf(route.sourceUrl.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Поиск маршрута — вручную") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Автоматический список кандидатов пока не подключён. Ищи по региону, вершинам и ориентирам, не только по длине. Даже расхождение 7 км само по себе не исключает маршрут; факт часов остаётся главным.")
            Text("Часы: ${km(activity.distanceM)} · +${activity.elevationGainM.roundToInt()} м. Если точного варианта нет, посмотри соседние маршруты.")
            val points = remember(activity.trackEncoded) { TrackCodec.decode(activity.trackEncoded) }
            if (points.isNotEmpty()) Text("Локальный район GPS: %.2f–%.2f° N, %.2f–%.2f° E. Этот конспект не отправляется автоматически.".format(points.minOf { it.latitude },points.maxOf { it.latitude },points.minOf { it.longitude },points.maxOf { it.longitude }))
            OutlinedTextField(query, { query = it }, label = { Text("Регион / вершины / ориентиры") })
            OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode("site:alltrails.com/trail/ " + query)))) }.onFailure { error = "Не удалось открыть браузер: ${it.message}" } }, enabled = query.isNotBlank()) { Text("Открыть поиск в браузере") }
            Text("Наружу передаётся только введённый запрос, не GPS-трек. Ошибка интернета/доступа не означает, что совпадений нет.")
            OutlinedTextField(link, { link = it }, label = { Text("Выбранная ссылка AllTrails") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(onClick = { vm.saveRoute(route.copy(sourceUrl = link.trim())); dismiss() }, enabled = com.mayushi.trailsense.data.AllTrailsImport.parseSharedText(link) != null) { Text("Подтвердить выбранный маршрут") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}

@Composable
private fun EditHikeDialog(route: RouteEntity, vm: AppViewModel, dismiss: () -> Unit) {
    var edit by remember(route.updatedAt) { mutableStateOf(route) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Изменить хайк") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(edit.title, { edit = edit.copy(title = it) }, Modifier.fillMaxWidth(), label = { Text("Название") })
                OutlinedTextField(edit.sourceUrl.orEmpty(), { edit = edit.copy(sourceUrl = it) }, Modifier.fillMaxWidth(), label = { Text("Ссылка AllTrails") })
            }
        },
        confirmButton = { Button(onClick = { vm.saveRoute(edit); dismiss() }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ManualHikeDate(activity: ActivityEntity, vm: AppViewModel) {
    var choosing by remember { mutableStateOf(false) }
    var selected by remember(activity.startedAt) { mutableStateOf(activity.startedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() } ?: java.time.LocalDate.now()) }
    TextButton(onClick = { choosing = true }) { Text("Исправить дату вручную") }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("Настоящий день похода") },
        text = { Column { Text("Если все timestamps файла неверны, выбери известную тебе дату. Приложение её не угадывает."); DateButton(selected) { selected = it } } },
        confirmButton = { Button(onClick = { vm.setManualDate(activity.id, selected); choosing = false }) { Text("Подтвердить дату") } },
        dismissButton = { Column { TextButton(onClick = { choosing = false }) { Text("Отмена") }; TextButton(onClick = { vm.setManualDate(activity.id, null); choosing = false }) { Text("Вернуть дату файла") } } })
}

@Composable
private fun CompanionSummary(activity: ActivityEntity, vm: AppViewModel) {
    val companions by vm.companions.collectAsStateWithLifecycle()
    val ids by vm.companionIds(activity.id).collectAsStateWithLifecycle(emptyList())
    val names = companions.filter { it.id in ids }.map { it.name }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle("Путники")
        Text(if (names.isEmpty()) "Шла одна" else names.joinToString(" · "))
        Text("Темп в целом: ${paceSetterLabel(activity.paceSetter)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackCorrectionPanel(activity: ActivityEntity, vm: AppViewModel) {
    val original = remember(activity.originalTrackEncoded, activity.trackEncoded) {
        TrackCodec.decode(activity.originalTrackEncoded ?: activity.trackEncoded)
    }
    var range by remember { mutableStateOf(0f..1f) }
    var recoveryPreview by remember(activity.updatedAt) { mutableStateOf<List<com.mayushi.trailsense.domain.TrackPoint>?>(null) }
    var recoveryError by remember { mutableStateOf<String?>(null) }
    var manualJoin by remember { mutableStateOf(0.97f) }
    var manual by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Коррекция записи", fontWeight = FontWeight.Bold)
            Text("Выделенный персиковый участок останется после обрезки. Все цифры и анализ пересчитаются.", style = MaterialTheme.typography.bodySmall)
            TopographicTrackMap(original, Modifier.fillMaxWidth(), range, mapHeight = 210.dp)
            RangeSlider(value = range, onValueChange = { if (it.endInclusive - it.start >= .03f) range = it }, valueRange = 0f..1f)
            Text("Оставить ${(range.start * 100).roundToInt()}–${(range.endInclusive * 100).roundToInt()}% записи")
            Button(onClick = { vm.trimTrack(activity.id, range.start.toDouble(), range.endInclusive.toDouble()) }, Modifier.fillMaxWidth(), enabled = range.start > 0f || range.endInclusive < 1f) {
                Icon(Icons.Default.ContentCut, null); Spacer(Modifier.width(8.dp)); Text("Обрезать и пересчитать")
            }
            OutlinedButton(onClick = { runCatching { TrackCorrection.inferMissingOutAndBackStart(original) }.onSuccess { recoveryPreview = it; recoveryError = null }.onFailure { recoveryError = it.message } }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Restore, null); Spacer(Modifier.width(8.dp)); Text("Восстановить забытое начало туда-обратно")
            }
            recoveryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            recoveryPreview?.let { preview ->
                Text("Предпросмотр: зелёный — запись, персиковый — предположение. Время зеркального участка — оценка, не измерение.")
                TopographicTrackMap(preview, Modifier.fillMaxWidth(), mapHeight = 210.dp)
                SwitchRow("Выбрать точку возврата вручную", manual) { manual = it }
                if (manual) {
                    Slider(value = manualJoin, onValueChange = { manualJoin = it; runCatching { TrackCorrection.inferMissingOutAndBackStart(original, manualJoinIndex = (original.lastIndex * it).roundToInt()) }.onSuccess { recoveryPreview = it; recoveryError = null }.onFailure { recoveryError = it.message } }, valueRange = .5f.. .999f)
                    Text("Точка возврата ${(original.lastIndex * manualJoin).roundToInt()}; не далее 150 м от начала записи")
                }
                Button(onClick = { vm.inferMissingTrackStart(activity.id, if (manual) (original.lastIndex * manualJoin).roundToInt() else null); recoveryPreview = null }, enabled = recoveryError == null) { Text("Подтвердить оценку начала") }
                TextButton(onClick = { recoveryPreview = null; recoveryError = null; manual = false }) { Text("Отменить восстановление") }
            }
            Text("Восстановление работает, когда запись включили уже на пути вверх, а закончили вовремя у исходной точки: начало зеркально оценивается по обратному участку.", style = MaterialTheme.typography.labelSmall)
            if (activity.trackCorrectionMode != "NONE") TextButton(onClick = { vm.resetTrackCorrection(activity.id) }, Modifier.fillMaxWidth()) { Text("Вернуть исходный трек") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HikeQuestionnaire(route: RouteEntity, activity: ActivityEntity, vm: AppViewModel) {
    val companions by vm.companions.collectAsStateWithLifecycle()
    val savedIds by vm.companionIds(activity.id).collectAsStateWithLifecycle(emptyList())
    val savedSegments by vm.paceSegments(activity.id).collectAsStateWithLifecycle(emptyList())
    val busy by vm.busy.collectAsStateWithLifecycle()
    var expanded by remember(activity.id) {
        mutableStateOf(HikeQuestionnairePresentation.initiallyExpanded(activity.questionnaireCompletedAt))
    }
    var editRoute by remember(route.id) { mutableStateOf(route) }
    var edit by remember(activity.id) { mutableStateOf(activity) }
    LaunchedEffect(route.updatedAt, activity.updatedAt, expanded) {
        if (!expanded) {
            editRoute = route
            edit = activity
        }
    }
    var selected by remember(savedIds) { mutableStateOf(savedIds.toSet()) }
    var segments by remember(savedSegments) { mutableStateOf(savedSegments) }
    var newCompanion by remember { mutableStateOf("") }
    var customPace by remember { mutableStateOf(false) }
    var segmentRange by remember { mutableStateOf(0f..1f) }
    var segmentSetter by remember { mutableStateOf("Я") }
    var segmentNote by remember { mutableStateOf("") }
    var selectedSegment by remember { mutableStateOf<Int?>(null) }
    val sleepImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.saveSleepScreenshot(activity.id, it) } }
    val points = remember(activity.trackEncoded) { TrackCodec.decode(activity.trackEncoded) }
    val selectedNames = companions.filter { it.id in selected }.map { it.name }
    val setterOptions = (listOf("Я", "Смешанный", "Без спутников") + selectedNames).distinct()

    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Моя анкета", fontWeight = FontWeight.Bold)
                    if (!expanded) Text(
                        if (activity.questionnaireCompletedAt != null) "Заполнена · нажми, чтобы изменить" else "Не заполнена · разверни, когда будешь готова",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Свернуть" else "Развернуть") }
            }
            if (expanded) {
                SectionTitle("Впечатления")
                RatingField("Общая тяжесть маршрута", editRoute.overallDifficulty) { editRoute = editRoute.copy(overallDifficulty = it) }
                RatingField("Тяжесть набора", editRoute.climbDifficulty) { editRoute = editRoute.copy(climbDifficulty = it) }
                RatingField("Общая нагрузка", edit.overallEffort) { edit = edit.copy(overallEffort = it) }
                RatingField("Подъём", edit.climbEffort) { edit = edit.copy(climbEffort = it) }
                RatingField("Спуск", edit.descentEffort) { edit = edit.copy(descentEffort = it) }
                RatingField("Техничность", edit.technicality) { edit = edit.copy(technicality = it); editRoute = editRoute.copy(technicality = it) }
                RatingField("Экспозиция", editRoute.exposure) { editRoute = editRoute.copy(exposure = it) }
                RatingField("Мои впечатления", edit.enjoyment) { edit = edit.copy(enjoyment = it); editRoute = editRoute.copy(beauty = it) }
                ChoiceChips(listOf("Тяжелее обычного" to "HARD", "Обычно" to "NORMAL", "Легче обычного" to "EASY"), edit.anomaly) { edit = edit.copy(anomaly = it) }

                SectionTitle("Мои критерии и ориентиры")
                OutlinedTextField(edit.customRatings, { edit = edit.copy(customRatings = it) }, Modifier.fillMaxWidth(), label = { Text("Критерии: имя | оценка | максимум") }, minLines = 2)
                Text("Каждая строка — отдельный критерий с твоей шкалой, например: Красота озера | 8 | 10")
                OutlinedTextField(edit.pointsOfInterest, { edit = edit.copy(pointsOfInterest = it) }, Modifier.fillMaxWidth(), label = { Text("POI: название | широта | долгота") }, minLines = 2)
                Text("Каждая строка — именованный ориентир, координаты можно взять с карты. Маркеры появятся после сохранения.")
                SectionTitle("Сон — по моим ощущениям")
                NumberTextField("Часов сна", edit.sleepHours) { edit = edit.copy(sleepHours = it) }
                RatingField("Как я оцениваю сон", edit.sleepQuality) { edit = edit.copy(sleepQuality = it) }
                TriChoice("Выспалась?", listOf("Да" to true, "Нет" to false), edit.feltRested) { edit = edit.copy(feltRested = it) }
                OutlinedButton(onClick = { sleepImagePicker.launch(arrayOf("image/*")) }, Modifier.fillMaxWidth(), enabled = !busy) {
                    Icon(Icons.Default.Bedtime, null); Spacer(Modifier.width(8.dp)); Text(if (activity.sleepScreenshotPath == null) "Добавить скриншот сна с часов (необязательно)" else "Заменить скриншот сна")
                }
                Text("Mayushi прочитает видимые показатели часов, но твоя собственная оценка сна останется главной.", style = MaterialTheme.typography.labelSmall)

                SectionTitle("Путники и темп")
                companions.forEach { companion ->
                    FilterChip(
                        selected = companion.id in selected,
                        onClick = { selected = if (companion.id in selected) selected - companion.id else selected + companion.id },
                        label = { Text(companion.name) },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newCompanion, { newCompanion = it }, Modifier.weight(1f), label = { Text("Новый путник") }, singleLine = true)
                    IconButton(onClick = { vm.addCompanion(newCompanion); newCompanion = "" }) { Icon(Icons.Default.PersonAdd, "Добавить") }
                }
                StringDropdown("Чьей скоростью шли в целом", setterOptions, paceSetterLabel(edit.paceSetter)) { edit = edit.copy(paceSetter = it) }
                SwitchRow("Скорость отражает мой обычный темп", edit.representativeSpeed) { edit = edit.copy(representativeSpeed = it) }
                ChoiceChips(listOf("Свободный" to "FREE", "Чуть медленнее" to "SLIGHT", "Заметно медленнее" to "NOTABLE", "Сильно медленнее" to "MUCH"), edit.paceConstraint) { edit = edit.copy(paceConstraint = it, representativeSpeed = it == "FREE") }
                SwitchRow("Были ожидания / вынужденные остановки", edit.hadWaitingStops) { edit = edit.copy(hadWaitingStops = it) }

                OutlinedButton(onClick = { customPace = !customPace }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Route, null); Spacer(Modifier.width(8.dp)); Text(if (customPace) "Скрыть участки разного темпа" else "Указать участки разного темпа (необязательно)")
                }
                if (customPace && points.size >= 2) {
                    TopographicTrackMap(points, Modifier.fillMaxWidth(), selectedSegment?.let { index -> segments.getOrNull(index)?.let { it.startFraction.toFloat()..it.endFraction.toFloat() } } ?: segmentRange, mapHeight = 210.dp)
                    RangeSlider(value = segmentRange, onValueChange = { if (it.endInclusive - it.start >= .03f) { segmentRange = it; selectedSegment = null } })
                    Row {
                        OutlinedTextField((segmentRange.start*100).roundToInt().toString(), { s -> s.toFloatOrNull()?.takeIf { it in 0f..100f && it/100 < segmentRange.endInclusive }?.let { segmentRange = it/100..segmentRange.endInclusive; selectedSegment = null } }, Modifier.weight(1f), label = { Text("Начало %") })
                        OutlinedTextField((segmentRange.endInclusive*100).roundToInt().toString(), { s -> s.toFloatOrNull()?.takeIf { it in 0f..100f && it/100 > segmentRange.start }?.let { segmentRange = segmentRange.start..it/100; selectedSegment = null } }, Modifier.weight(1f), label = { Text("Конец %") })
                    }
                    StringDropdown("Кто задавал темп на участке", setterOptions, segmentSetter) { segmentSetter = it }
                    OutlinedTextField(segmentNote, { segmentNote = it }, Modifier.fillMaxWidth(), label = { Text("Заметка к участку") })
                    Button(onClick = {
                        segments = segments + PaceSegmentEntity(
                            activityId = activity.id,
                            startFraction = segmentRange.start.toDouble(),
                            endFraction = segmentRange.endInclusive.toDouble(),
                            paceSetterName = segmentSetter,
                            note = segmentNote.trim(),
                        )
                        segmentNote = ""
                    }, Modifier.fillMaxWidth()) { Text("Добавить участок") }
                    segments.forEachIndexed { index, segment ->
                        ListItem(
                            headlineContent = { Text("${(segment.startFraction * 100).roundToInt()}–${(segment.endFraction * 100).roundToInt()}% · ${segment.paceSetterName}", Modifier.clickable { selectedSegment = index }) },
                            supportingContent = { if (segment.note.isNotBlank()) Text(segment.note) },
                            trailingContent = { IconButton(onClick = { segments = segments.filterIndexed { i, _ -> i != index } }) { Icon(Icons.Default.Close, "Удалить") } },
                        )
                    }
                }

                OutlinedTextField(edit.comment, { edit = edit.copy(comment = it) }, Modifier.fillMaxWidth(), label = { Text("Комментарий к походу") }, minLines = 3)
                OutlinedTextField(editRoute.notes, { editRoute = editRoute.copy(notes = it) }, Modifier.fillMaxWidth(), label = { Text("Заметка о маршруте") }, minLines = 2)
                Button(onClick = {
                    vm.saveHikeQuestionnaire(editRoute, edit, selected, segments) { expanded = false }
                }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Сохранить анкету") }
            }
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
