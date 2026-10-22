package com.mayushi.trailsense

import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mayushi.trailsense.data.*
import com.mayushi.trailsense.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val incomingIntent = mutableStateOf<Intent?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app always uses a dark palette, independent of the system theme.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        incomingIntent.value = intent
        setContent { TrailSenseTheme { TrailSenseApp(initialIntent = incomingIntent.value) } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); incomingIntent.value = intent }
}

private val Night = Color(0xFF0D1511)
private val NightSurface = Color(0xFF15211A)
private val NightSurfaceRaised = Color(0xFF203027)
private val Mint = Color(0xFFA4D8B8)
private val Peach = Color(0xFF64D8D0)
val Forest = Mint
val Coral = Peach
private val NightText = Color(0xFFE4ECE6)

@Composable fun TrailSenseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Mint,
            onPrimary = Color(0xFF103522),
            primaryContainer = Color(0xFF264D37),
            onPrimaryContainer = Color(0xFFD0F1DA),
            secondary = Peach,
            onSecondary = Color(0xFF073B3A),
            secondaryContainer = Color(0xFF164D4B),
            onSecondaryContainer = Color(0xFFB9F5EF),
            background = Night,
            onBackground = NightText,
            surface = NightSurface,
            onSurface = NightText,
            surfaceVariant = NightSurfaceRaised,
            onSurfaceVariant = Color(0xFFBFCBC2),
        ),
        content = content,
    )
}

