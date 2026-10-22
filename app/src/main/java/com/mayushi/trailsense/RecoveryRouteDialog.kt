package com.mayushi.trailsense

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mayushi.trailsense.domain.*
import com.mayushi.trailsense.network.OsmTrailClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun RecoveryRouteDialog(original: List<TrackPoint>, distanceM: Double, save: (List<TrackPoint>) -> Unit, dismiss: () -> Unit) {
    var manual by remember(original) { mutableStateOf(false) }
    var start by remember(original) { mutableStateOf(original.lastOrNull()) }
    var estimate by remember(original) { mutableStateOf<RecoveryEstimator.Estimate?>(null) }
    var pending by remember(original) { mutableStateOf<MissingStartRecovery.ManualRoute?>(null) }
    var error by remember(original) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var usedTrails by remember(original) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val autoRoute = remember(original) { runCatching { MissingStartRecovery.automatic(original) } }
    val trackRoute = remember(original, start, manual) {
        if (manual) runCatching { MissingStartRecovery.manual(original, requireNotNull(start)) } else null
    }
    val validRecording = remember(original) { runCatching { MissingStartRecovery.validateRecording(original) }.isSuccess }
    val trackFeasible = trackRoute?.isSuccess == true
    val area = start?.let { point -> original.firstOrNull()?.let { runCatching { OsmTrailClient.Area.from(point,it) }.getOrNull() } }

    fun accept(route: List<TrackPoint>) {
        // The existing save callback takes only the prefix; it appends the immutable original itself.
        estimate = RecoveryEstimator.estimate(route, original)
    }
    fun propose(route: MissingStartRecovery.ManualRoute) {
        if (route.needsConfirmation) pending = route else accept(route.points)
    }
    fun buildTrack() {
        error = null; estimate = null; usedTrails = false
        if (manual) {
            trackRoute?.fold(::propose, { error = it.message ?: "Не удалось построить по записи. Измени точку или построй по тропам." })
        } else {
            autoRoute.fold(::accept, { error = it.message ?: "Недостаточно данных. Выбери старт вручную." })
        }
    }
    fun buildTrails() {
        // Trails are a deliberate manual fallback, never part of automatic recovery.
        val selected = start ?: return
        val requestedArea = area ?: return
        if (!manual || trackFeasible || !validRecording || loading) return
        scope.launch {
            loading = true; error = null; estimate = null; usedTrails = true
            try {
                val fetched = OsmTrailClient(context).fetchResult(requestedArea)
                val route = withContext(Dispatchers.Default) { MissingStartRecovery.trails(original,selected,fetched.graph) }
                propose(route)
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: Exception) { error = e.message ?: "Тропы не загружены. Повтори позже." }
            finally { loading = false }
        }
    }
    fun selectTab(value: Boolean) {
        if (loading) return
        manual = value; estimate = null; pending = null; error = null; usedTrails = false
        start = original.lastOrNull()
    }

    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val maxDialogHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .94f).dp
        Surface(Modifier.fillMaxWidth().heightIn(max = maxDialogHeight).padding(horizontal = 8.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Пропущенный старт", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        InfoHint("Восстановление", "Пропущенное начало восстановим по обратному участку записи. Добавленная часть будет отмечена как восстановленная.")
                    }
                    TabRow(selectedTabIndex = if(manual) 1 else 0) {
                        Tab(selected = !manual, onClick = { selectTab(false) }, enabled = !loading, text = { Text("Автоматически") })
                        Tab(selected = manual, onClick = { selectTab(true) }, enabled = !loading, text = { Text("Выбрать старт") })
                    }
                    if (manual) Text("Нажми на карте настоящий старт.", style = MaterialTheme.typography.bodySmall)
                    TopographicTrackMap((estimate?.points ?: emptyList()) + original, Modifier.fillMaxWidth(), mapHeight = 260.dp, distanceM = distanceM, selectedPoint = start,
                        onPointSelected = { if (manual && !loading) { start=it; estimate=null; pending=null; error=null; usedTrails=false } })
                    Button(onClick = ::buildTrack, enabled = original.size >= 2 && start != null && !loading, modifier = Modifier.fillMaxWidth()) {
                        Text(if(manual) "Построить по треку" else "Достроить маршрут")
                    }
                    if (manual) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            OutlinedButton(onClick = ::buildTrails, enabled = !trackFeasible && validRecording && area != null && !loading, modifier = Modifier.weight(1f)) {
                                Text(if(loading) "Загрузка троп…" else "Построить по тропам")
                            }
                            InfoHint("Поиск троп", "Для поиска троп передаётся только область карты, без полного трека, времени, пульса и анкеты.")
                        }
                        if (!trackFeasible && estimate == null && error == null) Text(trackRoute?.exceptionOrNull()?.message ?: "Выбери старт на карте.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!trackFeasible && area == null) Text("Старт слишком далеко для поиска троп. Выбери точку ближе к началу записи.", style = MaterialTheme.typography.bodySmall)
                    }
                    if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    estimate?.let { e ->
                        Text(RecoveryEstimator.summary(e), style = MaterialTheme.typography.bodyMedium)
                        if (usedTrails) Text("OSM © contributors · ODbL", style = MaterialTheme.typography.labelSmall)
                    }
                }
                estimate?.let { e ->
                    Button(onClick = { save(e.points); dismiss() }, enabled = !loading && pending == null, modifier = Modifier.fillMaxWidth()) { Text("Сохранить восстановленное начало") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text("Отмена") }
                }
            }
        }
    }
    pending?.let { route ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Точка вне тропы") },
            text = { Text(buildString {
                if (route.connectorM > 5.0) append("От выбранной точки до тропы примерно ${route.connectorM.roundToInt()} м. Этот участок будет приблизительным. ")
                if (route.endConnectorM > 5.0) append("До начала записи ещё примерно ${route.endConnectorM.roundToInt()} м: соединение тоже будет приблизительным. ")
                append("Продолжить?")
            }) },
            confirmButton = { TextButton(onClick = { accept(route.points); pending = null }) { Text("Продолжить") } },
            dismissButton = { TextButton(onClick = { pending = null; estimate = null }) { Text("Изменить точку") } })
    }
}
