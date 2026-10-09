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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mayushi.trailsense.data.CalendarContextEntity
import com.mayushi.trailsense.data.PillPlanEntity
import com.mayushi.trailsense.domain.CalendarMonthGrid
import com.mayushi.trailsense.domain.PillPhase
import com.mayushi.trailsense.domain.PillPlan
import com.mayushi.trailsense.domain.PillSchedule
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun GraphicalMonthCalendar(
    month: YearMonth,
    today: LocalDate,
    contexts: List<CalendarContextEntity>,
    pillPlan: PillPlanEntity?,
    pillEvents: List<com.mayushi.trailsense.data.PillEventEntity> = emptyList(),
    hikeDays: Set<Long> = emptySet(),
    previousMonth: () -> Unit,
    nextMonth: () -> Unit,
    showToday: () -> Unit,
    selectDay: (LocalDate) -> Unit,
) {
    val cells = CalendarMonthGrid.cells(month)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = previousMonth) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Предыдущий месяц") }
                Text(
                    month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru"))).replaceFirstChar { it.titlecase(Locale("ru")) },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = nextMonth) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Следующий месяц") }
            }
            TextButton(onClick = showToday, Modifier.align(Alignment.CenterHorizontally)) { Text("Сегодня") }
            Row(Modifier.fillMaxWidth()) {
                listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { weekday ->
                    Text(
                        weekday,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { cell ->
                        CalendarDay(
                            date = cell.date,
                            inDisplayedMonth = cell.inDisplayedMonth,
                            isToday = cell.date == today,
                            hasHike = cell.date.toEpochDay() in hikeDays,
                            contextTypes = CalendarMonthGrid.contextTypes(cell.date, contexts),
                            fact = pillEvents.lastOrNull { it.epochDay == cell.date.toEpochDay() },
                            pillPhase = pillPlan?.let {
                                PillSchedule.day(PillPlan(it.anchorEpochDay, it.activeDays, it.breakDays), cell.date).phase
                            },
                            select = { selectDay(cell.date) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            CalendarLegend(pillPlan != null)
        }
    }
}

@Composable
private fun CalendarDay(
    date: LocalDate,
    inDisplayedMonth: Boolean,
    isToday: Boolean,
    hasHike: Boolean = false,
    contextTypes: List<String>,
    pillPhase: PillPhase?,
    fact: com.mayushi.trailsense.data.PillEventEntity? = null,
    select: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val baseColor = when {
        isToday -> MaterialTheme.colorScheme.primaryContainer
        contextTypes.isNotEmpty() -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }
    Surface(
        onClick = select,
        modifier = modifier.aspectRatio(1f).padding(1.5.dp),
        shape = shape,
        color = baseColor,
        border = if (isToday) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (inDisplayedMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .35f),
            )
            fact?.let { Text(if (it.actualTaken == true) "✓" else if (it.actualTaken == false) "×" else "↔", style = MaterialTheme.typography.labelSmall, color = Coral) }
            if (hasHike) Text("▲", color = Forest, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                contextTypes.take(3).forEach { type ->
                    Surface(
                        Modifier.size(6.dp),
                        shape = RoundedCornerShape(50),
                        color = contextColor(type),
                    ) {}
                }
            }
            pillPhase?.let { phase ->
                Spacer(Modifier.height(2.dp))
                Surface(
                    Modifier.fillMaxWidth(.72f).height(3.dp),
                    shape = RoundedCornerShape(50),
                    color = if (phase == PillPhase.ACTIVE) Forest else Coral,
                ) {}
            }
        }
    }
}

@Composable
private fun CalendarLegend(showPillPlan: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LegendItem(contextColor("NOTE"), "Заметка")
        LegendItem(Forest, "▲ Поход")
        LegendItem(contextColor("SEVERE_SLEEP_DEBT"), "Сильный недосып")
        LegendItem(contextColor("HIGH_STRESS"), "Сильный стресс")
        if (showPillPlan) Text("Полоска — план (зелёная: активные, бирюзовая: перерыв). ✓ принято · × не принято · ↔ отклонение", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(Modifier.size(8.dp), shape = RoundedCornerShape(50), color = color) {}
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun contextColor(type: String): Color = when (type) {
    "SEVERE_SLEEP_DEBT" -> MaterialTheme.colorScheme.tertiary
    "HIGH_STRESS" -> Coral
    else -> MaterialTheme.colorScheme.primary
}
