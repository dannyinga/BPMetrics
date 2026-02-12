package inga.bpmetrics

import android.app.Application
import android.util.Log

class BpmApp: Application() {

    private val tag = "BpmApplication"
    lateinit var serviceManager: BpmExerciseServiceManager
        private set
    lateinit var syncManager: BpmPhoneSyncManager
        private set

    override fun onCreate() {
        super.onCreate()
        serviceManager = BpmExerciseServiceManager(this)
        syncManager = BpmPhoneSyncManager(this)
        Log.d(tag, "Application created")
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.d(tag, "Application Terminated")
    }
}