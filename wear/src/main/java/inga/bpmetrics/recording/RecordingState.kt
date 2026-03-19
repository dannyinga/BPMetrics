package inga.bpmetrics.recording

/**
 * Enumeration of the possible states of the heart rate monitoring service.
 */
enum class RecordingState {
    /** Service is idle and not monitoring. */
    INACTIVE,
    /** Initializing sensors and preparing for exercise. */
    PREPARING,
    /** Device is incapable of heart rate monitoring or sensors are failed. */
    UNAVAILABLE,
    /** Actively seeking a heart rate signal lock. */
    ACQUIRING,
    /** Heart rate lock acquired; ready to start recording. */
    READY,
    /** Actively recording heart rate data. */
    RECORDING,
    /** Session is paused. */
    PAUSED,
    /** Session has ended and is being finalized. */
    ENDING
}