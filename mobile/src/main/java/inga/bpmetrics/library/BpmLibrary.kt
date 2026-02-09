package inga.bpmetrics.library

import android.content.Context
import android.util.Log
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.core.SampleBpmData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class BpmLibrary(
    context: Context,
) {

    private val tag = "BpmLibrary"

    private val db = AppDatabase.getInstance(context)
    private val dao = db.bpmRecordDao()

    suspend fun loadLibraryFlowFromDB(onUpdate: (List<BpmRecord>) -> Unit) {
//        Log.d(tag, "Calling DAO record flow")
        dao.getAllRecordsFlow()
            .collect { records ->
                onUpdate(records)  // send updates to controller/UI
            }
    }

    suspend fun updateRecordMetadata(record: BpmRecord) {
        dao.insertBpmRecordGetId(record.metadata)
    }

    suspend fun saveWatchRecordToLibrary(record: BpmWatchRecord) {
//        Log.d(tag, "Starting watch record save to DB")
        var title = record.date.toString()

        val formatter = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.getDefault())

        title += " ${Instant.ofEpochMilli(record.startTime)
            .atZone(ZoneId.systemDefault())
            .format(formatter)}"

        val recordEntity =
            BpmRecordEntity(
                title = title,
                date = record.date.time,
                startTime = record.startTime,
                endTime = record.endTime,
                durationMs = record.durationMs
            )

//        Log.d(tag, "Insert initial record to DB for record ID\n$recordEntity")
        val recordId = dao.insertBpmRecordGetId(recordEntity)

        var max = -1.0
        var maxId = 0L

        var min = 300.0
        var minId = 0L

        var bpmWeightedSum = 0.0
        var totalTime = 0L

        // For each data point, get
        for (i in 0 until record.dataPoints.size) {
            val dataPoint = record.dataPoints[i]

            // dt for last point is duration - timestamp, else it is timestamp_i+1 - timestamp_i
            val dt = (if (i < record.dataPoints.size - 1) record.dataPoints[i + 1].timestamp
                        else record.durationMs)
                    - dataPoint.timestamp

            val dataPointEntity =
                BpmDataPointEntity(
                    recordOwnerId = recordId,
                    timestamp = dataPoint.timestamp,
                    bpm = dataPoint.bpm
                )

            val dataPointId = dao.insertBpmDataPoint(dataPointEntity)
//            Log.d(tag, "New data point inserted $dataPointId\n$dataPointEntity")

            bpmWeightedSum += dataPoint.bpm * dt
            totalTime += dt

            if (dataPoint.bpm > max) {
//                Log.d(tag, "New max found for record id $recordId\n$dataPointEntity")
                maxId = dataPointId
                max = dataPoint.bpm
            }

            if (dataPoint.bpm < min) {
//                Log.d(tag, "New min found for record id $recordId\n$dataPointEntity")
                minId = dataPointId
                min = dataPoint.bpm
            }
        }

        val avg = bpmWeightedSum / totalTime
//        Log.d(tag, "Average found for record id $recordId = $avg")

        val updatedRecordEntity = recordEntity.copy(
            recordId = recordId,
            minId = minId,
            maxId = maxId,
            avg = avg
        )

//        Log.d(tag, "Insert updated record to DB\n$updatedRecordEntity")
        dao.insertBpmRecordGetId(updatedRecordEntity)
    }

    suspend fun uploadSampleDataToDB() {
//        Log.d(tag, "Attempting to upload sample watch records")
        val sampleWatchRecords = SampleBpmData.bpmWatchRecordList
        for (watchRecord in sampleWatchRecords) {
            saveWatchRecordToLibrary(watchRecord)
        }
    }

    suspend fun deleteAll() {
        dao.deleteAllRecords()
        dao.deleteAllDataPoints()
    }

    suspend fun getDataPointWithId(id: Long) : BpmDataPointEntity{
        return dao.getDataPoint(id)
    }
}