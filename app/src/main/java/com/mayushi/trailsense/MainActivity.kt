package com.mayushi.trailsense

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
    CALENDAR("Календарь", Icons.Default.CalendarMonth),
    SETTINGS("Настройки", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TrailSenseApp(initialIntent: Intent?, vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var routeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var routeEditRequested by rememberSaveable { mutableStateOf(false) }
    var routeMenu by remember { mutableStateOf(false) }
    var deleteRequested by remember { mutableStateOf(false) }
    var analysisChoice by remember { mutableStateOf(false) }
    var navigationHistory by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver<List<Pair<Tab,Long?>>,String>(
        save = { history -> history.map { "${it.first.name}:${it.second ?: ""}" } },
        restore = { values -> values.map { value -> val parts=value.split(":"); Tab.valueOf(parts[0]) to parts[1].toLongOrNull() } }
    )) { mutableStateOf(emptyList<Pair<Tab,Long?>>()) }
    val navigateToTab: (Tab) -> Unit = { destination ->
        if (routeId != null || tab != destination) {
            navigationHistory = navigationHistory + (tab to routeId)
            tab = destination
            routeId = null
        }
    }
    val openRoute: (Long) -> Unit = { id ->
        navigationHistory = navigationHistory + (tab to routeId)
        routeId = id
    }
    val goBack: () -> Unit = {
        val previous = navigationHistory.lastOrNull()
        if (previous != null) {
            navigationHistory = navigationHistory.dropLast(1)
            tab = previous.first
            routeId = previous.second
        } else if (routeId != null) {
            routeId = null
            tab = Tab.HOME
        }
    }
    BackHandler(enabled = routeId != null || navigationHistory.isNotEmpty(), onBack = goBack)
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val sharedFile by vm.pendingSharedFile.collectAsStateWithLifecycle()
    val routes by vm.routes.collectAsStateWithLifecycle()
    val pendingAi by vm.pendingAi.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(initialIntent) { vm.handleIntent(initialIntent) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    sharedFile?.let { AttachSharedFileDialog(it, routes, vm) }
    if (analysisChoice && routeId != null) HikeAnalysisChoice(routeId!!, vm) { analysisChoice = false }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (routeId != null) "Хайк" else tab.title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (routeId != null) IconButton(onClick = goBack) { Icon(Icons.Default.ArrowBack, "Назад") }
                },
                actions = {
                    if (routeId != null) {
                        Box {
                            IconButton(onClick = { routeMenu = true }) { Icon(Icons.Default.MoreVert, "Меню хайка") }
                            DropdownMenu(expanded = routeMenu, onDismissRequest = { routeMenu = false }) {
                                DropdownMenuItem(text = { Text("Изменить") }, onClick = { routeMenu = false; routeEditRequested = true })
                                DropdownMenuItem(text = { Text("Отправить на анализ") }, onClick = { routeMenu = false; analysisChoice = true })
                                DropdownMenuItem(text = { Text("Удалить хайк") }, onClick = { routeMenu = false; deleteRequested = true })
                            }
                        }
                    } else if (tab == Tab.HOME && pendingAi > 0) {
                        TextButton(onClick = { navigateToTab(Tab.SETTINGS) }) {
                            Icon(Icons.Default.PendingActions, null)
                            Spacer(Modifier.width(6.dp))
                            Text("$pendingAi на анализ")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (routeId == null) NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { navigateToTab(item) },
                        icon = { Icon(item.icon, null) },
                        label = { Text(item.title) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                routeId != null -> key(routeId) { RouteEventDetail(routeId!!, vm, goBack, routeEditRequested, { routeEditRequested = false }, deleteRequested, { deleteRequested = false }) }
                else -> when (tab) {
                    Tab.HOME -> EventsScreen(vm, openRoute)
                    Tab.CALENDAR -> ContextCalendarScreen(vm, openRoute)
                    Tab.SETTINGS -> CurrentSettingsScreen(vm)
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

@Composable fun HomeScreen(vm: AppViewModel, goRoutes: () -> Unit, goActivities: () -> Unit) {
    val routes by vm.routes.collectAsStateWithLifecycle(); val activities by vm.activities.collectAsStateWithLifecycle(); val pending by vm.pendingAi.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Твои маршруты — твоими глазами", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Объективные цифры отдельно от того, как маршрут ощущается именно тебе.", color = Color.DarkGray) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { StatCard("Маршруты", routes.size.toString(), Modifier.weight(1f), goRoutes); StatCard("Походы", activities.size.toString(), Modifier.weight(1f), goActivities); StatCard("AI очередь", pending.toString(), Modifier.weight(1f), {}) } }
        item { SectionTitle("Как добавить данные") }
        item { Card { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Маршрут AllTrails", fontWeight = FontWeight.Bold); Text("В AllTrails нажми «Поделиться» → «Мои тропы», либо вставь ссылку в разделе маршрутов."); Text("Тренировка с часов", fontWeight = FontWeight.Bold); Text("Экспортируй GPX/TCX и поделись файлом в это приложение. Все вычисления выполняются локально.") } } }
        item { Card(colors = CardDefaults.cardColors(containerColor = Forest.copy(alpha = .09f))) { Text("Mayushi получает только компактные итоги — не сырой GPS-файл. Запросы ждут в локальной очереди, пока ты не отправишь их через Telegram.", Modifier.padding(18.dp)) } }
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
            Text("Скриншот хранится локально и попадёт только в самодостаточный файл, когда ты нажмёшь «Отправить Mayushi»; распознанные значения нужно проверить.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
        OutlinedTextField(edit.reviewExcerpts, { edit = edit.copy(reviewExcerpts = it) }, Modifier.fillMaxWidth(), label = { Text("Фрагменты отзывов для анализа") }, supportingText = { Text("Вставь нужные отзывы сама — приложение не скрейпит AllTrails.") }, minLines = 3)
        Button(onClick = { vm.saveRoute(edit) }, Modifier.fillMaxWidth()) { Text("Сохранить оценки") }
        SectionTitle("Mayushi")
        AiCard(aiShort?.response ?: "Короткий анализ ждёт в очереди. Отправь задания Mayushi через Telegram в настройках.", aiShort?.promptTokens, aiShort?.completionTokens)
        aiDeep?.let { AiCard(it.response, it.promptTokens, it.completionTokens) }
        OutlinedButton(onClick = { vm.requestRouteAnalysis(id, "DEEP_ROUTE") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Глубокий анализ отзывов") }
        r.sourceUrl?.let { val context = LocalContext.current; TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }) { Text("Открыть исходник AllTrails") } }
    }
}

@Composable fun ActivityDetail(id: Long, vm: AppViewModel) {
    val activity by vm.activity(id).collectAsStateWithLifecycle(null); val companions by vm.companions.collectAsStateWithLifecycle(); val savedIds by vm.companionIds(id).collectAsStateWithLifecycle(emptyList()); val ai by vm.latestAi("ACTIVITY", id, "POST_HIKE").collectAsStateWithLifecycle(null); val screenshotAi by vm.latestAi("ACTIVITY", id, "HUAWEI_SCREENSHOT").collectAsStateWithLifecycle(null)
    val a = activity ?: return EmptyState("Загрузка", "")
    var edit by remember(a.updatedAt) { mutableStateOf(a) }; var selected by remember(savedIds) { mutableStateOf(savedIds.toSet()) }; var newCompanion by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(edit.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (edit.trackEncoded.isNotBlank()) TrackPreview(TrackCodec.decode(edit.trackEncoded), Modifier.fillMaxWidth().height(180.dp))
        FlowMetrics(edit)
        if (edit.sourceFormat == "HUAWEI_SCREENSHOT") {
            AiCard(screenshotAi?.response ?: "Показатели со скриншота ждут обработки. Отправь очередь Mayushi через Telegram в настройках.", screenshotAi?.promptTokens, screenshotAi?.completionTokens)
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
        RatingField("Общая нагрузка", edit.overallEffort) { edit = edit.copy(overallEffort = it) }
        RatingField("Подъём", edit.climbEffort) { edit = edit.copy(climbEffort = it) }
        RatingField("Спуск", edit.descentEffort) { edit = edit.copy(descentEffort = it) }
        RatingField("Техничность", edit.technicality) { edit = edit.copy(technicality = it) }
        RatingField("Удовольствие", edit.enjoyment) { edit = edit.copy(enjoyment = it) }
        SectionTitle("Сон")
        NumberTextField("Часов сна (твоя норма 9 ч)", edit.sleepHours) { edit = edit.copy(sleepHours = it) }
        RatingField("Качество сна", edit.sleepQuality) { edit = edit.copy(sleepQuality = it) }
        TriChoice("Выспалась?", listOf("Да" to true, "Нет" to false), edit.feltRested) { edit = edit.copy(feltRested = it) }
        SectionTitle("Аномалия")
        ChoiceChips(listOf("Подозрительно тяжело" to "HARD", "Ничего необычного" to "NORMAL", "Подозрительно легко" to "EASY"), edit.anomaly) { edit = edit.copy(anomaly = it) }
        SectionTitle("Темп и спутники")
        SwitchRow("Скорость отражает мой обычный темп", edit.representativeSpeed) { edit = edit.copy(representativeSpeed = it) }
        ChoiceChips(listOf("Свободный" to "FREE", "Чуть медленнее" to "SLIGHT", "Заметно медленнее" to "NOTABLE", "Сильно медленнее" to "MUCH"), edit.paceConstraint) { edit = edit.copy(paceConstraint = it, representativeSpeed = it == "FREE") }
        SwitchRow("Были ожидания / вынужденные остановки", edit.hadWaitingStops) { edit = edit.copy(hadWaitingStops = it) }
        companions.forEach { c -> FilterChip(selected = c.id in selected, onClick = { selected = if (c.id in selected) selected - c.id else selected + c.id }, label = { Text(c.name) }) }
        Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(newCompanion, { newCompanion = it }, Modifier.weight(1f), label = { Text("Новый спутник") }, singleLine = true); IconButton(onClick = { vm.addCompanion(newCompanion); newCompanion = "" }) { Icon(Icons.Default.PersonAdd, "Добавить") } }
        OutlinedTextField(edit.comment, { edit = edit.copy(comment = it) }, Modifier.fillMaxWidth(), label = { Text("Комментарий") }, minLines = 3)
        Button(onClick = { vm.saveActivity(edit, selected) }, Modifier.fillMaxWidth()) { Text("Сохранить и проанализировать") }
        SectionTitle("Mayushi")
        AiCard(ai?.response ?: "Анализ ждёт в очереди. Отправь задания Mayushi через Telegram в настройках.", ai?.promptTokens, ai?.completionTokens)
    }
}

@Composable fun FlowMetrics(a: ActivityEntity) {
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
    var shortAuto by remember { mutableStateOf(prefs.shortAutoAnalysis) }
    var postAuto by remember { mutableStateOf(prefs.postHikeAutoAnalysis) }
    var directEnabled by remember { mutableStateOf(prefs.directHermesEnabled) }
    var showDirectConnection by rememberSaveable { mutableStateOf(false) }
    val pendingAi by vm.pendingAi.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val importTelegramResponse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importTelegramResponse) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Mayushi через Telegram")
        Text("TrailSense подготовит файл и сразу откроет системное меню. Выбери Telegram → этот чат → «Отправить».")
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
        Text("Когда Mayushi пришлёт ответный JSON-файл, в Telegram нажми «Поделиться» → TrailSense или выбери его кнопкой выше. Готовые анализы сами попадут в нужные события.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider()
        SectionTitle("Автоматизация")
        OutlinedTextField(sleep, { sleep = it }, Modifier.fillMaxWidth(), label = { Text("Личная норма сна, часы") }, singleLine = true)
        SwitchRow("Короткий анализ нового маршрута автоматически", shortAuto) { shortAuto = it }
        SwitchRow("Анализ после похода автоматически", postAuto) { postAuto = it }
        Button(
            onClick = { vm.saveHermesSettings(endpoint, key, budget.toIntOrNull() ?: 0, sleep.toDoubleOrNull() ?: 9.0, shortAuto, postAuto, directEnabled) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Сохранить настройки анализа") }

        HorizontalDivider()
        OutlinedButton(onClick = { showDirectConnection = !showDirectConnection }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Cable, null)
            Spacer(Modifier.width(8.dp))
            Text(if (showDirectConnection) "Скрыть прямое подключение" else "Прямое подключение Hermes (необязательно)")
        }
        if (showDirectConnection) {
            Text("Запасной режим для собственного приватного сервера. Для обмена через Telegram он не нужен.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow("Разрешить приложению обращаться к Hermes напрямую", directEnabled) { directEnabled = it }
            OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("Адрес Hermes") }, singleLine = true)
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(if (vm.hasSavedKey()) "Новый API-ключ (старый уже сохранён)" else "API-ключ") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(budget, { budget = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Месячный лимит токенов") }, singleLine = true)
            Button(onClick = { vm.saveHermesSettings(endpoint, key, budget.toIntOrNull() ?: 0, sleep.toDoubleOrNull() ?: 9.0, shortAuto, postAuto, directEnabled) }, Modifier.fillMaxWidth()) { Text("Сохранить прямое подключение") }
            OutlinedButton(onClick = vm::testHermes, Modifier.fillMaxWidth(), enabled = directEnabled) { Text("Проверить соединение") }
            Button(onClick = vm::runAiQueue, Modifier.fillMaxWidth(), enabled = pendingAi > 0 && directEnabled) { Text(if (pendingAi > 0) "Запустить напрямую ($pendingAi)" else "Очередь пуста") }
        }

        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            OutlinedButton(onClick = { notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }, Modifier.fillMaxWidth()) { Text("Разрешить уведомления календаря") }
        }
        HorizontalDivider()
        SectionTitle("Приватность")
        Text("База, треки, оценки, сон, спутники и календарь хранятся только в закрытом хранилище приложения. В Telegram-файл попадает только компактная сводка из очереди и выбранный тобой скриншот — без API-ключа и сырого GPS-трека.")
    }
}

@Composable fun TrackPreview(points: List<TrackPoint>, modifier: Modifier = Modifier) {
    if (points.size < 2) return
    Card(modifier) { Canvas(Modifier.fillMaxSize().padding(16.dp)) { val minLat = points.minOf { it.latitude }; val maxLat = points.maxOf { it.latitude }; val minLon = points.minOf { it.longitude }; val maxLon = points.maxOf { it.longitude }; val latSpan = (maxLat - minLat).takeIf { it > 0 } ?: 1.0; val lonSpan = (maxLon - minLon).takeIf { it > 0 } ?: 1.0; val path = Path(); points.forEachIndexed { i, p -> val x = ((p.longitude - minLon) / lonSpan * size.width).toFloat(); val y = (size.height - (p.latitude - minLat) / latSpan * size.height).toFloat(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }; drawPath(path, Color(0xFF2389FF), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f)); drawCircle(Coral, 8f, Offset(0f, size.height)); } }
}

@Composable fun RatingField(label: String, value: Int?, set: (Int) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); (1..5).forEach { score -> IconButton(onClick = { set(score) }) { Icon(if ((value ?: 0) >= score) Icons.Default.Circle else Icons.Default.RadioButtonUnchecked, score.toString(), tint = if ((value ?: 0) >= score) Coral else Color.Gray) } } } }
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
@Composable fun AiCard(text: String, input: Int?, output: Int?) {
    var details by remember(text) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Coral.copy(alpha = .12f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RichAnalysisText(if (details) text else text.substringBefore("\n\n"))
            if (text.contains("\n\n") || input != null || output != null) TextButton(onClick = { details = !details }) { Text(if (details) "Скрыть детали" else "Детали анализа") }
            if (details && (input != null || output != null)) Text("Токены: ${(input ?: 0) + (output ?: 0)}", style = MaterialTheme.typography.labelSmall)
        }
    }
}
@Composable fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
@Composable fun EmptyState(title: String, text: String) { Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Hiking, null, Modifier.size(52.dp), tint = Forest); Text(title, fontWeight = FontWeight.Bold); if (text.isNotBlank()) Text(text, color = Color.Gray) } }

@Composable fun DateButton(value: LocalDate, set: (LocalDate) -> Unit) { val context = LocalContext.current; OutlinedButton(onClick = { DatePickerDialog(context, { _, y, m, d -> set(LocalDate.of(y,m+1,d)) }, value.year, value.monthValue-1, value.dayOfMonth).show() }) { Icon(Icons.Default.DateRange, null); Spacer(Modifier.width(8.dp)); Text(value.toString()) } }
@Composable fun DeviationDialog(onDismiss: () -> Unit, save: (LocalDate, LocalDate, DeviationPolicy, String) -> Unit) { var planned by remember { mutableStateOf(LocalDate.now()) }; var actual by remember { mutableStateOf(LocalDate.now()) }; var policy by remember { mutableStateOf(DeviationPolicy.KEEP_PLAN) }; var note by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = onDismiss, title = { Text("Отклонение от плана") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Плановая дата"); DateButton(planned) { planned = it }; Text("Фактическая дата"); DateButton(actual) { actual = it }; Text("Что сделать с расписанием?"); DeviationPolicy.entries.forEach { RadioButton(policy == it, { policy = it }); Text(deviationLabel(it), Modifier.clickable { policy = it }) }; OutlinedTextField(note, { note = it }, label = { Text("Комментарий") }) } }, confirmButton = { TextButton(onClick = { save(planned, actual, policy, note) }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }) }

fun km(m: Double) = String.format(java.util.Locale.getDefault(), "%.2f км", m / 1000)
fun duration(s: Long?): String = s?.let { "%d:%02d".format(it / 3600, (it % 3600) / 60) } ?: "—"
fun dateFromMs(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()
fun reminderLabel(kind: PillReminderKind) = when(kind) { PillReminderKind.LAST_ACTIVE -> "Последняя активная таблетка"; PillReminderKind.BREAK_START -> "Начало перерыва"; PillReminderKind.RESTART_EVE -> "Вечер перед возобновлением"; PillReminderKind.RESTART_DAY -> "Первая таблетка нового блока" }
fun deviationLabel(p: DeviationPolicy) = when(p) { DeviationPolicy.KEEP_PLAN -> "Оставить исходный график"; DeviationPolicy.RECALCULATE_ALL -> "Пересчитать текущий и следующие блоки"; DeviationPolicy.NEAREST_BOUNDARY_ONLY -> "Изменить только ближайшую границу" }
