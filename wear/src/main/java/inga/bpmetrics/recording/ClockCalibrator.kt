package inga.bpmetrics.recording

import android.os.SystemClock
import androidx.health.services.client.data.ExerciseUpdate
import java.time.Duration
import java.time.Instant

/**
 * Utility for calibrating the monotonic start time anchor.
 *
 * It ensures that timestamps from Health Services sensors (boot-time based)
 * correctly align with the session's active duration.
 */
object ClockCalibrator {

    /**
     * Calculates the boot-time (elapsedRealtime) at which the session started.
     *
     * @param checkpoint The duration checkpoint from Health Services.
     * @return The calibrated boot-time start anchor in milliseconds.
     */
    fun calculateStartAnchor(checkpoint: ExerciseUpdate.ActiveDurationCheckpoint): Long {
        val nowBoot = SystemClock.elapsedRealtime()
        val nowInstant = Instant.now()

        // Calculate how long ago the checkpoint was captured
        val durationSinceCheckpoint = Duration.between(checkpoint.time, nowInstant)
        val bootTimeOfCheckpoint = nowBoot - durationSinceCheckpoint.toMillis()

        // Subtract the active duration at that point to find the true start anchor
        return bootTimeOfCheckpoint - checkpoint.activeDuration.toMillis()
    }
}