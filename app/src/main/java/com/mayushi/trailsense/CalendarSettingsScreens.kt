package com.mayushi.trailsense

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mayushi.trailsense.data.*
import com.mayushi.trailsense.domain.*
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun ContextCalendarScreen(vm: AppViewModel, openHike: (Long) -> Unit = {}) {
    val activities by vm.activities.collectAsStateWithLifecycle()
    val routes by vm.routes.collectAsStateWithLifecycle()
    val contexts by vm.calendarContexts.collectAsStateWithLifecycle()
    val planEntity by vm.pillPlan.collectAsStateWithLifecycle()
    val versions by vm.pillPlanVersions.collectAsStateWithLifecycle()
    val pillEvents by vm.pillEvents.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val visibility = remember { CalendarVisibility(vm.getApplication()) }
    var visible by remember { mutableStateOf(visibility.visible()) }
    val medication = remember { MedicationPreferences(vm.getApplication()) }
    var mode by remember { mutableStateOf(medication.mode) }
    var dataOpen by remember { mutableStateOf(false) }
    var medicationOpen by remember { mutableStateOf(false) }
    var stressOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    var notesOpen by remember { mutableStateOf(false) }
    var exceptionsOpen by remember { mutableStateOf(false) }
    val hikesByDay = remember(activities) {
        activities.filter { it.routeId != null && (it.manualDateEpochDay != null || it.startedAt != null) }
            .groupBy { it.manualDateEpochDay ?: java.time.Instant.ofEpochMilli(it.startedAt!!).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay() }
    }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selectedDate by remember { mutableStateOf(today) }
    var showDay by remember { mutableStateOf(false) }
    var showContext by remember { mutableStateOf(false) }
    var contextType by remember { mutableStateOf("NOTE") }
    var editingContext by remember { mutableStateOf<CalendarContextEntity?>(null) }
    var editingPill by remember { mutableStateOf<PillEventEntity?>(null) }
    var showDeviation by remember { mutableStateOf(false) }
    var effectiveDialog by remember { mutableStateOf(false) }
    var effective by remember { mutableStateOf(today) }
    var anchor by remember { mutableStateOf(planEntity?.let { LocalDate.ofEpochDay(it.anchorEpochDay) } ?: today) }
    var blockDays by remember { mutableStateOf("21") }
    var blockCount by remember { mutableStateOf("4") }
    var pause by remember { mutableStateOf("7") }
    LaunchedEffect(planEntity) {
        planEntity?.toDomainPlan()?.let { plan ->
            anchor=LocalDate.ofEpochDay(plan.anchorEpochDay); blockDays=plan.subBlockDays.toString()
            blockCount=plan.subBlockCount.toString(); pause=plan.breakDays.toString()
        }
    }
    fun add(type:String) { contextType=type; editingContext=null; showContext=true }
    val currentDay = PillPlanVersions.forDate(versions,planEntity,today)?.let { PillSchedule.day(it.toDomainPlan(),today) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        GraphicalMonthCalendar(month=month,today=today,contexts=contexts,pillPlan=planEntity,
            pillPlanVersions=versions,pillEvents=pillEvents,hikeDays=hikesByDay.keys,
            previousMonth={month=month.minusMonths(1)},nextMonth={month=month.plusMonths(1)},
            showToday={month=YearMonth.from(today);selectedDate=today},
            selectDay={selectedDate=it;month=YearMonth.from(it);showDay=true},
            hikeTitles=hikesByDay.mapValues { (_,hikes)->hikes.map {DisplayTitle.resolve(it,routes)} },
            visibleCategoryNames=visible.map {it.name}.toSet())
        Button(onClick={add("NOTE")},Modifier.fillMaxWidth().heightIn(min=48.dp)) { Icon(Icons.Default.Add,null);Spacer(Modifier.width(8.dp));Text("Добавить запись") }
        CalendarExpander("Данные",dataOpen,{dataOpen=it}) {
            CalendarCategory.entries.forEach { category ->
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(category in visible,{visibility.set(category,it);visible=visibility.visible()},colors=CheckboxDefaults.colors(checkedColor=category.color))
                    Text(category.label)
                }
            }
        }
        CalendarExpander(if(mode==MedicationMode.PILLS) "Приём противозачаточных таблеток" else "Календарь менструаций",medicationOpen,{medicationOpen=it},
            if(mode==MedicationMode.PILLS) currentDay?.let { if(it.phase==PillPhase.ACTIVE) "День ${it.dayInSubBlock} · блок ${it.subBlockIndex} из ${it.subBlockCount}" else "Перерыв · день ${it.dayInPhase}" } else null) {
            listOf(MedicationMode.PILLS to "Приём противозачаточных",MedicationMode.MENSTRUATION to "Календарь менструаций").forEach { (value,label) ->
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(selected=mode==value,role=Role.RadioButton,onClick={mode=value;medication.mode=value;vm.reschedulePillReminders()}),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(mode==value,null);Text(label,Modifier.weight(1f))
                }
            }
            if(mode==MedicationMode.PILLS) {
                Text("Дата начала");CalendarDateButton(anchor) {anchor=it}
                OutlinedTextField(blockDays,{blockDays=it},Modifier.fillMaxWidth(),label={Text("Длительность блока, дней")},singleLine=true)
                OutlinedTextField(blockCount,{blockCount=it},Modifier.fillMaxWidth(),label={Text("Блоков подряд")},singleLine=true)
                OutlinedTextField(pause,{pause=it},Modifier.fillMaxWidth(),label={Text("Перерыв после блоков, дней")},singleLine=true)
                Text("Между блоками — без перерывов",style=MaterialTheme.typography.bodySmall)
                val days=blockDays.toIntOrNull();val count=blockCount.toIntOrNull();val rest=pause.toIntOrNull()
                val valid=days!=null && days>0 && count!=null && count>0 && rest!=null && rest>=0 && days.toLong()*count+rest<=Int.MAX_VALUE
                val changed=planEntity?.toDomainPlan()?.let {anchor.toEpochDay()!=it.anchorEpochDay || days!=it.subBlockDays || count!=it.subBlockCount || rest!=it.breakDays} ?: true
                Button(onClick={effective=today;effectiveDialog=true},Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=valid && changed) {Text("Сохранить изменения")}
                CalendarExpander("Расписание и исключения",exceptionsOpen,{exceptionsOpen=it}) {
                    OutlinedButton(onClick={showDeviation=true},Modifier.fillMaxWidth()) {Text("Изменить дату события")}
                    pillEvents.forEach { event -> PillEventRow(event) {editingPill=event} }
                }
                MedicationReminders(vm,medication)
            }
            Text("Записи менструаций",fontWeight=FontWeight.SemiBold)
            OutlinedButton(onClick={add("MENSTRUATION")},Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Добавить запись менструации")}
            contexts.filter {it.type=="MENSTRUATION"}.forEach { event -> ContextEventCard(event,{editingContext=event;showContext=true},{vm.deleteCalendarContext(event)}) }
        }
        listOf(Triple("Стресс",stressOpen,setOf(CalendarCategory.ACUTE,CalendarCategory.ONGOING)),Triple("Сон",sleepOpen,setOf(CalendarCategory.SLEEP)),Triple("Заметки",notesOpen,setOf(CalendarCategory.NOTES))).forEach { (label,expanded,categories) ->
            CalendarExpander(label,expanded,{when(label) {"Стресс"->stressOpen=it;"Сон"->sleepOpen=it;else->notesOpen=it}}) {
                OutlinedButton(onClick={add(when(label) {"Стресс"->"STRESS";"Сон"->"SEVERE_SLEEP_DEBT";else->"NOTE"})},Modifier.fillMaxWidth()) {Text("Добавить запись")}
                contexts.filter {CalendarCategory.context(it) in categories && CalendarCategory.context(it) in visible}.forEach { event ->
                    ContextEventCard(event,{editingContext=event;showContext=true},{vm.deleteCalendarContext(event)})
                }
            }
        }
    }
    if(showDay) ImeAlertDialog(onDismissRequest={showDay=false},title={Text(selectedDate.toString())},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            hikesByDay[selectedDate.toEpochDay()].orEmpty().filter {CalendarCategory.HIKES in visible}.forEach {hike ->
                Button(onClick={showDay=false;openHike(hike.routeId!!)},Modifier.fillMaxWidth()) {Text(DisplayTitle.resolve(hike,routes))}
            }
            Calendar037.contextsForDate(contexts,selectedDate,today).filter {CalendarCategory.context(it) in visible}.forEach {event ->
                ContextEventCard(event,{editingContext=event;showDay=false;showContext=true},{vm.deleteCalendarContext(event)})
            }
            PillPlanVersions.forDate(versions,planEntity,selectedDate)?.let {plan ->
                val day=PillSchedule.day(plan.toDomainPlan(),selectedDate)
                val category=if(day.phase==PillPhase.ACTIVE) CalendarCategory.ACTIVE else CalendarCategory.BREAK
                if(category in visible) Text("План: ${category.label} · день ${if(day.phase==PillPhase.ACTIVE) day.dayInSubBlock else day.dayInPhase}")
            }
        }
    },confirmButton={TextButton(onClick={showDay=false;add("NOTE")}) {Text("Добавить запись")}},dismissButton={TextButton(onClick={showDay=false}) {Text("Закрыть")}})
    if(showContext) CalendarContextDialog(selectedDate,editingContext,{showContext=false},initialType=contextType) {vm.saveCalendarContext(it);showContext=false}
    editingPill?.let {event -> PillFactDialog(event,{editingPill=null}) {vm.savePillEvent(it);editingPill=null} }
    if(showDeviation) DeviationDialog({showDeviation=false}) {planned,actual,policy,note -> vm.logPillDeviation(planned,actual,"BOUNDARY",policy,note);showDeviation=false}
    if(effectiveDialog) ImeAlertDialog(onDismissRequest={effectiveDialog=false},title={Text("С какой даты применить изменения?")},text={Column {
        CalendarDateButton(effective) {effective=it}
        Text("Предыдущая история сохраняется.")
    }},confirmButton={Button(onClick={vm.savePillRegimen(anchor,blockDays.toInt()*blockCount.toInt(),pause.toInt(),blockDays.toInt(),blockCount.toInt(),effective);effectiveDialog=false},enabled=effective>=today) {Text("Применить")}},dismissButton={TextButton(onClick={effectiveDialog=false}) {Text("Отмена")}})
}

