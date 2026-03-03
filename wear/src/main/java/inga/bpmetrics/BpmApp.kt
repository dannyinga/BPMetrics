package inga.bpmetrics

import android.app.Application
import android.util.Log

/**
 * Custom Application class for BPMetrics.
 * 
 * Initializes global managers and ensures the [BPMetricsRepository] is 
 * available with the application context.
 */
class BpmApp: Application() {

    private val tag = "BpmApplication"
    
    lateinit var serviceManager: BpmExerciseServiceManager
        private set
    lateinit var syncManager: BpmPhoneSyncManager
        private set

    override fun onCreate() {
        super.onCreate()
        
        // Initialize the singleton repository with application context
        BPMetricsRepository.getInstance(this)
        
        serviceManager = BpmExerciseServiceManager(this)
        syncManager = BpmPhoneSyncManager(this)
        
        Log.d(tag, "Application created")
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.d(tag, "Application Terminated")
    }
}
