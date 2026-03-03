package inga.bpmetrics

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import inga.bpmetrics.ui.LibraryNavHost

/**
 * Main activity for the BPMetrics mobile app.
 *
 * This activity serves as the entry point for the mobile application. It initializes
 * the UI navigation host and registers the [DataClientListener] with the activity's
 * lifecycle to start listening for record synchronization from the watch.
 * 
 * @property tag Log tag for identify activity-specific logs.
 * @property libraryRepository The repository responsible for BPM record storage and access.
 * @property dataClientListener The listener that manages data sync between the watch and mobile.
 */
class MainActivity : ComponentActivity() {

    private val tag = "BPMetrics Main Activity"

    /** Lazily initialized repository for managing BPM data. */
    private val libraryRepository by lazy { (application as BPMetricsApp).libraryRepository }

    /** Lazily initialized listener for Wearable data synchronization events. */
    private val dataClientListener by lazy { (application as BPMetricsApp).dataClientListener }

    /**
     * Initializes the activity, sets up the UI, and registers lifecycle observers.
     *
     * @param savedInstanceState If the activity is being re-initialized after
     * previously being shut down then this Bundle contains the data it most
     * recently supplied.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Register the data client listener to the activity's lifecycle
        lifecycle.addObserver(dataClientListener)
        
        // Set up the modern Android edge-to-edge UI
        enableEdgeToEdge()
        
        // Load the library screen with navigation
        setContent {
            LibraryNavHost(libraryRepository)
        }
    }

    /**
     * Cleans up resources when the activity is destroyed.
     */
    override fun onDestroy() {
        super.onDestroy()
        // Ensure the observer is removed to prevent leaks and unnecessary background processing
        lifecycle.removeObserver(dataClientListener)
    }
}
