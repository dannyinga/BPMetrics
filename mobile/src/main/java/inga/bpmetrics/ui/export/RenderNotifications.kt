package inga.bpmetrics.ui.export

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import inga.bpmetrics.BPMetricsApp
import kotlinx.coroutines.launch

/**
 * Whether renders can say how they're getting on, and the one-time ask before the first one.
 *
 * The app declared `POST_NOTIFICATIONS` and never asked for it, so on Android 13 and later every
 * render ran with no progress and no word when it finished or failed (Audit #3).
 */
class RenderNotificationGate internal constructor(
    /** Whether notifications can be shown right now. */
    val enabled: Boolean,
    private val gate: (then: () -> Unit) -> Unit,
    private val openSettings: () -> Unit
) {
    /** Runs [then], first asking for notifications if they've never been asked for. */
    fun beforeRender(then: () -> Unit) = gate(then)

    /** The app's notification settings, for turning them on after a refusal. */
    fun turnOn() = openSettings()
}

@Composable
fun rememberRenderNotificationGate(): RenderNotificationGate {
    val context = LocalContext.current
    val settings = remember(context) { (context.applicationContext as BPMetricsApp).settingsRepository }
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(notificationsEnabled(context)) }
    // Re-read on return, since they're turned on in system settings, not here.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = notificationsEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var waiting by remember { mutableStateOf<(() -> Unit)?>(null) }
    var explaining by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        enabled = notificationsEnabled(context)
        waiting?.invoke()
        waiting = null
    }

    if (explaining) {
        fun answer(allow: Boolean) {
            explaining = false
            scope.launch { settings.setAskedRenderNotifications() }
            if (allow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                waiting?.invoke()
                waiting = null
            }
        }
        AlertDialog(
            onDismissRequest = { answer(false) },
            title = { Text("Show render progress?") },
            text = {
                Text(
                    "Videos render in the background, even after you leave the app. With " +
                        "notifications on, you'll see each one's progress, and hear when it's done " +
                        "or why it failed. Without them, you'll only find out in the render queue."
                )
            },
            confirmButton = { TextButton(onClick = { answer(true) }) { Text("Allow notifications") } },
            dismissButton = { TextButton(onClick = { answer(false) }) { Text("Not now") } }
        )
    }

    return RenderNotificationGate(
        enabled = enabled,
        gate = gate@{ then ->
            if (enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                // Below 13 there's no prompt to show: notifications are on unless turned off in
                // settings, which step 4 offers a way to.
                then()
                return@gate
            }
            scope.launch {
                if (settings.askedRenderNotifications()) {
                    then()
                } else {
                    waiting = then
                    explaining = true
                }
            }
        },
        openSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    )
}

private fun notificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()
