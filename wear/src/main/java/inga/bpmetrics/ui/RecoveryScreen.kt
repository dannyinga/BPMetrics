package inga.bpmetrics.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import inga.bpmetrics.db.DbFailure
import inga.bpmetrics.theme.RecordingRed

/**
 * Shown when the watch's recording store will not open.
 *
 * The alternative was silence. Room opens lazily, so a database that cannot be read fails on the
 * first query — which happens inside a coroutine scope with an exception handler, where it was
 * logged and dropped. The app then started perfectly normally, showed no pending recordings, and
 * would have gone through the entire motion of recording a set while storing nothing of it. The
 * wearer would have found out hours later, with nothing to recover.
 *
 * So: say it, before a recording can be started. A watch that refuses to record is a bad evening.
 * A watch that pretends to record is a lost one.
 *
 * The two failures are kept apart deliberately, because they want opposite things — see [DbFailure].
 */
@Composable
fun WatchRecoveryScreen(
    failure: DbFailure,
    /** Deletes the store. Offered only where the store is genuinely unreadable. */
    onClearStore: () -> Unit
) {
    var confirming by remember { mutableStateOf(false) }

    WearScreen(centerItem = 1) {
        item {
            Text(
                text = when (failure) {
                    is DbFailure.NewerThanApp -> "Newer recordings"
                    is DbFailure.Unreadable -> "Store damaged"
                },
                style = MaterialTheme.typography.title3,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }

        item {
            Text(
                text = when (failure) {
                    // Named for what the wearer can do, not for what Room said. Room's own words are
                    // "a migration from 3 to 2 was required but not found", which is accurate and
                    // tells nobody that their recordings are fine and the app simply went backwards.
                    is DbFailure.NewerThanApp ->
                        "This watch has recordings saved by a newer version of the app. They are safe. " +
                            "Install the newer version again to reach them."
                    is DbFailure.Unreadable ->
                        "The recordings saved on this watch could not be read. Anything already sent to " +
                            "your phone is safe there."
                },
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        // Only for real damage. Offering it on a downgrade would be offering to delete a working
        // set of recordings that needed nothing but the right build — which is the single worst
        // thing this screen could do, and the reason the two cases are distinguished at all.
        if (failure is DbFailure.Unreadable) {
            if (!confirming) {
                item {
                    Chip(
                        onClick = { confirming = true },
                        label = { Text("Clear and start over", style = MaterialTheme.typography.caption1) },
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
            } else {
                item {
                    Text(
                        text = "Anything not yet on your phone will be lost.",
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center
                    )
                }
                item {
                    Chip(
                        onClick = onClearStore,
                        label = { Text("Clear it", style = MaterialTheme.typography.caption1) },
                        colors = ChipDefaults.primaryChipColors(backgroundColor = RecordingRed)
                    )
                }
                item {
                    Chip(
                        onClick = { confirming = false },
                        label = { Text("Cancel", style = MaterialTheme.typography.caption1) },
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
            }
        }
    }
}
