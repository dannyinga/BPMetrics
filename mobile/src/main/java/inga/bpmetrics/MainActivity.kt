package inga.bpmetrics

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import inga.bpmetrics.ui.util.StringFormatHelpers
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import inga.bpmetrics.export.BpmExportService
import inga.bpmetrics.ui.BPMetricsNavHost
import inga.bpmetrics.ui.theme.BPMetricsTheme

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
        // Install the splash screen before calling super.onCreate()
        installSplashScreen()
        
        super.onCreate(savedInstanceState)

        val app = application as BPMetricsApp

        // Before anything else touches the library. Every line below opens it one way or another —
        // the watch listener, the render queue, the nav host — and a file that will not open used
        // to take the process down right here, on the main thread, with nothing on screen to say
        // why and no way to reach the backups sitting on the same phone.
        if (!app.openLibrary()) {
            val failure = app.databaseFailure ?: IllegalStateException("The library did not open")
            enableEdgeToEdge()
            setContent {
                BPMetricsTheme(dynamicColor = false) {
                    inga.bpmetrics.ui.recovery.RecoveryScreen(this, failure)
                }
            }
            return
        }

        // Register the data client listener to the activity's lifecycle
        lifecycle.addObserver(dataClientListener)

        // Pick up renders left queued by a process the phone killed. Here rather than in the
        // application object because a foreground service cannot be started from the background on
        // Android 12 and later, and an activity being created is the one moment we are certainly
        // not in it. Restoring the queue is separate and has already happened by now; this only
        // starts the service, and does nothing if there is nothing waiting.
        runCatching { BpmExportService.resumeQueue(this) }
            .onFailure { android.util.Log.e("MainActivity", "Could not resume the render queue", it) }
        
        // Set up the modern Android edge-to-edge UI
        enableEdgeToEdge()
        
        val settings = (application as BPMetricsApp).settingsRepository

        setContent {
            // Collected at the root, because a theme is not something one screen has.
            //
            // No light mode and no theme choice: the charts, the export panel and the metric ramp
            // are all designed against dark, and a half-supported light theme is worse than none.
            val dynamicColour by settings.dynamicColour.collectAsState(initial = false)

            // Date and time formats are pushed into the formatter rather than passed down: they
            // are read by renderers and helpers that have no business knowing about DataStore.
            val use24Hour by settings.use24Hour.collectAsState(initial = false)
            val datePattern by settings.dateFormat
                .collectAsState(initial = inga.bpmetrics.ui.settings.DateFormats.DEFAULT)
            LaunchedEffect(use24Hour, datePattern) {
                StringFormatHelpers.use24Hour = use24Hour
                StringFormatHelpers.datePattern = datePattern
            }

            BPMetricsTheme(dynamicColor = dynamicColour) {
                BPMetricsNavHost(libraryRepository)
            }
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
