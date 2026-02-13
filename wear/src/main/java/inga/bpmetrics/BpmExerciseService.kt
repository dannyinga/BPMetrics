package inga.bpmetrics

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BpmExerciseService: LifecycleService() {
    private val tag = "BPMetrics Exercise Service"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val healthClient by lazy { HealthServices.getClient(this) }
    private val exerciseClient by lazy { healthClient.exerciseClient }
    private val repository = BPMetricsRepository.instance
    private lateinit var notificationManager: NotificationManager

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
        override fun onAvailabilityChanged(
            dataType: DataType<*, *>,
            availability: Availability
        ) {
            repository.onExerciseAvailabilityChanged(availability)

        }


        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            repository.onExerciseUpdate(update)

        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {

        }

        override fun onRegistered() {

        }

        override fun onRegistrationFailed(throwable: Throwable) {

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
        lifecycleScope.launch {
            val currentExerciseInfo = exerciseClient.getCurrentExerciseInfoAsync().await()

            when (currentExerciseInfo.exerciseTrackedStatus) {
                OTHER_APP_IN_PROGRESS -> {}// Warn user before continuing, will stop the existing workout.
                OWNED_EXERCISE_IN_PROGRESS -> {
                    if (repository.serviceState.value != BpmServiceState.RECORDING) {
                        Log.d(tag, "Exercise already in progress, but not recording")
                        endExercise()
                    }
                }
                NO_EXERCISE_IN_PROGRESS -> {}
            }

            repository.serviceState.collect { state ->
                when (state) {
                    BpmServiceState.ASLEEP -> prepareExercise()
                    BpmServiceState.RECORDING -> startExercise()
                    BpmServiceState.ENDING -> endExercise()
                    else -> {}
                }
            }
        }
    }

    override fun onDestroy() {
        scope.launch {
            endExercise()
            clearExerciseCallback()
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        startForegroundWithNotification()
        setExerciseCallback()
        Log.d(tag, "Service started")

        return START_STICKY
    }


    private fun startForegroundWithNotification() {
        val titleText = "BPMetrics Service"
        val channelId = "bpm_service_channel"
        val notificationId = 1

        val channel = NotificationChannel(
            channelId,
            titleText,
            NotificationManager.IMPORTANCE_DEFAULT
        )

        notificationManager.createNotificationChannel(channel)

        val launchActivityIntent = Intent(this, BpmActivity::class.java)

        val activityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchActivityIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(titleText)
            .setContentText("Listening to heart rate…")
            .setSmallIcon(R.drawable.ic_media_play)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val ongoingActivityStatus = Status.Builder()
            // Sets the text used across various surfaces.
            .addTemplate(titleText)
            .build()

        val ongoingActivity =
            OngoingActivity.Builder(applicationContext, notificationId, notificationBuilder)
                .setStaticIcon(R.drawable.ic_media_play)
                .setTouchIntent(activityPendingIntent)
                .setStatus(ongoingActivityStatus)
                .build()

        ongoingActivity.apply(applicationContext)

        startForeground(notificationId, notificationBuilder.build())

        Log.d(tag, "Foreground Service with Notification started")
    }

    private fun setExerciseCallback() {
        exerciseClient.setUpdateCallback(exerciseCallback)
        Log.d(tag, "Exercise update callback set")
    }

    private suspend fun clearExerciseCallback() {
        exerciseClient.clearUpdateCallback(exerciseCallback)
        Log.d(tag, "Exercise update callback cleared")
    }

    private fun prepareExercise() {
        try {
            exerciseClient.prepareExerciseAsync(warmUpConfig)
            Log.d(tag, "Preparing exercise")
        } catch (e: Exception) {
            Log.e(tag, "Exercise prepare failed", e)
        }

    }

    private fun startExercise() {
        exerciseClient.startExerciseAsync(exerciseConfig)
    }

    private suspend fun endExercise() {
        try {
            exerciseClient.endExercise()
        } catch (_: Exception) {
            Log.e(tag, "Exercise end failed")
        }
    }

}