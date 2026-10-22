package com.mayushi.trailsense

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import com.mayushi.trailsense.data.ActivityEntity

fun recordedDistance(activity: ActivityEntity): Double = activity.originalMetricsJson?.let { com.mayushi.trailsense.data.OriginalWatchMetrics.decode(it).distanceM } ?: activity.distanceM
fun displayedRecordedDistance(activity: ActivityEntity, points: List<com.mayushi.trailsense.domain.TrackPoint>): Double {
    val inferred = points.filter { it.inferred }
    val added = if (inferred.size >= 2) com.mayushi.trailsense.domain.TrackGeometry.cumulative(inferred).last() else 0.0
    return (activity.distanceM-added).coerceAtLeast(0.0)
}

@Composable
fun HikeAnalysisChoice(activityId: Long, vm: AppViewModel, dismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = dismiss, title = { Text("Персональный анализ") }, text = {
        Column {
            InfoHint("Персональный анализ", "Только текущий хайк. Отправка откроет системный выбор приложения; можно отменить.")
            TextButton(onClick = { vm.requestRecordingAnalysis(activityId); dismiss() }) { Text("Поставить в очередь") }
            TextButton(onClick = { vm.sendCurrentHike(context, activityId); dismiss() }) { Text("Отправить сейчас") }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}
