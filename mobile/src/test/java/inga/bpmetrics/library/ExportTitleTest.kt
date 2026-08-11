package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What an export is called.
 *
 * Worth pinning down in a test because three call sites depend on it — the video, the image, and the
 * clip card that promises what the video will be called — and the whole point of [ExportTitle] is
 * that those three cannot drift apart. A test on the rule is cheaper than noticing on a finished
 * render that the caption reads backwards.
 */
class ExportTitleTest {

    private val festival = EventEntity(eventId = 1, name = "Griz", locationId = 10)
    private val day = EventEntity(eventId = 2, name = "Day 2", parentId = 1)
    private val set = EventEntity(eventId = 3, name = "Griztronics", parentId = 2)
    private val events = listOf(festival, day, set)
    private val places = mapOf(10L to LocationEntity(locationId = 10, name = "The Gorge"))

    /** The structure Danny asked for, in the order he asked for it. */
    @Test
    fun `innermost first, then the family, then the venue`() {
        assertEquals(
            "Griztronics  |  Day 2  |  Griz @ The Gorge",
            ExportTitle.of(eventId = 3, events = events, places = places)
        )
    }

    /**
     * The rule that a filed recording is named after where it was filed.
     *
     * A graph of the Tape B set is a picture of the set, whoever's watch drew it — so prefixing one
     * of the four recordings it came from is arbitrary. The recording name is passed here and must
     * be ignored.
     */
    @Test
    fun `a filed recording is named after its event, not itself`() {
        assertEquals(
            "Griztronics  |  Day 2  |  Griz @ The Gorge",
            ExportTitle.of(
                recordingName = "Levity",
                eventId = 3,
                events = events,
                places = places
            )
        )
    }

    /**
     * The event's own export, which is the same caption minus the recording.
     *
     * A festival's export is not of any one recording, so naming one would be a lie about what is
     * in the frame.
     */
    @Test
    fun `an event names itself and its ancestry`() {
        assertEquals(
            "Day 2  |  Griz @ The Gorge",
            ExportTitle.of(eventId = 2, events = events, places = places)
        )
    }

    /**
     * The venue is attached once, to the line, not to each rung.
     *
     * A set inherits its festival's place, so resolving it per level would repeat "The Gorge" three
     * times across one caption.
     */
    @Test
    fun `the venue is appended once however deep the nesting`() {
        val title = ExportTitle.of(eventId = 3, events = events, places = places)
        assertEquals(1, title.split("The Gorge").size - 1)
    }

    /** No place recorded is not an error; the caption simply ends after the family. */
    @Test
    fun `no venue leaves the family alone`() {
        assertEquals("Day 2  |  Griz", ExportTitle.of(eventId = 2, events = events))
    }

    /** Danny's own case: four recordings in a set, inside one other event. */
    @Test
    fun `a set inside one event names both`() {
        val levitape = EventEntity(eventId = 20, name = "Levitape")
        val tapeB = EventEntity(eventId = 21, name = "Tape B", parentId = 20)
        assertEquals(
            "Tape B  |  Levitape",
            ExportTitle.of(eventId = 21, events = listOf(levitape, tapeB))
        )
    }

    /** An unfiled recording still has a name, and that name is the whole caption. */
    @Test
    fun `a recording outside any event is just its own name`() {
        assertEquals("Levity", ExportTitle.of(recordingName = "Levity", events = events))
    }

    /**
     * The innermost event covering several recordings, for a clip that ran across two sets.
     *
     * Not a title concern so much as a tree one, but it is the input the titles are built from and
     * getting it from `firstOrNull` was arbitrary.
     */
    @Test
    fun `several recordings are named by what contains all of them`() {
        // Two sets on the same day: the day is what they share.
        val other = EventEntity(eventId = 4, name = "Opener", parentId = 2)
        val all = events + other
        assertEquals(2L, EventTree.commonAncestorOf(all, listOf(3L, 4L)))
        // One set with itself is itself, not its parent.
        assertEquals(3L, EventTree.commonAncestorOf(all, listOf(3L, 3L)))
        // An unfiled recording in the group means nothing contains them all.
        assertEquals(null, EventTree.commonAncestorOf(all, listOf(3L, null)))
        // As does an event that has been deleted out from under a queued export.
        assertEquals(null, EventTree.commonAncestorOf(all, listOf(3L, 999L)))
        assertEquals(null, EventTree.commonAncestorOf(all, emptyList()))
    }

    /**
     * Nothing to say, said as nothing.
     *
     * The renderers draw this string; returning a placeholder like "Untitled" would burn it into
     * a video, so an empty caption has to survive all the way out.
     */
    @Test
    fun `nothing known produces no title`() {
        assertEquals("", ExportTitle.of())
        assertEquals("", ExportTitle.of(recordingName = "   "))
    }

    /** A dangling event id — deleted out from under a queued export — must not invent a family. */
    @Test
    fun `an event that no longer exists contributes nothing`() {
        assertEquals(
            "Levity",
            ExportTitle.of(recordingName = "Levity", eventId = 999, events = events, places = places)
        )
    }
}
