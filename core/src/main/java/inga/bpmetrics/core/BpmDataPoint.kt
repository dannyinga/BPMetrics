package inga.bpmetrics.core

import androidx.room.Entity

/**
 * A single data point for a BPM reading : (timestamp: Long, bpm: Double)
 */
@Entity()
data class BpmDataPoint(
    val timestamp: Long, // time since start of recording
    val bpm: Double
) : Comparable<BpmDataPoint> {

    init {
        validateParams()
    }

    private fun validateParams() {
        if (timestamp < 0)
            throw IllegalArgumentException("BPM Data Point can't be created with negative timestamp")

        if (!isValidBpm(bpm))
            throw IllegalArgumentException("BPM Data Point can't be created with bpm: $bpm\n" +
                                        "BPM must be between 0 and 250 inclusive")
    }

    /**
     * Sort data points based on their timestamp (ascending)
     */
    override fun compareTo(other: BpmDataPoint): Int {
        return this.timestamp.compareTo(other.timestamp)
    }

    /**
     * Formats into "Timestamp: #m #s #ms, BPM: #"
     */
    override fun toString(): String {
        val milliseconds = timestamp % 1000
        val seconds = timestamp / 1000 % 60
        val minutes = timestamp / (1000 * 60) % 60
        val formattedTimeStamp = "${minutes}m ${seconds}s ${milliseconds}ms"
        return "Timestamp: $formattedTimeStamp, BPM: $bpm"
    }

    companion object {
        /** Lowest heart rate this class accepts. */
        const val MIN_BPM = 0.0

        /**
         * Highest heart rate this class accepts. Optical sensors occasionally report values
         * far above this during motion artifacts; those readings are not real heart rates.
         */
        const val MAX_BPM = 250.0

        /**
         * Returns whether [bpm] is in the range the constructor accepts.
         *
         * Lets producers drop sensor artifacts before constructing a point, rather than
         * relying on the constructor throwing part-way through a batch.
         */
        fun isValidBpm(bpm: Double): Boolean = bpm in MIN_BPM..MAX_BPM
    }
}
