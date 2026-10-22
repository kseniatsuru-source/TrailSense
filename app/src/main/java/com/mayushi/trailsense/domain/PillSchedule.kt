package com.mayushi.trailsense.domain

import java.time.LocalDate

data class PillPlan(
    val anchorEpochDay: Long, val activeDays: Int = 84, val breakDays: Int = 7,
    val subBlockDays: Int = if (activeDays == 84) 21 else activeDays,
    val subBlockCount: Int = if (activeDays == 84) 4 else 1,
) {
    init {
        require(activeDays > 0 && breakDays >= 0 && activeDays.toLong() + breakDays <= Int.MAX_VALUE)
        require(subBlockDays > 0 && subBlockCount > 0 && subBlockDays.toLong() * subBlockCount == activeDays.toLong())
    }
    val blockDays: Int get() = activeDays + breakDays
}

enum class PillPhase { ACTIVE, BREAK }
enum class PillReminderKind { LAST_ACTIVE, BREAK_START, RESTART_EVE, RESTART_DAY }
enum class DeviationPolicy { KEEP_PLAN, RECALCULATE_ALL, NEAREST_BOUNDARY_ONLY }

data class PillDay(
    val date: LocalDate,
    val phase: PillPhase,
    val dayInPhase: Int,
    val dayInBlock: Int,
    val blockNumber: Long,
    val dayInSubBlock: Int = 0,
    val subBlockIndex: Int = 0,
    val subBlockCount: Int = 1,
)

data class PillBoundaryOverride(val plannedEpochDay: Long, val actualEpochDay: Long)
data class PillScheduleChange(val plan: PillPlan, val nearestBoundaryOverride: PillBoundaryOverride? = null)

object PillSchedule {
    fun day(plan: PillPlan, date: LocalDate): PillDay {
        require(plan.activeDays > 0 && plan.breakDays >= 0 && plan.activeDays.toLong()+plan.breakDays <= Int.MAX_VALUE)
        val relative = date.toEpochDay() - plan.anchorEpochDay
        val index = Math.floorMod(relative, plan.blockDays.toLong()).toInt()
        val block = Math.floorDiv(relative, plan.blockDays.toLong())
        return if (index < plan.activeDays) {
            PillDay(date, PillPhase.ACTIVE, index + 1, index + 1, block,
                index % plan.subBlockDays + 1, index / plan.subBlockDays + 1, plan.subBlockCount)
        } else {
            PillDay(date, PillPhase.BREAK, index - plan.activeDays + 1, index + 1, block, 0, 0, plan.subBlockCount)
        }
    }

    fun reminderDates(plan: PillPlan, blockNumber: Long): Map<PillReminderKind, LocalDate> {
        val blockStart = LocalDate.ofEpochDay(plan.anchorEpochDay).plusDays(blockNumber * plan.blockDays)
        return mapOf(
            PillReminderKind.LAST_ACTIVE to blockStart.plusDays((plan.activeDays - 1).toLong()),
            PillReminderKind.BREAK_START to blockStart.plusDays(plan.activeDays.toLong()),
            PillReminderKind.RESTART_EVE to blockStart.plusDays((plan.blockDays - 1).toLong()),
            PillReminderKind.RESTART_DAY to blockStart.plusDays(plan.blockDays.toLong()),
        )
    }

    fun effectiveReminderDates(plan: PillPlan, blockNumber: Long, overrides: List<PillBoundaryOverride>): Map<PillReminderKind, LocalDate> {
        val original = reminderDates(plan,blockNumber)
        val result = original.toMutableMap()
        overrides.forEach { override ->
            val selected = original.entries.firstOrNull { it.value.toEpochDay() == override.plannedEpochDay }?.key ?: return@forEach
            val delta = override.actualEpochDay - override.plannedEpochDay
            val pair = if (selected in listOf(PillReminderKind.LAST_ACTIVE,PillReminderKind.BREAK_START)) listOf(PillReminderKind.LAST_ACTIVE,PillReminderKind.BREAK_START) else listOf(PillReminderKind.RESTART_EVE,PillReminderKind.RESTART_DAY)
            pair.forEach { result[it] = original.getValue(it).plusDays(delta) }
        }
        return result
    }

    fun applyBoundaryDeviation(plan: PillPlan, plannedBoundary: LocalDate, actualBoundary: LocalDate, policy: DeviationPolicy): PillScheduleChange {
        val delta = actualBoundary.toEpochDay() - plannedBoundary.toEpochDay()
        return when (policy) {
            DeviationPolicy.KEEP_PLAN -> PillScheduleChange(plan)
            DeviationPolicy.RECALCULATE_ALL -> PillScheduleChange(plan.copy(anchorEpochDay = plan.anchorEpochDay + delta))
            DeviationPolicy.NEAREST_BOUNDARY_ONLY -> PillScheduleChange(plan, PillBoundaryOverride(plannedBoundary.toEpochDay(), actualBoundary.toEpochDay()))
        }
    }
}
