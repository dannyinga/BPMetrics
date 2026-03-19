package inga.bpmetrics

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import inga.bpmetrics.recording.RecordingRepository
import inga.bpmetrics.recording.RecordingState
import inga.bpmetrics.ui.ExerciseCapabilitiesScreen
import inga.bpmetrics.ui.ExerciseCapabilitiesViewModel
import inga.bpmetrics.ui.PermissionsScreen
import inga.bpmetrics.ui.PermissionsViewModel
import inga.bpmetrics.ui.RecordingScreen
import inga.bpmetrics.ui.RecordingViewModel
import inga.bpmetrics.ui.Screens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The main [ComponentActivity] for the watch app.
 *
 * Handles navigation between permissions, exercise capabilities, and the recording screen.
 * It also manages window flags (like keeping the screen on) based on the current recording state.
 */
class MainActivity : ComponentActivity() {
    private val tag = "MainActivity"

    private val serviceManager by lazy {
        (application as BPMetricsApp).serviceManager
    }

    private val repository by lazy {
        RecordingRepository.getInstance(applicationContext)
    }

    private val permissionsViewModel by lazy {
        PermissionsViewModel(applicationContext)
    }

    private val exerciseCapabilitiesViewModel by lazy {
        ExerciseCapabilitiesViewModel(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(tag, "Activity creating")
        installSplashScreen()
        super.onCreate(savedInstanceState)
        
        // Apply the device default theme for Wear OS
        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
            BpmNavHost()
        }
        
        // Observe lifecycle to manage window flags and service connection
        addLifecycleObservers()
        setWindowFlags()
    }

    /**
     * Connects the activity's lifecycle to the [inga.bpmetrics.health.HealthServiceManager].
     */
    private fun addLifecycleObservers() {
        lifecycle.addObserver(serviceManager)
    }

    /**
     * Observes the repository's service state to manage screen wake lock flags.
     * Keeps the screen awake during the "Acquisition/Warm-up" phase.
     */
    private fun setWindowFlags() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.recordingState
                    .map { state ->
                        // Only "Warm Up" states trigger the screen-on flag
                        state == RecordingState.PREPARING
                                || state == RecordingState.ACQUIRING
                                || state == RecordingState.INACTIVE
                    }
                    .distinctUntilChanged() // Prevents redundant calls when switching between PREPARING and ACQUIRING
                    .collect { isWarmingUp ->
                        if (isWarmingUp) {
                            addKeepScreenOnFlag()
                        } else {
                            clearKeepScreenOnFlag()
                        }
                    }
            }
        }
    }

    private fun clearKeepScreenOnFlag() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(tag, "Disabled Keep Screen On")
    }

    private fun addKeepScreenOnFlag() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(tag, "Enabled Keep Screen On")
    }

    /**
     * Composable that manages navigation within the watch app.
     */
    @Composable
    fun BpmNavHost() {
        val navController = rememberNavController()

        NavHost(
            navController = navController,
            startDestination = Screens.Permissions.route
        ) {
            // Screen 1: Permissions
            composable(Screens.Permissions.route) {
                PermissionsScreen(
                    permissionsViewModel,
                    onReady = {
                        navController.navigate(Screens.ExerciseCapabilities.route) {
                            popUpTo(Screens.Permissions.route) { inclusive = true }
                        }
                    }
                )
            }

            // Screen 2: Exercise Capabilities Check
            composable(Screens.ExerciseCapabilities.route) {
                ExerciseCapabilitiesScreen(
                    exerciseCapabilitiesViewModel,
                    onReady = {
                        navController.navigate(Screens.Recording.route) {
                            popUpTo(Screens.ExerciseCapabilities.route) { inclusive = true }
                        }
                    }
                )
            }

            // Screen 3: Recording Control Screen
            composable(Screens.Recording.route) {
                // Initialize the RecordingViewModel with the repository instance
                val recordingViewModel: RecordingViewModel = viewModel(
                    factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return RecordingViewModel(repository) as T
                        }
                    }
                )
                RecordingScreen(recordingViewModel)
            }
        }
    }
}
