package inga.bpmetrics

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.concurrent.futures.await
import androidx.core.app.NotificationCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.clearUpdateCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseTrackedStatus.Companion.NO_EXERCISE_IN_PROGRESS
import androidx.health.services.client.data.ExerciseTrackedStatus.Companion.OTHER_APP_IN_PROGRESS
import androidx.health.services.client.data.ExerciseTrackedStatus.Companion.OWNED_EXERCISE_IN_PROGRESS
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.WarmUpConfig
import androidx.health.services.client.endExercise
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * Foreground service for managing BPM recording and Health Services interaction.
 */
class BpmExerciseService : LifecycleService() {

    private val tag = "BpmExerciseService"
    private val healthClient by lazy { HealthServices.getClient(this) }
    private val exerciseClient by lazy { healthClient.exerciseClient }
    private val repository by lazy { BPMetricsRepository.getInstance(this) }
    private lateinit var notificationManager: NotificationManager
    
    private var endingTimeoutJob: Job? = null

    private val exerciseConfig = ExerciseConfig(
        exerciseType = ExerciseType.WORKOUT,
        dataTypes = setOf(DataType.HEART_RATE_BPM),
        isAutoPauseAndResumeEnabled = false,
        isGpsEnabled = false,
    )

    private val warmUpConfig = WarmUpConfig(
        ExerciseType.WORKOUT,
        setOf(DataType.HEART_RATE_BPM)
    )

    private val exerciseCallback = object : ExerciseUpdateCallback {
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            repository.onExerciseAvailabilityChanged(availability)
        }

        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            repository.onExerciseUpdate(update)
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {
            Log.e(tag, "Exercise callback registration failed", throwable)
        }
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    inner class LocalBinder : Binder() {
        fun getService(): BpmExerciseService = this@BpmExerciseService
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        setExerciseCallback()

        lifecycleScope.launch {
            checkCurrentExerciseStatus()

            // Fail-safe: Ensure sensors are prepared if we aren't in an active session.
            val currentState = repository.serviceState.value
            if (currentState != BpmServiceState.RECORDING && 
                currentState != BpmServiceState.PAUSED && 
                currentState != BpmServiceState.ENDING) {
                Log.d(tag, "Service started in non-active state ($currentState). Triggering sensor warm-up.")
                prepareExercise()
            }

            // Use distinctUntilChanged to ensure we only vibrate once per READY transition
            repository.serviceState
                .collect { state ->
                    when (state) {
                        BpmServiceState.READY -> {
                            Log.d(tag, "Service state: READY - Vibrating")
                            vibrateForAcquisition()
                        }
                        BpmServiceState.RECORDING -> {
                            cancelEndingTimeout()
                            startExercise()
                        }
                        BpmServiceState.ENDING -> {
                            startEndingTimeout()
                            endExerciseAndStopService()
                        }
                        BpmServiceState.PAUSED -> pauseExercise()
                        BpmServiceState.INACTIVE -> {
                            prepareExercise()
                        }
                        BpmServiceState.PREPARING,
                        BpmServiceState.ACQUIRING -> {
                            Log.d(tag, "Service state: $state - waiting for user or sensors")
                        }
                        BpmServiceState.UNAVAILABLE -> {
                            Log.w(tag, "Sensor is unavailable")
                        }
                        else -> {}
                    }
                }
        }
    }

    private fun vibrateForAcquisition() {
        val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        val vibrator = vibratorManager.defaultVibrator

        if (vibrator.hasVibrator()) {
            // A short "double pulse" effect to notify acquisition
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), -1)
            vibrator.vibrate(effect)
        }
    }

    private suspend fun checkCurrentExerciseStatus() {
        val currentExerciseInfo = exerciseClient.getCurrentExerciseInfoAsync().await()

        when (currentExerciseInfo.exerciseTrackedStatus) {
            OTHER_APP_IN_PROGRESS -> Log.w(tag, "Another app is tracking an exercise.")
            OWNED_EXERCISE_IN_PROGRESS -> {
                Log.d(tag, "Owned exercise in progress detected. Recovering recording state...")
                
                val persistedStartTime = repository.getPersistedStartTime()
                if (persistedStartTime > 0) {
                    val durationSinceStart = System.currentTimeMillis() - persistedStartTime
                    repository.resumeRecording(Duration.ofMillis(durationSinceStart))
                } else {
                    val firstTimestamp = repository.getFirstRecordedTimestamp()
                    repository.resumeRecording(Duration.ofMillis(firstTimestamp))
                }
                
                startForegroundWithNotification()
            }
            NO_EXERCISE_IN_PROGRESS -> {
                Log.d(tag, "No exercise in progress. Resetting repository.")
                repository.forceReset()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundWithNotification()
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val titleText = "BPMetrics Recording"
        val channelId = "bpm_service_channel"
        val notificationId = 1

        val channel = NotificationChannel(channelId, "Heart Rate Monitoring", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)

        val launchActivityIntent = Intent(this, BpmActivity::class.java)
        val activityPendingIntent = PendingIntent.getActivity(this, 0, launchActivityIntent, PendingIntent.FLAG_IMMUTABLE)

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(titleText)
            .setContentText("Monitoring heart rate…")
            .setSmallIcon(android.R.drawable.ic_media_play)
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

    private fun setExerciseCallback() {
        exerciseClient.setUpdateCallback(exerciseCallback)
    }

    private fun prepareExercise() {
        Log.d(tag, "Preparing sensor warm-up")
        exerciseClient.prepareExerciseAsync(warmUpConfig)
    }

    private fun startExercise() {
        lifecycleScope.launch {
            try {
                val info = exerciseClient.getCurrentExerciseInfoAsync().await()
                if (info.exerciseTrackedStatus != OWNED_EXERCISE_IN_PROGRESS) {
                    Log.d(tag, "Starting Health Services exercise")
                    startForegroundWithNotification()
                    exerciseClient.startExerciseAsync(exerciseConfig)
                }
            } catch (e: Exception) {
                Log.e(tag, "Error checking status before starting exercise", e)
            }
        }
    }

    private fun pauseExercise() {
        exerciseClient.pauseExerciseAsync()
    }

    private fun endExerciseAndStopService() {
        lifecycleScope.launch {
            Log.d(tag, "Ending exercise and stopping service")
            try {
                val info = exerciseClient.getCurrentExerciseInfoAsync().await()
                if (info.exerciseTrackedStatus == OWNED_EXERCISE_IN_PROGRESS) {
                    exerciseClient.endExercise()
                } else {
                    repository.forceReset()
                }
                exerciseClient.clearUpdateCallback(exerciseCallback)
            } catch (e: Exception) {
                Log.e(tag, "Error ending exercise", e)
                repository.forceReset() 
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startEndingTimeout() {
        endingTimeoutJob?.cancel()
        endingTimeoutJob = lifecycleScope.launch {
            delay(5000) 
            if (repository.serviceState.value == BpmServiceState.ENDING) {
                Log.w(tag, "Ending timeout reached! Force resetting repository.")
                repository.forceReset()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
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
