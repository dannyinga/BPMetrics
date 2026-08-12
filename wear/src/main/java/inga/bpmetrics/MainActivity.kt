package inga.bpmetrics

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import inga.bpmetrics.ui.LoadingScreen
import inga.bpmetrics.ui.WatchRecoveryScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val syncManager by lazy {
        (application as BPMetricsApp).syncManager
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
                        // Only the warm-up states hold the screen on, and only because the user is
                        // waiting on them. INACTIVE is not one of them: it is the resting state and
                        // the fallback for "nothing reported yet", so including it kept the display
                        // lit the entire time the app sat idle.
                        state == RecordingState.PREPARING || state == RecordingState.ACQUIRING
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

        // Before anything else can be reached. Room opens lazily, so a store that will not open
        // fails on the first *query* — inside a coroutine scope with an exception handler, where it
        // was logged and swallowed. The app came up looking healthy, listed no pending recordings,
        // and would have run a whole session storing none of it. Checked here, off the main thread,
        // and the recording screen is simply not reachable while it is broken.
        var probe by remember {
            mutableStateOf<Result<inga.bpmetrics.db.DbFailure?>?>(null)
        }
        LaunchedEffect(Unit) {
            probe = runCatching {
                withContext(Dispatchers.IO) {
                    inga.bpmetrics.db.RecordingDB.probe(applicationContext)
                }
            }
        }

        val outcome = probe
        if (outcome == null) {
            LoadingScreen(label = "Checking recordings...")
            return
        }

        // A probe that itself blew up is treated as a broken store rather than waved through: the
        // point of the check is that nothing downstream should assume the store works.
        //
        // `fold`, not `getOrNull() ?: ...` — this is a `Result<DbFailure?>`, so `getOrNull()`
        // answers null both when the probe threw *and* when it succeeded with nothing wrong, and
        // an elvis on it would have shown this screen on every healthy launch.
        val failure = outcome.fold(
            onSuccess = { it },
            onFailure = {
                inga.bpmetrics.db.DbFailure.Unreadable(
                    it.message ?: "The recording store could not be checked"
                )
            }
        )
        if (failure != null) {
            WatchRecoveryScreen(
                failure = failure,
                onClearStore = {
                    // Deleted, then the process ends. Room holds an instance pointing at a file
                    // that no longer exists, and there is no supported way to make it forget —
                    // restarting is the honest way to come back with a clean one.
                    applicationContext.deleteDatabase(inga.bpmetrics.db.RecordingDB.DB_NAME)
                    finishAffinity()
                    Runtime.getRuntime().exit(0)
                }
            )
            return
        }

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
                            return RecordingViewModel(
                                repository = repository,
                                syncManager = syncManager
                            ) as T
                        }
                    }
                )
                RecordingScreen(recordingViewModel)
            }
        }
    }
}
