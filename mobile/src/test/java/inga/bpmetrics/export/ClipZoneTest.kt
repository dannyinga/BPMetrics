package inga.bpmetrics.export

import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.exportZoneOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.ZoneOffset

/** Reading the clock a clip was filmed on out of the MP4, and where it ranks. */
class ClipZoneTest {

    // --- Building MP4s small enough to write by hand ---

    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val content = body.fold(ByteArray(0)) { all, part -> all + part }
        return u32(8 + content.size) + type.toByteArray(Charsets.ISO_8859_1) + content
    }

    private fun u32(value: Int) = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()
    )

    private fun keys(vararg names: String): ByteArray {
        val entries = names.map { name ->
            val bytes = name.toByteArray(Charsets.UTF_8)
            u32(8 + bytes.size) + "mdta".toByteArray() + bytes
        }
        return box("keys", u32(0), u32(names.size), *entries.toTypedArray())
    }

    private fun ilst(vararg values: String): ByteArray {
        val items = values.mapIndexed { i, value ->
            val data = box("data", u32(1), u32(0), value.toByteArray(Charsets.UTF_8))
            u32(8 + data.size) + u32(i + 1) + data
        }
        return box("ilst", *items.toTypedArray())
    }

    private val hdlr = box("hdlr", ByteArray(8), "mdta".toByteArray(), ByteArray(13))

    /** How Samsung and Android's muxer lay it out: `meta` straight under `moov`, footage first. */
    private fun androidMp4(tags: Map<String, String>): ByteArray {
        val meta = box("meta", hdlr, keys(*tags.keys.toTypedArray()), ilst(*tags.values.toTypedArray()))
        val out = ByteArrayOutputStream()
        out.write(box("ftyp", "mp42".toByteArray(), u32(0)))
        out.write(box("mdat", ByteArray(50_000))) // the footage, which is never read
        out.write(box("moov", box("mvhd", ByteArray(100)), box("trak", ByteArray(300)), meta))
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray) = ClipZone.readTags(ClipZone.ArraySource(bytes))

    // --- The file ---

    @Test
    fun `a Samsung clip's offset is read from its tags`() {
        val clip = androidMp4(
            mapOf(
                "com.android.version" to "16",
                "com.android.capture.fps" to "60.000000",
                ClipZone.SAMSUNG_OFFSET to "-0500"
            )
        )
        val tags = read(clip)
        assertEquals("-0500", tags[ClipZone.SAMSUNG_OFFSET])
        assertEquals(ZoneOffset.ofHours(-5), ClipZone.offsetFrom(tags))
    }

    @Test
    fun `an ISO meta box inside udta is read too`() {
        // A full box: four bytes of version and flags before its children.
        val meta = box("meta", ByteArray(4), hdlr, keys(ClipZone.APPLE_CREATION_DATE), ilst("2026-10-17T00:23:35-0500"))
        val clip = box("ftyp", "qt  ".toByteArray()) + box("moov", box("mvhd", ByteArray(100)), box("udta", meta)) +
            box("mdat", ByteArray(1_000))
        assertEquals(ZoneOffset.ofHours(-5), ClipZone.offsetFrom(read(clip)))
    }

    @Test
    fun `a clip that doesn't say has no offset`() {
        assertTrue(read(androidMp4(mapOf("com.android.version" to "16"))).keys == setOf("com.android.version"))
        assertNull(ClipZone.offsetFrom(read(androidMp4(mapOf("com.android.version" to "16")))))
        assertTrue(read(ByteArray(64)).isEmpty())
        assertTrue(read("not a video at all".toByteArray()).isEmpty())
    }

    @Test
    fun `only real offsets count`() {
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), ClipZone.offsetFrom(mapOf(ClipZone.SAMSUNG_OFFSET to "+05:30")))
        assertNull(ClipZone.offsetFrom(mapOf(ClipZone.SAMSUNG_OFFSET to "+0517")))
        assertNull(ClipZone.offsetFrom(mapOf(ClipZone.SAMSUNG_OFFSET to "-2000")))
        assertNull(ClipZone.offsetFrom(mapOf(ClipZone.APPLE_CREATION_DATE to "2026-10-17T00:23:35")))
    }

    // --- Where it ranks ---

    private fun recording(zone: String? = null) = BpmRecordEntity(
        recordId = 1, title = "Boogie T", date = 0, startTime = 0, endTime = 100, durationMs = 100,
        timeZoneId = zone
    )
    private val pacificPhone = ZoneId.of("America/Los_Angeles")

    @Test
    fun `the venue first, then the clip, then the phone`() {
        // Filed under a venue: the venue decides.
        assertEquals("America/Denver", exportZoneOf(listOf(recording("America/Denver")), "-05:00", pacificPhone))
        // Unfiled, exported from a phone in Pacific time: the clip's own clock, not the phone's.
        assertEquals("-05:00", exportZoneOf(listOf(recording()), "-05:00", pacificPhone))
        // Nothing says: the phone.
        assertEquals("America/Los_Angeles", exportZoneOf(listOf(recording()), null, pacificPhone))
    }
}
