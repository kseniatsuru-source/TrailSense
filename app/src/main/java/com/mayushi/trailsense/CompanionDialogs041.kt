package com.mayushi.trailsense

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mayushi.trailsense.data.*
import kotlinx.coroutines.launch

@Composable
fun CompanionPicker041(vm: AppViewModel, initial: Set<Long>, dismiss: () -> Unit, done: (Set<Long>) -> Unit) {
    val people by vm.companions.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(initial) }
    var search by remember { mutableStateOf("") }
    // History links can change without changing people; observe both through Room.
    val frequent by vm.frequentCompanionIds.collectAsStateWithLifecycle()
    var add by remember { mutableStateOf(false) }; var manage by remember { mutableStateOf(false) }
    fun toggle(id: Long) { selected=if(id in selected) selected-id else selected+id }
    val active=people.filterNot { it.deleted }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    ImeAlertDialog(onDismissRequest=dismiss,title={ Row(verticalAlignment=Alignment.CenterVertically) {
        Text("Путники",Modifier.weight(1f)); TextButton(onClick={done(selected)}) { Text("Готово") }
    } },text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(search,{search=it},Modifier.fillMaxWidth(),placeholder={Text("Поиск по имени")},singleLine=true)
        if(frequent.isNotEmpty() && search.isBlank()) {
            Text("Часто ходите вместе",style=MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                frequent.mapNotNull { id -> active.firstOrNull { it.id==id } }.forEach { p -> FilterChip(p.id in selected,{toggle(p.id)},{Text(p.name)}) }
            }
        }
        active.filter { it.name.contains(search,true) }.forEach { p ->
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(p.id in selected,role=Role.Checkbox,onValueChange={toggle(p.id)}),verticalAlignment=Alignment.CenterVertically) {
                Checkbox(p.id in selected,null); Text(p.name,Modifier.weight(1f))
            }
        }
        if(selected.any { id -> people.any { it.id==id && it.deleted } }) Text("Из истории: "+people.filter {it.id in selected && it.deleted}.joinToString {it.name},style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={add=true}) {Text("+ Добавить путника")}
        TextButton(onClick={manage=true}) {Text("Редактировать список")}
    } },confirmButton={})
    if(add) CompanionName041(vm,null,{add=false}) { id -> selected=selected+id;add=false }
    if(manage) CompanionSettings041(vm) {manage=false}
}

@Composable
fun CompanionSettings041(vm: AppViewModel,dismiss: () -> Unit) {
    val people by vm.companions.collectAsStateWithLifecycle()
    var deleted by remember {mutableStateOf(false)}
    var editing by remember {mutableStateOf<CompanionEntity?>(null)}; var add by remember {mutableStateOf(false)}
    var deleting by remember {mutableStateOf<CompanionEntity?>(null)}; var restoring by remember {mutableStateOf<CompanionEntity?>(null)}
    var error by remember {mutableStateOf<String?>(null)}; val scope=rememberCoroutineScope()
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
      Surface(Modifier.fillMaxSize().systemBarsPadding()) { Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=dismiss) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"Назад")}
            Text("Настройки · Путники",style=MaterialTheme.typography.titleLarge)
        }
        TabRow(selectedTabIndex=if(deleted) 1 else 0) {
            Tab(!deleted,{deleted=false},text={Text("В списке")})
            Tab(deleted,{deleted=true},text={Text("Удалённые")})
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick={add=true}) {Text("+ Добавить путника")}
        people.filter {it.deleted==deleted}.forEach {p -> Card(Modifier.fillMaxWidth().padding(vertical=4.dp)) {
            Row(Modifier.padding(horizontal=8.dp).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(p.name,Modifier.weight(1f).clickable {editing=p}.padding(8.dp))
                TextButton(onClick={if(deleted) restoring=p else deleting=p}) {Text(if(deleted) "Восстановить" else "Удалить")}
            }
        } }
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
      } }
    }
    if(add || editing!=null) CompanionName041(vm,editing,{add=false;editing=null}) {add=false;editing=null}
    deleting?.let {p -> ImeAlertDialog(onDismissRequest={deleting=null},title={Text("Удалить путника?")},text={Text("После удаления из списка путников человек останется в тех хайках, где уже был отмечен.\n\nЗаписи прошлых хайков сохранятся.")},confirmButton={Button(onClick={scope.launch {runCatching {vm.archiveCompanion(p.id,true)}.onSuccess {deleting=null}.onFailure {error=it.message;deleting=null}}}) {Text("Удалить")}},dismissButton={TextButton(onClick={deleting=null}) {Text("Отмена")}}) }
    restoring?.let {p -> CompanionRestore041(vm,listOf(p),{restoring=null},{restoring=null;editing=null;add=true}) {restoring=null} }
}

