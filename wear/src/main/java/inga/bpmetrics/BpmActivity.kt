/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package inga.bpmetrics

import android.R
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * The Watch BpmActivity and UI. Handles flow between application start, permissions, and the
 * recording screen. Contains logic to allow the user to communicate with the controller.
 */
class BpmActivity : ComponentActivity() {
    private val tag = "BpmActivity"
    //TODO: Allow HealthServices to continue recording even when application isn't in focus
    private val serviceManager by lazy {
        (application as BpmApp).serviceManager
    }
    private val spotMeasureManager by lazy {
        (application as BpmApp).spotMeasureManager
    }

    private val syncManager by lazy {
        (application as BpmApp).syncManager
    }
    private val permissionsViewModel by lazy {
        PermissionsViewModel(applicationContext)
    }
    private val recordingViewModel by lazy {
        RecordingViewModel(spotMeasureManager, serviceManager, syncManager)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setTheme(R.style.Theme_DeviceDefault)
        setContent {
            BpmNavHost()
        }
        Log.d(tag, "Activity Created")
    }

    override fun onStop() {
        super.onStop()
        recordingViewModel.onStop()
        Log.d(tag, "Activity Stopped")
    }

    override fun onResume() {
        super.onResume()
        recordingViewModel.onResume()
        Log.d(tag, "Activity Resumed")
    }

    override fun onDestroy() {
        super.onDestroy()
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