@Composable
private fun CalendarExpander(title:String, expanded:Boolean, set:(Boolean)->Unit, summary:String?=null, content:@Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal=12.dp, vertical=if(expanded) 12.dp else 0.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Surface(onClick={set(!expanded)},color=androidx.compose.ui.graphics.Color.Transparent,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                Row(Modifier.padding(vertical=6.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {Text(title,fontWeight=FontWeight.SemiBold);summary?.let {Text(it,color=Forest,style=MaterialTheme.typography.bodySmall)} }
                    Icon(if(expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,if(expanded) "Свернуть" else "Развернуть")
                }
            }
            if(expanded) content()
        }
    }
}

@Composable
private fun MedicationReminders(vm:AppViewModel,prefs:MedicationPreferences) {
    val context=LocalContext.current
    var enabled by remember {mutableStateOf(prefs.remindersEnabled)}
    var time by remember {mutableStateOf(prefs.reminderTime)}
    var revision by remember {mutableIntStateOf(0)}
    var granted by remember {mutableStateOf(android.os.Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted=it;vm.reschedulePillReminders()}
    SwitchRow("Напоминания",enabled) {enabled=it;prefs.remindersEnabled=it;vm.reschedulePillReminders()}
    if(enabled) {
        OutlinedTextField(time,{time=it;if(prefs.setReminderTime(it)) vm.reschedulePillReminders()},Modifier.fillMaxWidth(),label={Text("Время напоминаний")},singleLine=true,isError=!validReminderTime(time))
        val labels=listOf(PillReminderKind.LAST_ACTIVE to "Последний день перед перерывом",PillReminderKind.BREAK_START to "Начало перерыва",PillReminderKind.RESTART_EVE to "Накануне возобновления",PillReminderKind.RESTART_DAY to "День возобновления")
        key(revision) {labels.forEach {(kind,label) -> SwitchRow(label,prefs.option(kind)) {prefs.setOption(kind,it);revision++;vm.reschedulePillReminders()} } }
        if(!granted) OutlinedButton(onClick={permission.launch(Manifest.permission.POST_NOTIFICATIONS)},Modifier.fillMaxWidth()) {Text("Разрешить уведомления")}
    }
}

@Composable
private fun ContextEventCard(event: CalendarContextEntity, edit: () -> Unit, delete: () -> Unit) {
    val start = LocalDate.ofEpochDay(event.startEpochDay)
    val end = LocalDate.ofEpochDay(event.endEpochDay)
    Card(Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(Calendar037.label(event.type), fontWeight = FontWeight.SemiBold) },
            supportingContent = { Column {
                Text(if (event.ongoing) "$start — продолжается" else if (start == end) "$start" else "$start — $end")
                event.severity?.let { Text("Интенсивность: $it / 5") }
                if (event.note.isNotBlank()) Text(event.note)
            } }, leadingContent = { Icon(when (Calendar037.normalizedType(event.type)) {
                "SEVERE_SLEEP_DEBT" -> Icons.Default.Bedtime
                "STRESS", "STRONG_STRESS" -> Icons.Default.Bolt
                else -> Icons.AutoMirrored.Filled.Notes
            }, null) }, trailingContent = { Column {
                IconButton(onClick = edit, Modifier.size(48.dp).testTag("context-edit-${event.id}")) { Icon(Icons.Default.Edit, "Изменить весь период") }
                IconButton(onClick = delete, Modifier.size(48.dp)) { Icon(Icons.Default.Delete, "Удалить") }
            } })
    }
}

