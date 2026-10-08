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
import com.mayushi.trailsense.R
import com.mayushi.trailsense.data.PillEventEntity
import com.mayushi.trailsense.data.PillPlanEntity
import com.mayushi.trailsense.domain.*
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object PillReminderScheduler {
    private const val TAG = "pill-boundary-reminders"
    private const val CHANNEL = "pill_schedule"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Границы схемы 84 + 7", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Последний активный день, начало перерыва и возобновление"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun schedule(context: Context, entity: PillPlanEntity, events: List<PillEventEntity>) {
        val work = WorkManager.getInstance(context)
        work.cancelAllWorkByTag(TAG)
        if (!entity.enabled) return
        val plan = PillPlan(entity.anchorEpochDay, entity.activeDays, entity.breakDays)
        val now = ZonedDateTime.now()
        val currentBlock = PillSchedule.day(plan, LocalDate.now()).blockNumber
        val overrides = events.filter { it.deviationPolicy == DeviationPolicy.NEAREST_BOUNDARY_ONLY.name && it.plannedEpochDay != null }
            .map { PillBoundaryOverride(it.plannedEpochDay!!, it.epochDay) }
        for (block in currentBlock..(currentBlock + 9)) {
            for ((kind, date) in PillSchedule.effectiveReminderDates(plan, block, overrides)) {
                val runAt = date.atTime(9, 0).atZone(now.zone)
                if (!runAt.isAfter(now)) continue
                val delay = Duration.between(now, runAt).toMillis().coerceAtLeast(0)
                val request = OneTimeWorkRequestBuilder<PillReminderWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("kind" to kind.name, "epochDay" to date.toEpochDay()))
                    .addTag(TAG)
                    .build()
                work.enqueueUniqueWork("pill-${kind.name}-${date.toEpochDay()}", ExistingWorkPolicy.REPLACE, request)
            }
        }
    }

    internal fun title(kind: PillReminderKind): String = when (kind) {
        PillReminderKind.LAST_ACTIVE -> "Последний активный день"
        PillReminderKind.BREAK_START -> "Сегодня начинается перерыв"
        PillReminderKind.RESTART_EVE -> "Завтра возобновление"
        PillReminderKind.RESTART_DAY -> "Сегодня новый блок"
    }

    internal fun body(kind: PillReminderKind): String = when (kind) {
        PillReminderKind.LAST_ACTIVE -> "По твоему календарю сегодня последняя активная таблетка перед 7-дневным перерывом."
        PillReminderKind.BREAK_START -> "По сохранённому графику начинается 7-дневный перерыв."
        PillReminderKind.RESTART_EVE -> "По сохранённому графику завтра первая таблетка нового блока."
        PillReminderKind.RESTART_DAY -> "По сохранённому графику сегодня первая таблетка нового 84-дневного блока."
    }

    internal fun notify(context: Context, kind: PillReminderKind, epochDay: Long) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, epochDay.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title(kind)).setContentText(body(kind)).setStyle(NotificationCompat.BigTextStyle().bigText(body(kind)))
            .setContentIntent(pending).setAutoCancel(true).build()
        NotificationManagerCompat.from(context).notify((kind.name + epochDay).hashCode(), notification)
    }
}

class PillReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val kind = runCatching { PillReminderKind.valueOf(inputData.getString("kind").orEmpty()) }.getOrNull() ?: return Result.failure()
        PillReminderScheduler.notify(applicationContext, kind, inputData.getLong("epochDay", 0))
        return Result.success()
    }
}
