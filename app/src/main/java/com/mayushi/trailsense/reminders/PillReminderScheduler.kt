package com.mayushi.trailsense.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.mayushi.trailsense.MainActivity
import com.mayushi.trailsense.MedicationMode
import com.mayushi.trailsense.MedicationPreferences
import com.mayushi.trailsense.validReminderTime
import com.mayushi.trailsense.R
import com.mayushi.trailsense.data.PillEventEntity
import com.mayushi.trailsense.data.PillPlanEntity
import com.mayushi.trailsense.data.TrailDatabase
import com.mayushi.trailsense.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object PillReminderScheduler {
    private const val TAG = "pill-boundary-reminders"
    private const val CHANNEL = "pill_schedule"
    private val schedulingMutex = Mutex()

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Границы схемы приёма", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Последний активный день, начало перерыва и возобновление"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // Blocking WorkManager operation waits are strictly confined to Dispatchers.IO.
    // Read the database inside the mutex; caller snapshots must not resurrect stale work.
    suspend fun schedule(context: Context, entity: PillPlanEntity? = null, events: List<PillEventEntity> = emptyList()) = withContext(Dispatchers.IO) {
        schedulingMutex.withLock {
            val work = WorkManager.getInstance(context)
            work.cancelAllWorkByTag(TAG).result.get()
            val prefs = MedicationPreferences(context)
            if (!prefs.remindersEnabled || prefs.mode != MedicationMode.PILLS || !validReminderTime(prefs.reminderTime) || !permissionGranted(context)) return@withLock
            val dao = TrailDatabase.get(context).trailDao()
            val current = dao.pillPlan() ?: return@withLock
            val versions = dao.pillPlanVersions()
            val overrides = overrides(dao.pillEvents())
            val preferenceSnapshot = prefs.snapshot()
            val now = ZonedDateTime.now()
            val time = LocalTime.parse(prefs.reminderTime)
            val candidates = (versions.map { it.toCurrentPlan() } + current).distinct()
            val enqueued = mutableSetOf<String>()
            for (candidate in candidates) {
                if (!candidate.enabled) continue
                val plan = candidate.toDomainPlan()
                val block = PillSchedule.day(plan, now.toLocalDate()).blockNumber
                val effectiveStart = versions.firstOrNull { it.createdAt == candidate.updatedAt }?.effectiveEpochDay
                val futureBlock = effectiveStart?.let { PillSchedule.day(plan, LocalDate.ofEpochDay(it)).blockNumber } ?: block
                for (cycle in (block - 1)..(maxOf(block, futureBlock) + 9)) {
                    for ((kind, date) in PillSchedule.effectiveReminderDates(plan, cycle, overrides)) {
                        if (!prefs.option(kind)) continue
                        val selected = PillPlanVersions.forDate(versions, current, date) ?: continue
                        if (selected != candidate) continue
                        val runAt = date.atTime(time).atZone(now.zone)
                        if (!runAt.isAfter(now)) continue
                        val name = "pill-${kind.name}-${date.toEpochDay()}"
                        if (!enqueued.add(name)) continue
                        val request = OneTimeWorkRequestBuilder<PillReminderWorker>()
                            .setInitialDelay(Duration.between(now, runAt).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
                            .setInputData(workDataOf("kind" to kind.name, "epochDay" to date.toEpochDay(),
                                "revision" to current.updatedAt, "selectedRevision" to selected.updatedAt,
                                "preferences" to preferenceSnapshot, "time" to prefs.reminderTime))
                            .addTag(TAG).build()
                        work.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request).result.get()
                    }
                }
            }
        }
    }

    internal fun overrides(events: List<PillEventEntity>): List<PillBoundaryOverride> = events
        .filter { it.deviationPolicy == DeviationPolicy.NEAREST_BOUNDARY_ONLY.name && it.plannedEpochDay != null }
        .sortedWith(compareBy<PillEventEntity> { it.createdAt }.thenBy { it.id })
        .map { PillBoundaryOverride(it.plannedEpochDay!!, it.epochDay) }

    internal fun permissionGranted(context: Context): Boolean =
        (android.os.Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    internal fun title(kind: PillReminderKind): String = when (kind) {
        PillReminderKind.LAST_ACTIVE -> "Последний активный день"
        PillReminderKind.BREAK_START -> "Сегодня начинается перерыв"
        PillReminderKind.RESTART_EVE -> "Завтра возобновление"
        PillReminderKind.RESTART_DAY -> "Сегодня новый блок"
    }

    internal fun body(kind: PillReminderKind, breakDays: Int = 7): String = when (kind) {
        PillReminderKind.LAST_ACTIVE -> "По твоему календарю сегодня последняя активная таблетка перед перерывом ($breakDays дн.)."
        PillReminderKind.BREAK_START -> "По сохранённому графику начинается перерыв ($breakDays дн.)."
        PillReminderKind.RESTART_EVE -> "По сохранённому графику завтра первая таблетка нового блока."
        PillReminderKind.RESTART_DAY -> "По сохранённому графику сегодня первая таблетка нового блока."
    }

    internal fun notify(context: Context, kind: PillReminderKind, epochDay: Long, breakDays: Int = 7) {
        if (!permissionGranted(context)) return
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, epochDay.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = body(kind, breakDays)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title(kind)).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending).setAutoCancel(true).build()
        // Permission can be revoked between the check and platform delivery.
        try { NotificationManagerCompat.from(context).notify((kind.name + epochDay).hashCode(), notification) }
        catch (_: SecurityException) { }
    }
}

class PillReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = runBlocking(Dispatchers.IO) {
        val kind = runCatching { PillReminderKind.valueOf(inputData.getString("kind").orEmpty()) }.getOrNull() ?: return@runBlocking Result.failure()
        val prefs = MedicationPreferences(applicationContext)
        fun preferencesMatch() = prefs.remindersEnabled && prefs.mode == MedicationMode.PILLS && prefs.option(kind) &&
            prefs.snapshot() == inputData.getString("preferences") && validReminderTime(prefs.reminderTime)
        if (!preferencesMatch()) return@runBlocking Result.success()
        val date = LocalDate.ofEpochDay(inputData.getLong("epochDay", Long.MIN_VALUE))
        val now = ZonedDateTime.now()
        if (date != now.toLocalDate() || prefs.reminderTime != inputData.getString("time") || now.toLocalTime() < LocalTime.parse(prefs.reminderTime)) return@runBlocking Result.success()
        val dao = TrailDatabase.get(applicationContext).trailDao()
        val current = dao.pillPlan() ?: return@runBlocking Result.success()
        if (current.updatedAt != inputData.getLong("revision", -1)) return@runBlocking Result.success()
        val selected = PillPlanVersions.forDate(dao.pillPlanVersions(), current, date) ?: return@runBlocking Result.success()
        if (!selected.enabled || selected.updatedAt != inputData.getLong("selectedRevision", -1)) return@runBlocking Result.success()
        val plan = selected.toDomainPlan()
        val block = PillSchedule.day(plan, date).blockNumber
        val overrides = PillReminderScheduler.overrides(dao.pillEvents())
        val stillBoundary = (block - 1..block + 1).any { PillSchedule.effectiveReminderDates(plan, it, overrides)[kind] == date }
        if (stillBoundary && preferencesMatch() && dao.pillPlan()?.updatedAt == current.updatedAt)
            PillReminderScheduler.notify(applicationContext, kind, date.toEpochDay(), plan.breakDays)
        Result.success()
    }
}
