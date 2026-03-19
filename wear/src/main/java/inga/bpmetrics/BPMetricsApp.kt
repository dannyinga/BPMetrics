package inga.bpmetrics

import android.app.Application
import android.util.Log
import inga.bpmetrics.recording.RecordingRepository
import inga.bpmetrics.health.HealthServiceManager
import inga.bpmetrics.sync.PhoneSyncManager

/**
 * Custom Application class for BPMetrics.
 * 
 * Initializes global managers and ensures the [inga.bpmetrics.recording.RecordingRepository] is
 * available with the application context.
 */
class BPMetricsApp: Application() {

    private val tag = "BpmApplication"
    
    lateinit var serviceManager: HealthServiceManager
        private set
    lateinit var syncManager: PhoneSyncManager
        private set

    override fun onCreate() {
        super.onCreate()
        
        // Initialize the singleton repository with application context
        RecordingRepository.getInstance(this)
        
        serviceManager = HealthServiceManager(this)
        syncManager = PhoneSyncManager(this)
        
        Log.d(tag, "Application created")
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.d(tag, "Application Terminated")
    }
}