@Composable
private fun CalendarContextDialog(initialDate: LocalDate, existing: CalendarContextEntity?, onDismiss: () -> Unit, initialType:String = "NOTE", save: (CalendarContextEntity) -> Unit) {
    var start by remember(existing) { mutableStateOf(existing?.let { LocalDate.ofEpochDay(it.startEpochDay) } ?: initialDate) }
    var end by remember(existing) { mutableStateOf<LocalDate?>(if (existing?.ongoing == true) null else existing?.let { LocalDate.ofEpochDay(it.endEpochDay) } ?: initialDate) }
    var type by remember(existing) { mutableStateOf(existing?.type ?: initialType) }
    var severity by remember(existing) { mutableStateOf(existing?.severity) }
    var note by remember(existing) { mutableStateOf(existing?.note.orEmpty()) }
    val valid = (end?.let { it >= start } ?: (start <= LocalDate.now())) && (type != "NOTE" || note.isNotBlank())
    ImeAlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "Запись в календаре" else "Изменить весь период") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("NOTE", "SEVERE_SLEEP_DEBT", "STRESS", "STRONG_STRESS", "MENSTRUATION").forEach { value ->
                FilterChip(selected = Calendar037.normalizedType(type) == value, onClick = { type = value }, label = { Text(Calendar037.label(value)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
            }
            Text("Начало"); CalendarDateButton(start) { start = it; if (end?.let { e -> e < it } == true) end = it }
            SwitchRow("Продолжается (без даты окончания)", end == null) { end = if (it) null else maxOf(start, LocalDate.now()) }
            end?.let { date -> Text("Окончание"); CalendarDateButton(date) { end = it } }
            InfoHint("Период", "Редактируется весь исходный период, а не только выбранный день. Без окончания контекст действует от начала до сегодня, но не на будущие даты.")
            if (type !in listOf("NOTE","MENSTRUATION")) RatingField("Интенсивность", severity) { severity = it }
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Заметка") }, minLines = 3)
            InfoHint("Передача контекста", "Запись будет передана Mayushi только в компактном контексте анализа подходящего хайка.")
        }
    }, confirmButton = { Button(onClick = {
        val base = existing ?: CalendarContextEntity(startEpochDay = start.toEpochDay(), endEpochDay = start.toEpochDay(), type = type)
        save(base.copy(startEpochDay = start.toEpochDay(), endEpochDay = end?.toEpochDay() ?: base.endEpochDay,
            ongoing = end == null, type = type, severity = severity, note = note.trim(), updatedAt = System.currentTimeMillis()))
    }, modifier = Modifier.heightIn(min = 48.dp), enabled = valid) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss, Modifier.heightIn(min = 48.dp)) { Text("Отмена") } })
}

