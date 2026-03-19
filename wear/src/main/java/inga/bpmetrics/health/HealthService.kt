package inga.bpmetrics.health

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
import androidx.health.services.client.data.ExerciseTrackedStatus.Companion.OWNED_EXERCISE_IN_PROGRESS
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
        val state = repository.recordingState.value
        if (state != RecordingState.RECORDING && state != RecordingState.ENDING) {
            Log.d(tag, "App closed and not recording. Shutting down service.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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

            // 2. Fail-safe: Ensure sensors are prepared if idle
            if (repository.recordingState.value != RecordingState.RECORDING) {
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
            RecordingState.INACTIVE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
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
        if (info.exerciseTrackedStatus == OWNED_EXERCISE_IN_PROGRESS) {
            Log.d(tag, "Recovering active exercise...")
            val persistedStartTime = repository.getPersistedStartTime()
            if (persistedStartTime > 0) {
                val duration = System.currentTimeMillis() - persistedStartTime
                repository.resumeRecording(Duration.ofMillis(duration))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    /**
     * Initializes and starts the foreground service with a persistent notification.
     * Uses [OngoingActivity] to provide a shortcut back to the app from the watch face.
     */
    private fun startForegroundWithNotification(contentText: String = "Preparing...") {
        val titleText = "BPMetrics"
        val channelId = "bpm_service_channel"
        val notificationId = 1

        val channel = NotificationChannel(
            channelId,
            "Heart Rate Monitoring",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        val launchActivityIntent = Intent(this, MainActivity::class.java)
        val activityPendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            launchActivityIntent, 
            PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(titleText)
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val ongoingActivityStatus = Status.Builder().addTemplate(titleText).build()
        val ongoingActivity = OngoingActivity.Builder(applicationContext, notificationId, notificationBuilder)
            .setTouchIntent(activityPendingIntent)
            .setStatus(ongoingActivityStatus)
            .build()

        ongoingActivity.apply(applicationContext)

        startForeground(
            notificationId,
            notificationBuilder.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        )
    }

    /**
     * Updates the existing foreground notification with new status text.
     */
    private fun updateNotification(contentText: String) {
        startForegroundWithNotification(contentText)
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
            delay(8000) // Give repository 8 seconds to finalize sync and DB
            if (repository.recordingState.value == RecordingState.ENDING) {
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
        super.onDestroy()
    }
}
