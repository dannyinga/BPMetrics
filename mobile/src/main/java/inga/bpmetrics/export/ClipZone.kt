package inga.bpmetrics.export

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.time.ZoneOffset

/**
 * The clock a clip was filmed on, read from the clip itself.
 *
 * A video's creation time is a moment in UTC, which is enough to line it up with the recordings
 * but not to print a time on the frame: "05:23 UTC" could be 12:23 AM or 10:23 PM depending on
 * where it was. Some cameras say where. Samsung writes the offset as its own tag
 * (`com.samsung.android.utc_offset = -0500`), and an iPhone writes its creation date with the
 * offset on the end (`com.apple.quicktime.creationdate = 2026-10-17T00:23:35-0500`). Android's
 * own metadata reader exposes neither, so this reads the MP4's tags directly: the `keys` and
 * `ilst` boxes of the movie's `meta` box, and the plain `date` and `©day` boxes of its `udta`,
 * where a shared iPhone copy keeps the date. It seeks past the video data rather than reading it.
 *
 * The same dates say when filming began ([capturedAt]), which [VideoTiming] trusts above anything
 * it can work out from the stamp.
 *
 * An export's clock is the venue's zone, then this, then the phone's
 * ([inga.bpmetrics.library.exportZoneOf]).
 */
object ClipZone {

    private const val TAG = "ClipZone"
    const val SAMSUNG_OFFSET = "com.samsung.android.utc_offset"
    const val APPLE_CREATION_DATE = "com.apple.quicktime.creationdate"

    /**
     * The plain `date` box in the movie's `udta`, where an iPhone's shared or re-encoded copy
     * keeps when filming began ("2026-05-23T22:47:59-0700"). The copy's own creation time, which
     * Android reads as `DATE_TAKEN`, is when the copy was made.
     */
    const val UDTA_DATE = "udta.date"

    /** QuickTime's `©day` in `udta`: the same, as some exporters write it. */
    const val UDTA_DAY = "udta.©day"

    /** Where a camera says when filming began, best first. */
    private val CAPTURE_DATES = listOf(APPLE_CREATION_DATE, UDTA_DATE, UDTA_DAY)

    /** Where the bytes come from: a file in the app, an array in tests. */
    interface Source {
        val size: Long
        /** Reads up to [length] bytes at [position]; fewer only at the end. */
        fun read(position: Long, length: Int): ByteArray
    }

    class ArraySource(private val bytes: ByteArray) : Source {
        override val size: Long get() = bytes.size.toLong()
        override fun read(position: Long, length: Int): ByteArray {
            val from = position.coerceIn(0, size).toInt()
            return bytes.copyOfRange(from, (from + length).coerceAtMost(bytes.size))
        }
    }

    /** The clip's offset as a zone id ("-05:00"), or null when the file doesn't say. */
    fun of(context: Context, uri: Uri): String? = tagsOf(context, uri)?.let { offsetFrom(it)?.id }

    /**
     * When the camera says filming began, or null when it doesn't say. Only a date that states its
     * own zone counts: one without could be any of two dozen instants.
     */
    fun capturedAt(context: Context, uri: Uri): Long? = tagsOf(context, uri)?.let { capturedAt(it) }

    /**
     * [capturedAt], remembered for this run of the app. Finding clips opens every file that came
     * from somewhere else, and doing that again on every trip between steps is wasted work.
     *
     * @param version anything that changes when the file does (its `DATE_MODIFIED`), so an edited
     *   file is read again.
     */
    fun capturedAt(context: Context, uri: Uri, version: Long): Long? {
        val key = "$uri@$version"
        synchronized(captures) { captures[key] }?.let { return it.takeIf { it != NOT_STATED } }
        val read = capturedAt(context, uri)
        synchronized(captures) { captures[key] = read ?: NOT_STATED }
        return read
    }

    private const val NOT_STATED = Long.MIN_VALUE

