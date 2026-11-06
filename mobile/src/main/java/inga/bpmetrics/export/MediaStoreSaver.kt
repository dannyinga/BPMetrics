package inga.bpmetrics.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.OutputStream

/**
 * A file name made from a title.
 *
 * One rule, where there were five copies in two variants: one kept only ASCII letters, so
 * "Café Tacvba" saved as "Caf__Tacvba"; the other kept anything a file system allows, spaces
 * excepted (Model and Export Consolidation §6).
 */
object FileNames {

    /**
     * [title] with anything but letters, digits, `-` and `_` turned into `_`, runs of them
     * collapsed, and trimmed to a reasonable length. "BPMetrics" when nothing usable is left.
     */
    fun of(title: String): String = title
        .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')
        .take(MAX_LENGTH)
        .trimEnd('_')
        .ifEmpty { "BPMetrics" }

    private const val MAX_LENGTH = 80
}

/**
 * Saves an export into the phone's gallery: videos in Movies/BPMetrics, pictures in
 * Pictures/BPMetrics.
 *
 * One saver where there were two near-copies, and only the picture one cleaned up after itself: a
 * video that failed half way through copying left a broken entry in the gallery. Now both delete
 * what they started.
 */
object MediaStoreSaver {

    private const val TAG = "MediaStoreSaver"

    private enum class Kind(val collection: Uri, val folder: String, val mimeType: String, val extension: String) {
        VIDEO(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/BPMetrics", "video/mp4", "mp4"),
        // PNG, not negotiable: a graph exported at low opacity is transparent, and JPEG has no alpha.
        IMAGE(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/BPMetrics", "image/png", "png")
    }

    /** Copies a rendered video into the gallery. Null if it couldn't, with nothing left behind. */
    fun saveVideo(context: Context, file: File, title: String): Uri? =
        save(context, Kind.VIDEO, title) { out -> file.inputStream().use { it.copyTo(out) } }

    /** Writes a picture into the gallery as PNG. Null if it couldn't, with nothing left behind. */
    fun saveImage(context: Context, bitmap: Bitmap, title: String): Uri? =
        save(context, Kind.IMAGE, title) { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }

    private fun save(context: Context, kind: Kind, title: String, write: (OutputStream) -> Unit): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${FileNames.of(title)}_${System.currentTimeMillis()}.${kind.extension}")
            put(MediaStore.MediaColumns.MIME_TYPE, kind.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, kind.folder)
                // Hidden from the gallery until it's whole.
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(kind.collection, values) ?: return null

        return try {
            val stream = resolver.openOutputStream(uri) ?: error("The gallery gave nowhere to write")
            stream.use(write)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            Log.d(TAG, "Saved to ${kind.folder}: $uri")
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't save to ${kind.folder}", e)
            // Nothing half-written left in the gallery to find and wonder about.
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
