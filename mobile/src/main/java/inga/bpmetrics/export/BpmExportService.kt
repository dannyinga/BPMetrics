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
    private var exportJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    companion object {
        private const val CHANNEL_ID = "export_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "inga.bpmetrics.ACTION_STOP_EXPORT"

        val isExporting = MutableStateFlow(false)
        val exportProgress = MutableStateFlow(0f)
        val finishedFile = MutableStateFlow<File?>(null)

        fun startExport(context: Context, recordId: Long, config: VideoExporter.VideoExportConfig, targetUri: Uri?) {
            finishedFile.value = null
            val intent = Intent(context, BpmExportService::class.java).apply {
                putExtra("record_id", recordId)
                putExtra("overlay_uri", config.overlayVideoUri)
                putExtra("target_uri", targetUri)
                val configJson = Gson().toJson(config.copy(overlayVideoUri = null))
                putExtra("config_json", configJson)
            }
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancelCurrentExport()
            return START_NOT_STICKY
        }

        val recordId = intent?.getLongExtra("record_id", -1L) ?: -1L
        val configJson = intent?.getStringExtra("config_json")
        val overlayUri = intent?.getParcelableExtra<Uri>("overlay_uri")
        val targetUri = intent?.getParcelableExtra<Uri>("target_uri")

        if (recordId != -1L && configJson != null) {
            val repository = (application as BPMetricsApp).libraryRepository
            val baseConfig = Gson().fromJson(configJson, VideoExporter.VideoExportConfig::class.java)
            val config = baseConfig.copy(overlayVideoUri = overlayUri)

            startForeground(NOTIFICATION_ID, createNotification(0f))
            isExporting.value = true
            exportProgress.value = 0f

            exportJob = serviceScope.launch {
                try {
                    val record = repository.getRecordWithId(recordId)
                    val file =
                        VideoExporter.exportVideo(this@BpmExportService, record, config) { progress ->
                        exportProgress.value = progress
                        notificationManager.notify(NOTIFICATION_ID, createNotification(progress))
                    }

                    if (!file.exists()) {
                        throw IOException("Exported file does not exist: ${file.absolutePath}")
                    }

                    if (targetUri != null) {
                        contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                            file.inputStream().use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                    } else {
                        ExportUtils.shareFile(this@BpmExportService, file, "video/mp4")
                    }

                    showCompletionNotification(true)
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Don't show failure notification if it was explicitly cancelled
                    if (exportJob?.isCancelled != true) {
                        showCompletionNotification(false)
                    }
                } finally {
                    isExporting.value = false
                    exportProgress.value = 0f
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun cancelCurrentExport() {
        exportJob?.cancel()
        isExporting.value = false
        exportProgress.value = 0f
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(CHANNEL_ID, "Video Export", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(progress: Float): Notification {
        val stopIntent = Intent(this, BpmExportService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val contentIntent = Intent(this, MainActivity::class.java)
        val pendingContentIntent = PendingIntent.getActivity(this, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Exporting Video")
            .setContentText("${(progress * 100).toInt()}% complete")
            .setSmallIcon(R.drawable.stat_sys_download)
            .setProgress(100, (progress * 100).toInt(), false)
            .setOngoing(true)
            .setContentIntent(pendingContentIntent)
            .addAction(R.drawable.ic_menu_close_clear_cancel, "Cancel", stopPendingIntent)
            .build()
    }

    private fun showCompletionNotification(success: Boolean) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (success) "Export Complete" else "Export Failed")
            .setContentText(if (success) "Your BPM video is ready." else "There was an error during encoding.")
            .setSmallIcon(if (success) R.drawable.stat_sys_download_done else R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}