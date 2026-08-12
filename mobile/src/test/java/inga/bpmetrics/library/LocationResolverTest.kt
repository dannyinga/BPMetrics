package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which venue, and so which clock, applies to a recording.
 *
 * The design being guarded: a venue is a property of the *occasion*, set once on the festival, and
 * every recording under it inherits through the same walk tags and covers use. Nothing is written
 * downward, so a recording arriving late from a watch picks up the right clock the moment it is
 * filed, with nothing to clean up.
 *
 * The part that makes this more than a label: a window is entered as a wall-clock time, so if the
 * clock a recording is stored against and the clock a window is typed against disagree, membership
 * is wrong rather than merely the display.
 */
class LocationResolverTest {

    private fun place(id: Long, name: String, zone: String? = null) =
        LocationEntity(locationId = id, name = name, timeZoneId = zone, createdAt = id)

    private val gorge = place(1, "The Gorge", "America/Los_Angeles")
    private val showbox = place(2, "Showbox SoDo", "America/Los_Angeles")
    private val brooklyn = place(3, "Brooklyn Mirage", "America/New_York")
    private val unnamedZone = place(4, "A room somewhere")

    private fun registry(vararg places: LocationEntity) = places.associateBy { it.locationId }

    private fun event(id: Long, name: String, parent: Long? = null, at: Long? = null) =
        EventEntity(eventId = id, name = name, parentId = parent, createdAt = id, locationId = at)

    /** Griztronics at the Gorge › Day 1 › Subtronics. The venue is set once, at the top. */
    private fun festival() = listOf(
        event(1, "Griztronics", at = 1),
        event(2, "Day 1", parent = 1),
        event(3, "Subtronics", parent = 2)
    )

    // --- Inheritance ---

    @Test
    fun `a recording inherits the venue from the festival above it`() {
        val found = LocationResolver.forRecording(null, 3, festival(), registry(gorge))!!

        assertEquals("The Gorge", found.location.name)
        assertTrue(found.isInherited)
    }

    @Test
    fun `an event's own venue beats what it would inherit`() {
        val moved = festival() + event(4, "Afterparty", parent = 2, at = 2)

        assertEquals(
            "Showbox SoDo",
            LocationResolver.forRecording(null, 4, moved, registry(gorge, showbox))!!.location.name
        )
    }

    @Test
    fun `a recording's own venue beats the whole tree`() {
        // Someone who joined from elsewhere, or a watch left on the wrong clock.
        val found = LocationResolver.forRecording(3, 3, festival(), registry(gorge, brooklyn))!!

        assertEquals("Brooklyn Mirage", found.location.name)
        assertEquals(PlaceSource.DIRECT, found.source)
    }

    @Test
    fun `a recording filed nowhere has no venue`() {
        assertNull(LocationResolver.forRecording(null, null, festival(), registry(gorge)))
    }

    @Test
    fun `an event nobody located has no venue`() {
        val plain = listOf(event(1, "Somewhere"), event(2, "Inside it", parent = 1))

        assertNull(LocationResolver.forRecording(null, 2, plain, registry(gorge)))
    }

    // --- The registry is the point ---

    @Test
    fun `renaming a venue changes every event pointing at it`() {
        // The reason this is a registry rather than free text on each event. Nothing here needs
        // updating; the events hold an id.
        val renamed = gorge.copy(name = "The Gorge Amphitheatre")

        assertEquals(
            "The Gorge Amphitheatre",
            LocationResolver.forRecording(null, 3, festival(), registry(renamed))!!.location.name
        )
    }

    @Test
    fun `correcting a venue's zone changes every recording under it`() {
        val corrected = gorge.copy(timeZoneId = "America/Denver")

        assertEquals(
            "America/Denver",
            LocationResolver.zoneFor(null, 3, festival(), registry(corrected)).id
        )
    }

    @Test
    fun `two events at the same venue resolve to one identity`() {
        // What makes "the Gorge against Showbox" a comparison rather than a string match.
        val twoFestivals = listOf(event(1, "Grizt", at = 1), event(9, "Bass Canyon", at = 1))
        val registry = registry(gorge)

        assertEquals(
            LocationResolver.forRecording(null, 1, twoFestivals, registry)!!.location.locationId,
            LocationResolver.forRecording(null, 9, twoFestivals, registry)!!.location.locationId
        )
    }

