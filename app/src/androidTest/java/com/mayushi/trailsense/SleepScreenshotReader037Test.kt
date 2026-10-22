package com.mayushi.trailsense

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Constructed device tests; actual bundled recognition, not mocked OCR text. */
@RunWith(AndroidJUnit4::class)
class SleepScreenshotReader037Test {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun image(lines: List<String>): File {
        val bitmap = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f }
        lines.forEachIndexed { index, line -> canvas.drawText(line, 50f, 100f + index * 110f, paint) }
        val file = File.createTempFile("sleep-ocr037-", ".png", context.cacheDir)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    @Test fun bundledRecognizerReadsActualDurationAndDoesNotModifyLocalImage() = runBlocking {
        val file = image(listOf("Total sleep", "7 h 25 min", "Time in bed 9 h", "Bedtime 22:00", "Wake up 07:00"))
        try {
            val before = file.readBytes()
            val result = withTimeout(30_000) { SleepScreenshotReader(context).read(file.absolutePath) }
            assertEquals(445, result.minutes)
            assertTrue(result.confidence >= .85)
            assertArrayEquals(before, file.readBytes())
        } finally { file.delete() }
    }

    @Test fun bedtimeOnlyAndBlankAreUnknownWithoutGuess() = runBlocking {
        for (lines in listOf(listOf("Time in bed 9 h", "Bedtime 22:00", "Wake up 07:00"), emptyList())) {
            val file = image(lines)
            try {
                val result = withTimeout(30_000) { SleepScreenshotReader(context).read(file.absolutePath) }
                assertNull(result.minutes); assertEquals(0.0, result.confidence, 0.0)
            } finally { file.delete() }
        }
    }

    @Test fun explicitNightSleepCardStatusAndConflictsAreRecognizedLocally() = runBlocking {
        for ((lines, expected) in listOf(
            listOf("Night sleep", "7 h 7 min Normal", "Time in bed 9 h") to 427,
            listOf("Night sleep", "7 h 7 min", "Night sleep", "8 h 5 min") to null,
            listOf("Night sleep", "unreadable", "Time in bed 9 h") to null,
        )) {
            val file = image(lines)
            try { assertEquals(expected, withTimeout(30_000) { SleepScreenshotReader(context).read(file.absolutePath) }.minutes) }
            finally { file.delete() }
        }
    }
    @Test fun missingCorruptAndNonlocalPathFailClosed() = runBlocking {
        val corrupt = File.createTempFile("sleep-corrupt037-", ".png", context.cacheDir)
        try {
            corrupt.writeText("not an image")
            for (path in listOf(corrupt.absolutePath, "${context.cacheDir}/absent-sleep037.png", "https://example.com/sleep.png")) {
                assertNull(SleepScreenshotReader(context).read(path).minutes)
            }
        } finally { corrupt.delete() }
    }
}
