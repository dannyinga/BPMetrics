/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package inga.bpmetrics.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/**
 * The Watch Activity and UI. Handles flow between application start, permissions, and the
 * recording screen. Contains logic to allow the user to communicate with the controller.
 */
class MainActivity : ComponentActivity() {
    //TODO: Allow HealthServices to continue recording even when application isn't in focus
    private lateinit var controller: BpmWatchController

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setTheme(android.R.style.Theme_DeviceDefault)
        controller = BpmWatchController(applicationContext)

        setContent {
            BpmApp()
        }
    }

    /**
     * Main App content setting. First displays the permissions screen and if permission is granted,
     * will then display the start/stop screen.
     */
    @Composable
    fun BpmApp() {
        PermissionScreen {
            // This block runs only if permission is granted
            setContent { BPMStartStopScreen() }
        }
    }

    /**
     * If user has previously granted permission, asking for permissions is skipped. Otherwise,
     * permission screen first displays a message to the user saying the app must have access to
     * the user's watch sensor data as well as a button to grant permission. If permission is granted,
     * then the function returns and the callback function is called.
     */
    @Composable
    fun PermissionScreen(
        onPermissionsGranted: () -> Unit
    ) {
        var hasPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.BODY_SENSORS
                ) == PackageManager.PERMISSION_GRANTED
            )
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            hasPermission = granted
            if (granted) {
                onPermissionsGranted()
            }
        }

        if (hasPermission) {
            // Permissions already granted
            onPermissionsGranted()
        } else {

            // Permissions UI
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    BoxWithConstraints (
                        modifier = Modifier.fillMaxWidth(0.6f),
                    ) {
                        val boxWidth : Dp = this.maxWidth
                        val fontSize = (boxWidth.value / 10).sp
                        Text(
                            text = "This app requires access to your heart rate sensor to record BPM.",
                            fontSize = fontSize,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        modifier = Modifier.fillMaxWidth(0.8f),
                        onClick = {
                            // Open the grant permissions dialog
                            permissionLauncher.launch(Manifest.permission.BODY_SENSORS)
                        }
                    ) {
                        Text("Grant Permission")
                    }
                }
            }
        }
    }


    /**
     * The start stop screen composed of a live BPM indicator and a start/stop button.
     * The button communicates with the controller to start/stop recording.
     */
    @Composable
    fun BPMStartStopScreen() {
        var bpm by remember { mutableStateOf<Double?>(null) }
        var isRecording by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = bpm?.toString() ?: "--", style = MaterialTheme.typography.body1)

            Button(onClick = {
                if (isRecording) {
                    controller.stopRecording()
                    bpm = null
                } else {
                    controller.startRecording { newBpm ->
                        bpm = newBpm
                    }
                }
                isRecording = !isRecording
            }) {
                Text(if (isRecording) "Stop" else "Start")
            }
        }
    }
}