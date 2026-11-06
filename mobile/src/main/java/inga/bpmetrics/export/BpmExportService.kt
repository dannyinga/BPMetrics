package inga.bpmetrics.export

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import inga.bpmetrics.BPMetricsApp
import inga.bpmetrics.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

class BpmExportService : Service() {

    // Through [backgroundScope]: a render that throws in a way nothing anticipated should fail the
    // job and leave the queue running, not take the app down mid-export.
    private val serviceScope = inga.bpmetrics.util.backgroundScope("BpmExportService", Dispatchers.Default)
    private var queueJob: Job? = null
    private var exportJob: Job? = null
    private var currentRunningJobId: String? = null
    private lateinit var notificationManager: NotificationManager

    companion object {
        /** Progress: quiet, since it updates every second. */
        private const val CHANNEL_ID = "export_channel"

        /**
         * Finished and failed renders. Its own channel so these can make a sound: on the quiet
         * progress channel a failure arrived without a sound and was easy to miss.
         */
        private const val RESULTS_CHANNEL_ID = "export_results"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "inga.bpmetrics.ACTION_STOP_EXPORT"


        fun startExport(
            context: Context,
            recordId: Long,
            recordTitle: String,
            request: ExportRequest.Video,
            targetUri: Uri?,
            /** How it looks and where it came from, so a queue of six reads as six different jobs. */
            presetName: String? = null,
            sourceLabel: String? = null
        ) {
            RenderQueueManager.addJob(
                recordId = recordId,
                recordTitle = recordTitle,
                request = request,
                targetUri = targetUri,
                presetName = presetName,
                sourceLabel = sourceLabel
            )

            val intent = Intent(context, BpmExportService::class.java)
            context.startForegroundService(intent)
        }

        /** Starts the service so it picks up whatever is already queued, without adding anything. */
        fun resumeQueue(context: Context) {
            if (RenderQueueManager.getNextJob() == null) return
            context.startForegroundService(Intent(context, BpmExportService::class.java))
        }

        fun stopExport(context: Context) {
            val intent = Intent(context, BpmExportService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        RenderQueueManager.onJobCancelled = { jobId: String ->
            synchronized(this) {
                if (jobId == currentRunningJobId) {
                    exportJob?.cancel()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancelCurrentExport()
            return START_NOT_STICKY
        }

        // Media processing where the platform has the type (Android 15); Android 14 has no type
        // for it, so the render runs as data sync there (Play #14).
        val serviceType = when {
            android.os.Build.VERSION.SDK_INT >= 35 ->
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q ->
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        androidx.core.app.ServiceCompat.startForeground(
            this, NOTIFICATION_ID, createNotification("Starting renders", 0f), serviceType
        )
        
        // Ensure the queue processing loop is running
        if (queueJob == null || queueJob?.isCompleted == true) {
            queueJob = serviceScope.launch {
                processQueue()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun processQueue() {
        val repository = (application as BPMetricsApp).libraryRepository

        while (true) {
            val nextJob = RenderQueueManager.getNextJob() ?: break

            synchronized(this) {
                currentRunningJobId = nextJob.id
            }
            RenderQueueManager.updateJobStatus(nextJob.id, RenderStatus.RENDERING)

            // Create a child coroutine for this specific export job
            val job = serviceScope.launch {
                try {
                    // A job restored from disk carries record ids, not record data — the library is
                    // the authority on what someone's heart rate did, and the queue should not hold
                    // a second copy that drifts from it. Recordings deleted since it was queued
                    // fail the job rather than rendering whatever is left.
                    // Readings for the queued recordings only. The library stream no longer
                    // carries them, and a render is exactly the case that needs them — but only
                    // for what this job names.
                    val queued = repository.recordsWithPoints(nextJob.recordIds)
                    val hydrated = RenderJobStore.rehydrate(nextJob, queued)
                        ?: throw IOException(
                            "The recordings this export was queued for are no longer in the library"
                        )
                    // The recording the single-curve path draws comes from the request too. It was
                    // fetched again here by id, a second read of something the request already had.

                    // Update notification with title
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        createNotification("Rendering: ${nextJob.recordTitle}", 0f)
                    )

                    var lastNotificationTime = System.currentTimeMillis()
                    val file = VideoExporter.exportVideo(this@BpmExportService, hydrated.request) { progress ->
                        RenderQueueManager.updateJobProgress(nextJob.id, progress)
                        
                        val currentTime = System.currentTimeMillis()
                        if (progress == 0f || progress >= 1f || currentTime - lastNotificationTime >= 1000L) {
                            lastNotificationTime = currentTime
                            notificationManager.notify(
                                NOTIFICATION_ID,
                                createNotification("Rendering: ${nextJob.recordTitle}", progress)
                            )
                        }
                    }

                    if (!file.exists()) {
                        throw IOException("Exported file does not exist: ${file.absolutePath}")
                    }

                    val targetUri = nextJob.targetUri
                    val finalUri: Uri? = if (targetUri != null) {
                        contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                            file.inputStream().use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        targetUri
                    } else {
                        MediaStoreSaver.saveVideo(this@BpmExportService, file, nextJob.recordTitle)
                    }

                    // The render is staged in the cache and then copied to wherever it belongs.
                    // Nothing used to remove that staging copy, so every video ever exported was
                    // still on the phone at full size — which is what filled it.
                    ExportUtils.discardStagedExport(file)

                    RenderQueueManager.updateJobStatus(nextJob.id, RenderStatus.COMPLETED, targetUri = finalUri)
                    showSavedNotification(nextJob.recordTitle, finalUri)
                } catch (e: Exception) {
                    e.printStackTrace()
                    val wasCancelled = coroutineContext[Job]?.isCancelled == true
                    val status = if (wasCancelled) RenderStatus.CANCELLED else RenderStatus.FAILED
                    val reason = RenderFailure.describe(e)
                    RenderQueueManager.updateJobStatus(nextJob.id, status, error = if (wasCancelled) null else reason)

                    if (!wasCancelled) {
                        showFailedNotification(nextJob.recordTitle, reason)
                    }
                }
            }

            exportJob = job
            job.join()

            synchronized(this) {
                currentRunningJobId = null
                exportJob = null
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cancelCurrentExport() {
        synchronized(this) {
            currentRunningJobId?.let { jobId ->
                RenderQueueManager.cancelJob(jobId)
            }
        }
        exportJob?.cancel()
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Render progress", NotificationManager.IMPORTANCE_LOW)
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(RESULTS_CHANNEL_ID, "Finished and failed renders", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /** Opens the app on the render queue. Every render notification's tap goes there. */
    private fun openQueueIntent(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        this,
        requestCode,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_RENDER_QUEUE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun createNotification(contentTitle: String, progress: Float): Notification {
        val stopIntent = Intent(this, BpmExportService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText("${(progress * 100).toInt()}% done")
            .setSmallIcon(R.drawable.stat_sys_download)
            .setProgress(100, (progress * 100).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openQueueIntent(0))
            .addAction(R.drawable.ic_menu_close_clear_cancel, "Cancel", stopPendingIntent)
            .build()
    }

    private fun showSavedNotification(title: String, videoUri: Uri?) {
        val where = videoUri?.let { ExportUtils.describeLocation(this, it) } ?: "Movies/BPMetrics"
        val builder = NotificationCompat.Builder(this, RESULTS_CHANNEL_ID)
            .setContentTitle("Video saved: $title")
            .setContentText("Saved to $where")
            .setSmallIcon(R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(openQueueIntent(1))
        if (videoUri != null) {
            val play = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(videoUri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            builder.addAction(
                R.drawable.ic_media_play, "Play",
                PendingIntent.getActivity(this, 2, play, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
        }
        notificationManager.notify(NOTIFICATION_ID + 1 + System.currentTimeMillis().toInt(), builder.build())
    }

    /** A failed render, with its reason. It used to say only "There was an error encoding". */
    private fun showFailedNotification(title: String, reason: String) {
        val notification = NotificationCompat.Builder(this, RESULTS_CHANNEL_ID)
            .setContentTitle("Render failed: $title")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setSmallIcon(R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentIntent(openQueueIntent(3))
            .build()
        notificationManager.notify(NOTIFICATION_ID + 1 + System.currentTimeMillis().toInt(), notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        RenderQueueManager.onJobCancelled = null
        serviceScope.cancel()
    }
}
