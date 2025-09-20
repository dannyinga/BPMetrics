package inga.bpmetrics.core

import java.sql.Date

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