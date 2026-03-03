package inga.bpmetrics.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.ui.StringFormatHelpers.getDurationString
import inga.bpmetrics.ui.StringFormatHelpers.getTimeString
import inga.bpmetrics.ui.StringFormatHelpers.getDateString

/**
 * A reusable UI component that displays a summary of a BPM record in a card format.
 *
 * This tile is typically used in a list (like [LibraryScreen]) to provide a quick overview
 * of a specific heart rate recording session.
 *
 * @param record The [BpmRecord] data to be displayed in the tile.
 * @param onClick A callback function invoked when the tile is clicked.
 */
@Composable
fun BpmRecordTile(
    record: BpmRecord,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Displays the user-defined or generated title of the record
            Text("Title: ${record.metadata.title}")
            
            // Displays the calculated average heart rate during the session
            Text("Average BPM: ${record.metadata.avg}")
            
            // Displays the total duration of the session formatted as a string
            Text("Duration: ${getDurationString(record.metadata.durationMs)}")
            
            // Displays the date and start time of the session
            Text("Date/Time: ${getDateString(record.metadata.date)} " +
                    getTimeString(record.metadata.startTime)
            )
        }
    }
}
