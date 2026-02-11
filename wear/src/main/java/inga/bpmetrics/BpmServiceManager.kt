package inga.bpmetrics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The controller layer in the watch MVC architecture. The view tells the controller to start/stop
 * recording. The controller communicates with the factory to start/stop, updates the UI with the
 * live BPM, and sends the completed record to the paired device.
 */
class BpmServiceManager (private val context: Context) {
    private val tag = "BpmServiceManager"
    private var service: BpmExerciseService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as BpmExerciseService.LocalBinder
            service = localBinder.getService()
            bound = true
            Log.d(tag, "Service connected and bound")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            Log.d(tag, "Service disconnected")
        }
    }

    fun startAndBind() {
        Log.d(tag, "Attempting to start and bind service")
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
    }
}