    /** The last couple of thousand files read, least recently used dropped first. */
    private val captures = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 2_000
    }

    /** The file's tags, or null when it can't be read. */
    fun tagsOf(context: Context, uri: Uri): Map<String, String>? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                val source = object : Source {
                    override val size: Long get() = channel.size()
                    override fun read(position: Long, length: Int): ByteArray {
                        val buffer = ByteBuffer.allocate(length)
                        var at = position
                        while (buffer.hasRemaining()) {
                            val read = channel.read(buffer, at)
                            if (read <= 0) break
                            at += read
                        }
                        return buffer.array().copyOf(buffer.position())
                    }
                }
                readTags(source)
            }
        }
    }.onFailure { Log.w(TAG, "Couldn't read the clip's tags", it) }.getOrNull()

    /** The offset the tags state, or null. Only real offsets: whole quarter hours within ±14h. */
    fun offsetFrom(tags: Map<String, String>): ZoneOffset? {
        val stated = tags[SAMSUNG_OFFSET]?.trim()
            ?: CAPTURE_DATES.firstNotNullOfOrNull { key ->
                tags[key]?.let { Regex("""([+-]\d{2}:?\d{2})$""").find(it.trim())?.value }
            }
            ?: return null
        val match = Regex("""^([+-])(\d{2}):?(\d{2})$""").find(stated) ?: return null
        val sign = if (match.groupValues[1] == "-") -1 else 1
        val hours = match.groupValues[2].toInt()
        val minutes = match.groupValues[3].toInt()
        if (hours > 14 || minutes % 15 != 0) return null
        return runCatching { ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes) }.getOrNull()
    }

    /**
     * When the [tags] say filming began, as an instant, or null when they don't say with a zone.
     *
     * "2026-05-23T22:47:59-0700", with or without the colon in the offset, fractions of a second, or
     * a Z. A date with no zone at all is refused rather than guessed at.
     */
    fun capturedAt(tags: Map<String, String>): Long? = CAPTURE_DATES.firstNotNullOfOrNull { key ->
        tags[key]?.trim()?.let { text ->
            // ISO wants the offset's colon; QuickTime usually leaves it out.
            val iso = Regex("""([+-]\d{2})(\d{2})$""").replace(text) { "${it.groupValues[1]}:${it.groupValues[2]}" }
            runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        }
    }

    /**
     * The movie's tags by name: the `mdta` tags of its `meta` boxes, and the plain [UDTA_DATE] and
     * [UDTA_DAY] boxes of its `udta`. Empty when it has none or isn't an MP4.
     */
    fun readTags(source: Source): Map<String, String> {
        val moov = children(source, 0, source.size).firstOrNull { it.type == "moov" } ?: return emptyMap()
        val moovChildren = children(source, moov.bodyStart, moov.end)
        val udtaChildren = moovChildren.filter { it.type == "udta" }.flatMap { udta ->
            children(source, udta.bodyStart, udta.end)
        }
        // A `meta` straight under the movie (QuickTime and Android's muxer), or inside `udta`.
        val metas = moovChildren.filter { it.type == "meta" } + udtaChildren.filter { it.type == "meta" }
        val dates = buildMap {
            udtaChildren.firstOrNull { it.type == "date" }?.let { box ->
                put(UDTA_DATE, String(source.read(box.bodyStart, boxLength(box)), Charsets.UTF_8))
            }
            udtaChildren.firstOrNull { it.type == "©day" }?.let { box ->
                // QuickTime text: a two-byte length and a two-byte language before the text.
                val body = source.read(box.bodyStart, boxLength(box))
                if (body.size > 4) {
                    val length = ((body[0].toInt() and 0xFF) shl 8 or (body[1].toInt() and 0xFF)).coerceAtMost(body.size - 4)
                    put(UDTA_DAY, String(body, 4, length, Charsets.UTF_8))
                }
            }
        }
        return metas.fold(dates) { tags, meta -> tags + tagsIn(source, meta) }
    }

    private fun boxLength(box: Box): Int = (box.end - box.bodyStart).toInt().coerceIn(0, 256)

    private class Box(val type: String, val start: Long, val bodyStart: Long, val end: Long)

    /** The boxes laid end to end between [from] and [to]. Stops at anything malformed. */
    private fun children(source: Source, from: Long, to: Long): List<Box> {
        val boxes = mutableListOf<Box>()
        var at = from
        while (at + 8 <= to && boxes.size < 4_096) {
            val header = source.read(at, 16)
            if (header.size < 8) break
            var size = u32(header, 0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8L
            when (size) {
                1L -> {
                    if (header.size < 16) break
                    size = u64(header, 8)
                    headerSize = 16L
                }
                0L -> size = to - at
            }
            if (size < headerSize || at + size > to) break
            boxes += Box(type, at, at + headerSize, at + size)
            at += size
        }
        return boxes
    }

    /** The key names in `keys`, matched to the values in `ilst` by their 1-based index. */
    private fun tagsIn(source: Source, meta: Box): Map<String, String> {
        // ISO's `meta` is a full box with 4 bytes of version and flags before its children;
        // QuickTime's isn't. Tell them apart by where a child's type lands.
        val peek = source.read(meta.bodyStart, 12)
        val bodyStart = if (peek.size >= 12 && String(peek, 4, 4, Charsets.ISO_8859_1).isBoxName()) {
            meta.bodyStart
        } else {
            meta.bodyStart + 4
        }
        val inside = children(source, bodyStart, meta.end)
        val keysBox = inside.firstOrNull { it.type == "keys" } ?: return emptyMap()
        val ilst = inside.firstOrNull { it.type == "ilst" } ?: return emptyMap()

        val keysBody = source.read(keysBox.bodyStart, (keysBox.end - keysBox.bodyStart).toInt().coerceAtMost(65_536))
        if (keysBody.size < 8) return emptyMap()
        val names = mutableListOf<String>()
        var at = 8 // version and flags, then the count
        val count = u32(keysBody, 4).coerceAtMost(1_024)
        repeat(count.toInt()) {
            if (at + 8 > keysBody.size) return@repeat
            val size = u32(keysBody, at).toInt()
            if (size < 8 || at + size > keysBody.size) return@repeat
            names += String(keysBody, at + 8, size - 8, Charsets.UTF_8)
            at += size
        }

        val tags = mutableMapOf<String, String>()
        for (item in children(source, ilst.bodyStart, ilst.end)) {
            val index = item.type.toByteArray(Charsets.ISO_8859_1).let { u32(it, 0) }.toInt()
            val name = names.getOrNull(index - 1) ?: continue
            val data = children(source, item.bodyStart, item.end).firstOrNull { it.type == "data" } ?: continue
            val body = source.read(data.bodyStart, (data.end - data.bodyStart).toInt().coerceAtMost(4_096))
            // Type indicator (4) and locale (4), then the value. Type 1 is UTF-8 text.
            if (body.size < 8 || u32(body, 0) != 1L) continue
            tags[name] = String(body, 8, body.size - 8, Charsets.UTF_8)
        }
        return tags
    }

    private fun String.isBoxName() = length == 4 && all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '©' }

    private fun u32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)

    private fun u64(bytes: ByteArray, at: Int): Long = (u32(bytes, at) shl 32) or u32(bytes, at + 4)
}
