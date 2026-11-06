package inga.bpmetrics.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

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
     * Where a saved file is, as a person would look for it: "Movies/BPMetrics/Name_123.mp4".
     *
     * Read from the media store rather than assumed, so the queue shows the file's real name.
     * Null when the system won't say.
     */
    fun describeLocation(context: Context, uri: android.net.Uri): String? = runCatching {
        val columns = arrayOf(
            android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
            android.provider.MediaStore.MediaColumns.DISPLAY_NAME
        )
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val folder = cursor.getString(0)?.trimEnd('/')
            val name = cursor.getString(1)
            listOfNotNull(folder, name).joinToString("/").takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    /** Stages a bitmap as a PNG in the cache, for sharing. */
    fun stageImageForShare(context: Context, bitmap: android.graphics.Bitmap, title: String): File? {
        val sanitizedTitle = FileNames.of(title)
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
