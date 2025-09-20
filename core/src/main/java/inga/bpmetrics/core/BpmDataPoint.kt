package inga.bpmetrics.core

// A single heart rate reading
data class BpmDataPoint(
    val timestamp: Long, // Unix time (ms)
    val bpm: Int
) : Comparable<BpmDataPoint> {
    override fun compareTo(other: BpmDataPoint): Int {
        return this.timestamp.compareTo(other.timestamp)
    }
}
