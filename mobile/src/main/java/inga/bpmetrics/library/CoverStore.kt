package inga.bpmetrics.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Where cover images live, and how they get there.
 *
 * A **copy**, never a reference to the gallery. A `MediaStore` URI can have its permission revoked
 * and the photo behind it can be deleted, either of which would leave holes in the library that
 * nobody could do anything about — and the failure would arrive weeks later, when the person who
 * tidied their camera roll has no idea what they broke. Copying costs a few kilobytes each and
 * makes a cover something the app owns.
 *
 * Downscaled on the way in. A modern phone photo is several megabytes and forty times the pixels a
 * library tile can show; storing that would make the app's own storage the largest thing in it, for
 * a picture nobody will ever see at full size.
 */
object CoverStore {

    private const val TAG = "CoverStore"

    /** The longest edge a stored cover keeps. Comfortably above any tile, well below a photo. */
    const val MAX_EDGE_PX = 512

    /** JPEG rather than PNG: these are photographs, and PNG would be several times the size. */
    private const val QUALITY = 88

    /**
     * Which pile of pictures a name belongs to.
     *
     * People's photographs go through exactly the same import, downscale and delete machinery as
     * covers — the only difference is where they live and how they are shown. Two copies of this
     * file differing in one string is how one of them ends up without the two-pass decode.
     */
    enum class Kind(val directory: String) {
        COVER("covers"),
        PERSON("people")
    }

    private fun directory(context: Context, kind: Kind): File =
        File(context.filesDir, kind.directory).apply { if (!exists()) mkdirs() }

    /**
     * The file a stored name refers to.
     *
     * The kind is inferred from the name rather than passed in, so a caller holding only a string
     * off an entity can still find the file. Names carry their own prefix for exactly this reason.
     */
    fun fileFor(context: Context, name: String): File =
        File(directory(context, kindOf(name)), name)

    private fun kindOf(name: String): Kind =
        if (name.startsWith(PERSON_PREFIX)) Kind.PERSON else Kind.COVER

    /** Marks a stored name as a person's photograph rather than a cover. */
    private const val PERSON_PREFIX = "person-"

    /**
     * Whether a cover's file is actually still there.
     *
     * Worth asking before drawing: a restored backup carries the rows without the files, and a tile
     * that silently draws nothing is harder to explain than one that falls back to no cover at all.
     */
    fun exists(context: Context, name: String): Boolean = fileFor(context, name).isFile

    /**
     * Writes already-stored image bytes back into app storage, and returns the new stored name.
     *
     * The restore counterpart of [importFrom]. Bytes rather than a `Uri` because a backup carries
     * the picture inline — a stored *name* means nothing on the device the file is being restored
     * onto, which is how a restore came back with every crop intact and no images behind them.
     *
     * No downscale: these bytes left this app through [importFrom], so they are already within
     * [MAX_EDGE_PX]. Decoding and re-encoding them would only lose a generation of JPEG quality.
     *
     * A fresh name each time, rather than the one the backup came from, so restoring the same file
     * twice cannot have two libraries' entities pointing at one file — where deleting a cover in one
     * would blank it in the other.
     */
    fun writeBytes(
        context: Context,
        bytes: ByteArray,
        nameHint: String,
        id: Long,
        kind: Kind = Kind.COVER,
        stamp: Long = System.currentTimeMillis()
    ): String? {
        val name = storedName(nameHint, id, kind, stamp)
        return try {
            fileFor(context, name).writeBytes(bytes)
            name
        } catch (e: Exception) {
            Log.e(TAG, "Could not write a restored image", e)
            null
        }
    }

    /** The name a stored image gets. Shared so import and restore cannot drift apart. */
    private fun storedName(nameHint: String, id: Long, kind: Kind, stamp: Long): String {
        val safeHint = nameHint.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(24)
            .ifBlank { "image" }
        val prefix = if (kind == Kind.PERSON) PERSON_PREFIX else ""
        return "$prefix$safeHint-$id-$stamp.jpg"
    }

