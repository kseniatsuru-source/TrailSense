package com.mayushi.trailsense.domain

import com.mayushi.trailsense.data.ActivityEntity
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate

class RepeatChartPeriod039Test {
    @Test fun approvedHorizontalAxisHasSharedZeroHourAndTenMinuteTicks() {
        assertEquals(4800L, RepeatChartPeriod.axisMaximum(4720))
        assertEquals(listOf(0L, 3600L, 4200L, 4800L), RepeatChartPeriod.axisTicks(4800))
        assertEquals(3600L, RepeatChartPeriod.axisMaximum(1200))
        assertEquals(listOf(0L, 3600L), RepeatChartPeriod.axisTicks(3600))
        assertTrue(RepeatChartPeriod.axisTicks(14400).size <= 6)
    }
    private fun rows()=listOf("2024-01-01","2025-06-30","2026-03-31","2026-05-31","2026-06-30").mapIndexed { i,s->
        AutomaticRepeatRoutes.Row(ActivityEntity(id=i+1L,title="Constructed",sourceFileName="",sourceFormat="TCX",trackEncoded="",manualDateEpochDay=LocalDate.parse(s).toEpochDay(),distanceM=0.0,elevationGainM=0.0,elevationLossM=0.0),600+i*60L,0,1)
    }
    @Test fun everyPeriodInclusiveAndChronologicalEvenUnsorted() {
        val r=rows().reversed();val from=LocalDate.parse("2026-03-31");val to=LocalDate.parse("2026-05-31")
        fun ids(p:String)=RepeatChartPeriod.filter(r,p,from,to).map{it.activity.id}
        assertEquals(listOf(4L,5L),ids("Месяц"));assertEquals(listOf(3L,4L,5L),ids("Сезон"))
        assertEquals(listOf(2L,3L,4L,5L),ids("Год"));assertEquals(listOf(1L,2L,3L,4L,5L),ids("Всё"));assertEquals(listOf(3L,4L),ids("Период"))
    }
    @Test fun reversedCustomEmptyAndUndatedNeverInvented() {
        val r=rows();val from=LocalDate.parse("2026-06-30");val to=from.minusDays(1)
        assertTrue(RepeatChartPeriod.filter(r,"Период",from,to).isEmpty())
        assertTrue(RepeatChartPeriod.filter(listOf(r.first().copy(activity=r.first().activity.copy(manualDateEpochDay=null))),"Всё",from,to).isEmpty())
    }
}
