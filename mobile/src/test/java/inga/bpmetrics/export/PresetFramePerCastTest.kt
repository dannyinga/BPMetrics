package inga.bpmetrics.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which frame a clip gets, given how many people are on it.
 *
 * The reason this exists: a preset framed around two readouts was applied whole to every clip in a
 * batch, so four people squeezed to the minimum pill height and one person sat in a box half empty.
 * The look is the same at every cast; only the rectangle differs.
 *
 * Pure arithmetic over the preset, so it is worth pinning here rather than discovering across a
 * batch of six renders.
 */
class PresetFramePerCastTest {

    private val twoPeople = GraphFrame(0.25f, 0.58f, 0.75f, 0.91f)
    private val fourPeople = GraphFrame(0.20f, 0.40f, 0.80f, 0.93f)

    private fun preset(vararg pins: Pair<Int, GraphFrame>) =
        ExportPreset(framesByLaneCount = pins.toMap())

    /** An untouched preset behaves exactly as it did before any of this existed. */
    @Test
    fun `no pins means the preset's own frame, at every cast`() {
        val p = ExportPreset()
        listOf(0, 1, 2, 5, 40).forEach { assertEquals(p.baseFrame, p.frameFor(it)) }
    }

    /** The whole point: you looked at four people and said "this one". */
    @Test
    fun `an exact pin wins`() {
        val p = preset(2 to twoPeople, 4 to fourPeople)
        assertEquals(twoPeople, p.frameFor(2))
        assertEquals(fourPeople, p.frameFor(4))
    }

    /**
     * Three people, between a two and a four, lands halfway.
     *
     * A frame grown between the counts you cared about is far likelier to be right than the
     * two-person one applied whole, which is what used to happen.
     */
    @Test
    fun `between two pins, interpolate`() {
        val p = preset(2 to twoPeople, 4 to fourPeople)
        val three = p.frameFor(3)

        assertEquals((0.25f + 0.20f) / 2f, three.left, 0.0001f)
        assertEquals((0.58f + 0.40f) / 2f, three.top, 0.0001f)
        assertEquals((0.75f + 0.80f) / 2f, three.right, 0.0001f)
        assertEquals((0.91f + 0.93f) / 2f, three.bottom, 0.0001f)

        // And it really is between them, not merely different from both.
        assertTrue(three.height > twoPeople.height)
        assertTrue(three.height < fourPeople.height)
    }

    /**
     * Outside the pins, hold the nearest rather than extrapolate.
     *
     * Extrapolating means inventing a rate of growth, and a made-up rate applied to seven people
     * produces a rectangle nobody has ever looked at. The nearest pin is a frame that was actually
     * approved, for the closest cast that was actually framed.
     */
    @Test
    fun `outside the pins, the nearest one holds`() {
        val p = preset(2 to twoPeople, 4 to fourPeople)
        assertEquals(twoPeople, p.frameFor(1))
        assertEquals(fourPeople, p.frameFor(5))
        assertEquals(fourPeople, p.frameFor(40))
    }

    /** One pin is a decision about everything, since there is no slope to follow. */
    @Test
    fun `a single pin answers every cast`() {
        val p = preset(3 to fourPeople)
        listOf(1, 2, 3, 4, 9).forEach { assertEquals(fourPeople, p.frameFor(it)) }
    }

    /**
     * A cast of zero means "no clip in hand", not "a clip with nobody on it".
     *
     * That is the preset editor in Settings, which frames the look itself rather than any shot. It
     * has to answer the base frame, because the base frame is what dragging there writes to — the
     * alternative is a control displaying one rectangle and editing another.
     */
    @Test
    fun `no clip in hand gives the base frame, not a pin`() {
        val p = preset(1 to twoPeople, 4 to fourPeople)
        assertEquals(p.baseFrame, p.frameFor(0))
        assertEquals(twoPeople, p.frameFor(1))
    }

    /**
     * A collapsed pin is dropped, not drawn.
     *
     * A rectangle with no area renders an empty graph — the failure `sanitised` already guards the
     * preset's own frame against. A pin that draws nothing is worse than an absent one, which at
     * least falls back to a frame that works.
     */
    @Test
    fun `a collapsed pin is ignored`() {
        val flat = GraphFrame(0.5f, 0.5f, 0.5f, 0.5f)
        val p = preset(2 to flat, 4 to fourPeople)

        assertEquals(fourPeople, p.frameFor(2))
        assertTrue(p.sanitised().framesByLaneCount.keys.none { it == 2 })
    }

    /** Pins survive being written and read back, which is what makes them part of the look. */
    @Test
    fun `pins survive a round trip`() {
        val p = ExportPreset(
            name = "Festival wide",
            framesByLaneCount = mapOf(1 to twoPeople, 4 to fourPeople)
        )

        val read = ExportPreset.fromJson(p.toJson())

        assertEquals(p.framesByLaneCount, read?.framesByLaneCount)
        assertEquals(fourPeople, read?.frameFor(4))
    }
}