    /**
     * Copies [source] into app storage, downscaled, and returns the stored name.
     *
     * @param nameHint Something to make the file recognisable when someone goes looking; the id
     * appended keeps it unique without needing to check.
     * @return the file name to store on the entity, or null if the image could not be read.
     */
    fun importFrom(
        context: Context,
        source: Uri,
        nameHint: String,
        id: Long,
        kind: Kind = Kind.COVER
    ): String? {
        val bitmap = decodeDownscaled(context, source) ?: run {
            Log.w(TAG, "Could not read the chosen image")
            return null
        }

        val name = storedName(nameHint, id, kind, System.currentTimeMillis())

        return try {
            FileOutputStream(fileFor(context, name)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            }
            name
        } catch (e: Exception) {
            Log.e(TAG, "Could not write the cover", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Removes a cover's file.
     *
     * Called when its owner is deleted or its cover replaced. Silent about a file that is already
     * gone: that is the state being aimed for, and a restored backup reaches it having never had
     * the file at all.
     */
    fun delete(context: Context, name: String?) {
        val target = name?.takeIf { it.isNotBlank() } ?: return
        runCatching { fileFor(context, target).delete() }
    }

    /**
     * A stored image with its crop already applied, ready to draw.
     *
     * For the renderers, which cannot hold a crop rectangle around: they draw the same pill thirty
     * times a second and need a bitmap that is already the part that was chosen. Decoding and
     * cropping once here is the difference between a two-minute render and a ten-minute one.
     *
     * @return the cropped bitmap, or null if the file is missing or unreadable.
     */
    fun decodeCropped(context: Context, cover: Cover): Bitmap? {
        val file = fileFor(context, cover.path)
        if (!file.isFile) return null

        val full = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() ?: return null
        if (cover.cropWidth >= 0.999f && cover.cropHeight >= 0.999f) return full

        return try {
            val left = (cover.cropLeft * full.width).toInt().coerceIn(0, full.width - 1)
            val top = (cover.cropTop * full.height).toInt().coerceIn(0, full.height - 1)
            val width = (cover.cropWidth * full.width).toInt().coerceIn(1, full.width - left)
            val height = (cover.cropHeight * full.height).toInt().coerceIn(1, full.height - top)
            Bitmap.createBitmap(full, left, top, width, height).also {
                if (it !== full) full.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not crop a stored image", e)
            full
        }
    }

    /** Every stored image of one kind, for the storage breakdown and for clearing the lot. */
    fun all(context: Context, kind: Kind = Kind.COVER): List<File> =
        directory(context, kind).listFiles()?.filter { it.isFile }.orEmpty()

    /** What one kind adds up to, in bytes. */
    fun totalBytes(context: Context, kind: Kind = Kind.COVER): Long =
        all(context, kind).sumOf { it.length() }

    /** Removes every stored image of one kind. Callers must clear the rows pointing at them. */
    fun clearAll(context: Context, kind: Kind = Kind.COVER): Int {
        var removed = 0
        all(context, kind).forEach { if (it.delete()) removed++ }
        return removed
    }

    /**
     * Reads [source] at roughly [MAX_EDGE_PX] on its long edge.
     *
     * Two passes on purpose. The first reads only the header for the dimensions, so the full image
     * is never held at its original size — decoding a 50-megapixel photo to then shrink it is how
     * an import of several covers runs the app out of memory on the phone least able to spare it.
     */
    private fun decodeDownscaled(context: Context, source: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) {
            null
        } else {
            // Powers of two only — anything else is rounded down by the decoder, so computing a
            // precise ratio here would just be a more complicated way of getting the same number.
            var sample = 1
            while (longEdge / (sample * 2) >= MAX_EDGE_PX) sample *= 2

            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Could not decode the chosen image", e)
        null
    }
}
