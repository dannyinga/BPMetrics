package inga.bpmetrics.ui.recovery

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.LibraryDatabase
import inga.bpmetrics.ui.components.DeleteConfirmDialog
import inga.bpmetrics.ui.settings.StorageInspector
import inga.bpmetrics.ui.util.ReaderClock
import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString

/**
 * What the app shows when the library will not open.
 *
 * There was nothing here before, and nothing is the worst possible answer: opening the database
 * happens on the main thread during `Application.onCreate`, so anything wrong with the file killed
 * the process before a pixel was drawn. Every launch did it again. The pre-migration backups were
 * one directory away on the same phone and completely unreachable, and the person holding it had no
 * way to tell "the file is damaged" from "you installed the wrong build" — which are the same crash
 * and opposite problems.
 *
 * So: say what happened, in the specific rather than the general, and offer the two things that
 * actually recover from it.
 */
@Composable
fun RecoveryScreen(context: Context, failure: Throwable) {
    val fileVersion = remember { LibraryDatabase.fileVersion(context) }
    val expected = LibraryDatabase.EXPECTED_VERSION
    val downgraded = fileVersion != null && fileVersion > expected

    val backups = remember { StorageInspector.listBackups(context) }
    var restoring by remember { mutableStateOf<StorageInspector.Backup?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    restoring?.let { backup ->
        DeleteConfirmDialog(
            title = "Restore this backup?",
            message = "Replaces the library with the copy taken on " +
                "${getDateString(backup.takenAtMs, ReaderClock)}. Everything recorded since is " +
                "kept aside rather than deleted, and the app will close so it can reopen cleanly.",
            confirmLabel = "Restore",
            onDismiss = { restoring = null },
            onConfirm = {
                val failed = StorageInspector.restore(context, backup)
                restoring = null
                if (failed != null) problem = failed else closeApp()
            }
        )
    }

    Scaffold { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (downgraded) "This library is newer than the app" else "The library did not open",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))

            Text(
                if (downgraded) {
                    // Named exactly, because this one is not a fault in the data and the fix is not
                    // a restore. A newer build of the app upgraded the library to version
                    // $fileVersion; this build only knows version $expected, and databases do not
                    // go backwards. Nothing is lost and nothing needs repairing.
                    "Your recordings are safe and nothing is damaged. A newer build of BPMetrics " +
                        "upgraded this library to version $fileVersion, and this build only knows " +
                        "how to open version $expected. Databases are only ever upgraded, never " +
                        "downgraded.\n\nInstall the current build of the app and it will open " +
                        "normally. Restoring a backup would work too, but it would throw away " +
                        "everything recorded since that backup was taken — try the build first."
                } else {
                    "The library could not be opened, so the app has stopped rather than carry on " +
                        "and risk making it worse. Your recordings are still on the phone.\n\n" +
                        "If a backup below is from before the trouble started, restoring it is the " +
                        "way back."
                },
                style = MaterialTheme.typography.bodyMedium
            )

            problem?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Text("Backups", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                // Taken automatically before every migration, which is the moment a library is most
                // likely to be damaged and the moment nobody thinks to make one by hand.
                "Taken automatically before each upgrade.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            if (backups.isEmpty()) {
                Text(
                    "There are no backups on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            backups.forEach { backup ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                getDateString(backup.takenAtMs, ReaderClock) + ", " +
                                    getTimeString(backup.takenAtMs, ReaderClock),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "${backup.bytes / 1024} KB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { restoring = backup }) { Text("Restore") }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = { closeApp() }) { Text("Close the app") }

            Spacer(Modifier.height(20.dp))
            // Last, small, and present: the thing to paste into a bug report. Everything above is
            // for the person holding the phone; this is for whoever has to fix it.
            Text(
                "Details",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                failure.message ?: failure::class.java.simpleName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Ends the process.
 *
 * Rather than restarting it: Room caches the open connection for the life of the process, so a
 * library that has just been swapped underneath it has to be opened by a *new* one. Closing and
 * letting the person tap the icon again is the honest version of that, and it does not fight the
 * system over relaunching ourselves.
 */
private fun closeApp() {
    android.os.Process.killProcess(android.os.Process.myPid())
}
