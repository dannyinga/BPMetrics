/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package inga.bpmetrics

import android.R.style.Theme_DeviceDefault
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch

/**
 * The Watch BpmActivity and UI. Handles flow between application start, permissions, and the
 * recording screen. Contains logic to allow the user to communicate with the controller.
 */
class BpmActivity : ComponentActivity() {
    private val tag = "BpmActivity"
    private val serviceManager by lazy {
        (application as BpmApp).serviceManager
    }
    private val permissionsViewModel by lazy {
        PermissionsViewModel(applicationContext)
    }
    private val recordingViewModel by lazy {
        RecordingViewModel()
    }

    private val exerciseCapabilitiesViewModel by lazy {
        ExerciseCapabilitiesViewModel(applicationContext)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(tag, "Activity creating")
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(Theme_DeviceDefault)

        setContent {
            BpmNavHost()
        }
    }

    override fun onStop() {
        Log.d(tag, "Activity stopping")
        super.onStop()
    }

    override fun onResume() {
        Log.d(tag, "Activity resuming")
        super.onResume()
    }

    override fun onDestroy() {
        Log.d(tag, "Activity destroying")
        super.onDestroy()
    }

    private fun addLifecycleObservers() {
        lifecycle.addObserver(serviceManager)
    }

    private fun setWindowFlags() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(tag, "Turned on Keep Screen On")

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                BPMetricsRepository.instance.serviceState.collect { state ->
                    when (state) {
                        BpmServiceState.RECORDING -> {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            Log.d(tag, "Turned off Keep Screen On")
                        }

                        BpmServiceState.PREPARING -> {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            Log.d(tag, "Turned on Keep Screen On")
                        }
                        else -> { }
                    }
                }
            }

        }
    }


    @Composable
    fun BpmNavHost() {
        val navController = rememberNavController()

        NavHost(
            navController,
            startDestination = Screens.Permissions.route
        ) {
            composable(Screens.Permissions.route){
                PermissionsScreen(
                    permissionsViewModel,
                    onReady = {
                        navController.navigate(Screens.ExerciseCapabilities.route) {
                            popUpTo(Screens.Permissions.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screens.ExerciseCapabilities.route){
                ExerciseCapabilitiesScreen(
                    exerciseCapabilitiesViewModel,
                    onReady = {
                        addLifecycleObservers()
                        setWindowFlags()
                        navController.navigate(Screens.Recording.route) {
                            popUpTo(Screens.ExerciseCapabilities.route) { inclusive = true }
                        }
                    }
                )
            }


            composable(Screens.Recording.route) {
                RecordingScreen(recordingViewModel)
            }
        }
    }
}