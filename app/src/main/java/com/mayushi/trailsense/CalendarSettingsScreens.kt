package com.mayushi.trailsense

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mayushi.trailsense.data.CalendarContextEntity
import com.mayushi.trailsense.domain.PillPhase
import com.mayushi.trailsense.domain.PillPlan
import com.mayushi.trailsense.domain.PillSchedule
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun ContextCalendarScreen(vm: AppViewModel) {
    val planEntity by vm.pillPlan.collectAsStateWithLifecycle()
    val contexts by vm.calendarContexts.collectAsStateWithLifecycle()
    val pillEvents by vm.pillEvents.collectAsStateWithLifecycle()
    var showDeviation by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    var pillStart by remember { mutableStateOf(today) }
    var showContextDialog by remember { mutableStateOf(false) }
    var displayedMonth by remember { mutableStateOf(YearMonth.from(today)) }
    var selectedDate by remember { mutableStateOf(today) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Календарь контекста", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Стресс, сильный недосып, свободные заметки и назначенная схема попадут в анализы походов на соответствующие даты.")
                    Button(onClick = { selectedDate = today; showContextDialog = true }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Добавить запись")
                    }
                }
            }
        }

        item {
            GraphicalMonthCalendar(
                month = displayedMonth,
                today = today,
                contexts = contexts,
                pillPlan = planEntity,
                pillEvents = pillEvents,
                previousMonth = { displayedMonth = displayedMonth.minusMonths(1) },
                nextMonth = { displayedMonth = displayedMonth.plusMonths(1) },
                showToday = { displayedMonth = YearMonth.from(today); selectedDate = today },
                selectDay = { date ->
                    selectedDate = date
                    displayedMonth = YearMonth.from(date)
                    showContextDialog = true
                },
            )
        }

        item { SectionTitle("Назначенная схема") }
        if (planEntity == null) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Если хочешь, укажи первый день блока 84 + 7. Это календарь назначенной схемы, не медицинская рекомендация.")
                        DateButton(pillStart) { pillStart = it }
                        OutlinedButton(onClick = { vm.savePillStart(pillStart) }, Modifier.fillMaxWidth()) { Text("Сохранить начало блока") }
                    }
                }
            }
        } else {
            val plan = planEntity!!
            val day = PillSchedule.day(PillPlan(plan.anchorEpochDay, plan.activeDays, plan.breakDays), today)
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (day.phase == PillPhase.ACTIVE) "Активные таблетки" else "Перерыв без активных таблеток", fontWeight = FontWeight.Bold)
                        Text("День ${day.dayInPhase} · день ${day.dayInBlock} блока")
                        Text(today.format(DateTimeFormatter.ofPattern("d MMMM yyyy")), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (planEntity != null) item {
            SectionTitle("План / факт и отклонения")
            Text("План — сохранённая схема; факт отмечается тобой, не выводится из плана.")
            DateButton(selectedDate) { selectedDate = it }
            Row { TextButton(onClick = { vm.logPillTaken(selectedDate, true) }) { Text("Принята") }; TextButton(onClick = { vm.logPillTaken(selectedDate, false) }) { Text("Не принята") } }
            OutlinedButton(onClick = { showDeviation = true }, Modifier.fillMaxWidth()) { Text("Записать отклонение / сдвиг") }
            pillEvents.take(30).forEach { event ->
                Text("${LocalDate.ofEpochDay(event.epochDay)} · ${event.actualTaken?.let { if (it) "принята" else "не принята" } ?: "отклонение"} · план ${event.plannedEpochDay?.let(LocalDate::ofEpochDay) ?: "—"} · ${event.deviationPolicy ?: "без сдвига"} ${event.note}")
            }
        }
        item { SectionTitle("Стресс, сон и заметки") }
        if (contexts.isEmpty()) item { Text("Пока записей нет. Можно выбрать тип или оставить обычную заметку.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(contexts, key = { it.id }) { item ->
            ContextEventCard(item) { vm.deleteCalendarContext(item) }
        }
    }

    if (showDeviation) DeviationDialog(onDismiss = { showDeviation = false }) { planned, actual, policy, note -> vm.logPillDeviation(planned, actual, "BOUNDARY", policy, note); showDeviation = false }
    if (showContextDialog) CalendarContextDialog(
        initialDate = selectedDate,
        onDismiss = { showContextDialog = false },
        save = { start, end, type, severity, note ->
            vm.addCalendarContext(start, end, type, severity, note)
            showContextDialog = false
        },
    )
}

@Composable
private fun ContextEventCard(item: CalendarContextEntity, delete: () -> Unit) {
    val start = LocalDate.ofEpochDay(item.startEpochDay)
    val end = LocalDate.ofEpochDay(item.endEpochDay)
    val date = if (start == end) start.toString() else "$start — $end"
    Card(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(calendarContextLabel(item.type), fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Column {
                    Text(date)
                    item.severity?.let { Text("Интенсивность: $it / 5") }
                    if (item.note.isNotBlank()) Text(item.note)
                }
            },
            leadingContent = { Icon(calendarContextIcon(item.type), null) },
            trailingContent = { IconButton(onClick = delete) { Icon(Icons.Default.Delete, "Удалить") } },
        )
    }
}

