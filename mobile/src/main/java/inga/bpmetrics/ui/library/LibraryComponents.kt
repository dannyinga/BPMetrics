package inga.bpmetrics.ui.library

import inga.bpmetrics.library.FilterState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import inga.bpmetrics.ui.util.ReaderClock
import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString
import inga.bpmetrics.ui.components.FlowRow
import inga.bpmetrics.ui.components.ExpandableSection
import inga.bpmetrics.ui.components.PersonSwatch
import java.util.Calendar
import java.util.TimeZone


/**
 * Converts a local epoch timestamp to the UTC start-of-day equivalent.
 * This ensures the Material 3 DatePicker highlights the correct calendar day.
 */
private fun Long.toUtcStartOfDay(): Long {
    val localCal = Calendar.getInstance().apply { timeInMillis = this@toUtcStartOfDay }
    val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(localCal.get(Calendar.YEAR), localCal.get(Calendar.MONTH), localCal.get(Calendar.DAY_OF_MONTH))
    }
    return utcCal.timeInMillis
}

private fun Modifier.width(dp: Int): Modifier = this.then(Modifier.width(dp.dp))

/**
 * Attributes a hand-picked set of recordings to one person.
 *
 * Picking them out of the library is the only way to describe "these ones" — a batch that arrived
 * before its watch had anyone assigned is not a category any filter expresses. Everyone is listed
 * rather than hidden behind a dropdown, because with a handful of friends the whole set fits and
 * choosing becomes one tap.
 *
 * This overwrites whatever the chosen recordings were attributed to, so the count is stated plainly
 * and nothing happens until a name is tapped.
 */
@Composable
fun BulkWearerDialog(
    recordCount: Int,
    people: List<inga.bpmetrics.library.PersonEntity>,
    onDismiss: () -> Unit,
    onAssign: (Long?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attribute to") },
        text = {
            Column {
                Text(
                    text = if (recordCount == 1) {
                        "Attribute this recording to:"
                    } else {
                        "Attribute these $recordCount recordings to:"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "This replaces whoever they are attributed to now. It does not affect any " +
                        "other recording, or who is wearing the watch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                if (people.isEmpty()) {
                    Text(
                        "No people yet. Add someone in the People section first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(people) { person ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAssign(person.personId) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PersonSwatch(person.colorArgb, size = 18)
                                Spacer(Modifier.width(12.dp))
                                Text(person.displayName, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        item {
                            HorizontalDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAssign(null) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Nobody",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        // No confirm button: tapping a name is the action, so a second press would only be a
        // chance to change the answer after having already given it.
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
