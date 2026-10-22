package com.mayushi.trailsense

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mayushi.trailsense.domain.SleepDurationReader
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Bundled Latin OCR. Images, crops and recognized text never leave this device. */
class SleepScreenshotReader(context: Context) {
    private val appContext = context.applicationContext

    private suspend fun recognize(recognizer: TextRecognizer, image: InputImage): Text? =
        withContext(kotlinx.coroutines.NonCancellable) { suspendCancellableCoroutine { continuation ->
            try {
                recognizer.process(image).addOnCompleteListener { task ->
                    // Finish native bitmap use before recycling; cancellation never applies data.
                    if (continuation.isActive) continuation.resume(if (task.isSuccessful) task.result else null)
                }
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        } }

    suspend fun read(filePath: String): SleepDurationReader.Result = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.isFile || file.length() !in 1..20_000_000L) {
                return@withContext SleepDurationReader.unknown("Локальное изображение отсутствует или слишком велико")
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 16_000_000L) {
                return@withContext SleepDurationReader.unknown("Изображение не читается или слишком велико")
            }
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val full = recognize(recognizer, InputImage.fromFilePath(appContext, Uri.fromFile(file)))
                    ?: return@withContext SleepDurationReader.unknown("Локальное распознавание не удалось")
                val wakingDate = com.mayushi.trailsense.domain.SleepWakingDate.read(full.text)
                val direct = SleepDurationReader.read(full.text).copy(wakingDate = wakingDate)
                val spatialText = full.textBlocks.flatMap { it.lines }.sortedWith(compareBy<Text.Line> { it.boundingBox?.top ?: Int.MAX_VALUE }.thenBy { it.boundingBox?.left ?: Int.MAX_VALUE }).joinToString("\n") { it.text }
                val spatial = SleepDurationReader.read(spatialText)
                // Huawei's two-column cards and large summary break MLKit block reading order.
                // Re-read a bounded label-local bitmap region, not arbitrary nearby durations.
                val labels = full.textBlocks.flatMap { it.lines }.filter {
                    it.text.trim().equals("Night sleep", ignoreCase = true) && it.boundingBox != null
                }.take(4)
                if (labels.isEmpty()) return@withContext direct
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    ?: return@withContext direct
                val readings = mutableListOf<SleepDurationReader.Result>()
                try {
                    for (line in labels) {
                        val box = line.boundingBox!!
                        val halfWidth = (bitmap.width * .23).toInt()
                        val left = (box.centerX() - halfWidth).coerceAtLeast(0)
                        val right = (box.centerX() + halfWidth).coerceAtMost(bitmap.width)
                        val top = (box.top - box.height() / 3).coerceAtLeast(0)
                        val bottom = (box.bottom + maxOf(box.height() * 6, (bitmap.height * .08).toInt())).coerceAtMost(bitmap.height)
                        if (right <= left || bottom <= top) continue
                        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                        val localReadings = mutableListOf<SleepDurationReader.Result>()
                        try {
                            for ((factor, inverse) in listOf(2.5 to true, 1.0 to false, 1.5 to true)) {
                                val scale = minOf(factor, 1800.0 / maxOf(crop.width, crop.height))
                                val enlarged = Bitmap.createScaledBitmap(crop, (crop.width * scale).toInt(), (crop.height * scale).toInt(), true)
                                val prepared = if (inverse) Bitmap.createBitmap(enlarged.width, enlarged.height, Bitmap.Config.ARGB_8888).also {
                                    val paint = android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(-1f,0f,0f,0f,255f,0f,-1f,0f,0f,255f,0f,0f,-1f,0f,255f,0f,0f,0f,1f,0f)) }
                                    android.graphics.Canvas(it).drawBitmap(enlarged, 0f, 0f, paint)
                                } else enlarged
                                try {
                                    val local = recognize(recognizer, InputImage.fromBitmap(prepared, 0)) ?: continue
                                    val ordered = local.textBlocks.flatMap { it.lines }.sortedWith(compareBy<Text.Line> { it.boundingBox?.top ?: Int.MAX_VALUE }.thenBy { it.boundingBox?.left ?: Int.MAX_VALUE })
                                    val text = ordered.joinToString("\n") { it.text }
                                    // Mixed-size h can be mistaken for a digit. Crop fallback requires
                                    // an actually recognized hour unit, never converts 860 min to 8h0.
                                    if (Regex("""\d\s*(?:h|hr|hrs|hours?)\s*\d""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                                        SleepDurationReader.read(text).takeIf { it.minutes != null }?.let(localReadings::add)
                                    }
                                } finally {
                                    if (prepared !== enlarged) prepared.recycle()
                                    if (enlarged !== crop) enlarged.recycle()
                                }
                            }
                        } finally { crop.recycle() }
                        val values = localReadings.map { it.minutes }.distinct()
                        if (values.size > 1) return@withContext SleepDurationReader.unknown("Распознавание подписанной длительности противоречиво")
                        readings += localReadings.firstOrNull() ?: SleepDurationReader.unknown("Подписанная длительность не читается")
                    }
                } finally { bitmap.recycle() }
                val known = (readings + direct + spatial).filter { it.minutes != null }
                if (known.map { it.minutes }.distinct().size > 1) return@withContext SleepDurationReader.unknown("На изображении конфликтующие значения общего сна")
                if (readings.any { it.minutes == null } && direct.minutes == null && spatial.minutes == null) return@withContext SleepDurationReader.unknown("Подписанная длительность ночного сна не читается однозначно")
                (known.minByOrNull { it.confidence } ?: direct).copy(wakingDate = wakingDate)
            } finally { recognizer.close() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SleepDurationReader.unknown("Локальное изображение не удалось прочитать")
        }
    }
}
