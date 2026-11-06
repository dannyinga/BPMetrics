package inga.bpmetrics.ui.people

import inga.bpmetrics.ui.feedback.Feedback
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.verticalScroll
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
import inga.bpmetrics.ui.components.PersonAvatar
import inga.bpmetrics.ui.components.rememberCoverPicker
import inga.bpmetrics.ui.components.CoverCropDialog
import inga.bpmetrics.ui.components.CoverCropShape

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

    var editing by remember { mutableStateOf<PersonEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    // Reset whenever the dialog moves to a different person, or reframing one photograph would
    // open straight onto the next.
    var framingPhoto by remember(editing?.personId) { mutableStateOf(false) }

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

    editing?.let { snapshot ->
        // The live row, not the one captured when the dialog opened. A photo applies immediately,
        // and reading the snapshot would leave the avatar showing the initial until the dialog was
        // closed and reopened — which looks exactly like the photo not having saved.
        val person = uiState.people
            .firstOrNull { it.person.personId == snapshot.personId }
            ?.person
            ?: snapshot

        // Declared here rather than inside the dialog so the launcher survives the dialog's own
        // recompositions — a picker registered inside a dialog that closes while the gallery is
        // open comes back to nothing to deliver its result to.
        val pickPhoto = rememberCoverPicker { uri ->
            viewModel.setPhoto(context, person.personId, uri) { ok ->
                if (ok) {
                    // Straight into framing, while the choice is fresh. A photograph that lands
                    // showing the wrong shoulder and has to be hunted down to fix is worse than
                    // one extra step now. Already open when the pick came from inside the sheet.
                    framingPhoto = true
                } else {
                    Feedback.failed("That image couldn't be read.")
                }
            }
        }

        if (framingPhoto) {
            CoverCropDialog(
                cover = person.ownPhoto,
                onPick = pickPhoto,
                shape = CoverCropShape.CIRCLE,
                title = "Frame ${person.displayName}",
                hint = "Drag to move the photo, or a corner to zoom. Only what is inside the " +
                    "circle is kept.",
                previewContent = {},
                onDismiss = { framingPhoto = false },
                onConfirm = {
                    viewModel.setPhotoCrop(person.personId, it)
                    framingPhoto = false
                },
                // Stays open: removing is usually the first half of replacing.
                onRemove = { viewModel.clearPhoto(context, person.personId) }
            )
        }

        val row = uiState.people.firstOrNull { it.person.personId == person.personId }
        PersonEditDialog(
            person = person,
            recordCount = row?.recordCount ?: 0,
            pickPhoto = pickPhoto,
            onFramePhoto = { framingPhoto = true },
            onRemovePhoto = { viewModel.clearPhoto(context, person.personId) },
            watchNames = row?.watchNames.orEmpty(),
            onDismiss = { editing = null },
            onSave = { name, color ->
                viewModel.save(person.personId, name, color)
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
            "Add the people you hand watches to. Each gets a color, so you can tell their " +
                "recordings apart at a glance and their curve keeps that color in every chart " +
                "and video.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PersonCard(row: PersonRow, onClick: () -> Unit) {
    val colour = Color(row.person.colorArgb)

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        // A row of grey cards, on the one screen whose whole subject is who is who. Their colour is
        // the thing that tells their recordings apart in every chart, comparison and video in the
        // app, and the page where it is *chosen* was the one place it did not appear.
        //
        // Down the left edge, at the same 6dp as a recording tile — the same mark meaning the same
        // thing, so it reads as "this is Colin's" here for the reason it already does there.
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(colour)
            )
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
            // Ringed, which the stripe alone does not cover: with a photograph the circle is their
            // face and their colour is nowhere on it, so on this screen the two people with
            // pictures would be the two with no visible colour.
            PersonAvatar(person = row.person, size = 40.dp, ring = colour)
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
}

@Composable
private fun PersonEditDialog(
    person: PersonEntity,
    recordCount: Int,
    /** Opens the gallery. Applies straight away — see the note beside the avatar. */
    pickPhoto: () -> Unit,
    onFramePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    /** The watches they're wearing, which removing them leaves with no wearer. */
    watchNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, Int) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember(person.personId) { mutableStateOf(person.name) }
    var color by remember(person.personId) { mutableStateOf(person.colorArgb) }
    var confirmDelete by remember(person.personId) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit person") },
        text = {
            // Scrolls: photo, name and the colour grid don't fit a small phone with the keyboard up.
            Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                // Their photograph, alongside the name it belongs to. Saved as soon as it is
                // chosen rather than waiting for the dialog's Save: the file is already copied by
                // then, and a Cancel that leaves an orphaned image on disk is worse than a photo
                // that applies immediately.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PersonAvatar(person = person.copy(name = name, colorArgb = color), size = 56.dp)
                    Column {
                        TextButton(onClick = pickPhoto) {
                            Text(if (person.photoPath == null) "Add a photo" else "Change photo")
                        }
                        if (person.photoPath != null) {
                            TextButton(onClick = onFramePhoto) { Text("Reframe") }
                            TextButton(onClick = onRemovePhoto) { Text("Remove photo") }
                        } else {
                            Text(
                                // Not an apology for a missing photo. The colour is how a wearer
                                // is identified on every chart in the app already.
                                "Their color and initial otherwise.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }

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
                // Resting and maximum heart rate were here. Nothing read them (Audit #7, D1).

                // Apart from Save, in the body, so a destructive verb isn't the button beside it.
                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = { confirmDelete = true },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Remove ${person.displayName}…") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name, color) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (confirmDelete) {
        DeleteConfirmDialog(
            title = "Remove ${person.displayName}?",
            message = buildString {
                append(
                    if (recordCount > 0) {
                        "Their $recordCount recording${if (recordCount == 1) "" else "s"} will be " +
                            "kept and will still show their name — they just stop being someone " +
                            "you can filter by or recolour."
                    } else {
                        "They have no recordings."
                    }
                )
                // Said here because it changes what happens next, not just what is shown: the
                // watch's next recordings arrive with nobody on them.
                when (watchNames.size) {
                    0 -> Unit
                    1 -> append(
                        "\n\n${watchNames.single()} will have no wearer, so its next recordings " +
                            "arrive unattributed."
                    )
                    else -> append(
                        "\n\n${watchNames.joinToString()} will have no wearer, so their next " +
                            "recordings arrive unattributed."
                    )
                }
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

