package inga.bpmetrics.ui.export

import inga.bpmetrics.export.ImageExporter
import inga.bpmetrics.export.VideoExporter
import inga.bpmetrics.export.VideoTiming
import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.BpmRecordWithPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling a clip when it was really filmed.
 *
 * The case that asked for this: a screen recording made today, of a set filmed a month ago. Its file
 * says today, so it overlapped no recording, lined up with nothing, and the one alignment control on
 * offer — a ±30 second sync offset — could not bridge a month.
 */
class ClipRetimingTest {

    private val minute = 60_000L
    /** 3:19:08 PM on the day of the set, as an instant. */
    private val setStart = 1_786_738_748_000L
    /** When the screen recording was made — a month later. */
    private val today = setStart + 31L * 24 * 60 * minute

    private fun recording(id: Long, startedAt: Long, lengthMs: Long) = BpmRecordWithPoints(
        metadata = BpmRecordEntity(
            recordId = id,
            title = "Record $id",
            date = startedAt,
            startTime = startedAt,
            endTime = startedAt + lengthMs,
            durationMs = lengthMs
        ),
        dataPoints = emptyList(),
        minDataPoint = null,
        maxDataPoint = null
    )

    /** Two people recording the whole set, an hour long. */
    private val set = listOf(
        recording(1, setStart - 10 * minute, 60 * minute),
        recording(2, setStart - 10 * minute, 60 * minute)
    )

    private fun screenRecording(startedAt: Long = today, lengthMs: Long = 3 * minute) =
        VideoExporter.VideoClip(
            uri = io.mockk.mockk(),
            startedAtMs = startedAt,
            durationMs = lengthMs,
            displayName = "Screen_Recording.mp4"
        )

    private fun picked(clip: VideoExporter.VideoClip) = ClipSelection(
        clip = clip,
        recordIds = ClipRetiming.castFor(clip, set, emptySet()),
        selected = false
    )

    // --- The reported case, end to end ---

    /**
     * Set 3:19:08 PM and it syncs: the graph shows the stretch of the recording the footage is of.
     *
     * The whole promise of one number. The length is the file's, so correcting the start is enough
     * for the existing window arithmetic to put the right three minutes of curve over three minutes
     * of footage — no separate graph start or duration to keep in agreement with it.
     */
    @Test
    fun `a screen recording set to when it was filmed lines up with the set`() {
        val clip = picked(screenRecording())
        assertTrue("a month out, it should overlap nothing", clip.recordIds.isEmpty())

        val fixed = ClipRetiming.retime(clip, setStart, set)

        // Both people were recording then, so both are on it.
        assertEquals(setOf(1L, 2L), fixed.recordIds)

        // And the graph covers exactly the stretch the footage is of.
        val timeline = ImageExporter.Timeline(
            originWallClockMs = set.minOf { it.metadata.startTime },
            durationMs = 60 * minute
        )
        val window = fixed.clip.windowOn(timeline, syncOffsetMs = 0L)
        assertEquals(10 * minute, window.startMs)
        assertEquals(13 * minute, window.endMs)
    }

    /** It says it was set by hand, rather than claiming a stamp the file never held. */
    @Test
    fun `a corrected clip is marked as set by hand, and keeps the file's own`() {
        val file = screenRecording()
        val fixed = ClipRetiming.retime(picked(file), setStart, set)

        assertEquals(VideoTiming.Basis.SET_BY_HAND, fixed.clip.basis)
        assertEquals(file, fixed.originalClip)
        // The length is untouched: it is the one thing the file was right about.
        assertEquals(file.durationMs, fixed.clip.durationMs)
    }

    /** Correcting twice starts from the file each time, so the second does not compound the first. */
    @Test
    fun `correcting twice does not compound`() {
        val file = screenRecording()
        val once = ClipRetiming.retime(picked(file), setStart, set)
        val twice = ClipRetiming.retime(once, setStart + 5 * minute, set)

        assertEquals(setStart + 5 * minute, twice.clip.startedAtMs)
        assertEquals(file, twice.originalClip)
    }

