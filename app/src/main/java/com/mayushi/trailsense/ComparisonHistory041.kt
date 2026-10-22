package com.mayushi.trailsense

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mayushi.trailsense.data.AnalysisResultEntity
import com.mayushi.trailsense.domain.*
import com.mayushi.trailsense.network.ComparisonAnalysis
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import org.json.JSONObject

private val Mint041 = Color(0xFFA4D9C0)
private val Graphite041 = Color(0xFF272D30)
private val Date041 = DateTimeFormatter.ofPattern("dd.MM.yyyy")

@Composable internal fun ComparisonStoredList041(vm: AppViewModel) {
    val analyses by vm.comparisonAnalyses.collectAsStateWithLifecycle(emptyList())
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if(analyses.isEmpty()) item { Text("Пока нет анализов динамики") }
        items(analyses.size, key = { analyses[it].resultKey }) { index -> ComparisonResult041(vm, analyses[index]) }
    }
}

@Composable internal fun ComparisonHistory041(vm: AppViewModel, group: AutomaticRepeatRoutes.Group, openRecording: (Long, Long?) -> Unit) {
    var bounds by remember(group.id) { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var newest by remember(group.id) { mutableStateOf(true) }
    var periodDialog by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var listOnly by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = listOnly) { listOnly = false }
    val analyses by vm.comparisonAnalyses.collectAsStateWithLifecycle(emptyList())
    val ids = group.rows.map { it.activity.id }.toSet()
    val scopedAnalyses = analyses.filter { result ->
        result.scopeKey.startsWith("v1:${if(group.ascent) "ASCENT" else "WHOLE"}:") &&
            result.scopeKey.substringAfterLast(':').split(',').mapNotNull(String::toLongOrNull).all { it in ids }
    }
    val selected = RepeatChartPeriod.filter(group.rows, if(bounds == null) "Всё" else "Период",
        bounds?.first ?: LocalDate.MIN, bounds?.second ?: LocalDate.MAX).let { if(newest) it.reversed() else it }
    if (listOnly) {
        TextButton(onClick = { listOnly = false }) { Icon(Icons.Default.ArrowBack, null); Text("К прохождениям") }
        SectionTitle("Анализы динамики")
        scopedAnalyses.forEach { ComparisonResult041(vm, it) }
        if(scopedAnalyses.isEmpty()) Text("Пока нет анализов динамики")
    } else {
        OutlinedButton(onClick = { periodDialog = true }, Modifier.fillMaxWidth().testTag("comparison-period")) {
            Text("Период: " + (bounds?.let { "${it.first.format(Date041)} – ${it.second.format(Date041)}" } ?: "Вся история"), Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, null)
        }
        Box {
            OutlinedButton(onClick = { sortMenu = true }, Modifier.fillMaxWidth().testTag("comparison-sort")) {
                Text("Сортировка: " + if(newest) "Сначала новые" else "Сначала старые", Modifier.weight(1f)); Icon(Icons.Default.ExpandMore, null)
            }
            DropdownMenu(sortMenu, { sortMenu = false }) {
                listOf(true to "Сначала новые", false to "Сначала старые").forEach { (value, title) ->
                    DropdownMenuItem(text = { Text(title) }, onClick = { newest = value; sortMenu = false })
                }
            }
        }
        Text("Время прохождения", color = Mint041, fontWeight = FontWeight.Bold)
        val max = RepeatChartPeriod.axisMaximum(selected.maxOfOrNull { it.seconds } ?: 0)
        if(selected.isEmpty()) Text("За этот период прохождений нет")
        Row { Spacer(Modifier.width(88.dp)); Box(Modifier.weight(1f)) { ComparisonTimeAxis041(max) } }
        selected.forEach { row ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(recordingDate(row.activity), Modifier.width(88.dp), style = MaterialTheme.typography.labelSmall)
                ComparisonTimeBar041(row.seconds, max, Modifier.weight(1f).height(22.dp))
            }
        }
        Button(onClick = { vm.requestComparisonAnalysis(group.title, group.ascent, selected) }, Modifier.fillMaxWidth().testTag("comparison-submit"), enabled = selected.size >= 2) {
            Text("Отправить прохождения на анализ")
        }
        SectionTitle("Прохождения")
        selected.forEach { row ->
            val metrics = remember(row.activity.updatedAt, row.start, row.end) { ComparisonAnalysis.sectionMetrics(row.activity, row.start, row.end) }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF203830))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(recordingDate(row.activity), color = Mint041); Text(recordingDuration(row.seconds), color = Color(0xFFE4E6E8))
                    }
                    ComparisonTimeAxis041(max)
                    ComparisonTimeBar041(row.seconds, max, Modifier.fillMaxWidth().height(16.dp))
                    Text("${if(group.ascent) "Общий подъём" else "Общий маршрут"} · пульс ${metrics.optDouble("average_hr_bpm").takeIf { it.isFinite() }?.toInt()?.let { "$it уд/мин" } ?: "нет измерения"}", color = Color(0xFFE4E6E8), style = MaterialTheme.typography.bodySmall)
                    Text("Каденс участка: ${metrics.optDouble("average_cadence_per_minute").takeIf { it.isFinite() }?.let { "%.1f /мин".format(it) } ?: "нет измерения"}", color = Color(0xFFE4E6E8), style = MaterialTheme.typography.bodySmall)
                    Text("Часы, весь хайк: ${row.activity.elapsedSeconds?.let(::recordingDuration) ?: "нет измерения"}", color = Color(0xFFE4E6E8), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { openRecording(row.activity.id, row.activity.routeId) }) { Text("Открыть хайк", color = Mint041) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Анализы динамики", Modifier.weight(1f), color = Mint041, fontWeight = FontWeight.Bold)
            TextButton(onClick = { listOnly = true }) { Text("Все анализы") }
        }
        scopedAnalyses.forEach { ComparisonResult041(vm, it) }
    }
    if(periodDialog) ComparisonPeriodDialog041(group.rows, bounds, { periodDialog = false }) { bounds = it; periodDialog = false }
}

