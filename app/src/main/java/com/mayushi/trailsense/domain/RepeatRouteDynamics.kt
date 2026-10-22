package com.mayushi.trailsense.domain

import com.mayushi.trailsense.data.ActivityEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Selected actual GPS sections only; never substitutes watch totals or inferred geometry. */
object RepeatRouteDynamics {
    data class Member(val activityId: Long, val sameAscentConfirmed: Boolean = false, val recordingComplete: Boolean = false,
        val startIndex: Int? = null, val endIndex: Int? = null, val trackFingerprint: String? = null)
    data class Group(val id: String, val title: String, val sourceFormat: String, val members: List<Member> = emptyList())
    data class Row(val activity: ActivityEntity, val elapsedSeconds: Long?, val eligible: Boolean, val limitations: List<String>,
        val previousActivityId: Long? = null, val previousSeconds: Long? = null, val priorBestSeconds: Long? = null,
        val member: Member = Member(activity.id), val startEpochMs: Long? = null, val averageHeartRateBpm: Int? = null)
    data class Summary(val group: Group, val rows: List<Row>, val bestActivityId: Long?, val bestSeconds: Long?, val limitation: String,
        val referenceMember: Member? = null)
    const val LIMITATION = "Время выбранного подъёма считается по меткам GPS между подтверждёнными стартом и финишем, включая паузы. Первая пригодная выбранная секция — образец границ; другие секции проверяются с допуском GPS 100 м. Пользователь подтверждает ту же тропу и полный участок, не всю запись. Пропущенные границы, спуск, разрывы, оценочные/исправленные точки и другой источник исключены. Пульс участка показан только из его точек; сон и анкета относятся к походу. Причинная связь не установлена."
    fun fingerprint(activity: ActivityEntity): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(activity.trackEncoded.toByteArray()).joinToString("") { "%02x".format(it) }
    fun date(activity: ActivityEntity, zone: ZoneId = ZoneId.systemDefault()): LocalDate? = runCatching {
        activity.manualDateEpochDay?.let(LocalDate::ofEpochDay)
            ?: timestamp(activity)?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    }.getOrNull()
    fun timestamp(activity: ActivityEntity): Long? = activity.startedAt ?: activity.importedStartedAt
    fun section(member: Member, activity: ActivityEntity): List<TrackPoint>? {
        val points = runCatching { TrackCodec.decode(activity.trackEncoded) }.getOrNull() ?: return null
        val start = member.startIndex ?: return null
        val end = member.endIndex ?: return null
        if (start !in points.indices || end !in points.indices || end <= start) return null
        return points.subList(start, end + 1)
    }
    fun summarize(group: Group, activities: List<ActivityEntity>, zone: ZoneId = ZoneId.systemDefault()): Summary {
        val byId = activities.associateBy { it.id }
        val sections = mutableMapOf<Member, List<TrackPoint>>()
        val raw = group.members.distinct().mapNotNull { member ->
            val activity = byId[member.activityId] ?: return@mapNotNull null
            val points = section(member, activity)
            val duration = points?.let { p -> p.last().timeEpochMs?.let { end -> p.first().timeEpochMs?.let { (end - it) / 1000 } } }?.takeIf { it > 0 }
            val limitations = buildList {
                if (!member.sameAscentConfirmed) add("Одинаковый подъём не подтверждён")
                if (!member.recordingComplete) add("Полный участок не подтверждён: не участвует в лучшем времени")
                if (group.sourceFormat.isBlank() || activity.sourceFormat != group.sourceFormat) add("Другой или неизвестный источник времени")
                if (activity.trackCorrectionMode != "NONE" || points?.any { it.inferred } == true) add("Исправленная или оценочная запись исключена")
                if (member.trackFingerprint != null && member.trackFingerprint != fingerprint(activity)) add("GPS изменился: выбери и подтверди границы заново")
                if (points == null) add("Границы участка не выбраны или отсутствуют")
                if (points != null) {
                    if (points.drop(1).any { it.segmentStart }) add("Участок содержит разрывы")
                    if (points.any { it.timeEpochMs == null } || points.zipWithNext().any { (a,b) -> (b.timeEpochMs ?: 0) <= (a.timeEpochMs ?: 0) }) add("Нет непрерывной хронологии участка GPS")
                    if (points.any { it.elevationM == null || !it.elevationM.isFinite() }) add("Высоты участка неизвестны: подъём не проверен")
                    else {
                        val gain = points.zipWithNext().sumOf { (a,b) -> (b.elevationM!! - a.elevationM!!).coerceAtLeast(0.0) }
                        val loss = points.zipWithNext().sumOf { (a,b) -> (a.elevationM!! - b.elevationM!!).coerceAtLeast(0.0) }
                        if (points.last().elevationM!! - points.first().elevationM!! < 20 || loss > maxOf(20.0, gain * .15)) add("Выбранный участок не является непрерывным подъёмом: проверь границы")
                    }
                }
                if (duration == null) add("Время участка GPS неизвестно")
            }
            if (points != null) sections[member] = points
            val hr = points?.mapNotNull { it.heartRateBpm?.takeIf { value -> value > 0 } }?.takeIf { it.isNotEmpty() }?.average()?.toInt()
            Row(activity, duration, limitations.isEmpty(), limitations, member = member, startEpochMs = points?.firstOrNull()?.timeEpochMs, averageHeartRateBpm = hr)
        }
        val reference = raw.firstOrNull { it.eligible }
        val refPoints = reference?.let { sections[it.member] }
        val rows = raw.map { row ->
            val p = sections[row.member]
            if (row.eligible && refPoints != null && p != null &&
                (Geo.distanceM(refPoints.first(), p.first()) > 100 || Geo.distanceM(refPoints.last(), p.last()) > 100))
                row.copy(eligible = false, limitations = row.limitations + "Старт или финиш не совпадает с образцом (допуск GPS 100 м)")
            else row
        }.sortedWith(compareBy<Row> { date(it.activity, zone) == null }.thenBy { date(it.activity, zone) }
            .thenBy { it.startEpochMs ?: Long.MAX_VALUE }.thenBy { it.activity.id })
        fun earlier(a: Row, b: Row): Boolean {
            val ad = date(a.activity, zone) ?: return false
            val bd = date(b.activity, zone) ?: return false
            return if (ad != bd) ad < bd else a.startEpochMs != null && b.startEpochMs != null && a.startEpochMs < b.startEpochMs
        }
        val enriched = rows.map { row ->
            val prior = if (row.eligible) rows.filter { it.eligible && earlier(it, row) } else emptyList()
            row.copy(previousActivityId = prior.lastOrNull()?.activity?.id, previousSeconds = prior.lastOrNull()?.elapsedSeconds,
                priorBestSeconds = prior.minOfOrNull { it.elapsedSeconds!! })
        }
        val best = enriched.filter { it.eligible }.minByOrNull { it.elapsedSeconds!! }
        return Summary(group, enriched, best?.activity?.id, best?.elapsedSeconds, LIMITATION, reference?.member)
    }
}