enum class Tab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    HOME("Хайки", Icons.Default.Hiking),
    MAP("Карта", Icons.Default.Map),
    CALENDAR("Календарь", Icons.Default.CalendarMonth),
    SETTINGS("Настройки", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TrailSenseApp(initialIntent: Intent?, vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var routeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var standaloneActivityId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedRecordingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var routeEditRequested by rememberSaveable { mutableStateOf(false) }
    var routeMenu by remember { mutableStateOf(false) }
    var deleteRequested by remember { mutableStateOf(false) }
    var analysisChoice by remember { mutableStateOf(false) }
    var queueEntry by rememberSaveable { mutableIntStateOf(0) }
    var repeatScreen by rememberSaveable { mutableStateOf(false) }
    var repeatAnalyses by rememberSaveable { mutableStateOf(false) }
    var repeatRouteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val tabState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var navigationHistory by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver<List<Pair<Tab,Long?>>,String>(
        save = { history -> history.map { "${it.first.name}:${it.second ?: ""}" } },
        restore = { values -> values.map { value -> val parts=value.split(":"); Tab.valueOf(parts[0]) to parts[1].toLongOrNull() } }
    )) { mutableStateOf(emptyList<Pair<Tab,Long?>>()) }
    val navigateToTab: (Tab) -> Unit = { destination ->
        repeatScreen = false
        repeatRouteId = null
        routeMenu = false
        if (routeId != null || standaloneActivityId != null || tab != destination) {
            navigationHistory = navigationHistory + (tab to routeId)
            tab = destination
            routeId = null
            standaloneActivityId = null
            selectedRecordingId = null
        }
    }
    val openRoute: (Long) -> Unit = { id ->
        selectedRecordingId = null
        repeatScreen = false
        navigationHistory = navigationHistory + (tab to routeId)
        routeId = id
    }
    val goBack: () -> Unit = {
        selectedRecordingId = null
        val previous = navigationHistory.lastOrNull()
        if(standaloneActivityId != null) standaloneActivityId=null
        else if (previous != null) {
            navigationHistory = navigationHistory.dropLast(1)
            tab = previous.first
            routeId = previous.second
        } else if (routeId != null) {
            routeId = null
            tab = Tab.HOME
        }
    }
    BackHandler(enabled = routeId != null || standaloneActivityId != null || navigationHistory.isNotEmpty() || repeatScreen) { if(repeatScreen && repeatAnalyses) repeatAnalyses=false else if(repeatScreen) repeatScreen=false else goBack() }
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val sharedFile by vm.pendingSharedFile.collectAsStateWithLifecycle()
    val routes by vm.routeIndex.collectAsStateWithLifecycle()
    val toolbarRecordings by vm.recordingIndex.collectAsStateWithLifecycle()
    // Only the actual binding counts; suggestedRouteId is deliberately ignored.
    val toolbarRouteId = routeId ?: toolbarRecordings.firstOrNull { it.id == standaloneActivityId }?.routeId
    val toolbarTitle = hikeToolbarTitle(routes.firstOrNull { it.id == toolbarRouteId })
    val createdRouteId by vm.createdRouteId.collectAsStateWithLifecycle()
    LaunchedEffect(createdRouteId) { createdRouteId?.let { openRoute(it); vm.consumeCreatedRoute() } }
    val pendingAi by vm.pendingAi.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(initialIntent) { vm.handleIntent(initialIntent) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    val pendingSleep by vm.pendingSleep.collectAsStateWithLifecycle()
    pendingSleep?.let { pending -> AlertDialog(onDismissRequest=vm::cancelSleepUpload,
        title={ Text("Добавить данные сна?") },
        text={ Text("Сон: ${pending.result.wakingDate}\nХайк: ${pending.hikeDate}\nДата сна не совпадает с датой хайка. Всё равно добавить?") },
        confirmButton={ TextButton(onClick={vm.confirmSleepUpload()}) {Text("Добавить")} },
        dismissButton={ TextButton(onClick=vm::cancelSleepUpload) {Text("Отмена")} }) }
    sharedFile?.let { AttachSharedFileDialog(it, routes, vm) }
    HuaweiPreview041(vm)
    val analysisRecordings by vm.activities.collectAsStateWithLifecycle()
    val analysisRecording = if(selectedRecordingId != null) analysisRecordings.firstOrNull { it.id == selectedRecordingId && it.routeId == routeId }
        else analysisRecordings.filter { it.routeId == routeId && routeId != null }.sortedWith(compareByDescending<com.mayushi.trailsense.data.ActivityEntity> { it.startedAt != null }.thenByDescending { it.startedAt }.thenByDescending { it.id }).firstOrNull()
    if (analysisChoice && analysisRecording != null) HikeAnalysisChoice(analysisRecording.id, vm) { analysisChoice = false }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if(repeatScreen && repeatAnalyses) "Анализы динамики" else if(repeatScreen) "Повторные маршруты" else if (routeId != null || standaloneActivityId != null) toolbarTitle else tab.title,
                        modifier = Modifier.fillMaxWidth().testTag("hike-toolbar-title"),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (routeId != null || standaloneActivityId != null || repeatScreen) IconButton(onClick = { if(repeatScreen && repeatAnalyses) repeatAnalyses=false else if(repeatScreen) repeatScreen=false else goBack() }, modifier=Modifier.size(48.dp).testTag("hike-toolbar-back")) { Icon(Icons.Default.ArrowBack, "Назад") }
                },
                actions = {
                    if (routeId != null) {
                        Box {
                            IconButton(onClick = { routeMenu = true }, modifier=Modifier.size(48.dp).testTag("hike-toolbar-menu")) { Icon(Icons.Default.MoreVert, "Меню хайка") }
                            DropdownMenu(expanded = routeMenu, onDismissRequest = { routeMenu = false }) {
                                DropdownMenuItem(text = { Text("Изменить") }, onClick = { routeMenu = false; routeEditRequested = true })
                                DropdownMenuItem(text = { Text("Сравнить прохождения") }, onClick = { routeMenu=false; repeatRouteId=routeId; repeatScreen=true })
                                DropdownMenuItem(text = { Text("Отправить на анализ") }, onClick = { routeMenu = false; analysisChoice = true })
                                DropdownMenuItem(text = { Text("Удалить хайк") }, onClick = { routeMenu = false; deleteRequested = true })
                            }
                        }
                    } else if (tab == Tab.HOME) {
                        Box {
                            IconButton(onClick={routeMenu=true}) { Icon(Icons.Default.MoreVert,"Меню хайков") }
                            DropdownMenu(routeMenu,{routeMenu=false}) {
                                DropdownMenuItem(text={Text("Повторные маршруты")},onClick={routeMenu=false; repeatRouteId=null; repeatScreen=true})
                                if(pendingAi>0) DropdownMenuItem(text={Text("$pendingAi на анализ")},onClick={routeMenu=false; queueEntry++; navigateToTab(Tab.SETTINGS)})
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (routeId == null && standaloneActivityId == null) NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { navigateToTab(item) },
                        icon = { Icon(item.icon, null) },
                        label = { BottomTabLabel(item.title) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when {
                repeatScreen -> AutomaticRepeatScreen(vm,repeatRouteId,repeatAnalyses,{repeatAnalyses=true},
                    openRecording={recording,route -> if(route!=null) {openRoute(route);selectedRecordingId=recording} else {repeatScreen=false;standaloneActivityId=recording}},open=openRoute)
                standaloneActivityId != null -> key(standaloneActivityId) {ActivityDetail(standaloneActivityId!!,vm)}
                routeId != null -> key(routeId,selectedRecordingId) { RouteEventDetail(routeId!!, vm, goBack, routeEditRequested, { routeEditRequested = false }, deleteRequested, { deleteRequested = false },selectedRecordingId) }
                else -> tabState.SaveableStateProvider(tab.name) { when (tab) {
                    Tab.HOME -> EventsScreen(vm, openRoute)
                    Tab.MAP -> AllHikesMap(vm,openActivity={standaloneActivityId=it},openRecording={recording,route -> if(route!=null){openRoute(route);selectedRecordingId=recording}else standaloneActivityId=recording},open=openRoute)
                    Tab.CALENDAR -> ContextCalendarScreen(vm, openRoute)
                    Tab.SETTINGS -> CurrentSettingsScreen(vm, queueEntry)
                } }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

@Composable fun HomeScreen(vm: AppViewModel, goRoutes: () -> Unit, goActivities: () -> Unit) {
    val routes by vm.routes.collectAsStateWithLifecycle(); val activities by vm.activities.collectAsStateWithLifecycle(); val pending by vm.pendingAi.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Твои маршруты — твоими глазами", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); InfoHint("О данных", "Объективные цифры отдельно от того, как маршрут ощущается именно тебе.") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { StatCard("Маршруты", routes.size.toString(), Modifier.weight(1f), goRoutes); StatCard("Походы", activities.size.toString(), Modifier.weight(1f), goActivities); StatCard("AI очередь", pending.toString(), Modifier.weight(1f), {}) } }
        item { SectionTitle("Как добавить данные") }
        item { Card { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Маршрут AllTrails", fontWeight = FontWeight.Bold); InfoHint("Добавление AllTrails", "В AllTrails нажми «Поделиться» → «Мои тропы», либо вставь ссылку в разделе маршрутов."); Text("Тренировка с часов", fontWeight = FontWeight.Bold); InfoHint("Добавление часов", "Экспортируй GPX/TCX и поделись файлом в это приложение. Все вычисления выполняются локально.") } } }
        item { Card(colors = CardDefaults.cardColors(containerColor = Forest.copy(alpha = .09f))) { Column(Modifier.padding(18.dp)) { InfoHint("Очередь Mayushi", "Mayushi получает краткую сводку, а не полный трек. Запросы остаются в очереди на устройстве, пока ты не отправишь их.") } } }
    }
}

@Composable fun StatCard(label: String, value: String, modifier: Modifier, onClick: () -> Unit) { Card(modifier.clickable(onClick = onClick)) { Column(Modifier.padding(14.dp)) { Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(label, style = MaterialTheme.typography.labelMedium) } } }

@Composable fun RoutesScreen(vm: AppViewModel, open: (Long) -> Unit) {
    val routes by vm.routes.collectAsStateWithLifecycle(); var link by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importRouteFile) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("Ссылка AllTrails") }, singleLine = true, trailingIcon = { IconButton(onClick = { vm.importRouteLink(link); link = "" }) { Icon(Icons.Default.Add, "Добавить") } }); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { pick.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream")) }) { Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(6.dp)); Text("Импорт GPX") } } }
        if (routes.isEmpty()) item { EmptyState("Пока нет маршрутов", "Добавь ссылку AllTrails или экспортированный GPX.") }
        items(routes, key = { it.id }) { r -> Card(Modifier.fillMaxWidth().clickable { open(r.id) }) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(r.title, fontWeight = FontWeight.Bold); Text(listOfNotNull(r.distanceM?.let { km(it) }, r.elevationGainM?.let { "+${it.roundToInt()} м" }, r.sourceLabel).joinToString(" · "), style = MaterialTheme.typography.bodySmall) }; RatingDots(r.overallDifficulty) } } }
    }
}

@Composable fun ActivitiesScreen(vm: AppViewModel, open: (Long) -> Unit) {
    val activities by vm.activities.collectAsStateWithLifecycle(); val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importActivity) }
    val pickScreenshot = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importHuaweiScreenshot) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pick.launch(arrayOf("application/gpx+xml", "application/vnd.garmin.tcx+xml", "application/xml", "text/xml", "application/octet-stream")) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Watch, null); Spacer(Modifier.width(6.dp)); Text("Импорт GPX / TCX с часов") }
            OutlinedButton(onClick = { pickScreenshot.launch(arrayOf("image/*")) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.AddPhotoAlternate, null); Spacer(Modifier.width(6.dp)); Text("Добавить скриншот Huawei") }
            InfoHint("Скриншот Huawei", "Скриншот хранится на устройстве. Он будет отправлен только по твоему выбору вместе с запросом Mayushi. Проверь распознанные значения перед отправкой.")
        } }
        if (activities.isEmpty()) item { EmptyState("Пока нет походов", "Импортируй тренировку с часов — маршрут предложится по геометрии, но не привяжется без твоего подтверждения.") }
        items(activities, key = { it.id }) { a -> Card(Modifier.fillMaxWidth().clickable { open(a.id) }) { Column(Modifier.padding(16.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(a.title, fontWeight = FontWeight.Bold); Text(a.startedAt?.let(::dateFromMs) ?: "") }; Text("${km(a.distanceM)} · +${a.elevationGainM.roundToInt()} м · ${duration(a.elapsedSeconds)}", style = MaterialTheme.typography.bodySmall); if (a.comment.startsWith("Предложенное совпадение")) AssistChip(onClick = {}, label = { Text("Проверь совпадение маршрута") }) } } }
    }
}

