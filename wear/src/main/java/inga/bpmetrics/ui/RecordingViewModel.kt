package inga.bpmetrics.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.recording.RecordingRepository
import inga.bpmetrics.recording.RecordingState
import inga.bpmetrics.recording.SignalState
import inga.bpmetrics.sync.PhoneSyncManager
import inga.bpmetrics.sync.SyncOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the [RecordingScreen].
 * 
 * Provides UI state derived from the [RecordingRepository] and handles user interactions.
 *
 * @param repository The repository that manages the heart rate data and Health Services state.
 */
class RecordingViewModel(
    private val repository: RecordingRepository,
    /** Used by the manual send action; null in previews and tests. */
    private val syncManager: PhoneSyncManager? = null
) : ViewModel() {

    /**
     * UI state representing the current recording session.
     */
    /**
     * What the wearer is told about the last manual send. Cleared after a moment.
     */
    private val _sendResult = MutableStateFlow<String?>(null)

    private val _sending = MutableStateFlow(false)

    // Typed explicitly. Left to infer, the element type becomes whatever supertype these flows
    // happen to share, which changes whenever one is added or removed and which Kotlin is in the
    // process of making a hard error.
    val uiState: StateFlow<RecordingUIState> = combine<Any?, RecordingUIState>(
        repository.liveBpm,
        repository.recordingStartTime,
        repository.recordingState,
        repository.signalState,
        // The outbox is now the whole answer to "what does the phone not have". A record stays
        // in it until the phone acknowledges the save, so there is nothing to add to it.
        repository.pendingRecordCount,
        _sending,
        _sendResult
    ) { values ->
        RecordingUIState(
            bpm = values[0] as Double?,
            recordingStartTime = values[1] as Long,
            serviceState = values[2] as RecordingState,
            signalState = values[3] as SignalState,
            pendingRecordCount = values[4] as Int,
            isSending = values[5] as Boolean,
            sendResult = values[6] as String?,
            statusText = statusTextFor(values[2] as RecordingState, values[3] as SignalState)
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RecordingUIState(),
    )

    init {
        // Ensure prerequisites (permissions, etc.) are marked as granted
        repository.grantAllPrerequisites()
    }

    /**
     * Signals the repository to start a new heart rate recording.
     */
    fun onStartClicked() {
        repository.startRecording()
    }

    /**
     * Signals the repository to stop the current recording and finalize the session.
     */
    fun onStopClicked() {
        repository.stopRecording()
    }

    /**
     * Offers the phone everything the watch is still holding.
     *
     * Nothing is deleted here. A record leaves the watch only when the phone acknowledges it, so
     * the worst this can do is hand the same recording over again — which is why it is safe to
     * press repeatedly when a transfer looks stuck.
     */
    fun onSendNowClicked() {
        val manager = syncManager ?: return
        if (_sending.value) return

        viewModelScope.launch {
            _sending.value = true
            _sendResult.value = null
            try {
                _sendResult.value = describe(manager.syncNow())
            } catch (e: CancellationException) {
                // The screen went away mid-send. Not a failure, and rethrowing is required or the
                // coroutine machinery is left believing a cancelled job completed.
                throw e
            } catch (t: Throwable) {
                // `Throwable`, not `Exception`. `syncNow` already catches `Exception` internally,
                // so anything arriving here is the kind that does not get caught by tidy code —
                // a missing Play Services class, a `LinkageError` from a partial update. The point
                // is not that any of those is expected. It is that `viewModelScope` carries no
                // exception handler, so *whatever* escapes this block kills the app, and the last
                // thing a wearer should get for pressing a button labelled "Send now" is the app
                // disappearing off their wrist with the recording still on it.
                Log.e(TAG, "Manual send failed", t)
                _sendResult.value = "Send failed"
            } finally {
                _sending.value = false
            }
            delay(RESULT_VISIBLE_MS)
            _sendResult.value = null
        }
    }

    private companion object {
        const val TAG = "RecordingViewModel"

        /** How long the outcome of a manual send stays on the watch face. */
        const val RESULT_VISIBLE_MS = 4_000L

        /**
         * Says what actually happened, rather than always claiming success.
         *
         * "Sent" would be a lie in the common stuck case: the records left the watch some time
         * ago and are waiting on a phone that is out of range. Telling the wearer that
         * distinguishes a problem they can fix — move closer, open the phone app — from one they
         * cannot.
         */
        fun describe(outcome: SyncOutcome): String = when {
            outcome.failed -> "Could not reach the phone"
            outcome.sent > 0 -> "Sent ${outcome.sent}, waiting on phone"
            outcome.confirmed > 0 && outcome.stillWaiting == 0 -> "Phone has everything"
            outcome.stillWaiting > 0 -> "Already sent · waiting on phone"
            else -> "Nothing to send"
        }

        /**
         * Describes the session and the sensor together.
         *
         * While recording, the sensor's state is what the wearer needs to know — a recording
         * keeps running through a dropout, and saying only "Recording..." would hide the fact
         * that nothing is being measured.
         */
        fun statusTextFor(state: RecordingState, signal: SignalState): String = when (state) {
            RecordingState.RECORDING -> when (signal) {
                SignalState.AVAILABLE -> "Recording..."
                SignalState.ACQUIRING -> "Recording · finding pulse"
                SignalState.OFF_BODY -> "Recording · watch off wrist"
                SignalState.UNAVAILABLE -> "Recording · no signal"
                SignalState.UNKNOWN -> "Recording · starting sensor"
            }
            RecordingState.PAUSED -> "Paused"
            RecordingState.ENDING -> "Saving record..."
            RecordingState.PREPARING -> "Warming up sensor..."
            RecordingState.ACQUIRING -> "Acquiring heart rate..."
            RecordingState.READY -> "Ready to record"
            RecordingState.UNAVAILABLE -> when (signal) {
                SignalState.OFF_BODY -> "Watch not on wrist"
                else -> "Heart rate unavailable"
            }
            RecordingState.INACTIVE -> "Tap to start recording"
        }
    }
}

/**
 * Data class representing the UI state of the recording screen.
 *
 * @property bpm The most recent heart rate reading.
 * @property recordingStartTime The start timestamp of the current session.
 * @property serviceState The current [RecordingState] of the monitor.
 * @property statusText A human-readable description of the current state.
 */
data class RecordingUIState(
    val bpm: Double? = null,
    val recordingStartTime: Long = 0L,
    val serviceState: RecordingState = RecordingState.INACTIVE,
    val signalState: SignalState = SignalState.UNKNOWN,
    val statusText: String = "Initializing...",
    /** Finished recordings the phone has not received yet. */
    val pendingRecordCount: Int = 0,
    /** Whether a manual send is in flight. */
    val isSending: Boolean = false,
    /** What the last manual send achieved, while it is still worth showing. */
    val sendResult: String? = null
) {
    /**
     * Whether the start/stop control accepts a press.
     *
     * Recording no longer waits on the sensor: a session can begin from any state and captures
     * whatever the sensor delivers once it acquires. Only finalizing blocks input, because the
     * previous record is still being written.
     */
    val isControlEnabled: Boolean get() = serviceState != RecordingState.ENDING
}
