package inga.bpmetrics.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic that decides where a clip sits against a heart rate recording.
 *
 * Worth testing hard: every millisecond of error here is a millisecond the curve is out by in the
 * finished video, and the failure is invisible until someone watches it.
 */
class VideoTimingTest {

    private val noon = 1_700_000_000_000L
    private val minute = 60_000L

    @Test
    fun `a stamp corroborated as the first frame is taken as the start`() {
        // A camera that stamps when the muxer starts: taken == first frame, file closed at the end.
        val stamp = VideoTiming.resolve(
            dateTakenMs = noon,
            durationMs = 3 * minute,
            dateModifiedMs = noon + 3 * minute
        )

        assertEquals(noon, stamp?.startedAtMs)
        assertEquals(VideoTiming.Basis.STARTED, stamp?.basis)
    }

    @Test
    fun `a stamp corroborated as the last frame is wound back by the duration and the save`() {
        // An encoder that stamps at finalisation: taken == end of saving, and so does the mtime.
        // Saving took 0.8 s on average; cut to the second, the stamp has already lost 0.5 of it.
        val stamp = VideoTiming.resolve(
            dateTakenMs = noon + 3 * minute,
            durationMs = 3 * minute,
            dateModifiedMs = noon + 3 * minute
        )

        assertEquals(noon - 300L, stamp?.startedAtMs)
        assertEquals(VideoTiming.Basis.FINISHED, stamp?.basis)
    }

    @Test
    fun `a stamp to the millisecond is wound back by the whole save`() {
        val stamp = VideoTiming.resolve(
            dateTakenMs = noon + 3 * minute + 250L,
            durationMs = 3 * minute,
            dateModifiedMs = noon + 3 * minute
        )

        assertEquals(noon + 250L - 800L, stamp?.startedAtMs)
    }

    @Test
    fun `a few seconds of muxing does not change the verdict`() {
        val stamp = VideoTiming.resolve(
            dateTakenMs = noon,
            durationMs = 3 * minute,
            dateModifiedMs = noon + 3 * minute + 4_000L
        )

        assertEquals(noon, stamp?.startedAtMs)
        assertEquals(VideoTiming.Basis.STARTED, stamp?.basis)
    }

    @Test
    fun `a phone stamping local time as UTC is pulled back to the right hour`() {
        // The clip really ran noon to 12:03. The file says 17:00 because the camera wrote local
        // time into a field defined as UTC; the filesystem, which has no such confusion, says the
        // file was finished at 12:03.
        val fiveHours = 5 * 60 * minute
        val stamp = VideoTiming.resolve(
            dateTakenMs = noon + fiveHours,
            durationMs = 3 * minute,
            dateModifiedMs = noon + 3 * minute
        )

        assertEquals(noon, stamp?.startedAtMs)
        assertEquals(fiveHours, stamp?.zoneShiftMs)
    }

    @Test
    fun `a half hour zone is corrected too`() {
        // India, Newfoundland, and the reason this rounds to quarter hours rather than to hours.
        val offset = 30 * minute
        val stamp = VideoTiming.resolve(
            dateTakenMs = noon + offset,
            durationMs = 2 * minute,
            dateModifiedMs = noon + 2 * minute
        )

        assertEquals(noon, stamp?.startedAtMs)
        assertEquals(offset, stamp?.zoneShiftMs)
    }

    @Test
    fun `a file copied days later is left where it is`() {
        // The mtime now describes the copy, not the filming. No offset on earth explains three
        // days, so the stamp stands rather than being dragged to make the numbers agree.
        val threeDays = 3 * 24 * 60 * minute
        val stamp = VideoTiming.resolve(
            dateTakenMs = noon,
            durationMs = 3 * minute,
            dateModifiedMs = noon + 3 * minute + threeDays
        )

        assertEquals(noon, stamp?.startedAtMs)
        assertEquals(VideoTiming.Basis.ASSUMED, stamp?.basis)
        assertEquals(0L, stamp?.zoneShiftMs)
    }

