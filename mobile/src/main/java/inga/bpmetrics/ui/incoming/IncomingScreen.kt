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
import inga.bpmetrics.datasync.IncomingRecord
import inga.bpmetrics.datasync.IncomingRecordManager
import inga.bpmetrics.datasync.IncomingStatus
import inga.bpmetrics.datasync.isActive
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString

/**
 * Records arriving from watches, and how far along each one is.
 *
 * Syncing is otherwise invisible — recordings simply appear in the library some time later, with
 * no way to tell whether more are still coming. After an event with several watches that is
 * exactly the question.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingScreen(onOpenDrawer: () -> Unit) {
    val incoming by IncomingRecordManager.incoming.collectAsState()
    val hasFinished = incoming.any { !it.status.isActive }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Incoming") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open navigation menu")
                    }
                },
                actions = {
                    if (hasFinished) {
                        IconButton(onClick = { IncomingRecordManager.clearFinished() }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear finished")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (incoming.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(
                        "Nothing arriving",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Recordings appear here as they transfer from your watches. A watch holds " +
                            "onto its recordings until this phone confirms it has them, so nothing " +
                            "is lost while you are apart.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(incoming, key = { it.id }) { record ->
                    IncomingCard(record)
                }
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
                Text(
                    text = "⌚ ${record.label}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
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
        record.finishedAt?.let { append(" · ${getTimeString(it)}") }
    }
    IncomingStatus.FAILED -> "Failed — the watch still has it and will retry. " +
            (record.error ?: "")
}
