package inga.bpmetrics.ui.incoming

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Watch
import inga.bpmetrics.datasync.IncomingRecord
import inga.bpmetrics.datasync.IncomingRecordManager
import inga.bpmetrics.datasync.IncomingStatus
import inga.bpmetrics.datasync.isActive
import inga.bpmetrics.ui.util.ReaderClock
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString

/**
 * Records arriving from watches, and how far along each one is.
 *
 * Syncing is otherwise invisible — recordings simply appear in the library some time later, with
 * no way to tell whether more are still coming. After an event with several watches that is
 * exactly the question.
 *
 * This used to be a section of its own in the drawer. It is now a block inside the Sync settings,
 * beside the manual check and the retry interval: a list that is empty almost all of the time does
 * not earn a permanent place in the navigation, and the question it answers is the one the rest of
 * that section is already about.
 */
@Composable
fun IncomingList(modifier: Modifier = Modifier) {
    val incoming by IncomingRecordManager.incoming.collectAsState()

    if (incoming.isEmpty()) return

    Column(modifier.fillMaxWidth()) {
        incoming.forEach { record ->
            IncomingCard(record)
            Spacer(Modifier.height(8.dp))
        }
        if (incoming.any { !it.status.isActive }) {
            androidx.compose.material3.TextButton(
                onClick = { IncomingRecordManager.clearFinished() }
            ) {
                Text("Clear finished")
            }
        }
    }
}

@Composable
private fun IncomingCard(record: IncomingRecord) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusIcon(record.status)
            Spacer(Modifier.size(16.dp))

            Column(Modifier.fillMaxWidth()) {
                inga.bpmetrics.ui.components.BpmIconLabel(
                    icon = Icons.Default.Watch,
                    text = record.label,
                    tone = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = describe(record),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (record.status == IncomingStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(status: IncomingStatus) {
    when (status) {
        IncomingStatus.WAITING,
        IncomingStatus.RECEIVING,
        IncomingStatus.SAVING ->
            // Indeterminate on purpose: the payload length is not known until it has been read,
            // so a percentage here would be made up.
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)

        IncomingStatus.COMPLETED -> Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )

        IncomingStatus.FAILED -> Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp)
        )
    }
}

/** One line explaining where this record has got to. */
private fun describe(record: IncomingRecord): String = when (record.status) {
    IncomingStatus.WAITING -> "Waiting to transfer"
    IncomingStatus.RECEIVING -> "Receiving from watch…"
    IncomingStatus.SAVING -> "Saving to library… (${record.receivedBytes / 1024} KB received)"
    IncomingStatus.COMPLETED -> buildString {
        append("Added to your library")
        if (record.receivedBytes > 0) append(" · ${record.receivedBytes / 1024} KB")
        record.finishedAt?.let { append(" · ${getTimeString(it, ReaderClock)}") }
    }
    IncomingStatus.FAILED -> "Failed — the watch still has it and will retry. " +
            (record.error ?: "")
}
