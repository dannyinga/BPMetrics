package inga.bpmetrics.ui.locations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import inga.bpmetrics.library.LocationEntity
import inga.bpmetrics.ui.components.BpmEmptySection
import inga.bpmetrics.ui.components.DeleteConfirmDialog

/**
 * The venue registry — where things happened, and what the clock says there.
 *
 * Beside People and Watches because it is the same kind of list: a small set of things you name
 * once and point at from everywhere else. The payoff is the same too — rename the Gorge here and
 * every event at it follows, and comparing venues compares identities rather than spellings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationsScreen(
    viewModel: LocationsViewModel,
    onOpenDrawer: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LocationEntity?>(null) }
    var deleting by remember { mutableStateOf<LocationRow?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Locations") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add a location")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (uiState.isEmpty) {
                item {
                    BpmEmptySection(
                        "No locations yet",
                        "Name the places you record — the Gorge, the Showbox, home. Give each one " +
                            "its time zone and recordings made there read in the right clock, " +
                            "wherever you happen to be looking at them."
                    )
                }
            }

            items(uiState.locations, key = { it.location.locationId }) { row ->
                LocationCard(
                    row = row,
                    onEdit = { editing = row.location },
                    onDelete = { deleting = row }
                )
            }
        }
    }

    if (adding) {
        LocationDialog(
            initial = null,
            onDismiss = { adding = false },
            onConfirm = { name, zone, _, _ ->
                viewModel.addLocation(name, zone)
                adding = false
            }
        )
    }

    editing?.let { location ->
        LocationDialog(
            initial = location,
            onDismiss = { editing = null },
            onConfirm = { name, zone, lat, lon ->
                if (name != location.name) viewModel.rename(location.locationId, name)
                if (zone != location.timeZoneId) viewModel.setZone(location.locationId, zone)
                if (lat != location.latitude || lon != location.longitude) {
                    viewModel.setCoordinates(location.locationId, lat, lon)
                }
                editing = null
            }
        )
    }

    deleting?.let { row ->
        DeleteConfirmDialog(
            title = "Delete ${row.location.displayName}?",
            // Worth stating: this is a reference, so nothing goes with it. Every other delete in
            // the app takes something away, and the expectation carries over.
            message = if (row.eventCount > 0) {
                "The ${row.eventCount} event${if (row.eventCount == 1) "" else "s"} there keep " +
                    "their recordings and their times — they just stop saying where they were."
            } else {
                "Nothing is using this location."
            },
            onDismiss = { deleting = null },
            onConfirm = {
                viewModel.delete(context, row.location.locationId)
                deleting = null
            }
        )
    }
}

@Composable
private fun LocationCard(row: LocationRow, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    row.location.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    buildString {
                        // The clock first: it is the part that changes numbers rather than labels.
                        append(row.location.timeZoneId ?: "No time zone set")
                        append("  ·  ")
                        append("${row.eventCount} event${if (row.eventCount == 1) "" else "s"}")
                        if (row.location.hasCoordinates) append("  ·  pinned")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            var open by remember { mutableStateOf(false) }
            IconButton(onClick = { open = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    onClick = { open = false; onEdit() }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = { open = false; onDelete() }
                )
            }
        }
    }
}

/**
 * Naming a place and saying what time it is there.
 *
 * The zone is chosen from the platform's own list rather than worked out from coordinates. Someone
 * naming a venue knows what time it is there, and asking once — on a form filled in rarely — is
 * cheaper and more accurate than bundling a boundary dataset that can be wrong near a border.
 */
@Composable
private fun LocationDialog(
    initial: LocationEntity?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, zone: String?, lat: Double?, lon: Double?) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var zone by remember { mutableStateOf(initial?.timeZoneId.orEmpty()) }
    var lat by remember { mutableStateOf(initial?.latitude?.toString().orEmpty()) }
    var lon by remember { mutableStateOf(initial?.longitude?.toString().orEmpty()) }
    var pickingZone by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Asked only when the button is pressed, never on opening the screen. A permission prompt
    // someone did not ask for reads as the app wanting something, and this wants nothing.
    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) {
            DeviceLocation.lastKnown(context)?.let { (la, lo) ->
                lat = la.toString()
                lon = lo.toString()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New location" else "Edit location") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("The Gorge, Showbox SoDo, home…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                androidx.compose.material3.OutlinedButton(
                    onClick = { pickingZone = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Time zone",
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Start
                    )
                    Text(zone.ifBlank { "Choose…" })
                }
                Text(
                    "Recordings made here read in this clock, wherever you are when you look at " +
                        "them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(2.dp))

                // Optional and informational — nothing depends on them. Kept for a map one day and
                // for remembering where somewhere actually was.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = lat,
                        onValueChange = { lat = it },
                        label = { Text("Latitude") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lon,
                        onValueChange = { lon = it },
                        label = { Text("Longitude") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Optional. Nothing depends on these — the time zone above is what matters.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        if (DeviceLocation.hasPermission(context)) {
                            DeviceLocation.lastKnown(context)?.let { (la, lo) ->
                                lat = la.toString()
                                lon = lo.toString()
                            }
                        } else {
                            askLocation.launch(DeviceLocation.permissions)
                        }
                    }) { Text("Use current") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    // Half a pair is not a place, so both or neither. A latitude with no longitude
                    // is the row that makes a map draw something off the coast of Ghana.
                    val la = lat.trim().toDoubleOrNull()
                    val lo = lon.trim().toDoubleOrNull()
                    val paired = if (la != null && lo != null) la to lo else null
                    onConfirm(
                        name.trim(),
                        zone.takeIf { it.isNotBlank() },
                        paired?.first,
                        paired?.second
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (pickingZone) {
        TimeZonePickerDialog(
            current = zone,
            onDismiss = { pickingZone = false },
            onPick = { zone = it; pickingZone = false }
        )
    }
}

/**
 * Choosing a zone from the ones the platform knows.
 *
 * From `TimeZone.getAvailableIDs()` rather than a list of our own: Android keeps its tzdata current,
 * so a zone that gains a rule or splits in two is handled without an app update. Filtered by typing,
 * because there are several hundred.
 */
@Composable
private fun TimeZonePickerDialog(
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val all = remember { java.util.TimeZone.getAvailableIDs().sorted() }
    val shown = remember(query) {
        if (query.isBlank()) {
            // The device's own zone first: it is right far more often than not, and scrolling to
            // it through four hundred entries is a poor way to confirm the obvious.
            val here = java.util.TimeZone.getDefault().id
            listOf(here) + all.filterNot { it == here }
        } else {
            all.filter { it.contains(query.trim(), ignoreCase = true) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Time zone") },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    placeholder = { Text("Los_Angeles, London, Denver…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(shown, key = { it }) { id ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    id,
                                    fontWeight = if (id == current) FontWeight.Bold else null
                                )
                            },
                            onClick = { onPick(id) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
