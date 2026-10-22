package com.mayushi.trailsense

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Edit

import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mayushi.trailsense.data.ActivityEntity
import com.mayushi.trailsense.data.RouteEntity
import com.mayushi.trailsense.domain.Questionnaire037

/** See private/scratch/questionnaire037-api.md for the atomic history/OCR save contract. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HikeQuestionnaire037(
    route: RouteEntity,
    activity: ActivityEntity,
    vm: AppViewModel,
    history: String = "",
    suggestedSleepHours: Double? = null,
    legacySleepHours: Double? = null,
    onSave: ((RouteEntity, ActivityEntity, Set<Long>, String, () -> Unit) -> Unit)? = null,
    listScrollState: ScrollState? = null,
    viewportTopInRoot: Float = 0f,
) {
    val companions by vm.companions.collectAsStateWithLifecycle()
    val savedIds by vm.companionIds(activity.id).collectAsStateWithLifecycle(emptyList())
    val savedSegments by vm.paceSegments(activity.id).collectAsStateWithLifecycle(emptyList())
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pageScope=rememberCoroutineScope()
    var expanded by remember(activity.id) { mutableStateOf(false) }
    var initialized by remember(activity.id) { mutableStateOf(false) }
    var editSleep by remember(activity.id) { mutableStateOf(false) }
    var editWeather by remember(activity.id) { mutableStateOf(false) }
    var weather by remember(activity.id) { mutableStateOf(com.mayushi.trailsense.domain.WeatherContext.read(history)) }
    var draft by remember(activity.id) { mutableStateOf(activity) }
    var routeDraft by remember(route.id) { mutableStateOf(route) }
    var notes by remember(activity.id) { mutableStateOf("") }
    var selected by remember(activity.id) { mutableStateOf(emptySet<Long>()) }
    var idsDirty by remember(activity.id) { mutableStateOf(false) }
    LaunchedEffect(savedIds, idsDirty) { if (!idsDirty) selected = savedIds.toSet() }
    var selectCompanions by remember(activity.id) { mutableStateOf(false) }
    var climb by remember(activity.id) { mutableStateOf<Int?>(null) }
    var originalSleep by remember(activity.id) { mutableStateOf(legacySleepHours ?: activity.sleepHours) }
    var sleepDirty by remember(activity.id) { mutableStateOf(false) }
    var sleepResolved by remember(activity.id, suggestedSleepHours) { mutableStateOf(false) }
    var error by remember(activity.id) { mutableStateOf<String?>(null) }
    var sleepValid by remember(activity.id) { mutableStateOf(true) }
    var climbTouched by remember(activity.id) { mutableStateOf(false) }
    val ratingStates = remember(activity.id) { mutableStateMapOf<String,String>().apply { putAll(com.mayushi.trailsense.domain.RatingApplicability.states(history)) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.saveSleepScreenshot(activity.id, it) }
    }
    val actualHours = Questionnaire037.effectiveSleep(activity.sleepHours, draft.sleepHours, sleepDirty)
    val sleepConflict = Questionnaire037.sleepConflict(originalSleep, suggestedSleepHours)
    var sectionTop by remember(activity.id) { mutableFloatStateOf(Float.POSITIVE_INFINITY) }
    var sectionHeight by remember(activity.id) { mutableFloatStateOf(0f) }
    var headerHeight by remember(activity.id) { mutableFloatStateOf(0f) }
    Card(Modifier.fillMaxWidth().testTag("hike-questionnaire").onGloballyPositioned {
        // Store content-space bounds, not the translated header's bounds (no feedback loop).
        sectionTop = it.positionInRoot().y - viewportTopInRoot + (listScrollState?.value ?: 0)
        sectionHeight = it.size.height.toFloat()
    }) {
        Column {
            Row(Modifier.fillMaxWidth().zIndex(1f).graphicsLayer {
                translationY = if (listScrollState == null) 0f else questionnaireHeaderOffset(
                    listScrollState.value.toFloat(), sectionTop, sectionHeight, headerHeight)
            }.background(Color(0xFF23272B)).onSizeChanged { headerHeight = it.height.toFloat() }
                .padding(horizontal = 14.dp, vertical = 6.dp).testTag("questionnaire-header"),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Моя анкета", fontWeight = FontWeight.Bold, color = Color(0xFFE4E6E8))
                    if (!expanded) Text(if (activity.questionnaireCompletedAt == null) "Не заполнена" else "Заполнена · можно изменить", style = MaterialTheme.typography.labelSmall, color = Color(0xFFE4E6E8))
                }
                TextButton(onClick = {
                    if (!initialized) {
                        initialized = true;
                        draft = activity; routeDraft = route
                        weather = com.mayushi.trailsense.domain.WeatherContext.read(history)
                        notes = Questionnaire037.notesForDisplay(Questionnaire037.mergeNotes(activity.comment, route.notes, activity.customRatings, activity.pointsOfInterest))
                        selected = savedIds.toSet(); idsDirty = false
                        climb = Questionnaire037.initialClimb(route.climbDifficulty, activity.climbEffort); climbTouched = false; sleepValid = true
                        originalSleep = legacySleepHours ?: activity.sleepHours; sleepDirty = false; sleepResolved = false; error = null
                    }
                    val collapsing=expanded
                    expanded = !expanded
                    if(collapsing && listScrollState != null && sectionTop.isFinite()) {
                        pageScope.launch {listScrollState.scrollTo(sectionTop.toInt().coerceAtLeast(0))}
                    }
                }) { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if(expanded) "Свернуть анкету" else "Открыть анкету", tint = Color(0xFFE4E6E8)) }
            }
            if (expanded) {
                // Eager inline content shares the page scroll; offscreen movement cannot dispose the draft.
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Путники, рюкзак и темп")
                Text(if (selected.isEmpty()) "Шла одна" else companions.filter { it.id in selected }.joinToString(" · ") { it.name })
                TextButton(onClick={selectCompanions=true}) { Text("Изменить путников") }
                if(selectCompanions) CompanionPicker041(vm,selected,{selectCompanions=false}) { ids -> selected=ids;idsDirty=true;selectCompanions=false }
                SwitchRow("Тяжёлый рюкзак ≈15 кг", draft.heavyBackpack) { draft = draft.copy(heavyBackpack = it) }
                QuestionnaireSetter037((listOf("Я", "Смешанный", "Без спутников") + companions.filter { it.id in selected }.map { it.name } + draft.paceSetter).distinct(), draft.paceSetter) { draft = draft.copy(paceSetter = it) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Questionnaire037.paceLabels.forEach { label ->
                        FilterChip(Questionnaire037.paceLabel(draft.paceConstraint) == label, {
                            val value = Questionnaire037.selectPace(draft.paceConstraint, label)
                            if (value != draft.paceConstraint) draft = draft.copy(paceConstraint = value, representativeSpeed = value == "FREE")
                        }, { Text(label) })
                    }
                }
                if (draft.paceConstraint == "MUCH") InfoHint("Прежний темп", "Сохранён прежний вариант «Сильно медленнее»: без смены выбора он останется без изменений.")
                if (Questionnaire037.paceLabel(draft.paceConstraint) == null) InfoHint("Прежний темп", "Ранее сохранённый вариант темпа отличается от текущих вариантов. Он останется без изменений, если не выбрать новый.")
                SwitchRow("Скорость отражает мой обычный темп", draft.representativeSpeed) { draft = draft.copy(representativeSpeed = it) }
                SwitchRow("Были ожидания / вынужденные остановки", draft.hadWaitingStops) { draft = draft.copy(hadWaitingStops = it) }
                if (savedSegments.isNotEmpty()) InfoHint("Прежние участки", "Прежние участки темпа сохранены без изменений.")

                SectionTitle("Впечатления")
                ApplicableRating("Виды", routeDraft.beauty, ratingStates) { routeDraft = routeDraft.copy(beauty = it) }
                ApplicableRating("Мои впечатления", draft.enjoyment, ratingStates) { draft = draft.copy(enjoyment = it) }
                ApplicableRating("Общая тяжесть маршрута", routeDraft.overallDifficulty, ratingStates) { routeDraft = routeDraft.copy(overallDifficulty = it) }
                ApplicableRating("Общая нагрузка", draft.overallEffort, ratingStates) { draft = draft.copy(overallEffort = it) }
                if (Questionnaire037.climbConflict(route.climbDifficulty, activity.climbEffort)) Text("Прежние оценки подъёма различаются: маршрут ${route.climbDifficulty}/5, поход ${activity.climbEffort}/5. Выбери одну субъективную оценку; обе исходные сохранятся в истории.")
                ApplicableRating("Сложность подъёма", climb, ratingStates) { climb = it; climbTouched = true }

                ApplicableRating("Спуск", draft.descentEffort, ratingStates) { draft = draft.copy(descentEffort = it) }
                ApplicableRating("Техничность", draft.technicality, ratingStates) { draft = draft.copy(technicality = it); routeDraft = routeDraft.copy(technicality = it) }
                ApplicableRating("Экспозиция", routeDraft.exposure, ratingStates) { routeDraft = routeDraft.copy(exposure = it) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("По сравнению с обычным", Modifier.weight(1f)); InfoHint("По сравнению с обычным", "Сравни свои ощущения с привычным походом похожей сложности. Помогает заметить необычную нагрузку, но не устанавливает её причину.") }
                ChoiceChips(listOf("Тяжелее обычного" to "HARD", "Обычно" to "NORMAL", "Легче обычного" to "EASY"), draft.anomaly) { draft = draft.copy(anomaly = it) }

                SectionTitle("Сон")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { picker.launch(arrayOf("image/*")) }, modifier=Modifier.weight(1f), enabled = !busy) { Text(if (activity.sleepScreenshotPath == null) "Добавить данные сна" else "Обновить данные сна", fontWeight = FontWeight.Normal) }
                    InfoHint("Сон", "Загрузи скриншот приложения сна или часов: длительность будет прочитана на устройстве. Используется фактический сон, не время между отходом ко сну и пробуждением. Качество и ощущение отдыха оцениваешь ты.")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(actualHours?.let { val m = kotlin.math.round(it * 60).toInt(); "${m / 60} ч ${m % 60} мин" } ?: "Указать длительность",Modifier.weight(1f))
                    IconButton(onClick = { editSleep = true }) { Icon(Icons.Default.Edit, "Изменить длительность сна") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Выспалась?",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                    listOf(true to "Да", false to "Нет").forEach { (v,l) ->
                        OutlinedButton(onClick = { draft = draft.copy(feltRested = if(draft.feltRested == v) null else v) },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 32.dp).semantics { this.selected = draft.feltRested == v },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = if(draft.feltRested == v) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)) {
                            Text(l,maxLines=1,softWrap=false,style=MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                ApplicableRating("Как я оцениваю сон", draft.sleepQuality, ratingStates) { draft = draft.copy(sleepQuality = it) }

                TextButton(onClick = { editWeather = true }) { Text("Погода") }
                if(editWeather) WeatherDialog041(weather, { editWeather = false }) { weather = it; editWeather = false }

                OutlinedTextField(notes, { notes = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Заметки к походу") }, minLines = 3)
                InfoHint("Заметки, критерии и ориентиры", "Запиши, что повлияло на поход: спутники, рюкзак, чужой темп, вынужденные остановки или впечатления. Это помогает рассматривать цифры в контексте, а не угадывать причины.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = {
                    try {
                        require(sleepValid) { "Проверь длительность сна: ЧЧ:мм" }
                        require(!Questionnaire037.climbConflict(route.climbDifficulty, activity.climbEffort) || climb != null || climbTouched) { "Выбери оценку сложности подъёма или Н/П" }
                        // Existing manual duration is retained unless explicitly edited.
                        val parsed = Questionnaire037.parseNotes(Questionnaire037.notesForStorage(notes))
                        val hours = Questionnaire037.effectiveSleep(activity.sleepHours, draft.sleepHours, sleepDirty)
                        require(hours == null || (hours.isFinite() && hours in 0.0..24.0)) { "Часы сна: от 0 до 24" }
                        var audit = Questionnaire037.climbHistory(history, route.climbDifficulty, activity.climbEffort, climb)
                        audit = com.mayushi.trailsense.domain.RatingApplicability.save(audit, ratingStates)
                        audit = weather.save(audit)
                        if (activity.paceConstraint != draft.paceConstraint) audit = Questionnaire037.appendHistory(audit, "pace|previous=${activity.paceConstraint}|chosen=${draft.paceConstraint}")
                        if (sleepDirty && (sleepConflict || hours != activity.sleepHours)) audit = Questionnaire037.appendHistory(audit, "sleep|previous=$originalSleep|latest=${activity.sleepHours}|suggested=$suggestedSleepHours|chosen=$hours")
                        require(audit.isEmpty() || onSave != null) { "Сохранение истории ещё не подключено. Исходные оценки не изменены." }
                        // Copy ONLY questionnaire fields onto the latest activity: screenshots/OCR/watch data stay current.
                        val result = activity.copy(customRatings = parsed.ratings, pointsOfInterest = parsed.pois,
                            comment = parsed.comment, overallEffort = draft.overallEffort, climbEffort = climb,
                            descentEffort = draft.descentEffort, technicality = draft.technicality, enjoyment = draft.enjoyment,
                            sleepHours = hours, sleepQuality = draft.sleepQuality, feltRested = draft.feltRested,
                            anomaly = draft.anomaly, paceSetter = draft.paceSetter, paceConstraint = draft.paceConstraint,
                            representativeSpeed = draft.representativeSpeed, hadWaitingStops = draft.hadWaitingStops,
                            heavyBackpack = draft.heavyBackpack)
                        val resultRoute = route.copy(notes = "", overallDifficulty = routeDraft.overallDifficulty,
                            climbDifficulty = climb, technicality = routeDraft.technicality, exposure = routeDraft.exposure, beauty = routeDraft.beauty)
                        val success: () -> Unit = { expanded = false; error = null }
                        val saver = onSave
                        if (saver != null) saver(resultRoute, result, selected, audit, success)
                        else vm.saveHikeQuestionnaire(resultRoute, result, selected, savedSegments, success)
                    } catch (e: IllegalArgumentException) { error = e.message ?: "Проверь анкету" }
                }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Сохранить анкету") }
                }
            }
        }
    }
    if (editSleep) SleepDurationDialog(actualHours, dismiss = { editSleep = false }) { hours ->
        draft = draft.copy(sleepHours = hours); sleepDirty = true; sleepResolved = true
        sleepValid = true; editSleep = false
    }
}

/** Push the header out with the section end; never pin it over the following analysis section. */
internal fun questionnaireHeaderOffset(scroll: Float, sectionTop: Float, sectionHeight: Float, headerHeight: Float): Float =
    (scroll - sectionTop).coerceIn(0f, (sectionHeight - headerHeight).coerceAtLeast(0f))

@Composable
private fun QuestionnaireSetter037(values: List<String>, selected: String, set: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun label(value: String) = when (value) { "SELF" -> "Я"; "MIXED" -> "Смешанный"; "COMPANION" -> "Путник"; else -> value }
    Column {
        Text("Чьей скоростью шли в целом")
        OutlinedButton(onClick = { open = true }) { Text(label(selected)) }
        DropdownMenu(open, { open = false }) {
            values.distinctBy { label(it) }.forEach { value ->
                DropdownMenuItem(text = { Text(label(value)) }, onClick = { set(when(value) { "Я" -> "SELF"; "Смешанный" -> "MIXED"; else -> value }); open = false })
            }
        }
    }
}
