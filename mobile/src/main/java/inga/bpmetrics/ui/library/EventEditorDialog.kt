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
    val windowPeople: Set<Long>
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
                            if (!it) {
                                start = null
                                end = null
                                chosenPeople = emptySet()
                            }
                        }
                    )
                }

                if (hasWindow) {
                    EdgeButton("Starts", start) { picking = WindowEdge.START }
                    EdgeButton("Ends", end) { picking = WindowEdge.END }

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
                            windowPeople = if (hasWindow) chosenPeople else emptySet()
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    picking?.let { edge ->
        val existing = if (edge == WindowEdge.START) start else end
        DateTimePicker(
            initial = existing ?: defaultEdge(edge, start, end),
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
private fun EdgeButton(label: String, value: Long?, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
        Text(value?.let { edgeFormat.format(Date(it)) } ?: "Choose…")
    }
}

private val edgeFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

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
    onDismiss: () -> Unit,
    onPicked: (Long) -> Unit
) {
    var pickedDate by remember { mutableStateOf<Long?>(null) }

    if (pickedDate == null) {
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
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
        val calendar = remember { Calendar.getInstance().apply { timeInMillis = initial } }
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
                ) { TimePicker(state = state) }
            },
            confirmButton = {
                TextButton(onClick = {
                    // The date picker returns UTC midnight; the time is local. Combining them
                    // through a local calendar is what keeps a 21:00 set from landing an hour out
                    // in the summer.
                    val day = Calendar.getInstance().apply {
                        timeInMillis = pickedDate!!
                        val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                            .apply { timeInMillis = pickedDate!! }
                        set(Calendar.YEAR, utc.get(Calendar.YEAR))
                        set(Calendar.MONTH, utc.get(Calendar.MONTH))
                        set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
                        set(Calendar.HOUR_OF_DAY, state.hour)
                        set(Calendar.MINUTE, state.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onPicked(day.timeInMillis)
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }
}