@Composable
private fun PillEventRow(event: PillEventEntity, edit: () -> Unit) {
    ListItem(headlineContent = { Text("${LocalDate.ofEpochDay(event.epochDay)} · " + (event.actualTaken?.let { if (it) "принята" else "не принята" } ?: "отклонение")) },
        supportingContent = { Text("План: ${event.plannedEpochDay?.let(LocalDate::ofEpochDay) ?: "—"} · ${event.deviationPolicy ?: "без сдвига"}\n${event.note}") },
        trailingContent = { IconButton(onClick = edit, Modifier.size(48.dp)) { Icon(Icons.Default.Edit, "Изменить факт / дату") } })
}

@Composable
private fun PillFactDialog(event: PillEventEntity, onDismiss: () -> Unit, save: (PillEventEntity) -> Unit) {
    var date by remember(event) { mutableStateOf(LocalDate.ofEpochDay(event.epochDay)) }
    var planned by remember(event) { mutableStateOf(event.plannedEpochDay?.let(LocalDate::ofEpochDay)) }
    var taken by remember(event) { mutableStateOf(event.actualTaken) }
    var note by remember(event) { mutableStateOf(event.note) }
    ImeAlertDialog(onDismissRequest = onDismiss, title = { Text("Исправить факт / дату") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Фактическая дата"); CalendarDateButton(date) { date = it }
            planned?.let { value -> Text("Плановая дата"); CalendarDateButton(value) { planned = it } }
            listOf(true to "Принята", false to "Не принята", null to "Без факта приёма").forEach { (value, label) ->
                FilterChip(selected = taken == value, onClick = { taken = value }, label = { Text(label) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
            }
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Заметка") })
            InfoHint("Исправление записи", "Исправь эту запись, если раньше указала её неверно. Изменение записи само по себе не меняет назначенную схему приёма.")
        }
    }, confirmButton = { Button(onClick = { save(event.copy(epochDay = date.toEpochDay(), plannedEpochDay = planned?.toEpochDay(), actualTaken = taken, note = note.trim())) }, Modifier.heightIn(min = 48.dp)) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss, Modifier.heightIn(min = 48.dp)) { Text("Отмена") } })
}

