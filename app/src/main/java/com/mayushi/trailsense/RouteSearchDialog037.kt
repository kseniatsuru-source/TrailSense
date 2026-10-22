package com.mayushi.trailsense

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mayushi.trailsense.data.ActivityEntity
import com.mayushi.trailsense.data.RouteEntity
import com.mayushi.trailsense.domain.TrackCodec
import com.mayushi.trailsense.network.AllTrailsMcpClient

@Composable
internal fun RouteSearchDialog(route: RouteEntity, activity: ActivityEntity, vm: AppViewModel,
    initialCandidates: List<AllTrailsMcpClient.Candidate> = emptyList(), autoStart: Boolean = false, dismiss: () -> Unit) {
    val context = LocalContext.current
    val routes by vm.routes.collectAsStateWithLifecycle()
    val activities by vm.activities.collectAsStateWithLifecycle()
    val points = remember(activity.trackEncoded) { runCatching { TrackCodec.decode(activity.trackEncoded) }.getOrDefault(emptyList()) }
    val area = remember(points) { runCatching { AllTrailsMcpClient.Area.from(points) }.getOrNull() }
    var mode by remember { mutableStateOf(if(autoStart) "AUTO" else "") }
    var query by remember { mutableStateOf("") }
    // Never show/save the previous binding as an unselected search result.
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf(initialCandidates) }
    var searching by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(if(autoStart) 1 else 0) }
    var candidateTitle by remember { mutableStateOf<String?>(null) }
    var candidateId by remember { mutableStateOf<String?>(null) }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    val ranked = remember(activity, routes, activities, candidates) {
        com.mayushi.trailsense.domain.ConfirmedRoutePrioritizer.rank(activity,routes,activities,candidates)
    }
    LaunchedEffect(attempt) {
        if(attempt > 0) {
            searching = true; error = null
            try {
                if(area == null) error = "Для автоматического поиска нужен GPS-трек"
                else candidates = AllTrailsMcpClient().search(area,activity.distanceM,activity.elevationGainM)
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: Exception) { error = e.message ?: "Поиск недоступен" }
            finally { searching = false }
        }
    }
    ImeAlertDialog(onDismissRequest = dismiss, title = { Text("Маршрут на AllTrails") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { mode="AUTO"; link=""; candidateTitle=null; candidateId=null; selectedSource=null; candidates=emptyList(); attempt++ }, enabled = !searching, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Автоматически") }
                OutlinedButton(onClick = { mode="MANUAL"; link=""; candidateTitle=null; candidateId=null; selectedSource=null }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Вручную") }
            }
            if(mode == "AUTO") InfoHint("Данные поиска", "Отправляется только округлённый район и приблизительная длина, без GPS-точек, пульса и сна.")
            if(searching) { Text("Поиск…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { Text("$it. Это не означает, что маршрутов нет.", color = MaterialTheme.colorScheme.error) }
            if(mode == "AUTO" && attempt > 0 && !searching && error == null && candidates.isEmpty()) Text("Кандидатов нет в ответе. Попробуй ручной поиск.")
            if(mode == "AUTO" || initialCandidates.isNotEmpty()) ranked.forEach { rankedCandidate ->
                val c = rankedCandidate.candidate
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.name, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.url))) }.onFailure { error="Не удалось открыть браузер" }
                        })
                        if(rankedCandidate.priorConfirmed) Text("Ранее подтверждён для похожего GPS-трека", color=MaterialTheme.colorScheme.primary, style=MaterialTheme.typography.labelSmall)
                        Text(c.url, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.url))) }.onFailure { error="Не удалось открыть браузер" } }) { Text("Открыть кандидата ↗") }
                        TextButton(onClick = { link=c.url; candidateTitle=c.name; candidateId=c.id.takeIf { it != 0L }?.toString(); selectedSource=rankedCandidate.sourceLabel }) { Text(if(link==c.url) "Выбран" else "Выбрать") }
                        InfoHint("Кандидат AllTrails", "Это предложение из района похода или из ранее подтверждённого похожего пути, а не доказательство совпадения. «Открыть» позволяет посмотреть маршрут и ничего не привязывает. Подтверждение сохраняет только ссылку и название.")
                    }
                }
            }
            if(mode == "MANUAL") {
                OutlinedTextField(query, { query=it }, Modifier.fillMaxWidth(), label = { Text("Район / ориентиры") })
                OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q="+Uri.encode("site:alltrails.com/trail/ "+query)))) }.onFailure { error="Не удалось открыть браузер" } }, enabled=query.isNotBlank()) { Text("Открыть поиск ↗") }
                OutlinedTextField(link,{ link=it; candidateTitle=null; candidateId=null; selectedSource=null },Modifier.fillMaxWidth(),label={ Text("Ссылка AllTrails") })
            } else if(link.isNotBlank()) Text(link,style=MaterialTheme.typography.bodySmall)
            InfoHint("Поиск маршрута", "В AllTrails передаются только приблизительный район и длина пути, без полного трека и личной анкеты. Предложение из этого района ещё не означает, что ты шла именно там. Старые ссылка и название сохраняются до подтверждения; отмена ничего не меняет. Показатели часов не изменяются.")
        }
    }, confirmButton = {
        if(AllTrailsMcpClient.validRouteUrl(link.trim())) Button(onClick = {
            val title = candidateTitle ?: com.mayushi.trailsense.data.AllTrailsImport.parseSharedText(link)?.suggestedTitle
            vm.saveRoute(route.copy(title=com.mayushi.trailsense.domain.DisplayTitle.boundTitle(route,title),sourceUrl=link.trim(),sourceLabel=(selectedSource?.let { "$it; USER_CONFIRMED" } ?: candidateId?.let { "AllTrails MCP:$it; USER_CONFIRMED" } ?: "AllTrails: подтверждено вручную") + if(route.sourceLabel.contains("CUSTOM_TITLE")) "; CUSTOM_TITLE" else "")); dismiss()
        }) { Text("Подтвердить") }
    }, dismissButton = { TextButton(onClick=dismiss) { Text("Отмена") } })
}
