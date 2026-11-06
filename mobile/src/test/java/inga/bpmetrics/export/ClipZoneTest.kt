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

    // --- When the camera says filming began (an iPhone's shared copy) ---

    /** A shared iPhone copy as it really is: no `meta`, the date and place as plain `udta` boxes. */
    private fun sharedIphoneCopy(udtaBoxes: ByteArray): ByteArray =
        box("ftyp", "mp42".toByteArray(), u32(0)) + box("mdat", ByteArray(5_000)) +
            box("moov", box("mvhd", ByteArray(100)), box("trak", ByteArray(300)), box("udta", udtaBoxes))

    @Test
    fun `a shared iPhone copy says when filming began, in its udta date box`() {
        // VID_20260524_094251.mp4: shared the next morning, filmed during Subtronics on day one.
        val clip = sharedIphoneCopy(
            box("loci", ByteArray(19)) + box("date", "2026-05-23T22:47:59-0700".toByteArray())
        )
        val tags = read(clip)
        assertEquals("2026-05-23T22:47:59-0700", tags[ClipZone.UDTA_DATE])
        assertEquals(java.time.Instant.parse("2026-05-24T05:47:59Z").toEpochMilli(), ClipZone.capturedAt(tags))
        assertEquals(ZoneOffset.ofHours(-7), ClipZone.offsetFrom(tags))
    }

    @Test
    fun `QuickTime's copyright-c day box is read too`() {
        val text = "2026-05-23T22:47:59-07:00".toByteArray()
        val day = byteArrayOf(0, text.size.toByte(), 0x15, 0xC7.toByte()) + text
        val tags = read(sharedIphoneCopy(box("©day", day)))
        assertEquals(java.time.Instant.parse("2026-05-24T05:47:59Z").toEpochMilli(), ClipZone.capturedAt(tags))
    }

    @Test
    fun `an original iPhone clip's creation date is a capture date`() {
        val tags = mapOf(ClipZone.APPLE_CREATION_DATE to "2026-10-17T00:23:35-0500")
        assertEquals(java.time.Instant.parse("2026-10-17T05:23:35Z").toEpochMilli(), ClipZone.capturedAt(tags))
    }

    @Test
    fun `a date without a zone is not a capture date`() {
        assertNull(ClipZone.capturedAt(mapOf(ClipZone.UDTA_DATE to "2026-05-23T22:47:59")))
        assertNull(ClipZone.capturedAt(mapOf(ClipZone.UDTA_DATE to "yesterday")))
        assertNull(ClipZone.capturedAt(read(androidMp4(mapOf(ClipZone.SAMSUNG_OFFSET to "-0500")))))
    }
}
