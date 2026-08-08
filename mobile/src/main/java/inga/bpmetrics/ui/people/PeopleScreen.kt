package inga.bpmetrics.ui.people

import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.People
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
import inga.bpmetrics.library.PersonColors
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.ui.components.ColorPicker
import inga.bpmetrics.ui.components.DeleteConfirmDialog
import inga.bpmetrics.ui.components.PersonSwatch

/**
 * The people who wear the watches.
 *
 * Each carries a name and a colour, and that colour follows them everywhere they appear — the
 * stripe on their recordings in the library, their curve in a concurrent analysis, their line and
 * live reading in an exported video. Set once here rather than re-picked for every export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(onOpenDrawer: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BPMetricsApp

    val viewModel: PeopleViewModel = viewModel(
        factory = PeopleViewModel.Factory(app.libraryRepository)
    )

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<PersonEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.people.size) { viewModel.refreshCounts() }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("People") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open navigation menu")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add someone")
            }
        }
    ) { padding ->
        if (uiState.isEmpty) {
            EmptyPeople(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.people, key = { it.person.personId }) { row ->
                    PersonCard(row = row, onClick = { editing = row.person })
                }
            }
        }
    }

    editing?.let { person ->
        PersonEditDialog(
            person = person,
            recordCount = uiState.people.firstOrNull { it.person.personId == person.personId }?.recordCount ?: 0,
            onDismiss = { editing = null },
            onSave = { name, color, resting, max ->
                viewModel.save(person.personId, name, color, resting, max)
                editing = null
            },
            onDelete = {
                viewModel.delete(person.personId)
                editing = null
            }
        )
    }

    if (showAddDialog) {
        PersonAddDialog(
            suggestedColor = PersonColors.defaultFor(uiState.people.size),
            onDismiss = { showAddDialog = false },
            onAdd = { name, color ->
                viewModel.addPerson(name, color)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun EmptyPeople(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.People,
            contentDescription = null,
            modifier = Modifier.height(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text("Nobody yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Add the people you hand watches to. Each gets a colour, so you can tell their " +
                "recordings apart at a glance and their curve keeps that colour in every chart " +
                "and video.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PersonCard(row: PersonRow, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PersonSwatch(row.person.colorArgb, size = 28)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.person.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when (row.recordCount) {
                        0 -> "No recordings yet"
                        1 -> "1 recording"
                        else -> "${row.recordCount} recordings"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PersonEditDialog(
    person: PersonEntity,
    recordCount: Int,
    onDismiss: () -> Unit,
    onSave: (String, Int, Int?, Int?) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember(person.personId) { mutableStateOf(person.name) }
    var color by remember(person.personId) { mutableStateOf(person.colorArgb) }
    var resting by remember(person.personId) { mutableStateOf(person.restingBpm) }
    var max by remember(person.personId) { mutableStateOf(person.maxBpm) }
    var confirmDelete by remember(person.personId) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit person") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = if (recordCount > 0) {
                        "Renaming updates all $recordCount of their recordings. It does not change " +
                            "who any recording belongs to."
                    } else {
                        "Renaming updates every recording of theirs."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                ColorPicker(colorArgb = color, onColorChange = { color = it })

                Spacer(Modifier.height(16.dp))
                Text(
                    "Heart rate",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Text(
                    // Their own figures if given; the app-wide ones otherwise. A shared value
                    // would make time-in-zone say something false about whoever it did not fit.
                    "Leave blank to use the figures in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionalBpmField(
                        value = resting,
                        label = "Resting",
                        modifier = Modifier.weight(1f)
                    ) { resting = it }
                    OptionalBpmField(
                        value = max,
                        label = "Maximum",
                        modifier = Modifier.weight(1f)
                    ) { max = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name, color, resting, max) }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(
                onClick = { confirmDelete = true },
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text("Remove") }
        }
    )

    if (confirmDelete) {
        DeleteConfirmDialog(
            title = "Remove ${person.displayName}?",
            message = if (recordCount > 0) {
                "Their $recordCount recording${if (recordCount == 1) "" else "s"} will be kept and " +
                    "will still show their name — they just stop being someone you can filter by " +
                    "or recolour."
            } else {
                "They have no recordings."
            },
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete()
            }
        )
    }
}

@Composable
private fun PersonAddDialog(
    suggestedColor: Int,
    onDismiss: () -> Unit,
    onAdd: (String, Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(suggestedColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add someone") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("Kyle") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                ColorPicker(colorArgb = color, onColorChange = { color = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onAdd(name, color) }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * A heart rate that may simply not be given.
 *
 * Empty is a real answer here — it means "use the app-wide figure" — so the field commits null
 * rather than refusing to be cleared. That distinction is the whole point of storing these as
 * nullable: zero is a heart rate, and absence is not.
 */
@Composable
private fun OptionalBpmField(
    value: Int?,
    label: String,
    modifier: Modifier = Modifier,
    onValue: (Int?) -> Unit
) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }

    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            val cleaned = typed.filter { it.isDigit() }.take(3)
            text = cleaned
            onValue(cleaned.toIntOrNull())
        },
        label = { Text(label) },
        placeholder = { Text("Default") },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
        ),
        modifier = modifier
    )
}