    @Test
    fun `a gap of a few hours is not blamed on a timezone`() {
        // Three hours is a legal offset, so the tempting reading is a zone error. But a zone error
        // leaves seconds of slack, not two and a half minutes, and this file was simply touched
        // later. Guessing here would move a correct clip three hours.
        val stamp = VideoTiming.resolve(
            dateTakenMs = noon,
            durationMs = 3 * minute,
            dateModifiedMs = noon + 3 * minute + 3 * 60 * minute + 150_000L
        )

        assertEquals(noon, stamp?.startedAtMs)
        assertEquals(VideoTiming.Basis.ASSUMED, stamp?.basis)
    }

    @Test
    fun `with no modification time the stamp is read as the start`() {
        val stamp = VideoTiming.resolve(
            dateTakenMs = noon,
            durationMs = 3 * minute,
            dateModifiedMs = 0L
        )

        assertEquals(noon, stamp?.startedAtMs)
        assertEquals(VideoTiming.Basis.ASSUMED, stamp?.basis)
        assertTrue(stamp?.corroborated == false)
    }

    @Test
    fun `no stamp at all is no answer, not a wrong one`() {
        assertNull(VideoTiming.resolve(dateTakenMs = 0L, durationMs = 3 * minute, dateModifiedMs = noon))
    }

    @Test
    fun `a zero length clip resolves to its stamp either way`() {
        // Both readings coincide when there is no duration to wind back, and neither should throw.
        val stamp = VideoTiming.resolve(dateTakenMs = noon, durationMs = 0L, dateModifiedMs = noon)

        assertEquals(noon, stamp?.startedAtMs)
    }

    private fun clip(startedAtMs: Long, durationMs: Long) = VideoExporter.VideoClip(
        uri = io.mockk.mockk(),
        startedAtMs = startedAtMs,
        durationMs = durationMs,
        displayName = "VID_0001.mp4"
    )

    @Test
    fun `a clip sits where it was filmed on the recording timeline`() {
        // Recording began at noon; filming began two minutes in and ran for one.
        val timeline = VideoFrameRenderer.Timeline(originWallClockMs = noon, durationMs = 10 * minute)

        val window = clip(noon + 2 * minute, minute).windowOn(timeline, syncOffsetMs = 0L)

        assertEquals(2 * minute, window.startMs)
        assertEquals(3 * minute, window.endMs)
    }

    @Test
    fun `the sync offset moves the clip along the timeline`() {
        // What the preview was missing: it computed this window without the offset, so nudging the
        // setting moved the exported video and left the preview showing the old alignment.
        val timeline = VideoFrameRenderer.Timeline(originWallClockMs = noon, durationMs = 10 * minute)
        val filmed = clip(noon + 2 * minute, minute)

        val ahead = filmed.windowOn(timeline, syncOffsetMs = 5_000L)
        val behind = filmed.windowOn(timeline, syncOffsetMs = -5_000L)

        assertEquals(2 * minute + 5_000L, ahead.startMs)
        assertEquals(2 * minute - 5_000L, behind.startMs)
        // The clip is still the same length; the offset shifts it, it does not stretch it.
        assertEquals(minute, ahead.endMs - ahead.startMs)
        assertEquals(minute, behind.endMs - behind.startMs)
    }

    @Test
    fun `a window cannot start before the timeline or run past its end`() {
        val timeline = VideoFrameRenderer.Timeline(originWallClockMs = noon, durationMs = 90_000L)

        val clamped = clip(noon, 10 * minute).windowOn(timeline, syncOffsetMs = -60_000L)

        assertEquals(0L, clamped.startMs)
        assertEquals(90_000L, clamped.endMs)
        assertTrue(clamped.spanMs > 0L)
    }

    /**
     * Filming began before the watch did, as it did at the Gorge: 1.755 s before the press.
     *
     * The footage from before the press has no curve, so the window starts at the press and the
     * first 1.755 s of the file are skipped. The preview used to start from the top of the file
     * anyway, drawing every frame against the curve from 1.755 s later.
     */
    @Test
    fun `footage from before the recording began is skipped, not slid along`() {
        val timeline = VideoFrameRenderer.Timeline(originWallClockMs = noon, durationMs = 10 * minute)

        val window = clip(noon - 1_755L, 37_535L).windowOn(timeline, syncOffsetMs = 0L)

        assertEquals(0L, window.startMs)
        assertEquals(1_755L, window.intoClipMs)
        // It ends where the footage does, not 1.755 s after.
        assertEquals(37_535L - 1_755L, window.endMs)
    }

