package inga.bpmetrics.health

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import inga.bpmetrics.MainActivity
import inga.bpmetrics.R
import inga.bpmetrics.recording.RecordingRepository
import inga.bpmetrics.recording.RecordingState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * Foreground service for maintaining the recording session while the app is in the background.
 *
 * This service manages the lifecycle of heart rate monitoring. It starts as a foreground 
 * service immediately upon creation to comply with system requirements and ensures 
 * a notification is present while sensor hardware is active.
 */
class HealthService : LifecycleService() {

    private val tag = "HealthService"
    private val repository by lazy { RecordingRepository.Companion.getInstance(this) }
    private lateinit var notificationManager: NotificationManager

    private companion object {
        const val CHANNEL_ID = "bpm_service_channel"
        const val NOTIFICATION_ID = 1
        const val TITLE_TEXT = "BPMetrics"

        /** How long finalization may take before the session is assumed stuck. */
        const val ENDING_TIMEOUT_MS = 30_000L
    }

    private var endingTimeoutJob: Job? = null
    private val binder = LocalBinder()

    /**
     * Local binder class to allow activity components to bind directly to this service.
     */
    inner class LocalBinder : Binder() {
        fun getService(): HealthService = this@HealthService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        Log.d(tag, "Service bound")
        return binder
    }

    /**
     * Handles activity unbinding. If the app is closed (unbound) and a recording 
     * is not currently in progress, the service will shut itself down to save battery.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        // Asks the repository directly rather than reading recordingState. That flow reports
        // INACTIVE until its first emission, so during startup it would claim nothing is
        // happening and this would shut down a service that is recording.
        if (repository.sessionActive.value || repository.isFinalizing) {
            Log.d(tag, "App closed but a recording is open. Staying up.")
            return super.onUnbind(intent)
        }
        Log.d(tag, "App closed and not recording. Shutting down service.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return super.onUnbind(intent)
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)

        // Start foreground immediately to satisfy Android 14+ requirements
        startForegroundWithNotification()

        lifecycleScope.launch {
            // 1. Recover session if system says an exercise is already tracked by us
            checkAndRecoverSession()

            // 2. Fail-safe: warm the sensors up, but only when nothing is recording. Preparing
            //    while an exercise is running ends it, and the guard used to read recordingState,
            //    which reports INACTIVE until its first emission — so on a service restart during
            //    a recording this would reliably kill the very session it was recovering.
            if (!repository.sessionActive.value) {
                repository.prepareExercise()
            }

            // 3. Observe state changes to manage notification and feedback
            repository.recordingState.collect { state ->
                handleStateChange(state)
            }
        }
    }

    /**
     * Logic for responding to state transitions within the [RecordingRepository].
     */
    private fun handleStateChange(state: RecordingState) {
        when (state) {
            RecordingState.READY -> {
                vibrateOnAcquisition()
                updateNotification("Ready to record")
            }
            RecordingState.RECORDING -> {
                cancelEndingTimeout()
                updateNotification("Recording heart rate...")
            }
            RecordingState.ENDING -> {
                updateNotification("Saving record...")
                startEndingTimeout()
            }
            // INACTIVE deliberately does not shut the service down. recordingState starts at
            // INACTIVE and only later emits what is really happening, so this collector's first
            // value is routinely INACTIVE — including while a recording is running. Acting on it
            // tore down the notification moments after it was posted and left the service alive
            // only as long as the app stayed open, which is why recordings died on screen-off.
            // Shutdown belongs to onUnbind, which knows whether anyone still needs the service.
            RecordingState.PAUSED -> {
                updateNotification("Recording paused")
            }
            else -> {
                updateNotification("Preparing sensor...")
            }
        }
    }

    private suspend fun checkAndRecoverSession() {
        val info = repository.getCurrentExerciseInfo()
        if (info.isOwnedExerciseInProgress()) {
            Log.d(tag, "Recovering active exercise...")
            val persistedStartTime = repository.getPersistedStartTime()
            if (persistedStartTime > 0) {
                val duration = System.currentTimeMillis() - persistedStartTime
                repository.resumeRecording(Duration.ofMillis(duration))
            }
            return
        }

        // A session can outlive the process that opened it: the markers and the readings are on
        // disk, so as far as the wearer is concerned the recording is still going — but nothing is
        // measuring it. Put Health Services back to work rather than waiting for someone to notice.
        repository.resumeInterruptedSessionIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    /**
     * Builds the ongoing notification, wired to the [OngoingActivity] that provides a shortcut
     * back to the app from the watch face.
     */
    private fun buildNotification(contentText: String): Notification {
        val launchActivityIntent = Intent(this, MainActivity::class.java)
        val activityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchActivityIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(TITLE_TEXT)
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val ongoingActivityStatus = Status.Builder().addTemplate(TITLE_TEXT).build()
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, notificationBuilder)
            .setTouchIntent(activityPendingIntent)
            .setStatus(ongoingActivityStatus)
            .build()
            .apply(applicationContext)

        return notificationBuilder.build()
    }

    /**
     * Promotes the service to the foreground. Called once; later text changes go through
     * [updateNotification].
     */
    private fun startForegroundWithNotification(contentText: String = "Preparing...") {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Heart Rate Monitoring", NotificationManager.IMPORTANCE_LOW)
        )

        startForeground(
            NOTIFICATION_ID,
            buildNotification(contentText),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        )
    }

    /**
     * Updates the ongoing notification, and re-asserts foreground status along with it.
     *
     * Goes through [startForeground] rather than a plain `notify`. The two look alike while all
     * is well — both replace the notification with the same id — but only one of them puts a
     * demoted service back in the foreground. A service that has been demoted and merely posts a
     * notification looks correct on screen while being an ordinary background service the system
     * is free to kill, which is what let long recordings disappear.
     *
     * The channel is not recreated here; that is done once in [startForegroundWithNotification].
     */
    private fun updateNotification(contentText: String) {
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(contentText),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            )
        } catch (e: Exception) {
            // Android refuses foreground promotion in some states (an app in the background with
            // no exemption). The status text still matters, so fall back to posting it.
            Log.w(tag, "Could not re-assert foreground; posting notification only", e)
            notificationManager.notify(NOTIFICATION_ID, buildNotification(contentText))
        }
    }

    private fun vibrateOnAcquisition() {
        val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
        val vibrator = vibratorManager.defaultVibrator
        if (vibrator.hasVibrator()) {
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), -1)
            vibrator.vibrate(effect)
        }
    }

    private fun startEndingTimeout() {
        endingTimeoutJob?.cancel()
        endingTimeoutJob = lifecycleScope.launch {
            delay(ENDING_TIMEOUT_MS)
            // Never reset over the top of a write in progress. A two-hour recording is thousands
            // of readings to serialize, and forcing a reset midway wipes the table the record is
            // still being built from — turning a slow save into a lost one.
            if (repository.recordingState.value == RecordingState.ENDING && !repository.isFinalizing) {
                Log.w(tag, "Finalization did not complete in time; resetting")
                repository.forceReset()
            }
        }
    }

    private fun cancelEndingTimeout() {
        endingTimeoutJob?.cancel()
        endingTimeoutJob = null
    }

    override fun onDestroy() {
        cancelEndingTimeout()
        // The warm-up started in onCreate keeps the optical sensor lit until something ends it,
        // so it has to be released here or it outlives the service that asked for it.
        repository.releaseSensorsIfIdle()
        super.onDestroy()
    }
}
