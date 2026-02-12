package inga.bpmetrics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * The controller layer in the watch MVC architecture. The view tells the controller to start/stop
 * recording. The controller communicates with the factory to start/stop, updates the UI with the
 * live BPM, and sends the completed record to the paired device.
 */
class BpmExerciseServiceManager (private val context: Context) : DefaultLifecycleObserver {
    private val tag = "BpmExerciseServiceManager"
    private val repository = BPMetricsRepository.instance
    private var service: BpmExerciseService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as BpmExerciseService.LocalBinder
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

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        startAndBindIfNeeded()
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        if (repository.serviceState.value != BpmServiceState.RECORDING) {
            unbindAndStop()
        }
    }

    private fun startAndBindIfNeeded() {
        if (!bound) startAndBind()
    }

    private fun startAndBind() {
        val intent = Intent(context, BpmExerciseService::class.java)
        ContextCompat.startForegroundService(context, intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbindAndStop() {
        if (bound) {
            context.unbindService(connection)
            bound = false
        }

        context.stopService(Intent(context, BpmExerciseService::class.java))
        Log.d(tag, "Service unbound and stopped")
    }
}
