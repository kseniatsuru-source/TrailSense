package com.mayushi.trailsense

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mayushi.trailsense.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun RepeatComparisonSetting() {
    val context = LocalContext.current
    val prefs = remember { RepeatRoutePreferences(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Сравнение повторных маршрутов",Modifier.weight(1f))
        Switch(enabled, { enabled=it; prefs.enabled=it })
    }
}
@Composable fun AutomaticRepeatScreen(vm: AppViewModel, routeId: Long? = null, analysisList: Boolean = false, showAnalyses: () -> Unit = {}, openRecording: ((Long, Long?) -> Unit)? = null, open: (Long) -> Unit) {
    if(analysisList) {
        ComparisonStoredList041(vm)
        return
    }
    val context = LocalContext.current
    val prefs = remember { RepeatRoutePreferences(context) }
    val activities by vm.activities.collectAsStateWithLifecycle()
    val routes by vm.routes.collectAsStateWithLifecycle()
    var excluded by remember { mutableStateOf(prefs.excluded()) }
    var groups by remember { mutableStateOf(emptyList<AutomaticRepeatRoutes.Group>()) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activities,routes,excluded) {
        loading=true
        groups=if(prefs.enabled) withContext(Dispatchers.Default) { AutomaticRepeatRoutes.discover(activities,routes,excluded) } else emptyList()
        loading=false
    }
    val filtered = groups.filter { g -> (routeId == null || g.rows.any { it.activity.routeId == routeId }) && g.title.contains(query,true) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { TextButton(onClick = showAnalyses) { Text("Анализы динамики") } }
        item { OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("Найти маршрут")},singleLine=true) }
        item { InfoHint("Сравнение", "Сравниваем совпадающую записанную часть пути: общий подъём без подхода и спуска либо целый маршрут. Названия сами по себе не подтверждают совпадение. Время участка включает паузы; время часов для всего хайка показано отдельно. Исправленные, оценочные и неполные записи могут не подходить для сравнения. Разница в цифрах не объясняет её причину.") }
        if(!prefs.enabled) item { Text("Включи «Сравнение повторных маршрутов» в настройках.") }
        if(loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if(!loading && filtered.isEmpty() && prefs.enabled) item { Text("Пока нет двух записей с сопоставимой геометрией и временем GPS.") }
        items(filtered,key={it.id}) { group ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(group.title,modifier=Modifier.fillMaxWidth().clickable { selected=if(selected==group.id) null else group.id },style=MaterialTheme.typography.titleMedium)
                    Text("${group.rows.size} прохождения · " + if(group.ascent) "общий подъём" else "общий маршрут")
                    if(selected == group.id || routeId != null) {
                        ComparisonHistory041(vm, group) { recording, route -> if(openRecording != null) openRecording(recording, route) else route?.let(open) }
                    }
                }
            }
        }
    }
}
