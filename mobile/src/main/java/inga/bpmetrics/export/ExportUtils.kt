package inga.bpmetrics.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Common utility methods for file sharing across different exporters.
 */
object ExportUtils {

    /**
     * Removes a render's staging copy once it has reached wherever it was going.
     *
     * Exports are written to the cache and then copied to their destination. Nothing used to
     * remove the staging copy, so every video ever exported was still sitting on the phone at full
     * size — invisible, because the cache is not somewhere anyone thinks to look.
     */
    fun discardStagedExport(file: File) {
        if (!file.exists()) return
        val size = file.length()
        if (file.delete()) {
            android.util.Log.d("ExportUtils", "Released ${size / 1024}KB of staged export")
        } else {
            android.util.Log.w("ExportUtils", "Could not release staged export ${file.name}")
        }
    }

    /**
     * Clears staged exports left behind by earlier versions, or by a crash mid-render.
     *
     * Runs once at startup. Anything still here is by definition finished with: a render in
     * progress belongs to a process that is no longer running.
     */
    fun clearStagedExports(context: Context) {
        val stale = context.cacheDir
            .listFiles { f -> f.isFile && (f.extension == "mp4" || f.name == "black_bg.png") }
            ?: return
        if (stale.isEmpty()) return

        val freed = stale.sumOf { it.length() }
        stale.forEach { it.delete() }
        android.util.Log.i(
            "ExportUtils",
            "Reclaimed ${freed / 1_000_000}MB from ${stale.size} staged export(s)"
        )
    }

    /**
     * Generic method to share a [File] using FileProvider and an Intent.
     *
     * @param context Android context.
     * @param file The file to share.
     * @param mimeType The MIME type of the file.
     */
    fun shareFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Export BPM Data").apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /**
     * Saves a video file into the public MediaStore (Movies/BPMetrics) so it appears in the device Gallery.
     */
    fun saveVideoToGallery(context: Context, videoFile: File, title: String): android.net.Uri? {
        val sanitizedTitle = title.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val displayName = "${sanitizedTitle}_${System.currentTimeMillis()}.mp4"

        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/BPMetrics")
                put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    videoFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                android.util.Log.d("ExportUtils", "Successfully saved video to MediaStore Movies/BPMetrics: $uri")
                return uri
            } catch (e: Exception) {
                android.util.Log.e("ExportUtils", "Failed to copy video to MediaStore: ${e.message}", e)
            }
        }
        return null
    }

    /**
     * Writes a graph image into the gallery.
     *
     * PNG rather than JPEG, and not negotiable: a graph exported at low opacity is transparent, and
     * JPEG has no alpha to store it in. Saving one as JPEG would silently composite it onto black
     * and destroy the only reason the opacity setting exists.
     */
    fun saveImageToGallery(
        context: Context,
        bitmap: android.graphics.Bitmap,
        title: String
    ): android.net.Uri? {
        val sanitizedTitle = title.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val displayName = "${sanitizedTitle}_${System.currentTimeMillis()}.png"

        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BPMetrics")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            uri
        } catch (e: Exception) {
            android.util.Log.e("ExportUtils", "Failed to save image to MediaStore", e)
            // Leave nothing half-written in the gallery for the user to find and wonder about.
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** Stages a bitmap as a PNG in the cache, for sharing. */
    fun stageImageForShare(context: Context, bitmap: android.graphics.Bitmap, title: String): File? {
        val sanitizedTitle = title.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val file = File(context.cacheDir, "${sanitizedTitle}_${System.currentTimeMillis()}.png")
        return try {
            java.io.FileOutputStream(file).use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            file
        } catch (e: Exception) {
            android.util.Log.e("ExportUtils", "Could not stage image for sharing", e)
            null
        }
    }

    /**
     * Generic method to share multiple [File]s using FileProvider and an Intent.
     */
    fun shareMultipleFiles(context: Context, files: List<File>, mimeType: String) {
        val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, java.util.ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Export BPM Data").apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (android.graphics.Color.alpha(color) * factor).toInt()
        val r = android.graphics.Color.red(color)
        val g = android.graphics.Color.green(color)
        val b = android.graphics.Color.blue(color)
        return android.graphics.Color.argb(alpha, r, g, b)
    }

    /**
     * Parses the metadata date string into milliseconds since epoch.
     */
    fun parseDateToMillis(dateString: String?): Long? {
        if (dateString == null) return null

        val formats = arrayOf(
            "yyyyMMdd'T'HHmmss.SSS'Z'",
            "yyyyMMdd'T'HHmmss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "EEE MMM dd HH:mm:ss zzz yyyy",
            "yyyy-MM-dd HH:mm:ss"
        )

        for (format in formats) {
            try {
                // Fixed: SimpleDateFormat now uses java.util.Locale
                val sdf = SimpleDateFormat(format, Locale.US)

                // Fixed: sdf.timeZone now uses java.util.TimeZone
                if (format.endsWith("'Z'") || format.contains("zzz")) {
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                }

                val date = sdf.parse(dateString)
                if (date != null) return date.time
            } catch (e: Exception) {
                // Try next format
            }
        }

        return dateString.toLongOrNull()
    }
}

/**
 * Clears a paint back to the state every export draw starts from.
 *
 * `Paint.reset()` drops the font feature settings along with everything else, and these renderers
 * reset the same paint object twenty-odd times per frame. Numbers drawn without tabular figures use
 * proportional digits, which are different widths — so the live stats pill visibly changed size as
 * a reading crossed from 99 to 100, and the clock twitched on every tick. On a still that is merely
 * untidy; on thirty frames a second it is the most distracting thing on screen.
 *
 * Tabular figures only change the advance width of digits, so applying this to every export paint
 * rather than picking out the numeric ones costs nothing and cannot be forgotten at a new draw site.
 */
fun android.graphics.Paint.resetForExport() {
    reset()
    isAntiAlias = true
    fontFeatureSettings = inga.bpmetrics.ui.theme.MetricNumerals
}