@Composable
private fun CalendarContextDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    save: (LocalDate, LocalDate, String, Int?, String) -> Unit,
) {
    var start by remember(initialDate) { mutableStateOf(initialDate) }
    var end by remember(initialDate) { mutableStateOf(initialDate) }
    var type by remember { mutableStateOf("NOTE") }
    var severity by remember { mutableStateOf<Int?>(null) }
    var note by remember { mutableStateOf("") }
    val valid = end >= start && (type != "NOTE" || note.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Запись в календаре") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    "Заметка" to "NOTE",
                    "Сильный недосып" to "SEVERE_SLEEP_DEBT",
                    "Сильный стресс" to "HIGH_STRESS",
                ).forEach { (label, value) ->
                    FilterChip(
                        selected = type == value,
                        onClick = { type = value },
                        label = { Text(label) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("Начало", style = MaterialTheme.typography.labelMedium)
                DateButton(start) { start = it; if (end < it) end = it }
                Text("Окончание", style = MaterialTheme.typography.labelMedium)
                DateButton(end) { end = it }
                if (type != "NOTE") RatingField("Интенсивность", severity) { severity = it }
                OutlinedTextField(
                    note,
                    { note = it },
                    Modifier.fillMaxWidth(),
                    label = { Text(if (type == "NOTE") "Текст заметки" else "Что происходило (необязательно)") },
                    minLines = 3,
                )
                Text("Запись будет передана Mayushi только в компактном контексте анализа подходящего хайка.", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { Button(onClick = { save(start, end, type, severity, note) }, enabled = valid) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun CurrentSettingsScreen(vm: AppViewModel) {
    val prefs = vm.preferences()
    var sleep by remember { mutableStateOf(prefs.sleepNormHours.toString()) }
    var shortAuto by remember { mutableStateOf(prefs.shortAutoAnalysis) }
    var postAuto by remember { mutableStateOf(prefs.postHikeAutoAnalysis) }
    val pendingAi by vm.pendingAi.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val importResponse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importTelegramResponse) }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val saveRequest = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::saveAiQueue) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Mayushi через Telegram")
        Text("TrailSense создаёт самодостаточный JSON. Выбери Telegram и отправь его в этот чат.")
        Button(onClick = { vm.shareAiQueue(context) }, Modifier.fillMaxWidth(), enabled = pendingAi > 0 && !busy) {
            Icon(Icons.Default.Share, null)
            Spacer(Modifier.width(8.dp))
            Text(when {
                busy -> "Готовлю файл…"
                pendingAi > 0 -> "Отправить Mayushi ($pendingAi)"
                else -> "Очередь пуста"
            })
        }
        OutlinedButton(onClick = { saveRequest.launch("TrailSense-request.json") }, Modifier.fillMaxWidth(), enabled = pendingAi > 0 && !busy) { Text("Сохранить JSON локально") }
        OutlinedButton(onClick = { importResponse.launch(arrayOf("application/json", "text/json", "application/octet-stream")) }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Download, null)
            Spacer(Modifier.width(8.dp))
            Text("Импортировать ответ Mayushi")
        }

        HorizontalDivider()
        SectionTitle("Анализ")
        OutlinedTextField(sleep, { sleep = it }, Modifier.fillMaxWidth(), label = { Text("Личная норма сна, часы") }, singleLine = true)
        SwitchRow("Короткий анализ нового маршрута", shortAuto) { shortAuto = it }
        SwitchRow("Анализ после заполнения анкеты", postAuto) { postAuto = it }
        Button(onClick = { vm.saveAnalysisPreferences(sleep.replace(',', '.').toDoubleOrNull() ?: 9.0, shortAuto, postAuto) }, Modifier.fillMaxWidth()) { Text("Сохранить") }

        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            OutlinedButton(onClick = { notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }, Modifier.fillMaxWidth()) { Text("Разрешить уведомления календаря") }
        }

        HorizontalDivider()
        SectionTitle("Приватность")
        Text("Треки, анкеты, сон и календарь хранятся локально. В Telegram уходят компактные показатели и только выбранный тобой скриншот — без API-ключей и сырого GPS-трека. Топографическая подложка загружается с OpenTopoMap: сервис получает запросы плиток района, но не файл трека.")
    }
}

private fun calendarContextLabel(type: String) = when (type) {
    "SEVERE_SLEEP_DEBT" -> "Сильный недосып"
    "HIGH_STRESS" -> "Сильный стресс"
    else -> "Заметка"
}

private fun calendarContextIcon(type: String) = when (type) {
    "SEVERE_SLEEP_DEBT" -> Icons.Default.Bedtime
    "HIGH_STRESS" -> Icons.Default.Bolt
    else -> Icons.AutoMirrored.Filled.Notes
}