@Composable fun RouteDetail(id: Long, vm: AppViewModel) {
    val route by vm.route(id).collectAsStateWithLifecycle(null); val aiShort by vm.latestAi("ROUTE", id, "SHORT_ROUTE").collectAsStateWithLifecycle(null); val aiDeep by vm.latestAi("ROUTE", id, "DEEP_ROUTE").collectAsStateWithLifecycle(null)
    val r = route ?: return EmptyState("Загрузка", "")
    var edit by remember(r.updatedAt) { mutableStateOf(r) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(edit.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        edit.trackEncoded?.let { TrackPreview(TrackCodec.decode(it), Modifier.fillMaxWidth().height(180.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { edit.distanceM?.let { Metric("Дистанция", km(it)) }; edit.elevationGainM?.let { Metric("Набор", "+${it.roundToInt()} м") }; edit.elevationLossM?.let { Metric("Спуск", "−${it.roundToInt()} м") }; edit.sourceRating?.let { Metric("AllTrails", "$it ★") } }
        edit.sourceDifficulty?.let { Text("Исходная сложность: $it", style = MaterialTheme.typography.bodyMedium) }
        SectionTitle("Моя оценка")
        RatingField("Общая тяжесть", edit.overallDifficulty) { edit = edit.copy(overallDifficulty = it) }
        RatingField("Тяжесть набора", edit.climbDifficulty) { edit = edit.copy(climbDifficulty = it) }
        RatingField("Техничность", edit.technicality) { edit = edit.copy(technicality = it) }
        RatingField("Экспозиция", edit.exposure) { edit = edit.copy(exposure = it) }
        RatingField("Красота", edit.beauty) { edit = edit.copy(beauty = it) }
        OutlinedTextField(edit.notes, { edit = edit.copy(notes = it) }, Modifier.fillMaxWidth(), label = { Text("Мои заметки") }, minLines = 3)
        OutlinedTextField(edit.reviewExcerpts, { edit = edit.copy(reviewExcerpts = it) }, Modifier.fillMaxWidth(), label = { Text("Фрагменты отзывов для анализа") }, minLines = 3)
        InfoHint("Отзывы", "Вставь нужные отзывы сама — приложение не скрейпит AllTrails.")
        Button(onClick = { vm.saveRoute(edit) }, Modifier.fillMaxWidth()) { Text("Сохранить оценки") }
        SectionTitle("Mayushi")
        AiCard(aiShort?.response ?: "Короткий анализ ожидается.", aiShort?.promptTokens, aiShort?.completionTokens)
        if(aiShort == null) InfoHint("Очередь анализа", "Отправь задания Mayushi через Telegram в настройках.")
        aiDeep?.let { AiCard(it.response, it.promptTokens, it.completionTokens) }
        OutlinedButton(onClick = { vm.requestRouteAnalysis(id, "DEEP_ROUTE") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Глубокий анализ отзывов") }
        r.sourceUrl?.let { val context = LocalContext.current; TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }) { Text("Открыть исходник AllTrails") } }
    }
}

@Composable fun ActivityDetail(id: Long, vm: AppViewModel) {
    val activity by vm.activity(id).collectAsStateWithLifecycle(null); val companions by vm.companions.collectAsStateWithLifecycle(); val savedIds by vm.companionIds(id).collectAsStateWithLifecycle(emptyList()); val ai by vm.latestAi("ACTIVITY", id, "POST_HIKE").collectAsStateWithLifecycle(null); val screenshotAi by vm.latestAi("ACTIVITY", id, "HUAWEI_SCREENSHOT").collectAsStateWithLifecycle(null)
    val a = activity ?: return EmptyState("Загрузка", "")
    var edit by remember(a.updatedAt) { mutableStateOf(a) }; var selected by remember(savedIds) { mutableStateOf(savedIds.toSet()) }; var pickingCompanions by remember { mutableStateOf(false) }
    val applicability = remember(a.id) { mutableStateMapOf<String,String>().apply {putAll(RatingApplicability.states(a.questionnaireHistory))} }
    var sleepValid by remember(a.id) {mutableStateOf(true)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(edit.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (edit.trackEncoded.isNotBlank()) TrackPreview(TrackCodec.decode(edit.trackEncoded), Modifier.fillMaxWidth().height(180.dp))
        FlowMetrics(edit)
        WatchDetails041(a,vm)
        if (edit.sourceFormat == "HUAWEI_SCREENSHOT") {
            AiCard(screenshotAi?.response ?: "Показатели со скриншота ждут обработки.", screenshotAi?.promptTokens, screenshotAi?.completionTokens)
            if(screenshotAi == null) InfoHint("Обработка скриншота", "Отправь очередь Mayushi через Telegram в настройках.")
            Text("Проверь распознанные значения и исправь при необходимости:", fontWeight = FontWeight.Bold)
            NumberTextField("Дистанция, км", edit.distanceM.takeIf { it > 0 }?.div(1000)) { edit = edit.copy(distanceM = (it ?: 0.0) * 1000) }
            NumberTextField("Набор, м", edit.elevationGainM.takeIf { it > 0 }) { edit = edit.copy(elevationGainM = it ?: 0.0) }
            NumberTextField("Спуск, м", edit.elevationLossM.takeIf { it > 0 }) { edit = edit.copy(elevationLossM = it ?: 0.0) }
            NumberTextField("Всего, минуты", edit.elapsedSeconds?.div(60.0)) { edit = edit.copy(elapsedSeconds = it?.times(60)?.toLong()) }
            NumberTextField("В движении, минуты", edit.movingSeconds?.div(60.0)) { edit = edit.copy(movingSeconds = it?.times(60)?.toLong()) }
            NumberTextField("Средний пульс", edit.averageHeartRateBpm?.toDouble()) { edit = edit.copy(averageHeartRateBpm = it?.roundToInt()) }
            NumberTextField("Максимальный пульс", edit.maxHeartRateBpm?.toDouble()) { edit = edit.copy(maxHeartRateBpm = it?.roundToInt()) }
        }
        SectionTitle("Маршрут")
        val routes by vm.routes.collectAsStateWithLifecycle()
        edit.suggestedRouteId?.let { suggested -> routes.firstOrNull { it.id == suggested }?.let { route -> Card(colors = CardDefaults.cardColors(containerColor = Forest.copy(alpha = .08f))) { Column(Modifier.padding(14.dp)) { Text("Похоже на: ${route.title}", fontWeight = FontWeight.Bold); Text("Совпадение ${(edit.matchConfidence?.times(100) ?: 0.0).roundToInt()}%. Подтверди или выбери другой маршрут."); if (edit.routeId == null) Button(onClick = { edit = edit.copy(routeId = route.id) }) { Text("Подтвердить совпадение") } } } } }
        RouteChoice(routes, edit.routeId) { edit = edit.copy(routeId = it) }
        SectionTitle("Как это ощущалось")
        ApplicableRating("Общая нагрузка", edit.overallEffort,applicability) { edit = edit.copy(overallEffort = it) }
        ApplicableRating("Подъём", edit.climbEffort,applicability) { edit = edit.copy(climbEffort = it) }
        ApplicableRating("Спуск", edit.descentEffort,applicability) { edit = edit.copy(descentEffort = it) }
        ApplicableRating("Техничность", edit.technicality,applicability) { edit = edit.copy(technicality = it) }
        ApplicableRating("Удовольствие", edit.enjoyment,applicability) { edit = edit.copy(enjoyment = it) }
        SectionTitle("Сон")
        SleepClockField("Фактический сон", edit.sleepHours,{sleepValid=it}) { edit = edit.copy(sleepHours = it) }
        ApplicableRating("Качество сна", edit.sleepQuality,applicability) { edit = edit.copy(sleepQuality = it) }
        TriChoice("Выспалась?", listOf("Да" to true, "Нет" to false), edit.feltRested) { edit = edit.copy(feltRested = it) }
        SectionTitle("Аномалия")
        ChoiceChips(listOf("Подозрительно тяжело" to "HARD", "Ничего необычного" to "NORMAL", "Подозрительно легко" to "EASY"), edit.anomaly) { edit = edit.copy(anomaly = it) }
        SectionTitle("Темп и спутники")
        SwitchRow("Скорость отражает мой обычный темп", edit.representativeSpeed) { edit = edit.copy(representativeSpeed = it) }
        ChoiceChips(listOf("Свободный" to "FREE", "Чуть медленнее" to "SLIGHT", "Заметно медленнее" to "NOTABLE", "Сильно медленнее" to "MUCH"), edit.paceConstraint) { edit = edit.copy(paceConstraint = it, representativeSpeed = it == "FREE") }
        SwitchRow("Были ожидания / вынужденные остановки", edit.hadWaitingStops) { edit = edit.copy(hadWaitingStops = it) }
        Text(if(selected.isEmpty()) "Шла одна" else companions.filter {it.id in selected}.joinToString {it.name})
        TextButton(onClick={pickingCompanions=true}) {Text("Изменить путников")}
        if(pickingCompanions) CompanionPicker041(vm,selected,{pickingCompanions=false}) {selected=it;pickingCompanions=false}
        OutlinedTextField(edit.comment, { edit = edit.copy(comment = it) }, Modifier.fillMaxWidth(), label = { Text("Комментарий") }, minLines = 3)
        Button(onClick = { vm.saveActivity(edit.copy(questionnaireHistory=RatingApplicability.save(edit.questionnaireHistory,applicability)), selected) }, Modifier.fillMaxWidth(),enabled=sleepValid) { Text("Сохранить") }
        SectionTitle("Mayushi")
        AiCard(ai?.response ?: "Анализ ожидается.", ai?.promptTokens, ai?.completionTokens)
        if(ai == null) InfoHint("Очередь анализа", "Отправь задания Mayushi через Telegram в настройках.")
    }
}

@Composable fun FlowMetrics(recording: ActivityEntity) {
    val a = com.mayushi.trailsense.data.HuaweiWatchDisplay.activity(recording)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("Дистанция", km(a.distanceM))
            Metric("Всего", duration(a.elapsedSeconds))
            a.averageHeartRateBpm?.let { Metric("Пульс", "$it ср.") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("Набор", "+${a.elevationGainM.roundToInt()} м")
            Metric("Спуск", "−${a.elevationLossM.roundToInt()} м")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("≈ в движении", duration(a.movingSeconds))
            Metric("≈ остановки", duration(a.stoppedSeconds))
        }
    }
}

@Composable fun PillScreen(vm: AppViewModel) {
    val planEntity by vm.pillPlan.collectAsStateWithLifecycle(); val events by vm.pillEvents.collectAsStateWithLifecycle(); val today = LocalDate.now(); var start by remember { mutableStateOf(today) }; var showDeviation by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Card(colors = CardDefaults.cardColors(containerColor = Forest.copy(alpha = .08f))) { Text("Это календарь твоей назначенной схемы, а не медицинская рекомендация. Он не называет дни естественными фазами цикла.", Modifier.padding(16.dp)) } }
        if (planEntity == null) item { SectionTitle("Начало блока"); DateButton(start) { start = it }; Button(onClick = { vm.savePillStart(start) }, Modifier.fillMaxWidth()) { Text("Создать календарь 84 + 7") } }
        else {
            val p = planEntity!!; val day = PillSchedule.day(PillPlan(p.anchorEpochDay, p.activeDays, p.breakDays), today)
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) { Text(if (day.phase == PillPhase.ACTIVE) "Активные таблетки" else "Семидневный перерыв", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("День ${day.dayInPhase} · день ${day.dayInBlock} текущего блока"); Text(today.format(DateTimeFormatter.ofPattern("d MMMM yyyy"))) } } }
            item { val dates = PillSchedule.reminderDates(PillPlan(p.anchorEpochDay, p.activeDays, p.breakDays), day.blockNumber); SectionTitle("Ближайшие границы"); dates.forEach { (kind, date) -> Text("${reminderLabel(kind)} — ${date.format(DateTimeFormatter.ofPattern("d MMM"))}") } }
            item { OutlinedButton(onClick = { showDeviation = true }, Modifier.fillMaxWidth()) { Text("Записать единоразовое отклонение") } }
            items(events.take(20)) { event -> ListItem(headlineContent = { Text(event.type) }, supportingContent = { Text("${LocalDate.ofEpochDay(event.epochDay)} · ${event.deviationPolicy ?: "только запись"}${event.note.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}") }, leadingContent = { Icon(Icons.Default.EditCalendar, null) }) }
        }
    }
    if (showDeviation && planEntity != null) DeviationDialog(onDismiss = { showDeviation = false }) { planned, actual, policy, note -> vm.logPillDeviation(planned, actual, "Отклонение", policy, note); showDeviation = false }
}

