package inga.bpmetrics.export

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import inga.bpmetrics.core.BpmDataPoint
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.library.BpmRecord
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.sql.Date

/**
 * Serialized representation of a complete BPM record for JSON export and import.
 */
data class BpmRecordJsonDto(
    val title: String,
    val description: String = "",
    val deviceId: String = "Watch",
    val wearerName: String? = null,
    val startTime: Long,
    val endTime: Long,
    val tags: List<String> = emptyList(), // "Category:Tag" format
    val dataPoints: List<BpmDataPointDto> = emptyList()
)

data class BpmDataPointDto(
    val timestamp: Long,
    val bpm: Double
)

/**
 * Handles JSON batch export and import for single or multiple BPM records.
 */
object JsonExporter {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Converts a list of [BpmRecord]s into a JSON string.
     */
    fun toJsonString(records: List<BpmRecord>): String {
        val dtos = records.map { record ->
            BpmRecordJsonDto(
                title = record.metadata.title,
                description = record.metadata.description,
                deviceId = record.metadata.deviceId,
                wearerName = record.metadata.wearerName.takeIf { it.isNotBlank() },
                startTime = record.metadata.startTime,
                endTime = record.metadata.endTime,
                tags = record.tags.map { "${it.parentCategoryId}:${it.name}" },
                dataPoints = record.dataPoints.map { BpmDataPointDto(it.timestamp, it.bpm) }
            )
        }
        return gson.toJson(dtos)
    }

    /**
     * Imports a list of [BpmWatchRecord]s from a JSON URI.
     */
    fun importFromJson(context: Context, uri: Uri): List<BpmWatchRecord> {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                val jsonString = reader.readText()
                val listType = object : TypeToken<List<BpmRecordJsonDto>>() {}.type
                val dtos: List<BpmRecordJsonDto> = try {
                    gson.fromJson(jsonString, listType)
                } catch (e: Exception) {
                    // Try parsing single object
                    val singleDto = gson.fromJson(jsonString, BpmRecordJsonDto::class.java)
                    if (singleDto != null) listOf(singleDto) else emptyList()
                }

                dtos.mapNotNull { dto ->
                    if (dto.startTime > 0 && dto.endTime > dto.startTime && dto.dataPoints.isNotEmpty()) {
                        BpmWatchRecord(
                            date = Date(dto.startTime),
                            dataPoints = dto.dataPoints.map { BpmDataPoint(it.timestamp, it.bpm) },
                            startTime = dto.startTime,
                            endTime = dto.endTime,
                            title = dto.title,
                            description = dto.description,
                            tagNames = dto.tags,
                            deviceId = dto.deviceId,
                            wearerName = dto.wearerName
                        )
                    } else null
                }
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Shares multiple [BpmRecord]s as a JSON backup file using an Intent.
     */
    fun shareJson(context: Context, records: List<BpmRecord>) {
        if (records.isEmpty()) return
        val fileName = if (records.size == 1) {
            val title = records.first().metadata.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").replace(" ", "_")
            "${title}_export.bpmjson"
        } else {
            "BPMetrics_Export_${System.currentTimeMillis()}.bpmjson"
        }
        val tempFile = File(context.cacheDir, fileName)
        try {
            FileWriter(tempFile).use { it.write(toJsonString(records)) }
            ExportUtils.shareFile(context, tempFile, "application/json")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
