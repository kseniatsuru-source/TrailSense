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

@Composable
fun RecoveryRouteDialog(original: List<TrackPoint>, distanceM: Double, save: (List<TrackPoint>) -> Unit, dismiss: () -> Unit) {
    var start by remember { mutableStateOf<TrackPoint?>(null) }
    var estimate by remember { mutableStateOf<RecoveryEstimator.Estimate?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val area = start?.let { runCatching { OsmTrailClient.Area.from(it,original.first()) }.getOrNull() }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Пропущенный старт", style = MaterialTheme.typography.titleLarge)
                Text("Нажми на карте место, откуда начала идти до включения часов. Это не точка возврата.")
                TopographicTrackMap((estimate?.points ?: emptyList()) + original, Modifier.fillMaxWidth(), mapHeight = 280.dp, distanceM = distanceM, selectedPoint = start, onPointSelected = { if (!loading) { start = it; estimate = null; error = null } })
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if(start == null) Text("Карта остаётся сверху. Одним пальцем двигай, двумя меняй масштаб, коротким нажатием ставь маркер.")
                    area?.let { Text("OSM/Overpass (overpass-api.de; при сбое overpass.kumi.systems) получит только округлённую область ${it.disclosure()}. Возможен кэш до суток. Полный трек, время, пульс и анкета не отправляются.", style = MaterialTheme.typography.bodySmall) }
                    if(start != null && area == null) Text("Старт слишком далеко для небольшого запроса. Передвинь маркер ближе к началу записи.")
                    Button(onClick = {
                        scope.launch {
                            loading = true; error = null; estimate = null
                            try { val graph = OsmTrailClient(context).fetch(area!!); val path = graph.route(start!!,original.first()); estimate = RecoveryEstimator.estimate(path, original); start = path.first() }
                            catch(e: kotlinx.coroutines.CancellationException) { throw e }
                            catch(e: Exception) { error = e.message ?: "Тропы не загружены. Повтори позже." }
                            finally { loading = false }
                        }
                    }, enabled = area != null && !loading) { Text(if(loading) "Загрузка троп…" else "Построить по тропам OSM") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    estimate?.let { e ->
                        Text(RecoveryEstimator.summary(e), style = MaterialTheme.typography.titleMedium)
                        Text("Зелёный пунктир: оценка по графу OSM. Маркер привязан к ближайшему узлу (до 75 м); до записи может оставаться небольшой разрыв, прямое соединение не выдумывается.")
                        var details by remember { mutableStateOf(false) }
                        TextButton(onClick = { details = !details }) { Text("Как рассчитана оценка") }
                        if(details) Text("OSM © contributors, ODbL. Проходимость и актуальность троп не гарантируются. Высота берётся только с совпадающего участка записи в пределах 25 м, иначе неизвестна. Время оценивается по участкам этого же похода со схожим уклоном и направлением, без пауз и восстановленных точек; если данных мало, неизвестно.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { save(e.points); dismiss() }) { Text("Подтвердить оценку начала") }
                    }
                }
                TextButton(onClick = dismiss, Modifier.fillMaxWidth()) { Text("Отмена") }
            }
        }
    }
}
