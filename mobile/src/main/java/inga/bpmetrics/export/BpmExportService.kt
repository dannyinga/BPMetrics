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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

class BpmExportService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var queueJob: Job? = null
    private var exportJob: Job? = null
    private var currentRunningJobId: String? = null
    private lateinit var notificationManager: NotificationManager

    companion object {
        private const val CHANNEL_ID = "export_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "inga.bpmetrics.ACTION_STOP_EXPORT"

        val isExporting = MutableStateFlow(false)
        val exportProgress = MutableStateFlow(0f)
        val finishedFile = MutableStateFlow<File?>(null)

        fun startExport(context: Context, recordId: Long, recordTitle: String, config: VideoExporter.VideoExportConfig, targetUri: Uri?) {
            finishedFile.value = null
            
            // Add job to render queue manager
            RenderQueueManager.addJob(recordId, recordTitle, config, targetUri)

            val intent = Intent(context, BpmExportService::class.java)
            context.startForegroundService(intent)
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

        startForeground(NOTIFICATION_ID, createNotification("Queue processing started", 0f))
        
        // Ensure the queue processing loop is running
        if (queueJob == null || queueJob?.isCompleted == true) {
            isExporting.value = true
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
            exportProgress.value = 0f

            // Create a child coroutine for this specific export job
            val job = serviceScope.launch {
                try {
                    val record = repository.getRecordWithId(nextJob.recordId)

                    // Update notification with title
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        createNotification("Rendering: ${nextJob.recordTitle}", 0f)
                    )

                    var lastNotificationTime = System.currentTimeMillis()
                    val file = VideoExporter.exportVideo(this@BpmExportService, record, nextJob.config) { progress ->
                        RenderQueueManager.updateJobProgress(nextJob.id, progress)
                        exportProgress.value = progress
                        
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
                        ExportUtils.saveVideoToGallery(this@BpmExportService, file, nextJob.recordTitle)
                    }

                    RenderQueueManager.updateJobStatus(nextJob.id, RenderStatus.COMPLETED, targetUri = finalUri)
                    showCompletionNotification(nextJob.recordTitle, true, finalUri)
                } catch (e: Exception) {
                    e.printStackTrace()
                    val wasCancelled = coroutineContext[Job]?.isCancelled == true
                    val status = if (wasCancelled) RenderStatus.CANCELLED else RenderStatus.FAILED
                    RenderQueueManager.updateJobStatus(nextJob.id, status, error = e.message ?: e.toString())

                    if (!wasCancelled) {
                        showCompletionNotification(nextJob.recordTitle, false)
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

        isExporting.value = false
        exportProgress.value = 0f
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
        val channel =
            NotificationChannel(CHANNEL_ID, "Video Export", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(contentTitle: String, progress: Float): Notification {
        val stopIntent = Intent(this, BpmExportService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val contentIntent = Intent(this, MainActivity::class.java)
        val pendingContentIntent = PendingIntent.getActivity(this, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText("${(progress * 100).toInt()}% complete")
            .setSmallIcon(R.drawable.stat_sys_download)
            .setProgress(100, (progress * 100).toInt(), false)
            .setOngoing(true)
            .setContentIntent(pendingContentIntent)
            .addAction(R.drawable.ic_menu_close_clear_cancel, "Cancel", stopPendingIntent)
            .build()
    }

    private fun showCompletionNotification(title: String, success: Boolean, videoUri: Uri? = null) {
        val contentIntent = if (success && videoUri != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(videoUri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (success) "Export Complete" else "Export Failed")
            .setContentText(if (success) "Saved to Movies/BPMetrics. Tap to play video." else "There was an error encoding '$title'.")
            .setSmallIcon(if (success) R.drawable.stat_sys_download_done else R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .apply {
                if (contentIntent != null) setContentIntent(contentIntent)
            }
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