@Composable
private fun CompanionName041(vm: AppViewModel,person: CompanionEntity?,dismiss: () -> Unit,done: (Long) -> Unit) {
    val people by vm.companions.collectAsStateWithLifecycle()
    var name by remember(person?.id) {mutableStateOf(person?.name.orEmpty())}
    var matches by remember {mutableStateOf(emptyList<CompanionEntity>())}
    var error by remember {mutableStateOf<String?>(null)}; var saving by remember {mutableStateOf(false)}
    var other by remember {mutableStateOf(false)}; val scope=rememberCoroutineScope()
    ImeAlertDialog(onDismissRequest=dismiss,title={Text(if(person==null) "Добавить путника" else "Изменить имя")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(name,{name=it;error=null},Modifier.fillMaxWidth(),label={Text(if(other) "Уточни имя другого человека" else "Имя")},singleLine=true)
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(enabled=!saving && name.isNotBlank(),onClick={
        val deleted=people.filter {it.deleted && it.id!=person?.id && CompanionIdentity.key(it.name)==CompanionIdentity.key(name)}
        if(person==null && deleted.isNotEmpty()) {
            if(other) error="Уточни имя: оно должно отличаться от имени в истории." else matches=deleted
        } else {
            saving=true;scope.launch {runCatching {if(person==null) vm.createCompanion(name) else {vm.renameCompanion(person.id,name);person.id}}.onSuccess(done).onFailure {error=it.message};saving=false}
        }
    }) {Text("Сохранить")}},dismissButton={TextButton(onClick=dismiss) {Text("Отмена")}})
    if(matches.isNotEmpty()) CompanionRestore041(vm,matches,{matches=emptyList()},{matches=emptyList();other=true;name=""},done)
}

@Composable
private fun CompanionRestore041(vm: AppViewModel,matches: List<CompanionEntity>,dismiss: () -> Unit,other: () -> Unit,done: (Long) -> Unit) {
    var chosen by remember(matches) {mutableStateOf(matches.singleOrNull()?.id)}
    var examples by remember {mutableStateOf(emptyMap<Long,List<CompanionExample>>())}
    var error by remember {mutableStateOf<String?>(null)};val scope=rememberCoroutineScope()
    LaunchedEffect(matches) {examples=matches.associate {it.id to vm.companionExamples(it.id)}}
    ImeAlertDialog(onDismissRequest=dismiss,title={Text("Среди удалённых путников есть ${matches.first().name}.")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Хочешь вернуть его в список?")
        matches.forEach {p ->
            if(matches.size>1) Row(Modifier.fillMaxWidth().clickable {chosen=p.id},verticalAlignment=Alignment.CenterVertically) {RadioButton(chosen==p.id,{chosen=p.id});Text(p.name)}
            val rows=examples[p.id].orEmpty()
            if(rows.isNotEmpty()) Text("Вы ходили вместе:")
            rows.forEach {h -> Text(h.routeTitle ?: h.title);Text(h.manualDateEpochDay?.let {java.time.LocalDate.ofEpochDay(it).toString()} ?: h.startedAt?.let(::dateFromMs) ?: "Дата не указана",style=MaterialTheme.typography.bodySmall)}
        }
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        Button(enabled=chosen!=null,onClick={scope.launch {runCatching {vm.archiveCompanion(requireNotNull(chosen),false)}.onSuccess {done(requireNotNull(chosen))}.onFailure {error=it.message}}},modifier=Modifier.fillMaxWidth()) {Text("Да, восстановить")}
        OutlinedButton(onClick=other,modifier=Modifier.fillMaxWidth()) {Text("Нет, другой человек")}
    }},confirmButton={},dismissButton={TextButton(onClick=dismiss) {Text("Отмена")}})
}