@Composable fun SettingsScreen(vm: AppViewModel) {
    val prefs = vm.preferences()
    var endpoint by remember { mutableStateOf(prefs.hermesEndpoint) }
    var key by remember { mutableStateOf("") }
    var budget by remember { mutableStateOf(prefs.monthlyTokenBudget.toString()) }
    var sleep by remember { mutableStateOf(prefs.sleepNormHours.toString()) }
    var directEnabled by remember { mutableStateOf(prefs.directHermesEnabled) }
    var showDirectConnection by rememberSaveable { mutableStateOf(false) }
    val pendingAi by vm.pendingAi.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val importTelegramResponse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importTelegramResponse) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Mayushi через Telegram")
        InfoHint("Отправка", "TrailSense подготовит файл и сразу откроет системное меню. Выбери Telegram → этот чат → «Отправить».")
        Button(onClick = { vm.shareAiQueue(context) }, Modifier.fillMaxWidth(), enabled = pendingAi > 0 && !busy) {
            Icon(Icons.Default.Share, null)
            Spacer(Modifier.width(8.dp))
            Text(when {
                busy -> "Готовлю файл…"
                pendingAi > 0 -> "Отправить Mayushi ($pendingAi)"
                else -> "Очередь пуста"
            })
        }
        OutlinedButton(onClick = { importTelegramResponse.launch(arrayOf("application/json", "text/json", "application/octet-stream")) }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Download, null)
            Spacer(Modifier.width(8.dp))
            Text("Импортировать ответ Mayushi")
        }
        InfoHint("Ответ Mayushi", "Когда Mayushi пришлёт файл с анализом, в Telegram нажми «Поделиться» → TrailSense или выбери файл кнопкой выше. Ответ появится в соответствующем хайке.")

        HorizontalDivider()
        SectionTitle("Автоматизация")
        OutlinedTextField(sleep, { sleep = it }, Modifier.fillMaxWidth(), label = { Text("Личная норма сна, часы") }, singleLine = true)
        Button(
            onClick = { vm.saveHermesSettings(endpoint, key, budget.toIntOrNull() ?: 0, sleep.toDoubleOrNull() ?: 9.0, false, false, directEnabled) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Сохранить настройки анализа") }

        HorizontalDivider()
        OutlinedButton(onClick = { showDirectConnection = !showDirectConnection }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Cable, null)
            Spacer(Modifier.width(8.dp))
            Text(if (showDirectConnection) "Скрыть прямое подключение" else "Прямое подключение Hermes (необязательно)")
        }
        if (showDirectConnection) {
            InfoHint("Прямое подключение", "Дополнительный способ связи с Mayushi. Для отправки файла через Telegram включать его не нужно.")
            SwitchRow("Разрешить приложению обращаться к Hermes напрямую", directEnabled) { directEnabled = it }
            OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("Адрес Hermes") }, singleLine = true)
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(if (vm.hasSavedKey()) "Новый API-ключ (старый уже сохранён)" else "API-ключ") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(budget, { budget = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Месячный лимит токенов") }, singleLine = true)
            Button(onClick = { vm.saveHermesSettings(endpoint, key, budget.toIntOrNull() ?: 0, sleep.toDoubleOrNull() ?: 9.0, false, false, directEnabled) }, Modifier.fillMaxWidth()) { Text("Сохранить прямое подключение") }
            OutlinedButton(onClick = vm::testHermes, Modifier.fillMaxWidth(), enabled = directEnabled) { Text("Проверить соединение") }
            Button(onClick = vm::runAiQueue, Modifier.fillMaxWidth(), enabled = pendingAi > 0 && directEnabled) { Text(if (pendingAi > 0) "Запустить напрямую ($pendingAi)" else "Очередь пуста") }
        }

        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            OutlinedButton(onClick = { notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }, Modifier.fillMaxWidth()) { Text("Разрешить уведомления календаря") }
        }
        HorizontalDivider()
        SectionTitle("Приватность")
        InfoHint("Приватность", "База, треки, оценки, сон, спутники и календарь хранятся только в закрытом хранилище приложения. В Telegram-файл попадает только компактная сводка из очереди и выбранный тобой скриншот — без API-ключа и сырого GPS-трека.")
    }
}

