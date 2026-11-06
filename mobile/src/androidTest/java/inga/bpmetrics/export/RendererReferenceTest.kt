package inga.bpmetrics.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import inga.bpmetrics.library.BpmDataPointEntity
import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.BpmRecordWithPoints
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Each renderer, drawing a fixed input, against a picture of what it drew when it was right.
 *
 * Both renderers are a long run of canvas calls that no unit test sees, and they've drifted before
 * without anyone noticing (a render that didn't match its preview, Audit #27). This catches a change
 * to what comes out, intended or not; when intended, re-record.
 *
 * The references live in `src/androidTest/assets/reference/` and were drawn on the Pixel_7_Pro
 * emulator. Text is drawn by the device's fonts, so another device may differ by more than the
 * tolerance; compare on that emulator.
 *
 * To re-record: delete the reference, run this, and pull the new one from the path the failure
 * names (`adb shell run-as inga.bpmetrics ls files/reference`) into the assets folder.
 */
@RunWith(AndroidJUnit4::class)
class RendererReferenceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Two people over twenty minutes, with a gap in one of them, in a fixed zone. */
    private val startMs = 1_750_000_000_000L
    private val records = listOf(
        recording(1, "Danny", "Blue") { minute -> 72.0 + 60.0 * kotlin.math.sin(minute / 3.0).coerceAtLeast(0.0) },
        recording(2, "Bethany", "The OG", gapMinutes = 9..12) { minute -> 88.0 + minute * 2.5 }
    )
    private val colours = mapOf(1L to 0xFF42A5F5.toInt(), 2L to 0xFFEF5350.toInt())

    private fun recording(
        id: Long, wearer: String, device: String,
        gapMinutes: IntRange = IntRange.EMPTY,
        bpmAt: (Double) -> Double
    ): BpmRecordWithPoints {
        val points = (0 until 20 * 12)
            .map { it * 5_000L }
            .filter { (it / 60_000L).toInt() !in gapMinutes }
            .map { offset -> BpmDataPointEntity(recordOwnerId = id, timestamp = offset, bpm = bpmAt(offset / 60_000.0)) }
        return BpmRecordWithPoints(
            metadata = BpmRecordEntity(
                recordId = id, title = "Untitled $id", date = startMs, startTime = startMs,
                endTime = startMs + 20 * 60_000L, durationMs = 20 * 60_000L,
                wearerName = wearer, deviceId = device
            ),
            dataPoints = points,
            minDataPoint = points.minBy { it.bpm },
            maxDataPoint = points.maxBy { it.bpm }
        )
    }

    private val look = ExportPreset(width = 1080, height = 1080)

    @Test
    fun aVideoFrameDrawsWhatItDrewBefore() {
        val content = ExportContent(
            records = records, startMs = startMs, endMs = startMs + 20 * 60_000L,
            title = "Reference", colours = colours, clipZoneId = "UTC"
        )
        val bitmap = Bitmap.createBitmap(look.width, look.height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        VideoFrameRenderer.renderAlignedRecordsOnCanvas(
            Canvas(bitmap), VideoFrameRenderer.alignRecords(records), content, look,
            currentTimeMs = 14 * 60_000.0
        )
        assertMatchesReference("video_frame", bitmap)
    }

    @Test
    fun aPictureDrawsWhatItDrewBefore() {
        val spec = ImageRenderer.Spec(
            title = "Reference",
            windowStartWallClockMs = startMs,
            windowEndWallClockMs = startMs + 20 * 60_000L,
            series = records.map { record ->
                ImageRenderer.Series(
                    label = ChartKit.wearerLabel(record),
                    colorArgb = colours.getValue(record.metadata.recordId),
                    points = record.dataPoints.map { ImageRenderer.Series.Point(it.timestamp, it.bpm) }
                )
            },
            timeZoneId = "UTC",
            look = look
        )
        val bitmap = ImageRenderer.render(spec)
        assertNotNull(bitmap)
        assertMatchesReference("image", bitmap!!)
    }

    /** The same input drawn twice comes out the same, before any reference comes into it. */
    @Test
    fun drawingIsRepeatable() {
        val spec = ImageRenderer.Spec(
            title = "Twice", windowStartWallClockMs = startMs, windowEndWallClockMs = startMs + 20 * 60_000L,
            series = listOf(ImageRenderer.Series("Danny", colours.getValue(1), records[0].dataPoints.map {
                ImageRenderer.Series.Point(it.timestamp, it.bpm)
            })),
            timeZoneId = "UTC", look = look
        )
        val difference = differenceBetween(ImageRenderer.render(spec)!!, ImageRenderer.render(spec)!!)
        assertTrue("Two draws of one input differed in ${difference.share * 100}% of pixels", difference.share == 0.0)
    }

    private fun assertMatchesReference(name: String, actual: Bitmap) {
        val reference = runCatching {
            instrumentation.context.assets.open("reference/$name.png").use(BitmapFactory::decodeStream)
        }.getOrNull()

        if (reference == null) {
            val out = File(instrumentation.targetContext.filesDir, "reference/$name.png")
            out.parentFile?.mkdirs()
            out.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fail("No reference for $name yet. Recorded one at ${out.absolutePath}; check it by eye and copy it into src/androidTest/assets/reference/.")
            return
        }

        assertTrue(
            "$name is ${actual.width}×${actual.height}, the reference ${reference.width}×${reference.height}",
            actual.width == reference.width && actual.height == reference.height
        )
        val difference = differenceBetween(actual, reference)
        if (difference.share > MAX_DIFFERING_SHARE) {
            val out = File(instrumentation.targetContext.filesDir, "reference/$name.actual.png")
            out.parentFile?.mkdirs()
            out.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fail("$name differs from its reference in ${"%.2f".format(difference.share * 100)}% of pixels. What it drew now is at ${out.absolutePath}.")
        }
    }

    private class Difference(val share: Double)

    /** The share of pixels where any channel is off by more than [CHANNEL_TOLERANCE]. */
    private fun differenceBetween(a: Bitmap, b: Bitmap): Difference {
        val count = a.width * a.height
        val pa = IntArray(count).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(count).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        var differing = 0
        for (i in 0 until count) {
            val x = pa[i]
            val y = pb[i]
            if (x == y) continue
            if (abs(Color.alpha(x) - Color.alpha(y)) > CHANNEL_TOLERANCE ||
                abs(Color.red(x) - Color.red(y)) > CHANNEL_TOLERANCE ||
                abs(Color.green(x) - Color.green(y)) > CHANNEL_TOLERANCE ||
                abs(Color.blue(x) - Color.blue(y)) > CHANNEL_TOLERANCE
            ) differing++
        }
        return Difference(differing.toDouble() / count)
    }

    private companion object {
        /** Antialiasing can land a channel a few steps differently. */
        const val CHANNEL_TOLERANCE = 8

        /** Under half a percent: a moved label or a different curve is far more than this. */
        const val MAX_DIFFERING_SHARE = 0.005
    }
}
