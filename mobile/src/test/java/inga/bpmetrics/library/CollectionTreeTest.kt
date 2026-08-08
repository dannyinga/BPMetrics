package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules for nesting collections.
 *
 * Worth testing hard because the worst failure here does not throw. A cycle makes every walk of the
 * tree run forever, on a background thread, with nothing in the log — the app just stops
 * responding. Every function here is written to terminate on malformed data, and these are what
 * say so.
 */
class CollectionTreeTest {

    private fun collection(id: Long, name: String, parent: Long? = null) =
        EventGroupEntity(groupId = id, name = name, parentGroupId = parent)

    /** Coachella → Day 1 → (nothing yet); Day 2 alongside; an unrelated tour. */
    private val festival = listOf(
        collection(1, "Coachella"),
        collection(2, "Day 1", parent = 1),
        collection(3, "Day 2", parent = 1),
        collection(4, "Bass Canyon")
    )

    @Test
    fun `a collection contains itself and everything under it`() {
        assertEquals(setOf(1L, 2L, 3L), CollectionTree.descendantsOf(festival, 1))
    }

    @Test
    fun `a leaf contains only itself`() {
        assertEquals(setOf(2L), CollectionTree.descendantsOf(festival, 2))
    }

    @Test
    fun `an unrelated collection is not swept in`() {
        assertFalse(4L in CollectionTree.descendantsOf(festival, 1))
    }

    @Test
    fun `depth counts from the top`() {
        assertEquals(1, CollectionTree.depthOf(festival, 1))
        assertEquals(2, CollectionTree.depthOf(festival, 2))
    }

    @Test
    fun `a collection cannot be filed inside itself`() {
        assertFalse(CollectionTree.canReparent(festival, groupId = 1, parentGroupId = 1))
    }

    @Test
    fun `a collection cannot be filed inside its own child`() {
        // The move that would make Coachella and Day 1 enclose each other. Every walk from either
        // would then run forever, so this is the one that must never get through.
        assertFalse(CollectionTree.canReparent(festival, groupId = 1, parentGroupId = 2))
    }

    @Test
    fun `a collection can be filed inside an unrelated one`() {
        assertTrue(CollectionTree.canReparent(festival, groupId = 4, parentGroupId = 1))
    }

    @Test
    fun `anything can be lifted back to the top`() {
        assertTrue(CollectionTree.canReparent(festival, groupId = 2, parentGroupId = null))
    }

    @Test
    fun `nesting stops at the depth cap`() {
        val deep = listOf(
            collection(1, "A"),
            collection(2, "B", parent = 1),
            collection(3, "C", parent = 2),
            collection(4, "D", parent = 3),
            collection(5, "Loose")
        )

        // D already sits at the cap, so nothing may go under it.
        assertEquals(CollectionTree.MAX_DEPTH, CollectionTree.depthOf(deep, 4))
        assertFalse(CollectionTree.canReparent(deep, groupId = 5, parentGroupId = 4))
        assertTrue(CollectionTree.canReparent(deep, groupId = 5, parentGroupId = 3))
    }

    @Test
    fun `moving a tall subtree accounts for its own height`() {
        // Filing a two-level subtree under something at level 3 would push its leaf to level 4.
        // Checking only the parent's depth would let a subtree exceed the cap in one move.
        val tree = listOf(
            collection(1, "A"),
            collection(2, "B", parent = 1),
            collection(3, "C", parent = 2),
            collection(10, "X"),
            collection(11, "Y", parent = 10)
        )

        assertEquals(2, CollectionTree.heightOf(tree, 10))
        assertTrue(CollectionTree.canReparent(tree, groupId = 10, parentGroupId = 2))
        assertFalse(CollectionTree.canReparent(tree, groupId = 10, parentGroupId = 3))
    }

    @Test
    fun `a cycle in stored data terminates rather than hanging`() {
        // Not reachable through the UI, but reachable through a restored backup or a hand-edited
        // database — and "should not happen" is not a reason to hang.
        val cyclic = listOf(
            collection(1, "A", parent = 2),
            collection(2, "B", parent = 1)
        )

        assertEquals(setOf(1L, 2L), CollectionTree.descendantsOf(cyclic, 1))
        assertTrue(CollectionTree.depthOf(cyclic, 1) <= CollectionTree.MAX_DEPTH + 1)
        assertTrue(CollectionTree.heightOf(cyclic, 1) <= CollectionTree.MAX_DEPTH + 1)
        assertEquals(2, CollectionTree.flatten(cyclic).size)
    }

    @Test
    fun `flatten reads as the tree, deepest last within each branch`() {
        val flat = CollectionTree.flatten(festival)

        assertEquals(
            listOf("Coachella" to 1, "Day 1" to 2, "Day 2" to 2, "Bass Canyon" to 1),
            flat.map { it.group.name to it.depth }
        )
    }

    @Test
    fun `a collection whose parent no longer exists is shown at the top, not lost`() {
        // A dangling parent id would otherwise make it vanish from every list, which is
        // unrecoverable through the UI — a worse failure than showing it in the wrong place.
        val orphaned = listOf(collection(7, "Stranded", parent = 999))

        val flat = CollectionTree.flatten(orphaned)

        assertEquals(1, flat.size)
        assertEquals(1, flat.single().depth)
    }

    @Test
    fun `ancestry reads from the top down`() {
        assertEquals(
            listOf("Coachella", "Day 1"),
            CollectionTree.ancestryOf(festival, 2).map { it.name }
        )
    }

    @Test
    fun `filing under something that does not exist is refused`() {
        assertFalse(CollectionTree.canReparent(festival, groupId = 2, parentGroupId = 999))
    }
}