    // --- Which clock to read in ---

    @Test
    fun `the zone is the venue's, not the reader's`() {
        val zone = LocationResolver.zoneFor(
            null, 3, festival(), registry(gorge),
            default = java.util.TimeZone.getTimeZone("Europe/London")
        )

        assertEquals("America/Los_Angeles", zone.id)
    }

    @Test
    fun `an unlocated recording falls back to the reader's zone`() {
        // What every screen did before venues existed. A null here would have each call site
        // inventing its own fallback, and the ones picking UTC would be silently hours out.
        val reader = java.util.TimeZone.getTimeZone("Europe/London")

        assertEquals(
            reader.id,
            LocationResolver.zoneFor(null, null, festival(), registry(gorge), reader).id
        )
    }

    @Test
    fun `a venue with no zone falls back rather than claiming one`() {
        val roomOnly = listOf(event(1, "A night in", at = 4))
        val reader = java.util.TimeZone.getTimeZone("Europe/London")

        assertEquals(
            reader.id,
            LocationResolver.zoneFor(null, 1, roomOnly, registry(unnamedZone), reader).id
        )
    }

    @Test
    fun `an unrecognisable zone falls back rather than reading as GMT`() {
        // `TimeZone.getTimeZone` answers GMT for anything it does not know, so a typo or a row from
        // a build with a different id set would resolve silently to GMT — seven hours out on the
        // west coast, and indistinguishable from a real answer.
        val nonsense = place(5, "Olympus Mons", "Mars/Olympus_Mons")
        val onMars = listOf(event(1, "Odd", at = 5))
        val reader = java.util.TimeZone.getTimeZone("Europe/London")

        assertEquals(
            reader.id,
            LocationResolver.zoneFor(null, 1, onMars, registry(nonsense), reader).id
        )
    }

    // --- What gets frozen onto the recording ---

    @Test
    fun `an unlocated recording freezes nothing rather than the device zone`() {
        // Storing the fallback would record wherever the phone happened to be at reconcile time as
        // though somebody had chosen it, and no later reader could tell it from a real answer.
        assertNull(LocationResolver.resolvedZoneId(null, null, festival(), registry(gorge)))
    }

    @Test
    fun `a located recording freezes its venue's zone`() {
        assertEquals(
            "America/Los_Angeles",
            LocationResolver.resolvedZoneId(null, 3, festival(), registry(gorge))
        )
    }

    // --- Degenerate input ---

    @Test
    fun `a reference to a deleted venue resolves to nothing rather than throwing`() {
        // Deleting a venue orphans rather than cascades, so a stale id is a state that happens.
        assertNull(LocationResolver.forRecording(null, 3, festival(), emptyMap()))
    }

    @Test
    fun `a cycle does not hang`() {
        val cyclic = listOf(
            event(1, "A", parent = 3, at = 1),
            event(2, "B", parent = 1),
            event(3, "C", parent = 2)
        )

        assertEquals(
            "The Gorge",
            LocationResolver.forRecording(null, 2, cyclic, registry(gorge))!!.location.name
        )
    }

    @Test
    fun `an event pointing at a missing parent still resolves its own venue`() {
        val orphan = listOf(event(9, "Lost", parent = 99, at = 1))

        assertEquals(
            "The Gorge",
            LocationResolver.forRecording(null, 9, orphan, registry(gorge))!!.location.name
        )
    }

    // --- Where it came from ---

    @Test
    fun `a venue on the recording's own event reads as the event's`() {
        val here = festival() + event(4, "Afterparty", parent = 2, at = 2)

        assertEquals(
            PlaceSource.EVENT,
            LocationResolver.forRecording(null, 4, here, registry(gorge, showbox))!!.source
        )
    }

    @Test
    fun `a venue from further up reads as inherited`() {
        assertEquals(
            PlaceSource.ANCESTOR,
            LocationResolver.forRecording(null, 3, festival(), registry(gorge))!!.source
        )
    }

    @Test
    fun `an event resolves its own venue through the tree`() {
        assertEquals("The Gorge", LocationResolver.forEvent(3, festival(), registry(gorge))!!.location.name)
    }
}