    @Test
    fun `a clip inside the timeline starts at the top of its file`() {
        val timeline = VideoFrameRenderer.Timeline(originWallClockMs = noon, durationMs = 10 * minute)

        assertEquals(0L, clip(noon + minute, minute).windowOn(timeline, 0L).intoClipMs)
    }

    @Test
    fun `the picker and the exporter cannot disagree`() {
        // The defect this exists to prevent: the clip list read DATE_TAKEN as the first frame while
        // the exporter read it as the last, so every MP4 was rendered a clip-length away from where
        // its own preview said it was. One resolver, one answer.
        val taken = noon + 3 * minute
        val duration = 3 * minute
        val modified = noon + 3 * minute

        val forPicker = VideoTiming.resolve(taken, duration, modified)
        val forExport = VideoTiming.resolve(taken, duration, modified)

        assertEquals(forPicker?.startedAtMs, forExport?.startedAtMs)
        assertEquals(noon - 300L, forPicker?.startedAtMs)
    }

    // --- The camera's file name ---
    //
    // Real rows from the Galaxy S23 Ultra's gallery. Samsung stamps when saving finishes, to the
    // second; the name is local time at the press, also to the second.

    /** 2026-11-05, when the test clips were copied back onto the phone. */
    private val copiedBack = 1_793_935_803_000L

    /**
     * The Gorge clip, whose watch frame showed the curve running a second ahead of the footage.
     *
     * The watch on screen reads 1 s at 3.1–3.7 s into the clip, so the press (03:03:39.255 UTC,
     * from the recording) came 1.6–2.1 s in. The name puts it 1.755 s in.
     */
    @Test
    fun `the Gorge clip starts when its name says, which is where its watch frame puts it`() {
        val stamp = VideoTiming.resolve(
            dateTakenMs = 1_779_678_256_000L, // 03:04:16 UTC, saving finished
            durationMs = 37_535L,
            dateModifiedMs = 1_779_678_256_000L,
            fileName = "20260524_200337.mp4" // 20:03:37 at -07:00
        )

        assertEquals(VideoTiming.Basis.NAMED, stamp?.basis)
        assertEquals(1_779_678_217_500L, stamp?.startedAtMs)
        val pressAt = 1_779_678_219_255L - stamp!!.startedAtMs
        assertTrue("watch press $pressAt ms in", pressAt in 1_600L..2_100L)
    }

    /**
     * A clip copied back onto a phone still starts in the right place.
     *
     * Its mtime is the day it was copied, which corroborates neither reading of the stamp, so on its
     * own it would be taken as starting when it actually finished — a whole clip out.
     */
    @Test
    fun `a clip copied onto the phone later is placed by its name`() {
        val withoutName = VideoTiming.resolve(1_779_678_256_000L, 37_535L, copiedBack)
        assertEquals(VideoTiming.Basis.ASSUMED, withoutName?.basis)

        val stamp = VideoTiming.resolve(1_779_678_256_000L, 37_535L, copiedBack, "20260524_200337.mp4")

        assertEquals(VideoTiming.Basis.NAMED, stamp?.basis)
        assertEquals(1_779_678_217_500L, stamp?.startedAtMs)
    }

    @Test
    fun `the other test clips are placed by their names in their own zones`() {
        // Central daylight time, Pacific daylight time, Central again.
        data class Row(val name: String, val taken: Long, val length: Long, val start: Long)
        listOf(
            Row("20260313_235545.mp4", 1_773_464_178_000L, 32_005L, 1_773_464_145_500L),
            Row("20260524_235354.mp4", 1_779_692_063_000L, 27_343L, 1_779_692_034_500L),
            Row("20261017_002335.mp4", 1_792_214_652_000L, 36_565L, 1_792_214_615_500L)
        ).forEach { row ->
            val stamp = VideoTiming.resolve(row.taken, row.length, copiedBack, row.name)
            assertEquals(row.name, VideoTiming.Basis.NAMED, stamp?.basis)
            assertEquals(row.name, row.start, stamp?.startedAtMs)
        }
    }

