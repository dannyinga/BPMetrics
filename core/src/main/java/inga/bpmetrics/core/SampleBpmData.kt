package inga.bpmetrics.core

import java.sql.Date

class SampleBpmData {
    companion object {

        val bpmWatchRecord1 = constructNewRecordWithStartTimeAndAvgBpm(0L, 60.0)
        val bpmWatchRecord2 = constructNewRecordWithStartTimeAndAvgBpm(10000L, 70.0)
        val bpmWatchRecord3 = constructNewRecordWithStartTimeAndAvgBpm(20000L, 65.0)
        val bpmWatchRecord4 = constructNewRecordWithStartTimeAndAvgBpm(30000L, 85.0)

        val bpmWatchRecordList = listOf<BpmWatchRecord>(bpmWatchRecord1, bpmWatchRecord2, bpmWatchRecord3, bpmWatchRecord4)


        private fun constructNewRecordWithStartTimeAndAvgBpm(startTime: Long, avgBpm: Double) : BpmWatchRecord {
            val bpmDataPoint1: BpmDataPoint = BpmDataPoint(1000L, avgBpm)
            val bpmDataPoint2: BpmDataPoint = BpmDataPoint(2000L, avgBpm - 5)
            val bpmDataPoint3: BpmDataPoint = BpmDataPoint(3000L, avgBpm + 5)
            val bpmDataPoint4: BpmDataPoint = BpmDataPoint(4000L, avgBpm - 10)
            val bpmDataPoint5: BpmDataPoint = BpmDataPoint(5000L, avgBpm + 10)
            val bpmDataPointList1: List<BpmDataPoint> = listOf(bpmDataPoint1,
                bpmDataPoint2,
                bpmDataPoint3,
                bpmDataPoint4,
                bpmDataPoint5)
                .sorted()

            return BpmWatchRecord(
                Date(System.currentTimeMillis()),
                bpmDataPointList1,
                startTime,
                startTime + 6000L)
        }
  }
}