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
    fun `a stamp corroborated as the last frame is wound back by the duration`() {
        // An encoder that stamps at finalisation: taken == last frame, and so does the mtime.
        val stamp = VideoTiming.resolve(
            dateTakenMs = noon + 3 * minute,
            durationMs = 3 * minute,
            dateModifiedMs = noon + 3 * minute
        )

        assertEquals(noon, stamp?.startedAtMs)
        assertEquals(VideoTiming.Basis.FINISHED, stamp?.basis)
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
        val timeline = ImageExporter.Timeline(originWallClockMs = noon, durationMs = 10 * minute)

        val window = clip(noon + 2 * minute, minute).windowOn(timeline, syncOffsetMs = 0L)

        assertEquals(2 * minute, window.startMs)
        assertEquals(3 * minute, window.endMs)
    }

    @Test
    fun `the sync offset moves the clip along the timeline`() {
        // What the preview was missing: it computed this window without the offset, so nudging the
        // setting moved the exported video and left the preview showing the old alignment.
        val timeline = ImageExporter.Timeline(originWallClockMs = noon, durationMs = 10 * minute)
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
        val timeline = ImageExporter.Timeline(originWallClockMs = noon, durationMs = 90_000L)

        val clamped = clip(noon, 10 * minute).windowOn(timeline, syncOffsetMs = -60_000L)

        assertEquals(0L, clamped.startMs)
        assertEquals(90_000L, clamped.endMs)
        assertTrue(clamped.spanMs > 0L)
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
        assertEquals(noon, forPicker?.startedAtMs)
    }
}
