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
        // Clean up only when there is genuinely nothing to keep alive.
        //
        // This used to check `recordingState != RECORDING`, which let go of the service in two
        // situations where it was still needed: while a record was being written (ENDING), and in
        // the moments after the button was pressed but before that flow had caught up. On a watch
        // the screen turns off seconds after a press, so that second case was not a rare race —
        // it was the normal way to start a recording.
        if (!repository.sessionActive.value && !repository.isFinalizing) {
            unbindAndStop()
        } else {
            Log.d(tag, "Recording in progress; leaving the service running")
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

    /**
     * Releases the service, unless it is holding a recording.
     *
     * [Context.stopService] goes around [HealthService.onUnbind] entirely — the service is
     * destroyed whatever its own guard would have decided. So the guard has to be repeated here,
     * or unbinding at the wrong moment ends a recording the user never stopped.
     */
    fun unbindAndStop() {
        if (bound) {
            context.unbindService(connection)
            bound = false
        }

        if (repository.sessionActive.value || repository.isFinalizing) {
            Log.d(tag, "Unbound, but a recording is open; leaving the service running")
            return
        }

        context.stopService(Intent(context, HealthService::class.java))
        Log.d(tag, "Service unbound and stopped")
    }
}