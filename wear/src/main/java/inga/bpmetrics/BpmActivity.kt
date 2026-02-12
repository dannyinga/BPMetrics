/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package inga.bpmetrics

import android.R
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    private val syncManager by lazy {
        (application as BpmApp).syncManager
    }
    private val permissionsViewModel by lazy {
        PermissionsViewModel(applicationContext)
    }
    private val recordingViewModel by lazy {
        RecordingViewModel()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(tag, "Activity creating")
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_DeviceDefault)

        addLifecycleObservers()
        setWindowFlags()

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
        lifecycle.addObserver(syncManager)
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
                        navController.navigate(Screens.Recording.route) {
                            popUpTo(Screens.Permissions.route) { inclusive = true }
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