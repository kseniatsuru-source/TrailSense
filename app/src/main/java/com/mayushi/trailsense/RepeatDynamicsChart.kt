package com.mayushi.trailsense

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mayushi.trailsense.domain.*
import java.time.LocalDate

@Composable internal fun RepeatDynamicsChart(rows:List<AutomaticRepeatRoutes.Row>, ascent:Boolean = false) {
    var period by remember { mutableStateOf("Всё") }
    var from by remember { mutableStateOf(rows.mapNotNull {RepeatRouteDynamics.date(it.activity)}.minOrNull() ?: LocalDate.now()) }
    var to by remember { mutableStateOf(rows.mapNotNull {RepeatRouteDynamics.date(it.activity)}.maxOrNull() ?: LocalDate.now()) }
    var selected by remember { mutableStateOf<AutomaticRepeatRoutes.Row?>(null) }
    val filtered=RepeatChartPeriod.filter(rows,period,from,to)
    Column {
        Text("Динамика времени ${if(ascent) "общего подъёма" else "общего маршрута"} · минуты")
        Row(Modifier.fillMaxWidth()) {
            var menu by remember {mutableStateOf(false)}
            TextButton(onClick={menu=true},Modifier.testTag("chart-period")) {Text(period)}
            DropdownMenu(menu,{menu=false}) {listOf("Месяц","Сезон","Год","Всё","Период").forEach {p->DropdownMenuItem(text={Text(p)},onClick={period=p;selected=null;menu=false})}}
        }
        if(period=="Период") {
            Text("С даты"); DateButton(from){from=it;selected=null}
            Text("По дату включительно"); DateButton(to){to=it;selected=null}
        }
        if(period=="Период" && from>to) Text("Начало периода должно быть не позже конца")
        else if(filtered.isEmpty()) Text("За этот период измерений нет") else {
            val min=filtered.minOf {RepeatRouteDynamics.date(it.activity)!!.toEpochDay()}
            val max=filtered.maxOf {RepeatRouteDynamics.date(it.activity)!!.toEpochDay()}
            val upper=filtered.maxOf {it.seconds/60.0}.coerceAtLeast(1.0)
            fun x(row:AutomaticRepeatRoutes.Row) = if(min==max) .5f else ((RepeatRouteDynamics.date(row.activity)!!.toEpochDay()-min).toDouble()/(max-min)).toFloat()
            Text("%.1f мин".format(upper),style=MaterialTheme.typography.labelSmall)
            val color=MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(130.dp).testTag("repeat-time-chart").pointerInput(filtered) {detectTapGestures {p->
                // Include y: different recordings on one date must both be selectable.
                selected=filtered.minByOrNull {
                    val dx=(.04f+.92f*x(it))*size.width-p.x
                    val dy=size.height*(1-(it.seconds/60.0/upper).toFloat())-p.y
                    dx*dx+dy*dy
                }
            }}) {
                drawLine(color.copy(alpha=.4f),Offset(size.width*.04f,0f),Offset(size.width*.04f,size.height),1f)
                drawLine(color.copy(alpha=.4f),Offset(size.width*.04f,size.height),Offset(size.width*.96f,size.height),1f)
                val positions=filtered.map { Offset(size.width*(.04f+.92f*x(it)),size.height*(1-(it.seconds/60.0/upper).toFloat())) }
                positions.zipWithNext().forEach {(a,b)->drawLine(color,a,b,3f)}
                positions.forEach {drawCircle(color,5.dp.toPx(),it)}
            }
            Text("0 мин",style=MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(LocalDate.ofEpochDay(min).toString(),style=MaterialTheme.typography.labelSmall,modifier=Modifier.testTag("chart-first-date"))
                Text(LocalDate.ofEpochDay(max).toString(),style=MaterialTheme.typography.labelSmall,modifier=Modifier.testTag("chart-last-date"))
            }
            Text("Дата прохождения · нажми точку",style=MaterialTheme.typography.labelSmall)
            selected?.takeIf {it in filtered}?.let { Text("${recordingDate(it.activity)} · ${recordingDuration(it.seconds)}",Modifier.testTag("chart-selected-value")) }
        }
    }
}
