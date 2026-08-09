package inga.bpmetrics.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.ui.components.PersonSwatch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** What the editor hands back when it is saved. */
data class EventEdit(
    val name: String,
    val type: String?,
    val windowStart: Long?,
    val windowEnd: Long?,
    val windowPeople: Set<Long>,
    /** Which event this sits inside, or null at the top of the timeline. */
    val parentId: Long? = null,
    /** Where it happened, or null to inherit from the event above. */
    val locationId: Long? = null
)

/**
 * Everything about an event that is set by typing rather than by dragging.
 *
 * Name, type and window in one dialog because they are decided together — "this was the Subtronics
 * set, nine till half ten" is one thought, and three separate menu items would make it three.
 *
 * The window is the part that matters. It is not a label on the event, it is the rule that decides
 * which recordings belong to it: anything starting inside it lands here unless something nested
 * deeper claims it first. Saving one moves recordings, which is why the dialog says so rather than
 * leaving the change to be noticed.
 *
 * @param collisionError Set by the caller when the repository refused the window, naming what it
 *   collided with. Shown inline and cleared on the next edit — a toast would vanish while the
 *   person was still looking at the field that caused it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EventEditorDialog(
    initialName: String,
    initialType: String? = null,
    initialStart: Long? = null,
    initialEnd: Long? = null,
    initialPeople: Set<Long> = emptySet(),
    /**
     * The span of what the event already holds, earliest start to latest end.
     *
     * What a window defaults to when one is switched on. An event nearly always gets its recordings
     * before it gets a window — you record the set, then tidy up afterwards — so the recordings
     * already say when it happened, and making someone re-enter that from two date pickers is
     * asking them to retype what the app knows.
     *
     * A suggestion, not a constraint: widening it to catch a recording that arrives later is the
     * next thing anyone will want, and the window is the membership rule rather than a description
     * of what is currently inside.
     */
    suggestedStart: Long? = null,
    suggestedEnd: Long? = null,
    /**
     * Where this sits, and everything it could sit in.
     *
     * Offered at creation as well as afterwards, because "a new set inside Day 1" is one thought
     * and making it two steps — create, then find it in the list and move it — is how events end up
     * at the top level and stay there.
     *
     * [parentOptions] must already exclude anything that would make a cycle; the editor does not
     * re-derive that. See `EventParentPickerDialog`, which is where a move from the card goes.
     */
    initialParentId: Long? = null,
    /**
     * Where it happened, and the registry to choose from.
     *
     * Null means inherit from the event above — a set inside a festival needs no venue of its own,
     * and saying so once at the top is the whole point of the registry.
     */
    initialLocationId: Long? = null,
    locations: List<inga.bpmetrics.library.LocationEntity> = emptyList(),
    inheritedLocationName: String? = null,
    /**
     * The clock this event's window is typed in.
     *
     * The correctness half of venues. A window is entered as a wall-clock time and stored as an
     * instant, so "nine at the Gorge" and "nine here" are different moments — typing one and
     * storing the other is how a window claims the wrong recordings. Defaults to the reader's zone,
     * which is right when the event has no venue and is what the app did before venues existed.
     */
    zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
    parentOptions: List<Pair<inga.bpmetrics.library.EventEntity, Int>> = emptyList(),
    /** Types already used in this library, offered so a vocabulary forms instead of scattering. */
    knownTypes: List<String> = emptyList(),
    people: List<PersonEntity> = emptyList(),
    collisionError: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (EventEdit) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var type by rememberSaveable { mutableStateOf(initialType.orEmpty()) }
    var start by rememberSaveable { mutableStateOf(initialStart) }
    var end by rememberSaveable { mutableStateOf(initialEnd) }
    var chosenPeople by rememberSaveable { mutableStateOf(initialPeople) }
    var hasWindow by rememberSaveable { mutableStateOf(initialStart != null && initialEnd != null) }
    var parentId by rememberSaveable { mutableStateOf(initialParentId) }
    var pickingParent by remember { mutableStateOf(false) }
    var locationId by rememberSaveable { mutableStateOf(initialLocationId) }
    var pickingLocation by remember { mutableStateOf(false) }

    var picking by remember { mutableStateOf<WindowEdge?>(null) }

    // Backwards is caught here as well as in the repository. The repository check is the one that
    // protects the database; this one is so Save is visibly unavailable rather than tapped and
    // silently ignored.
    val backwards = hasWindow && start != null && end != null && end!! < start!!
    val incomplete = hasWindow && (start == null || end == null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialName.isBlank()) "New event" else "Edit event") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = type,
                    onValueChange = { type = it },
                    label = { Text("Type") },
                    placeholder = { Text("Concert, Gaming session, Workout…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (knownTypes.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        knownTypes.take(8).forEach { known ->
                            FilterChip(
                                selected = type.equals(known, ignoreCase = true),
                                onClick = { type = if (type.equals(known, true)) "" else known },
                                label = { Text(known) }
                            )
                        }
                    }
                }

                if (parentOptions.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { pickingParent = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Inside",
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Start
                        )
                        Text(
                            parentOptions.firstOrNull { it.first.eventId == parentId }
                                ?.first?.displayName
                                ?: "Nothing — top level"
                        )
                    }
                }

                // Always shown, even with an empty registry. Hiding it until a location existed made
                // the feature invisible to anyone who had not already found the Locations screen —
                // which is everyone, the first time.
                run {
                    OutlinedButton(
                        onClick = { pickingLocation = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Where",
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Start
                        )
                        Text(
                            locations.firstOrNull { it.locationId == locationId }?.displayName
                                // Says what it will inherit rather than "None", so nobody sets a
                                // venue on a set that already has the right one from its festival.
                                ?: inheritedLocationName?.let { "$it (inherited)" }
                                ?: "Not set"
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Time window", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Recordings made in this window belong to this event.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = hasWindow,
                        onCheckedChange = {
                            hasWindow = it
                            if (it) {
                                // Filled from what the event holds, and only where nothing has been
                                // chosen — flicking the switch off and on again must not discard
                                // times someone just typed.
                                if (start == null) start = suggestedStart
                                if (end == null) end = suggestedEnd
                            } else {
                                start = null
                                end = null
                                chosenPeople = emptySet()
                            }
                        }
                    )
                }

                if (hasWindow) {
                    EdgeButton("Starts", start, zone) { picking = WindowEdge.START }
                    EdgeButton("Ends", end, zone) { picking = WindowEdge.END }
                    if (zone.id != java.util.TimeZone.getDefault().id) {
                        Text(
                            "Typed in ${zone.id} — this event's clock, not yours.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (people.isNotEmpty()) {
                        Text(
                            "Applies to",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            // The reason overlap is allowed at all: two stages running at the same
                            // time are two events, told apart by who was at each.
                            if (chosenPeople.isEmpty()) {
                                "Everyone. Name people only when another event runs at the same time."
                            } else {
                                "Only these recordings will be claimed."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            people.forEach { person ->
                                FilterChip(
                                    selected = person.personId in chosenPeople,
                                    onClick = {
                                        chosenPeople = if (person.personId in chosenPeople) {
                                            chosenPeople - person.personId
                                        } else {
                                            chosenPeople + person.personId
                                        }
                                    },
                                    leadingIcon = { PersonSwatch(person.colorArgb) },
                                    label = { Text(person.name) }
                                )
                            }
                        }
                    }
                }

                val problem = when {
                    backwards -> "The end is before the start."
                    incomplete -> "A window needs both a start and an end."
                    else -> collisionError
                }
                problem?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !backwards && !incomplete,
                onClick = {
                    onConfirm(
                        EventEdit(
                            name = name.trim(),
                            type = type.trim().takeIf { it.isNotBlank() },
                            windowStart = start.takeIf { hasWindow },
                            windowEnd = end.takeIf { hasWindow },
                            windowPeople = if (hasWindow) chosenPeople else emptySet(),
                            parentId = parentId,
                            locationId = locationId
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (pickingLocation) {
        AlertDialog(
            onDismissRequest = { pickingLocation = false },
            title = { Text("Where") },
            text = {
                Column(
                    Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Text(
                                inheritedLocationName?.let { "Inherit — $it" }
                                    ?: "Not set"
                            )
                        },
                        onClick = { locationId = null; pickingLocation = false }
                    )
                    if (locations.isEmpty()) {
                        Text(
                            "No locations yet. Make one under Locations in the menu, then events " +
                                "can point at it — and recordings there read in its clock.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    locations.forEach { place ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Column {
                                    Text(place.displayName)
                                    place.timeZoneId?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            onClick = { locationId = place.locationId; pickingLocation = false }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickingLocation = false }) { Text("Cancel") }
            }
        )
    }

    if (pickingParent) {
        AlertDialog(
            onDismissRequest = { pickingParent = false },
            title = { Text("Inside") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Nothing — keep it at the top") },
                        onClick = { parentId = null; pickingParent = false }
                    )
                    parentOptions.forEach { (event, depth) ->
                        androidx.compose.material3.DropdownMenuItem(
                            modifier = Modifier.padding(
                                start = (depth.coerceAtLeast(0) * 14).dp
                            ),
                            text = { Text(event.displayName) },
                            onClick = { parentId = event.eventId; pickingParent = false }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickingParent = false }) { Text("Cancel") }
            }
        )
    }

    picking?.let { edge ->
        val existing = if (edge == WindowEdge.START) start else end
        DateTimePicker(
            initial = existing ?: defaultEdge(edge, start, end),
            zone = zone,
            onDismiss = { picking = null },
            onPicked = { chosen ->
                if (edge == WindowEdge.START) start = chosen else end = chosen
                picking = null
            }
        )
    }
}

private enum class WindowEdge { START, END }

/**
 * Where a picker opens when that edge has not been set.
 *
 * The other edge if there is one, so setting an end after a start opens on the right day rather
 * than on today — a set at one in the morning is otherwise a date correction every time.
 */
private fun defaultEdge(edge: WindowEdge, start: Long?, end: Long?): Long =
    when (edge) {
        WindowEdge.START -> end
        WindowEdge.END -> start
    } ?: System.currentTimeMillis()

@Composable
private fun EdgeButton(
    label: String,
    value: Long?,
    zone: java.util.TimeZone,
    onClick: () -> Unit
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
        Text(value?.let { edgeFormat(zone).format(Date(it)) } ?: "Choose…")
    }
}

// Seconds shown as well as stored. A window is a membership rule, so "21:00" and "21:00:47" decide
// different things and the editor has to let you see which one it holds.
/**
 * A window edge, written in the event's own clock.
 *
 * Built per call rather than held as a constant: a `SimpleDateFormat` carries its zone, and one
 * shared instance would render every event in whichever zone was set on it last.
 */
private fun edgeFormat(zone: java.util.TimeZone) =
    SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.getDefault()).apply { timeZone = zone }

/**
 * A date then a time, as two steps.
 *
 * Material offers no combined picker, and the alternative — two buttons the user has to know to
 * press in order — leaves half-set windows lying around. Cancelling either step cancels both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePicker(
    initial: Long,
    /** The event's clock. Both the readout and the instant built from it use this. */
    zone: java.util.TimeZone,
    onDismiss: () -> Unit,
    onPicked: (Long) -> Unit
) {
    var pickedDate by remember { mutableStateOf<Long?>(null) }

    if (pickedDate == null) {
        // The picker works in UTC, so it is handed the instant shifted by the event's offset —
        // otherwise a 00:30 set at the Gorge opens the calendar on the previous day for a reader
        // in London, and picking "today" silently moves the window twenty-four hours.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial + zone.getOffset(initial)
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = { pickedDate = state.selectedDateMillis }
                ) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    } else {
        val calendar = remember { Calendar.getInstance(zone).apply { timeInMillis = initial } }
        // Material has no seconds field, and a window that silently rounds to the minute is not the
        // same window: a set starting at 21:00:47 would claim the 21:00:12 recording from the act
        // before it. So the seconds are carried through and editable beside the clock.
        var seconds by remember { mutableStateOf(calendar.get(Calendar.SECOND).toString()) }
        val state = rememberTimePickerState(
            initialHour = calendar.get(Calendar.HOUR_OF_DAY),
            initialMinute = calendar.get(Calendar.MINUTE),
            is24Hour = android.text.format.DateFormat.is24HourFormat(
                androidx.compose.ui.platform.LocalContext.current
            )
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Time") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                ) {
                    TimePicker(state = state)
                    OutlinedTextField(
                        value = seconds,
                        onValueChange = { typed ->
                            // Digits only, and never past 59 — a field that accepts "90" and then
                            // rolls the minute over is a worse surprise than one that refuses.
                            val digits = typed.filter { it.isDigit() }.take(2)
                            if (digits.isEmpty() || digits.toInt() <= 59) seconds = digits
                        },
                        label = { Text("Seconds") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // The date picker returns UTC midnight; the time is local. Combining them
                    // through a local calendar is what keeps a 21:00 set from landing an hour out
                    // in the summer.
                    val day = Calendar.getInstance(zone).apply {
                        timeInMillis = pickedDate!!
                        val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                            .apply { timeInMillis = pickedDate!! }
                        set(Calendar.YEAR, utc.get(Calendar.YEAR))
                        set(Calendar.MONTH, utc.get(Calendar.MONTH))
                        set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
                        set(Calendar.HOUR_OF_DAY, state.hour)
                        set(Calendar.MINUTE, state.minute)
                        set(Calendar.SECOND, seconds.toIntOrNull()?.coerceIn(0, 59) ?: 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onPicked(day.timeInMillis)
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }
}
