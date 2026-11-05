package inga.bpmetrics.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A number is applied when typing is done, and a change to it is said, not silent (Audit #8). */
class NumberEntryTest {

    private val pixels = 16..4096

    @Test
    fun `a number in range is taken as typed`() {
        val result = NumberEntry.commit("1280", current = 1920, range = pixels)
        assertEquals(1280, result.value)
        assertNull(result.note)
    }

    @Test
    fun `too small or too large is clamped, and says so`() {
        assertEquals(NumberEntry.Result(16, "Set to 16, the least it can be."), NumberEntry.commit("3", 1920, pixels))
        assertEquals(NumberEntry.Result(4096, "Set to 4096, the most it can be."), NumberEntry.commit("9999", 1920, pixels))
    }

    @Test
    fun `an emptied field keeps what it had`() {
        assertEquals(NumberEntry.Result(1920, "Kept 1920. It needs a number."), NumberEntry.commit("", 1920, pixels))
    }
}
