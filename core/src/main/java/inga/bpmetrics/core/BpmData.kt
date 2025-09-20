package inga.bpmetrics.core

import java.sql.Date
import java.sql.Time

// A single heart rate reading
data class BpmDataPoint(
    val timestamp: Long, // Unix time (ms)
    val bpm: Int
)

// A collection of readings grouped under a tag
data class BpmWatchRecord(
    val date: Date,
    val dataPoints: List<BpmDataPoint>,
    val startTime: Long,
    val endTime: Long,

    val max: BpmDataPoint,
    val avg: Int,
    val min: BpmDataPoint,

)

// A collection of readings grouped under a tag
data class BpmRecord(
    val date: Date,
    val dataPoints: List<BpmDataPoint>,
    val startTime: Long,
    val endTime: Long,

    val id: Int,
    var title: String,

    val max: BpmDataPoint,
    val avg: Int,
    val min: BpmDataPoint
)
