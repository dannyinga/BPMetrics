package inga.bpmetrics

import android.app.Application
import android.util.Log

class BpmApp: Application() {

    private val tag = "BpmApplication"
    lateinit var serviceManager: BpmServiceManager
        private set
    lateinit var syncManager: BpmSyncManager
        private set

    lateinit var spotMeasureManager: BpmSpotMeasureManager
        private set

    override fun onCreate() {
        super.onCreate()
        serviceManager = BpmServiceManager(this)
        serviceManager.unbindAndStop()
        syncManager = BpmSyncManager(this)
        spotMeasureManager = BpmSpotMeasureManager(this)
        Log.d(tag, "Application created")
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.d(tag, "Application Terminated")
    }
}