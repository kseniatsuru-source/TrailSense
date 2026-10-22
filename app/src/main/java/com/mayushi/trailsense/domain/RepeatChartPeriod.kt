package com.mayushi.trailsense.domain

import java.time.LocalDate

/** Rolling calendar periods ending at the latest recording, inclusive; no undated placeholders. */
object RepeatChartPeriod {
    fun axisMaximum(seconds: Long): Long {
        require(seconds in 0..31536000)
        return maxOf(3600, ((seconds + 599) / 600) * 600)
    }
    fun axisTicks(maximum: Long): List<Long> {
        require(maximum >= 3600)
        val step = maxOf(600, ((maximum - 3600 + 1799) / 1800) * 600)
        return (listOf(0L) + (3600L..maximum step step).toList() + maximum).distinct()
    }
    fun filter(rows:List<AutomaticRepeatRoutes.Row>,period:String,from:LocalDate,to:LocalDate):List<AutomaticRepeatRoutes.Row> {
        val end=if(period=="Период") to else rows.mapNotNull{RepeatRouteDynamics.date(it.activity)}.maxOrNull() ?: return emptyList()
        val start=when(period) { "Месяц"->end.minusMonths(1); "Сезон"->end.minusMonths(3); "Год"->end.minusYears(1); "Период"->from; else->LocalDate.MIN }
        return rows.filter {RepeatRouteDynamics.date(it.activity)?.let{date->date>=start && date<=end}==true}
            .sortedWith(compareBy<AutomaticRepeatRoutes.Row>{RepeatRouteDynamics.date(it.activity)}.thenBy{it.activity.startedAt}.thenBy{it.activity.id})
    }
}