@Composable
private fun CalendarDateButton(value: LocalDate, set: (LocalDate) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        android.app.DatePickerDialog(context, { _, year, month, day -> set(LocalDate.of(year, month + 1, day)) },
            value.year, value.monthValue - 1, value.dayOfMonth).show()
    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(Icons.Default.DateRange, null); Spacer(Modifier.width(8.dp)); Text(value.toString())
    }
}

@Composable
fun CurrentSettingsScreen(vm: AppViewModel, queueEntry: Int = 0) {
    var companionSettings by remember { mutableStateOf(false) }
    val prefs = vm.preferences()
    var sleep by remember { mutableStateOf(prefs.sleepNormHours) }
    var sleepValid by remember { mutableStateOf(true) }
    val pendingAi by vm.pendingAi.collectAsStateWithLifecycle()
    val activities by vm.activities.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val importResponse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importTelegramResponse) }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val saveRequest = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::saveAiQueue) }
    val settingsScroll = rememberScrollState()
    var handledQueueEntry by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(queueEntry) { if(queueEntry != handledQueueEntry) { settingsScroll.scrollTo(0); handledQueueEntry = queueEntry } }
    Column(Modifier.fillMaxSize().verticalScroll(settingsScroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Box(Modifier.weight(1f, fill = false)) { SectionTitle("Mayushi через Telegram") }
            InfoHint("Отправка Mayushi", "Приложение подготовит файл для Mayushi. В меню отправки выбери Telegram и нужный чат.", compactInline = true)
        }
        Button(onClick = { vm.shareAiQueue(context) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = pendingAi > 0 && !busy) {
            Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp))
            Text(when { busy -> "Готовлю файл…"; pendingAi > 0 -> "Отправить на анализ ($pendingAi)"; else -> "Очередь пуста" })
        }
        OutlinedButton(onClick = { saveRequest.launch("TrailSense-request.json") }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = pendingAi > 0 && !busy) { Text("Сохранить JSON локально") }
        OutlinedButton(onClick = { importResponse.launch(arrayOf("application/json", "text/json", "application/octet-stream")) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Импортировать ответ Mayushi")
        }
        HorizontalDivider(); SectionTitle("Анализ")
        RepeatComparisonSetting()
        SleepClockField("Личная норма сна", sleep, { sleepValid = it }) { value -> if(value != null && value > 0) { sleep=value;vm.saveAnalysisPreferences(value,false,false) } }
        HorizontalDivider(); SectionTitle("Данные")
        HuaweiImport041(vm)
        OutlinedButton(onClick={companionSettings=true},Modifier.fillMaxWidth()) { Text("Путники") }
        HorizontalDivider(); SectionTitle("О приложении")
        Text("TrailSense ${BuildConfig.VERSION_NAME} · сборка ${BuildConfig.VERSION_CODE}")
        HorizontalDivider(); SectionTitle("Приватность")
        Text("Треки, анкеты, сон и календарь хранятся локально. Данные для анализа экспортируются только по твоему действию. Карта запрашивает плитки области карты, без полного трека, времени, пульса и анкеты.")
    }
    if(companionSettings) CompanionSettings041(vm) { companionSettings=false }
}
