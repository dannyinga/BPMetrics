package inga.bpmetrics.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto-naming may replace a title only while the app wrote it. These are titles from a real
 * library: the hand-written ones that end in a number are the case a looser rule got wrong.
 */
class AutoTitleTest {

    private val characters = listOf("Spiderman", "Hulk")

    @Test
    fun `blank and Untitled titles are generated`() {
        assertTrue(AutoTitle.isGenerated(null, characters))
        assertTrue(AutoTitle.isGenerated("  ", characters))
        assertTrue(AutoTitle.isGenerated("Untitled 3", characters))
        assertTrue(AutoTitle.isGenerated("untitled 12", emptyList()))
    }

    @Test
    fun `a title made from a naming tag is generated`() {
        assertTrue(AutoTitle.isGenerated("Spiderman 5", characters))
        assertTrue(AutoTitle.isGenerated("hulk 1", characters))
    }

    @Test
    fun `hand-written titles are left alone, even ones ending in a number`() {
        assertFalse(AutoTitle.isGenerated("Mom's birthday", characters))
        assertFalse(AutoTitle.isGenerated("Basket Case 1", characters))
        assertFalse(AutoTitle.isGenerated("KC to PHX 2", characters))
        // A naming tag's name, but not in the generated shape.
        assertFalse(AutoTitle.isGenerated("Spiderman", characters))
        assertFalse(AutoTitle.isGenerated("Spiderman at the Gorge 2", characters))
    }
}