@Composable fun TrackPreview(points: List<TrackPoint>, modifier: Modifier = Modifier) {
    if (points.size < 2) return
    Card(modifier) { Canvas(Modifier.fillMaxSize().padding(16.dp)) { val minLat = points.minOf { it.latitude }; val maxLat = points.maxOf { it.latitude }; val minLon = points.minOf { it.longitude }; val maxLon = points.maxOf { it.longitude }; val latSpan = (maxLat - minLat).takeIf { it > 0 } ?: 1.0; val lonSpan = (maxLon - minLon).takeIf { it > 0 } ?: 1.0; val path = Path(); points.forEachIndexed { i, p -> val x = ((p.longitude - minLon) / lonSpan * size.width).toFloat(); val y = (size.height - (p.latitude - minLat) / latSpan * size.height).toFloat(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }; drawPath(path, Color(0xFF2389FF), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f)); drawCircle(Coral, 8f, Offset(0f, size.height)); } }
}

@Composable fun RatingField(label: String, value: Int?, notApplicable: Boolean = false, set: (Int?) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            RatingExplanation(label)?.let { InfoHint(label, it) }
        }
        Row(Modifier.fillMaxWidth()) { (1..5).forEach { score -> IconButton(onClick = { set(if (value == score) null else score) }, modifier = Modifier.weight(1f)) { Icon(if ((value ?: 0) >= score) Icons.Default.Circle else Icons.Default.RadioButtonUnchecked, "$label: $score", tint = if ((value ?: 0) >= score) Coral else Color.Gray) } } }
    }
}
@Composable fun RatingDots(value: Int?) { Text(value?.let { "$it / 5" } ?: "—", color = Forest, fontWeight = FontWeight.Bold) }
@Composable fun NumberTextField(label: String, value: Double?, set: (Double?) -> Unit) { OutlinedTextField(value?.toString() ?: "", { set(it.replace(',', '.').toDoubleOrNull()) }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true) }
@Composable fun SwitchRow(label: String, checked: Boolean, set: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, set) } }
@Composable fun <T> TriChoice(label: String, choices: List<Pair<String,T>>, selected: T?, set: (T) -> Unit) { Column { Text(label); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { choices.forEach { (name, v) -> FilterChip(selected == v, { set(v) }, { Text(name) }) } } } }
@OptIn(ExperimentalLayoutApi::class)
@Composable fun ChoiceChips(choices: List<Pair<String,String>>, selected: String, set: (String) -> Unit) { FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { choices.forEach { (name, v) -> FilterChip(selected == v, { set(v) }, { Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }) } } }
@Composable fun RouteChoice(routes: List<RouteEntity>, selectedId: Long?, set: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = routes.firstOrNull { it.id == selectedId }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { expanded = true }, Modifier.fillMaxWidth()) { Text(selected?.title ?: "Не связывать с маршрутом") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Не связывать") }, onClick = { set(null); expanded = false })
            routes.forEach { route -> DropdownMenuItem(text = { Text(route.title) }, onClick = { set(route.id); expanded = false }) }
        }
    }
}
@Composable fun Metric(label: String, value: String) { Card(colors = CardDefaults.cardColors(containerColor = Forest.copy(alpha = .08f))) { Column(Modifier.padding(10.dp)) { Text(value, fontWeight = FontWeight.Bold); Text(label, style = MaterialTheme.typography.labelSmall) } } }
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun AiCard(text: String, input: Int?, output: Int?) {
    var details by remember(text) { mutableStateOf(false) }
    val anchor = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF272D30))) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color(0xFFE4E6E8)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().bringIntoViewRequester(anchor).clickable { details = !details }, verticalAlignment = Alignment.CenterVertically) {
                Text("Анализ Mayushi", Modifier.weight(1f), color = Color(0xFFA4D9C0), fontWeight = FontWeight.Bold)
                Icon(if(details) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if(details) "Свернуть анализ" else "Развернуть анализ", tint = Color(0xFFA4D9C0))
            }
            RichAnalysisText(if (details) text else text.substringBefore("\n\n"))
            if(details) IconButton(onClick = { details = false; scope.launch { anchor.bringIntoView() } }, Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Default.ExpandLess, "Свернуть и вернуться к заголовку", tint = Color(0xFFA4D9C0))
            }
        }
        }
    }
}
@Composable fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
@Composable fun EmptyState(title: String, text: String) { Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Hiking, null, Modifier.size(52.dp), tint = Forest); Text(title, fontWeight = FontWeight.Bold); if (text.isNotBlank()) InfoHint(title, text) } }

