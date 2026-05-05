package inga.bpmetrics.export

import android.content.Context
import android.net.Uri
import inga.bpmetrics.core.BpmDataPoint
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.ui.util.StringFormatHelpers
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.sql.Date

/**
 * Handles CSV export and import for BPM records.
 */
object CsvExporter {

    /**
     * Formats a [BpmRecord] into a CSV string.
     *
     * @param record The BPM record to format.
     * @return A string containing the record metadata and all data points in CSV format.
     */
    fun getCsvString(record: BpmRecord): String {
        val writer = StringBuilder()
        writer.appendLine("BPMetrics Data Export")
        writer.appendLine("Title,${record.metadata.title}")
        writer.appendLine("Description,${record.metadata.description}")
        writer.appendLine("Date,${StringFormatHelpers.getDateString(record.metadata.date)}")
        writer.appendLine("Start Time (ms),${record.metadata.startTime}")
        writer.appendLine("End Time (ms),${record.metadata.endTime}")
        writer.appendLine("Duration (ms),${record.metadata.durationMs}")
        writer.appendLine("Average BPM,${record.metadata.avg ?: 0.0}")
        writer.appendLine("Min BPM,${record.minDataPoint?.bpm ?: 0.0}")
        writer.appendLine("Max BPM,${record.maxDataPoint?.bpm ?: 0.0}")
        writer.appendLine("Tags,${record.tags.joinToString(" | ") { it.name }}")
        writer.appendLine()

        writer.appendLine("Timestamp (ms),BPM")
        record.dataPoints.sortedBy { it.timestamp }.forEach { point ->
            writer.appendLine("${point.timestamp},${point.bpm}")
        }
        return writer.toString()
    }

    /**
     * Imports a [BpmWatchRecord] from a CSV file.
     *
     * @param context Android context for resolving the URI.
     * @param uri The URI of the CSV file to import.
     * @return A [BpmWatchRecord] if the file was successfully parsed, null otherwise.
     */
    fun importFromCsv(context: Context, uri: Uri): BpmWatchRecord? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                var title: String? = null
                var description: String? = null
                var startTime = 0L
                var endTime = 0L
                var tags = emptyList<String>()
                val dataPoints = mutableListOf<BpmDataPoint>()
                var isDataSection = false

                reader.forEachLine { line ->
                    val parts = line.split(",", limit = 2)
                    if (!isDataSection) {
                        when {
                            line.startsWith("Title,") -> title = parts.getOrNull(1)
                            line.startsWith("Description,") -> description = parts.getOrNull(1)
                            line.startsWith("Start Time (ms),") -> startTime = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                            line.startsWith("End Time (ms),") -> endTime = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                            line.startsWith("Tags,") -> tags = parts.getOrNull(1)?.split(" | ")?.filter { it.isNotBlank() } ?: emptyList()
                            line.startsWith("Timestamp (ms),BPM") -> isDataSection = true
                        }
                    } else {
                        val dataParts = line.split(",")
                        if (dataParts.size >= 2) {
                            val ts = dataParts[0].toLongOrNull()
                            val bpm = dataParts[1].toDoubleOrNull()
                            if (ts != null && bpm != null) {
                                dataPoints.add(BpmDataPoint(ts, bpm))
                            }
                        }
                    }
                }

                if (startTime != 0L && endTime != 0L && dataPoints.isNotEmpty()) {
                    BpmWatchRecord(
                        date = Date(startTime),
                        dataPoints = dataPoints,
                        startTime = startTime,
                        endTime = endTime,
                        title = title,
                        description = description,
                        tagNames = tags
                    )
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Shares a [BpmRecord] as a CSV file using an Intent.
     *
     * @param context Android context to start the sharing intent.
     * @param record The record to share.
     */
    fun shareCsv(context: Context, record: BpmRecord) {
        val sanitizedTitle = record.metadata.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").replace(" ", "_")
        val fileName = "${sanitizedTitle}_data.csv"
        val tempFile = File(context.cacheDir, fileName)

        try {
            FileWriter(tempFile).use { it.write(getCsvString(record)) }
            ExportUtils.shareFile(context, tempFile, "text/csv")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
