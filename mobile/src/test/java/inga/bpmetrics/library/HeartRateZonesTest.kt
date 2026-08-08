package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which figures a person's zones are measured against.
 *
 * The rule is one sentence, and the reason it is a function is that writing it out at each call
 * site would eventually leave one of them dividing by a null — or by zero, which is worse, because
 * it produces a number rather than a crash.
 */
class HeartRateZonesTest {

    private fun person(resting: Int? = null, max: Int? = null) = PersonEntity(
        personId = 1,
        name = "Kyle",
        colorArgb = 0,
        restingBpm = resting,
        maxBpm = max
    )

    @Test
    fun `someone with their own figures uses them`() {
        val range = HeartRateZones.forPerson(person(resting = 48, max = 201), 60, 190)

        assertEquals(48, range.restingBpm)
        assertEquals(201, range.maxBpm)
    }

    @Test
    fun `someone with none falls back to the app-wide figures`() {
        val range = HeartRateZones.forPerson(person(), 60, 190)

        assertEquals(60, range.restingBpm)
        assertEquals(190, range.maxBpm)
    }

    @Test
    fun `one figure of their own does not drag the other with it`() {
        // A runner may know their resting rate and never have measured a maximum. Taking both or
        // neither would force them to invent one.
        val range = HeartRateZones.forPerson(person(resting = 44), 60, 190)

        assertEquals(44, range.restingBpm)
        assertEquals(190, range.maxBpm)
    }

    @Test
    fun `nobody attributed still gets a usable range`() {
        val range = HeartRateZones.forPerson(null, 60, 190)

        assertEquals(60, range.restingBpm)
        assertEquals(190, range.maxBpm)
    }

    @Test
    fun `figures the wrong way round do not invert every percentage`() {
        // Reachable from a restored backup or a hand-edited row. A negative span turns every zone
        // reading inside out, and does it quietly.
        val range = HeartRateZones.forPerson(person(resting = 200, max = 150), 60, 190)

        assertTrue("resting must sit below maximum", range.restingBpm < range.maxBpm)
        assertTrue("span must be positive", range.span > 0)
    }

    @Test
    fun `a reading is placed between resting and maximum`() {
        val range = HeartRateZones.forPerson(person(resting = 60, max = 180), 60, 190)

        assertEquals(0f, range.fractionOf(60.0), 0.001f)
        assertEquals(0.5f, range.fractionOf(120.0), 0.001f)
        assertEquals(1f, range.fractionOf(180.0), 0.001f)
    }

    @Test
    fun `readings outside the range are clamped rather than reported as impossible`() {
        val range = HeartRateZones.forPerson(person(resting = 60, max = 180), 60, 190)

        assertEquals(0f, range.fractionOf(40.0), 0.001f)
        assertEquals(1f, range.fractionOf(220.0), 0.001f)
    }

    @Test
    fun `a person marks whether they have figures of their own`() {
        assertTrue(person(resting = 48).hasOwnZones)
        assertTrue(person(max = 201).hasOwnZones)
        assertTrue(!person().hasOwnZones)
    }
}