@Composable fun DateButton(value: LocalDate, set: (LocalDate) -> Unit) { val context = LocalContext.current; OutlinedButton(onClick = { DatePickerDialog(context, { _, y, m, d -> set(LocalDate.of(y,m+1,d)) }, value.year, value.monthValue-1, value.dayOfMonth).show() }) { Icon(Icons.Default.DateRange, null); Spacer(Modifier.width(8.dp)); Text(value.toString()) } }
@Composable fun DeviationDialog(onDismiss: () -> Unit, save: (LocalDate, LocalDate, DeviationPolicy, String) -> Unit) { var planned by remember { mutableStateOf(LocalDate.now()) }; var actual by remember { mutableStateOf(LocalDate.now()) }; var policy by remember { mutableStateOf(DeviationPolicy.KEEP_PLAN) }; var note by remember { mutableStateOf("") }; ImeAlertDialog(onDismissRequest = onDismiss, title = { Text("Отклонение от плана") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Плановая дата"); DateButton(planned) { planned = it }; Text("Фактическая дата"); DateButton(actual) { actual = it }; Text("Что сделать с расписанием?"); DeviationPolicy.entries.forEach { RadioButton(policy == it, { policy = it }); Text(deviationLabel(it), Modifier.clickable { policy = it }) }; OutlinedTextField(note, { note = it }, label = { Text("Комментарий") }) } }, confirmButton = { TextButton(onClick = { save(planned, actual, policy, note) }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }) }

fun km(m: Double) = String.format(java.util.Locale.getDefault(), "%.2f км", m / 1000)
fun duration(s: Long?): String = s?.let { "%d:%02d".format(it / 3600, (it % 3600) / 60) } ?: "—"
fun dateFromMs(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()

/** The saved route association is consent; a watch title or matching suggestion is not. */
internal fun hikeToolbarTitle(route: RouteEntity?): String =
    route?.takeIf { bound ->
        bound.title.isNotBlank() && sequenceOf(bound.sourceUrl, bound.canonicalUrl)
            .filterNotNull().any { com.mayushi.trailsense.network.AllTrailsMcpClient.validRouteUrl(it) }
    }?.title ?: "Хайк"
fun reminderLabel(kind: PillReminderKind) = when(kind) { PillReminderKind.LAST_ACTIVE -> "Последняя активная таблетка"; PillReminderKind.BREAK_START -> "Начало перерыва"; PillReminderKind.RESTART_EVE -> "Вечер перед возобновлением"; PillReminderKind.RESTART_DAY -> "Первая таблетка нового блока" }
fun deviationLabel(p: DeviationPolicy) = when(p) { DeviationPolicy.KEEP_PLAN -> "Оставить исходный график"; DeviationPolicy.RECALCULATE_ALL -> "Пересчитать текущий и следующие блоки"; DeviationPolicy.NEAREST_BOUNDARY_ONLY -> "Изменить только ближайшую границу" }
