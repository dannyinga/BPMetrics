package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "might be the same watch" hint. The cases are from a real library: "The OG" recorded until
 * Aug 17 and "OG" from Sep 12, both SM-R905U; "Blue" and "G5 Bears" are two SM-R900s worn the same
 * weekend.
 */
class WatchDuplicatesTest {

    private val day = 86_400_000L
    private fun watch(id: String, model: String) = WatchEntity(watchId = id, lastKnownModel = model)

    private val theOg = watch("c2e6fb77", "SM-R905U")
    private val og = watch("228d8e0c", "SM-R905U")
    private val blue = watch("501af8fd", "SM-R900")
    private val bears = watch("2417d611", "SM-R900")

    private val spans = mapOf(
        theOg.watchId to (5 * day)..(17 * day),
        og.watchId to (43 * day)..(78 * day),
        blue.watchId to (5 * day)..(78 * day),
        bears.watchId to (14 * day)..(17 * day)
    )

    @Test
    fun `the same model that never recorded at the same time is suggested`() {
        val same = WatchDuplicates.likelySame(listOf(theOg, og, blue, bears), spans)
        assertEquals(listOf(og), same[theOg.watchId])
        assertEquals(listOf(theOg), same[og.watchId])
    }

    @Test
    fun `two watches recording at the same time are not`() {
        val same = WatchDuplicates.likelySame(listOf(theOg, og, blue, bears), spans)
        assertTrue(blue.watchId !in same && bears.watchId !in same)
    }

    @Test
    fun `a different model is not`() {
        val pixel = watch("34c934f5", "Google Pixel Watch 2")
        val same = WatchDuplicates.likelySame(
            listOf(theOg, pixel),
            mapOf(theOg.watchId to 0L..1L, pixel.watchId to 5L..6L)
        )
        assertTrue(same.isEmpty())
    }

    @Test
    fun `a pair marked as different stops being suggested`() {
        val same = WatchDuplicates.likelySame(
            listOf(theOg, og), spans,
            distinct = setOf(WatchDuplicates.pairKey(og.watchId, theOg.watchId))
        )
        assertTrue(same.isEmpty())
    }

    @Test
    fun `a watch with no recordings has nothing to compare`() {
        val same = WatchDuplicates.likelySame(listOf(theOg, og), mapOf(theOg.watchId to 0L..1L))
        assertTrue(same.isEmpty())
    }
}