@Composable private fun ComparisonTimeAxis041(max: Long) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp)) {
        RepeatChartPeriod.axisTicks(max).forEach { tick ->
            val position = when(tick) {
                0L -> Modifier.align(Alignment.CenterStart)
                max -> Modifier.align(Alignment.CenterEnd)
                else -> Modifier.offset(x = maxWidth * (tick.toFloat() / max) - 12.dp).align(Alignment.CenterStart)
            }
            Text(if(tick == 0L) "0" else "%d:%02d".format(tick / 3600, tick % 3600 / 60), style = MaterialTheme.typography.labelSmall, color = Mint041, modifier = position)
        }
    }
}
@Composable private fun ComparisonTimeBar041(seconds: Long, max: Long, modifier: Modifier) {
    Canvas(modifier) {
        RepeatChartPeriod.axisTicks(max).forEach { tick ->
            val x = size.width * tick.toFloat() / max
            drawLine(Mint041.copy(alpha = .18f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        }
        val width = size.width * seconds.toFloat() / max
        val hour = size.width * 3600f / max
        drawLine(Color(0xFF426754), Offset(0f, size.height / 2), Offset(width, size.height / 2), size.height * .6f)
        if(width > hour) drawLine(Color(0xFF87B89B), Offset(hour, size.height / 2), Offset(width, size.height / 2), size.height * .6f)
    }
}

@Composable private fun ComparisonPeriodDialog041(rows: List<AutomaticRepeatRoutes.Row>, bounds: Pair<LocalDate, LocalDate>?, dismiss: () -> Unit, apply: (Pair<LocalDate, LocalDate>?) -> Unit) {
    val dates = rows.mapNotNull { RepeatRouteDynamics.date(it.activity) }
    var from by remember { mutableStateOf(bounds?.first ?: dates.minOrNull() ?: LocalDate.now()) }
    var to by remember { mutableStateOf(bounds?.second ?: dates.maxOrNull() ?: LocalDate.now()) }
    var yearMenu by remember { mutableStateOf(false) }
    var monthMenu by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Период прохождений") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            TextButton(onClick = { apply(null) }) { Text("Вся история") }
            Text("Первая дата"); DateButton(from) { from = it }
            Text("Последняя дата включительно"); DateButton(to) { to = it }
            Box {
                TextButton(onClick = { yearMenu = true }) { Text("Выбрать год") }
                DropdownMenu(yearMenu, { yearMenu = false }) { dates.map { it.year }.distinct().sortedDescending().forEach { year ->
                    DropdownMenuItem(text = { Text(year.toString()) }, onClick = { from = LocalDate.of(year,1,1); to = LocalDate.of(year,12,31); yearMenu = false })
                } }
            }
            Box {
                TextButton(onClick = { monthMenu = true }) { Text("Выбрать месяц ${from.year}") }
                DropdownMenu(monthMenu, { monthMenu = false }) { (1..12).forEach { month ->
                    val ym = YearMonth.of(from.year, month)
                    DropdownMenuItem(text = { Text(ym.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, java.util.Locale("ru"))) },
                        onClick = { from = ym.atDay(1); to = ym.atEndOfMonth(); monthMenu = false })
                } }
            }
            if(from > to) Text("Начало периода должно быть не позже конца", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { apply(from to to) }, enabled = from <= to) { Text("Готово") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ComparisonResult041(vm: AppViewModel, result: AnalysisResultEntity) {
    var input by remember(result.responseJson) { mutableStateOf(JSONObject(result.responseJson).optJSONObject("_trailsense_comparison_scope")) }
    LaunchedEffect(result.jobId, result.inputRevision) { if(input == null) input = vm.comparisonInput(result.jobId) }
    var expanded by remember(result.resultKey) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val anchor = remember { BringIntoViewRequester() }
    val pageScope = rememberCoroutineScope()
    fun displayDate(raw: String) = runCatching { LocalDate.parse(raw).format(Date041) }.getOrDefault("дата не указана")
    val period = input?.let { "с ${displayDate(it.optString("period_start"))} по ${displayDate(it.optString("period_end"))}" } ?: "Период не указан"
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = { expanded = !expanded }, onLongClick = { delete = true }),
        colors = CardDefaults.cardColors(containerColor = Graphite041)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.bringIntoViewRequester(anchor), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(period, color = Mint041, fontWeight = FontWeight.Bold)
                    Text("${input?.optInt("recording_count") ?: result.scopeKey.substringAfterLast(':').split(',').size} прохождений", color = Color(0xFFE4E6E8))
                }
                IconButton(onClick = { expanded = !expanded }) { Icon(if(expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Анализ Mayushi", tint = Mint041) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Действия анализа", tint = Mint041) }
                    DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; delete = true }) }
                }
            }
            if(expanded) {
                CompositionLocalProvider(LocalContentColor provides Color(0xFFE4E6E8)) { RichAnalysisText(JSONObject(result.responseJson).optString("summary")) }
                IconButton(onClick = { expanded = false; pageScope.launch { anchor.bringIntoView() } }, Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(Icons.Default.ExpandLess, "Свернуть и вернуться к заголовку", tint = Mint041)
                }
            }
        }
    }
    if(delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Удалить анализ?") }, text = {
        Text("Прохождения $period\n\nУдалится только этот анализ. Хайки, анкеты и треки сохранятся.")
    }, confirmButton = { TextButton(onClick = { vm.deleteComparisonAnalysis(result.resultKey); delete = false }) { Text("Удалить", color = Color(0xFFE6A8B3)) } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("Отмена") } })
}
