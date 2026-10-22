package com.mayushi.trailsense

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneOffset

@Composable
fun HuaweiImport041(vm: AppViewModel) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {it?.let(vm::previewHuawei)}
    OutlinedButton(enabled=!busy,onClick={pick.launch(arrayOf("application/json","text/plain","application/octet-stream"))}) {Text("Импорт Huawei JSON")}
}

@Composable
fun HuaweiPreview041(vm: AppViewModel) {
    val preview by vm.huaweiPreview.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    preview?.let {p ->
        val choices=remember(p) {mutableStateMapOf<String,String>()}
        var reviewing by remember(p) {mutableStateOf(false)}
        val years=remember(p) {p.rows.filter {it.kind=="workouts"}.map {Instant.ofEpochMilli(it.start).atOffset(ZoneOffset.of(it.offset)).year}.distinct().sorted().joinToString(", ")}
        val targets=choices.values.mapNotNull {it.toLongOrNull()}
        val duplicateTargets=targets.size!=targets.toSet().size
        val ready=p.conflicts.all {choices.containsKey(it.key)} && !duplicateTargets
        // Dialog's WRAP_CONTENT measurement can use the full configuration height even
        // when its window starts below the status bar. Bound this real dialog to the
        // host window's visible system-bar-safe height, not an isolated test scaffold.
        val hostView = androidx.compose.ui.platform.LocalView.current
        val density = androidx.compose.ui.platform.LocalDensity.current
        val safeHeight = with(density) {
            val bars = androidx.core.view.ViewCompat.getRootWindowInsets(hostView)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            ((hostView.rootView.height.takeIf { it > 0 } ?: hostView.resources.displayMetrics.heightPixels) -
                (bars?.top ?: 0) - (bars?.bottom ?: 0)).toDp()
        }
        Dialog(onDismissRequest={if(!busy) vm.dismissHuawei()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=true,dismissOnBackPress=!busy,dismissOnClickOutside=false)) {
          Surface(Modifier.heightIn(max=safeHeight).fillMaxSize()) {Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(enabled=!busy,onClick=vm::dismissHuawei) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"Назад")}
                Text("Импорт данных Huawei",style=MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Хайки и данные сна",style=MaterialTheme.typography.titleMedium)
                    if(years.isNotBlank()) Text("$years годы")
                    Text("В файле: ${p.rows.count {it.kind=="workouts"}} хайков")
                }}
                listOf("Добавить новые" to p.count("NEW"),"Дополнить сохранённые" to p.count("ENRICH"),"Проверить совпадения" to p.count("CONFLICT")).forEach {(label,count) ->
                    Card(Modifier.fillMaxWidth()) {Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {Text(label,Modifier.weight(1f));Text(count.toString(),color=MaterialTheme.colorScheme.primary)}}
                }
                if(p.count("UNCHANGED")>0) Text("Уже импортированы, без изменений: ${p.count("UNCHANGED")}")
                Text("Данные сна",style=MaterialTheme.typography.titleMedium)
                Text("Сессий в файле: ${p.rows.count {it.kind=="sleeps"}} · новых: ${p.count("NEW_SLEEP")}")
                Text("Добавим сон, который удалось однозначно сопоставить с днём хайка.")
                Card(Modifier.fillMaxWidth()) {Text("Твои анкеты, заметки и привязки к AllTrails сохранятся. Исправления трека и исходные показатели останутся без изменений.",Modifier.padding(16.dp))}
                if(p.conflicts.isNotEmpty()) Button(enabled=!busy,onClick={reviewing=!reviewing},modifier=Modifier.fillMaxWidth()) {Text("Проверить совпадения")}
                if(reviewing) p.conflicts.forEach {row ->
                    HorizontalDivider()
                    Text("${row.title} · ${Instant.ofEpochMilli(row.start).atOffset(ZoneOffset.of(row.offset)).toLocalDate()}")
                    row.candidates.forEach {c -> FilterChip(choices[row.key]==c.id.toString(),{choices[row.key]=c.id.toString()},{Text("Дополнить: ${c.title} · ${c.startedAt?.let(::dateFromMs) ?: "без даты"}")},enabled=!busy)}
                    if(row.kind=="sleeps") FilterChip(choices[row.key]=="REPLACE",{choices[row.key]="REPLACE"},{Text("Обновить эту сессию сна")},enabled=!busy)
                    else if(!row.existingIdentity) FilterChip(choices[row.key]=="NEW",{choices[row.key]="NEW"},{Text("Это другой хайк — создать новый")},enabled=!busy)
                    FilterChip(choices[row.key]=="SKIP",{choices[row.key]="SKIP"},{Text("Пропустить")},enabled=!busy)
                }
                if(duplicateTargets) Text("Для разных тренировок выбери разные записи.",color=MaterialTheme.colorScheme.error)
                Button(enabled=!busy && ready,onClick={vm.importHuawei(choices.toMap())},modifier=Modifier.fillMaxWidth()) {Text(if(busy) "Импорт…" else "Импортировать")}
                if(!ready) Text("Сначала нужно разобрать спорные совпадения.")
            }
          }}
        }
    }
}
