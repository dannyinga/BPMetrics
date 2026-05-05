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