    /**
     * A clip trimmed in the gallery keeps its name and gets a new stamp, 22 s apart here.
     *
     * The name now describes footage that was cut away, so the stamp is the better witness.
     */
    @Test
    fun `a trimmed clip's name is not believed`() {
        val stamp = VideoTiming.resolve(
            dateTakenMs = 1_789_877_653_000L,
            durationMs = 43_212L,
            dateModifiedMs = 1_789_877_714_000L,
            fileName = "20260919_231308.mp4"
        )

        assertTrue(stamp?.basis != VideoTiming.Basis.NAMED)
        assertEquals(1_789_877_653_000L, stamp?.startedAtMs)
    }

    @Test
    fun `a renamed clip falls back to its stamp`() {
        val stamp = VideoTiming.resolve(1_779_678_256_000L, 37_535L, 1_779_678_256_000L, "Gorge.mp4")

        assertEquals(VideoTiming.Basis.FINISHED, stamp?.basis)
        assertEquals(1_779_678_256_000L - 37_535L - 300L, stamp?.startedAtMs)
    }

    @Test
    fun `a name a day out is not mistaken for a timezone`() {
        val stamp = VideoTiming.resolve(1_779_678_256_000L, 37_535L, 1_779_678_256_000L, "20260523_200337.mp4")

        assertEquals(VideoTiming.Basis.FINISHED, stamp?.basis)
    }

    @Test
    fun `only names that start with a camera's date are read`() {
        listOf(
            "Screen_Recording_20260524-200337_Instagram.mp4",
            "edit_20260524_200337.mp4",
            "20261399_250000.mp4",
            "20260524_2003371.mp4"
        ).forEach { name ->
            val stamp = VideoTiming.resolve(1_779_678_256_000L, 37_535L, 1_779_678_256_000L, name)
            assertEquals(name, VideoTiming.Basis.FINISHED, stamp?.basis)
        }
    }

    @Test
    fun `duplicate and older camera names are read`() {
        listOf("20260524_200337(1).mp4", "20260524_200337_01.mp4", "VID_20260524_200337.mp4").forEach { name ->
            val stamp = VideoTiming.resolve(1_779_678_256_000L, 37_535L, 1_779_678_256_000L, name)
            assertEquals(name, 1_779_678_217_500L, stamp?.startedAtMs)
        }
    }

    @Test
    fun `a Pixel name to the millisecond is taken to the millisecond`() {
        // Pixels name files in UTC; the quarter-hour comparison does not care which zone it is.
        val stamp = VideoTiming.resolve(1_779_678_256_000L, 37_535L, 1_779_678_256_000L, "PXL_20260525_030337123.mp4")

        assertEquals(1_779_678_217_123L, stamp?.startedAtMs)
    }

    @Test
    fun `a name for a stamp that marks the first frame is read as well`() {
        val stamp = VideoTiming.resolve(
            dateTakenMs = 1_779_678_217_000L,
            durationMs = 37_535L,
            dateModifiedMs = 1_779_678_256_000L,
            fileName = "20260524_200337.mp4"
        )

        assertEquals(VideoTiming.Basis.NAMED, stamp?.basis)
        assertEquals(1_779_678_217_500L, stamp?.startedAtMs)
    }

    @Test
    fun `an iPhone copy shared the next morning is placed when it was filmed`() {
        // VID_20260524_094251.mp4, as Android lists it: stamped when the copy was made, named and
        // finished when the phone saved it. The copy's udta date says when filming began.
        val at = { iso: String -> java.time.Instant.parse(iso).toEpochMilli() }
        val fromStore = VideoTiming.resolve(
            dateTakenMs = at("2026-05-24T16:28:14Z"),
            durationMs = 118_700L,
            dateModifiedMs = at("2026-05-24T16:42:51Z"),
            fileName = "VID_20260524_094251.mp4"
        )!!
        // Nothing about the file itself can be trusted: neither the stamp nor the name agree.
        assertEquals(VideoTiming.Basis.ASSUMED, fromStore.basis)

        val captured = VideoTiming.resolve(
            dateTakenMs = at("2026-05-24T16:28:14Z"),
            durationMs = 118_700L,
            dateModifiedMs = at("2026-05-24T16:42:51Z"),
            fileName = "VID_20260524_094251.mp4",
            capturedAtMs = at("2026-05-24T05:47:59Z")
        )!!
        assertEquals(VideoTiming.Basis.CAPTURED, captured.basis)
        // Ten forty-eight in the evening on day one, half a second into the second it names.
        assertEquals(at("2026-05-24T05:47:59.500Z"), captured.startedAtMs)
    }
}
