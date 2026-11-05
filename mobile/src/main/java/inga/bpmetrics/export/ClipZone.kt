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
 * `ilst` boxes of the movie's `meta` box. It seeks past the video data rather than reading it.
 *
 * An export's clock is the venue's zone, then this, then the phone's
 * ([inga.bpmetrics.library.exportZoneOf]).
 */
object ClipZone {

    private const val TAG = "ClipZone"
    const val SAMSUNG_OFFSET = "com.samsung.android.utc_offset"
    const val APPLE_CREATION_DATE = "com.apple.quicktime.creationdate"

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
    fun of(context: Context, uri: Uri): String? = runCatching {
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
                offsetFrom(readTags(source))?.id
            }
        }
    }.onFailure { Log.w(TAG, "Couldn't read the clip's tags", it) }.getOrNull()

    /** The offset the tags state, or null. Only real offsets: whole quarter hours within ±14h. */
    fun offsetFrom(tags: Map<String, String>): ZoneOffset? {
        val stated = tags[SAMSUNG_OFFSET]?.trim()
            ?: tags[APPLE_CREATION_DATE]?.let { Regex("""([+-]\d{2}:?\d{2})$""").find(it.trim())?.value }
            ?: return null
        val match = Regex("""^([+-])(\d{2}):?(\d{2})$""").find(stated) ?: return null
        val sign = if (match.groupValues[1] == "-") -1 else 1
        val hours = match.groupValues[2].toInt()
        val minutes = match.groupValues[3].toInt()
        if (hours > 14 || minutes % 15 != 0) return null
        return runCatching { ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes) }.getOrNull()
    }

    /** The movie's `mdta` tags, by name. Empty when it has none or isn't an MP4. */
    fun readTags(source: Source): Map<String, String> {
        val moov = children(source, 0, source.size).firstOrNull { it.type == "moov" } ?: return emptyMap()
        val moovChildren = children(source, moov.bodyStart, moov.end)
        // A `meta` straight under the movie (QuickTime and Android's muxer), or inside `udta`.
        val metas = moovChildren.filter { it.type == "meta" } +
            moovChildren.filter { it.type == "udta" }.flatMap { udta ->
                children(source, udta.bodyStart, udta.end).filter { it.type == "meta" }
            }
        return metas.fold(emptyMap()) { tags, meta -> tags + tagsIn(source, meta) }
    }

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
