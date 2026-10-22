package com.mayushi.trailsense.domain

import com.mayushi.trailsense.data.PillPlanEntity
import com.mayushi.trailsense.data.PillPlanVersionEntity
import java.time.LocalDate

object PillPlanVersions {
    fun forDate(versions: List<PillPlanVersionEntity>, current: PillPlanEntity?, date: LocalDate): PillPlanEntity? {
        val day = date.toEpochDay()
        if (versions.isEmpty()) return current?.takeIf { it.anchorEpochDay <= day }
        val version = versions.filter { it.effectiveEpochDay <= day }
            .maxWithOrNull(compareBy<PillPlanVersionEntity> { it.effectiveEpochDay }.thenBy { it.id }.thenBy { it.createdAt }) ?: return null
        return version.toCurrentPlan()
    }

    fun effectiveDate(requested: LocalDate, today: LocalDate = LocalDate.now()): LocalDate =
        if (requested < today) today else requested

    // Old three-argument callers have no subdivision information.
    fun normalizedPlan(start: LocalDate, active: Int, pause: Int, days: Int = 21, count: Int = 4): PillPlan {
        val inferLegacy = active != 84 && days == 21 && count == 4
        return PillPlan(start.toEpochDay(), active, pause, if (inferLegacy) active else days, if (inferLegacy) 1 else count)
    }
}

fun PillPlanVersionEntity.toCurrentPlan() = PillPlanEntity(
    anchorEpochDay = anchorEpochDay, activeDays = activeDays, breakDays = breakDays,
    enabled = enabled, updatedAt = createdAt, subBlockDays = subBlockDays, subBlockCount = subBlockCount,
)

fun PillPlanEntity.toDomainPlan(): PillPlan {
    val legacy = subBlockDays == 0 && subBlockCount == 0
    return PillPlan(anchorEpochDay, activeDays, breakDays,
        if (legacy) { if (activeDays == 84) 21 else activeDays } else subBlockDays,
        if (legacy) { if (activeDays == 84) 4 else 1 } else subBlockCount)
}

fun PillPlanVersionEntity.toDomainPlan(): PillPlan = toCurrentPlan().toDomainPlan()