    @Test
    fun `using the file's time again undoes the correction`() {
        val file = screenRecording()
        val fixed = ClipRetiming.retime(picked(file), setStart, set)

        val reset = ClipRetiming.reset(fixed, set)

        assertEquals(file, reset.clip)
        assertNull(reset.originalClip)
        assertTrue("back a month out, nobody was recording", reset.recordIds.isEmpty())
    }

    @Test
    fun `resetting a clip that was never corrected changes nothing`() {
        val clip = picked(screenRecording(startedAt = setStart))
        assertEquals(clip, ClipRetiming.reset(clip, set))
    }

    // --- Who is on it ---

    /**
     * Nudging a time keeps someone unticked on purpose.
     *
     * Correcting by a few seconds is ordinary, and throwing away a deliberate choice each time would
     * make the correction something to be done before choosing people rather than alongside it.
     */
    @Test
    fun `a deliberate untick survives a small correction`() {
        val fixed = ClipRetiming.retime(picked(screenRecording()), setStart, set)
        val justOne = fixed.copy(recordIds = setOf(1L))

        val nudged = ClipRetiming.retime(justOne, setStart + 2_000L, set)

        assertEquals(setOf(1L), nudged.recordIds)
    }

    /**
     * A time nobody was recording at cannot stay ticked.
     *
     * It would render footage with no curve on it. The Contents step already refuses to tick such a
     * clip; a correction must not be a way around that.
     */
    @Test
    fun `a clip moved to an empty moment is unticked`() {
        val ticked = ClipRetiming.retime(picked(screenRecording()), setStart, set).copy(selected = true)

        val nowhere = ClipRetiming.retime(ticked, setStart + 10 * 60 * minute, set)

        assertTrue(nowhere.recordIds.isEmpty())
        assertFalse(nowhere.selected)
    }

    /** Moving it back undoes the untick it caused (Audit #19). */
    @Test
    fun `a clip moved back into range is ticked again`() {
        val ticked = ClipRetiming.retime(picked(screenRecording()), setStart, set).copy(selected = true)
        val nowhere = ClipRetiming.retime(ticked, setStart + 10 * 60 * minute, set)

        val back = ClipRetiming.retime(nowhere, setStart, set)

        assertTrue(back.selected)
        assertFalse(back.untickedByTime)
    }

    /** One unticked by hand stays unticked wherever it's moved. */
    @Test
    fun `a clip unticked by hand isn't ticked by a correction`() {
        val unticked = ClipRetiming.retime(picked(screenRecording()), setStart, set)

        val moved = ClipRetiming.retime(unticked, setStart + 2_000L, set)

        assertFalse(moved.selected)
    }

    /** The old sparkline drew the old stretch, and must not sit under the new time. */
    @Test
    fun `a correction clears the sparkline so the wrong evening is not shown`() {
        val drawn = picked(screenRecording()).copy(
            sparks = listOf(ClipSpark("Kyle", 0, listOf(0.5f), 150)),
            bpmScale = 100..160
        )

        val fixed = ClipRetiming.retime(drawn, setStart, set)

        assertTrue(fixed.sparks.isEmpty())
        assertNull(fixed.bpmScale)
    }

    // --- Where a picked video starts ---

    /** A clip that already lands on a recording is left where its file put it. */
    @Test
    fun `a picked video whose own time is right is left alone`() {
        val alreadyRight = screenRecording(startedAt = setStart)
        assertEquals(setStart, ClipRetiming.startingPoint(alreadyRight, set))
    }

    /**
     * One that lands on nothing starts at the first recording.
     *
     * So there is a graph to preview while its time is put right. Left at today, it would preview an
     * empty graph with nothing to correct against.
     */
    @Test
    fun `a picked video that lands on nothing starts at the first recording`() {
        assertEquals(
            set.minOf { it.metadata.startTime },
            ClipRetiming.startingPoint(screenRecording(), set)
        )
    }
}
