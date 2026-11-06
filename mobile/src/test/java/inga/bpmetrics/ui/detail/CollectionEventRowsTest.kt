package inga.bpmetrics.ui.detail

import inga.bpmetrics.library.EventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a collection's Events tab lists (S15.6). */
class CollectionEventRowsTest {

    private val festival = EventEntity(eventId = 1, name = "Festival", createdAt = 100)
    private val day = EventEntity(eventId = 2, name = "Day 1", parentId = 1, createdAt = 110)
    private val set = EventEntity(eventId = 3, name = "Set", parentId = 2, createdAt = 120)
    private val concert = EventEntity(eventId = 4, name = "Concert", createdAt = 300)
    private val older = EventEntity(eventId = 5, name = "Older", createdAt = 50)
    private val tree = listOf(festival, day, set, concert, older)

    @Test
    fun `the events it holds, newest first, each once`() {
        val rows = CollectionEventRows.rows(tree, emptyMap(), listOf(5, 4, 1, 4), expanded = emptySet())
        assertEquals(listOf(4L, 1L, 5L), rows.map { it.second.event.eventId })
        assertTrue(rows.all { it.second.depth == 0 })
    }

    @Test
    fun `an event with something inside it can be opened, and opens all the way down`() {
        val closed = CollectionEventRows.rows(tree, emptyMap(), listOf(1), expanded = emptySet())
        assertTrue(closed.single().second.hasChildren)

        val open = CollectionEventRows.rows(tree, emptyMap(), listOf(1), expanded = setOf(1, 2))
        assertEquals(listOf(1L to 0, 2L to 1, 3L to 2), open.map { it.second.event.eventId to it.second.depth })
        assertFalse(open.last().second.hasChildren)
    }

    @Test
    fun `an event listed and also inside another listed one is two rows with two keys`() {
        val rows = CollectionEventRows.rows(tree, emptyMap(), listOf(1, 2), expanded = setOf(1))
        val keys = rows.map { it.first }
        assertEquals(keys.toSet().size, keys.size)
        assertEquals(2, rows.count { it.second.event.eventId == 2L })
    }

    @Test
    fun `when it happened beats when it was made`() {
        // Made later, happened earlier.
        val rows = CollectionEventRows.rows(tree, mapOf(4L to 10L, 5L to 20L), listOf(4, 5), emptySet())
        assertEquals(listOf(5L, 4L), rows.map { it.second.event.eventId })
    }
}
