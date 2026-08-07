package inga.bpmetrics.ui.watches

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import inga.bpmetrics.BPMetricsApp
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.WatchEntity
import inga.bpmetrics.ui.components.PersonPicker
import inga.bpmetrics.ui.components.PersonSwatch
import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString
import java.util.UUID

/**
 * The list of watches recordings arrive from, and where each is given a name.
 *
 * Naming is forward-looking by design: a recording keeps the name it was made under, so renaming a
 * watch that changed hands does not rewrite who was wearing it last week. Recordings that arrived
 * before a watch was named can be corrected explicitly from each watch's dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchesScreen(onOpenDrawer: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BPMetricsApp

    val viewModel: WatchesViewModel = viewModel(
        factory = WatchesViewModel.Factory(app.libraryRepository)
    )

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<WatchEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.watches.size) { viewModel.refreshCounts() }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Watches") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open navigation menu")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Register a watch")
            }
        }
    ) { padding ->
        if (uiState.isEmpty) {
            EmptyWatches(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.watches, key = { it.watch.watchId }) { row ->
                    WatchCard(row = row, onClick = { editing = row.watch })
                }
            }
        }
    }

    editing?.let { watch ->
        WatchEditDialog(
            watch = watch,
            people = uiState.people,
            onDismiss = { editing = null },
            onSave = { deviceName, personId ->
                viewModel.save(watch.watchId, deviceName, personId)
                editing = null
            },
            onReattributeAll = { personId ->
                // Covers the common case directly: everything this watch ever sent.
                viewModel.reattribute(watch.watchId, personId, Long.MIN_VALUE, Long.MAX_VALUE)
                editing = null
            },
            onDelete = {
                viewModel.delete(watch.watchId)
                editing = null
            }
        )
    }

    if (showAddDialog) {
        AddWatchDialog(
            people = uiState.people,
            onDismiss = { showAddDialog = false },
            onAdd = { deviceName, personId ->
                // A watch registered before it has ever been seen has no identifier to key on yet,
                // so one is minted here and reconciled by merging once its records arrive.
                viewModel.addWatch(UUID.randomUUID().toString(), deviceName, personId)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun EmptyWatches(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(Icons.Default.Watch, contentDescription = null, modifier = Modifier.height(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("No watches yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "A watch appears here the first time a recording arrives from it. " +
                    "Name it before an event and its recordings will be attributed to the right person.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun WatchCard(row: WatchRow, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Watch, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text(
                    text = "  ${row.watch.displayName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                row.wearer?.let {
                    PersonSwatch(it.colorArgb, size = 12)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = row.wearer?.let { "Worn by ${it.displayName}" } ?: "👤 No wearer set",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.wearer != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    if (row.watch.isNamed && row.watch.lastKnownModel.isNotBlank()) {
                        append(row.watch.lastKnownModel).append(" · ")
                    }
                    append(if (row.recordCount == 1) "1 recording" else "${row.recordCount} recordings")
                    if (row.watch.lastSeen > 0) append(" · last seen ${getDateString(row.watch.lastSeen)}")
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (row.wearer == null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Recordings from this watch will arrive unattributed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun WatchEditDialog(
    watch: WatchEntity,
    people: List<PersonEntity>,
    onDismiss: () -> Unit,
    onSave: (deviceName: String, personId: Long?) -> Unit,
    onReattributeAll: (Long) -> Unit,
    onDelete: () -> Unit
) {
    var deviceName by remember(watch.watchId) { mutableStateOf(watch.deviceName) }
    var personId by remember(watch.watchId) { mutableStateOf(watch.currentPersonId) }
    var confirmingDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(watch.displayName) },
        text = {
            Column {
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    label = { Text("Watch name") },
                    placeholder = { Text(watch.lastKnownModel.ifBlank { "Watch A" }) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Names the device itself. Use something you will recognise when handing it out.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(16.dp))

                PersonPicker(
                    people = people,
                    selectedId = personId,
                    onSelect = { personId = it },
                    label = "Current wearer"
                )
                Text(
                    "Applies to recordings that arrive from now on. Recordings already in your " +
                        "library stay with whoever was wearing it at the time, so changing this is " +
                        "how you hand the watch to someone else.",
                    style = MaterialTheme.typography.bodySmall
                )

                if (watch.lastKnownModel.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Model: ${watch.lastKnownModel}", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(deviceName, personId) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = personId != null,
                    onClick = { personId?.let(onReattributeAll) }
                ) { Text("Apply to past") }
                TextButton(onClick = { confirmingDelete = true }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    )

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Remove this watch?") },
            text = {
                Text(
                    "Recordings from it stay in your library and keep their names. The watch will " +
                        "reappear if it sends another recording."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AddWatchDialog(
    people: List<PersonEntity>,
    onDismiss: () -> Unit,
    onAdd: (deviceName: String, personId: Long?) -> Unit
) {
    var deviceName by remember { mutableStateOf("") }
    var personId by remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Register a watch") },
        text = {
            Column {
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    label = { Text("Watch name") },
                    placeholder = { Text("Watch A") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                PersonPicker(
                    people = people,
                    selectedId = personId,
                    onSelect = { personId = it },
                    label = "Who will be wearing it"
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Use this to set a wearer before an event, so the first recordings arrive " +
                        "attributed. Once the watch sends one it will appear as its own entry, " +
                        "which you can merge into this one.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = deviceName.isNotBlank() || personId != null,
                onClick = { onAdd(deviceName, personId) }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
