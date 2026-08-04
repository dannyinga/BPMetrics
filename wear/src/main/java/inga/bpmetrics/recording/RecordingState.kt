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

/**
 * What the heart rate sensor is doing right now, independent of whether a recording is running.
 *
 * Deliberately separate from [RecordingState]: a recording continues through a signal dropout, so
 * a single combined state cannot report both truthfully at once. This is derived only from the
 * live availability signal, never from buffered readings, so it never reports a stale lock.
 */
enum class SignalState {
    /** Health Services has not reported on the sensor yet. */
    UNKNOWN,
    /** The sensor is powered and searching for a pulse. */
    ACQUIRING,
    /** The sensor has a usable signal. */
    AVAILABLE,
    /** The sensor cannot currently produce a reading. */
    UNAVAILABLE,
    /** The watch is not being worn. */
    OFF_BODY;

    /** Whether readings are actually arriving right now. */
    val hasSignal: Boolean get() = this == AVAILABLE
}