package inga.bpmetrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.BpmRecord
import java.sql.Date

@Composable
fun BpmLibraryView() {
    val librarian = BpmLibrarian.getInstance()
    var currentRecords by remember { mutableStateOf(emptyList<BpmRecord>()) }

    LaunchedEffect(Unit) {
        librarian.startObservingLibrary { newRecords ->
            currentRecords = newRecords.sortedByDescending { it.metadata.startTime }
        }
    }

    LazyColumn (
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(8.dp),

    ) {
        items(currentRecords) { record ->
            Text("$record")
        }
    }
}