package com.mayushi.trailsense

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mayushi.trailsense.data.*
import com.mayushi.trailsense.domain.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun GraphicalMonthCalendar(month: YearMonth, today: LocalDate, contexts: List<CalendarContextEntity>,
    pillPlan: PillPlanEntity?, pillEvents: List<PillEventEntity> = emptyList(), hikeDays: Set<Long> = emptySet(),
    pillPlanVersions: List<PillPlanVersionEntity> = emptyList(), previousMonth: () -> Unit, nextMonth: () -> Unit,
    showToday: () -> Unit, selectDay: (LocalDate) -> Unit,
    hikeTitles: Map<Long,List<String>> = emptyMap(), visibleCategoryNames:Set<String> = CalendarCategory.entries.map{it.name}.toSet()) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(previousMonth) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Предыдущий месяц") }
                Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru"))), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                IconButton(nextMonth) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Следующий месяц") }
            }
            Row(Modifier.fillMaxWidth()) { listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach {
                Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelSmall)
            } }
            CalendarMonthGrid.cells(month).chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    week.forEach { cell ->
                        val date = cell.date
                        val events = Calendar037.contextsForDate(contexts,date,today).filter{CalendarCategory.context(it).name in visibleCategoryNames}
                        val strips = mutableListOf<Pair<String,Color>>()
                        if(CalendarCategory.HIKES.name in visibleCategoryNames) {
                            val titles=hikeTitles[date.toEpochDay()].orEmpty()
                            titles.forEach { strips.add(it to CalendarCategory.HIKES.color) }
                            if(titles.isEmpty() && date.toEpochDay() in hikeDays) strips.add("Хайк" to CalendarCategory.HIKES.color)
                        }
                        events.forEach { strips.add(Calendar037.label(it.type) to CalendarCategory.context(it).color) }
                        val plan=PillPlanVersions.forDate(pillPlanVersions,pillPlan,date)
                        plan?.let {
                            val category=if(PillSchedule.day(it.toDomainPlan(),date).phase == PillPhase.ACTIVE) CalendarCategory.ACTIVE else CalendarCategory.BREAK
                            if(category.name in visibleCategoryNames) strips.add(category.label to category.color)
                        }
                        Surface(onClick = { selectDay(date) }, modifier = Modifier.weight(1f).heightIn(min = (64 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp).semantics {
                            contentDescription = "$date" + strips.joinToString { it.first }
                        }, shape = RoundedCornerShape(4.dp), color=Color.Transparent,
                            border = BorderStroke(if(date == today) 2.dp else 1.dp, if(date == today) Forest else MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.padding(2.dp), verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                Text(date.dayOfMonth.toString(), fontWeight=if(date==today) FontWeight.Bold else FontWeight.Normal,
                                    color=if(cell.inDisplayedMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                strips.take(2).forEach { (title,color) ->
                                    Surface(Modifier.fillMaxWidth(), color=color.copy(alpha=.24f), shape=RoundedCornerShape(2.dp)) {
                                        Text(title,Modifier.padding(2.dp),style=MaterialTheme.typography.labelSmall,maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis)
                                    }
                                }
                                if(strips.size>2) Text("+${strips.size-2}",style=MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
