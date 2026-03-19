package inga.bpmetrics.health

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import inga.bpmetrics.recording.RecordingRepository
import inga.bpmetrics.recording.RecordingState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The controller layer in the watch MVC architecture.
 * This manager coordinates the lifecycle of the [HealthService], ensuring it's only started
 * and bound after all prerequisites (like permissions) are met.
 */
class HealthServiceManager (private val context: Context) : DefaultLifecycleObserver {
    private val tag = "HealthServiceManager"
    private val repository = RecordingRepository.Companion.getInstance(context)
    private var service: HealthService? = null
    private var bound = false
    private var syncJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as HealthService.LocalBinder
            service = localBinder.getService()
            bound = true
            Log.d(tag, "Exercise service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            Log.d(tag, "Service disconnected")
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        // Observe the repository's prerequisite flag to start the service only when ready
        syncJob = owner.lifecycleScope.launch {
            repository.hasAllPrerequisites.collect { ready ->
                if (ready) {
                    Log.d(tag, "Prerequisites met. Starting service.")
                    startAndBindIfNeeded()
                }
            }
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        // Ensure service is running if we return to the app and permissions are already granted
        if (repository.hasAllPrerequisites.value) {
            startAndBindIfNeeded()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        // Clean up the service if we're not actively recording
        if (repository.recordingState.value != RecordingState.RECORDING) {
            unbindAndStop()
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        syncJob?.cancel()
        super.onDestroy(owner)
    }

    private fun startAndBindIfNeeded() {
        if (!bound) startAndBind()
    }

    private fun startAndBind() {
        Log.d(tag, "startAndBind called")
        val intent = Intent(context, HealthService::class.java)
        try {
            ContextCompat.startForegroundService(context, intent)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(tag, "Failed to start or bind service", e)
        }
    }

    fun unbindAndStop() {
        if (bound) {
            context.unbindService(connection)
            bound = false
        }

        context.stopService(Intent(context, HealthService::class.java))
        Log.d(tag, "Service unbound and stopped")
    }
}