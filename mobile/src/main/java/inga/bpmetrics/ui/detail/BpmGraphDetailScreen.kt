package inga.bpmetrics.ui.detail

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.ui.graph.BpmGraph
import inga.bpmetrics.ui.graph.rememberGraphState
import java.sql.Date
import kotlin.math.max
import kotlin.math.min

/**
 * A detailed, full-screen view of the BPM graph.
 * This screen supports interactive zooming, panning, and data export features.
 *
 * @param viewModel The [BpmRecordViewModel] for the specific record.
 * @param onBack Callback for navigating back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BpmGraphDetailScreen(
    viewModel: BpmRecordViewModel,
    onBack: () -> Unit
) {
    val record by viewModel.record.collectAsState()
    val context = LocalContext.current
    var showImageExportDialog by remember { mutableStateOf(false) }
    var pendingBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Launcher for saving the CSV locally
    val saveCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        uri?.let {
            record?.let { r ->
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(ExportHelpers.getCsvString(r).toByteArray())
                }
            }
        }
    }

    // Launcher for saving the Image locally
    val saveImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png")
    ) { uri: Uri? ->
        uri?.let {
            pendingBitmap?.let { bitmap ->
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    Toast.makeText(context, "Image saved successfully!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    record?.let { r ->
        val graphState = rememberGraphState(totalDuration = r.metadata.durationMs)
        
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Detailed Graph", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        // Image Export
                        IconButton(onClick = { showImageExportDialog = true }) {
                            Icon(Icons.Default.Image, contentDescription = "Export Image")
                        }
                        // CSV Save Locally
                        IconButton(onClick = { 
                            saveCsvLauncher.launch("${r.metadata.title.replace(" ", "_")}.csv") 
                        }) {
                            Icon(Icons.Default.Save, contentDescription = "Save CSV Locally")
                        }
                        // CSV Share
                        IconButton(onClick = { ExportHelpers.shareCsv(context, r) }) {
                            Icon(Icons.Default.Share, contentDescription = "Share CSV")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
            ) {
                // The interactive graph
                BpmGraph(
                    record = r,
                    modifier = Modifier.weight(1f),
                    state = graphState
                )

                // Split Recording UI
                AnimatedVisibility(visible = graphState.selectionStartMs != null && graphState.selectionEndMs != null) {
                    val start = graphState.selectionStartMs ?: 0L
                    val end = graphState.selectionEndMs ?: 0L
                    val actualStart = min(start, end)
                    val actualEnd = max(start, end)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Selected Range: ${inga.bpmetrics.ui.graph.TimeUtils.formatMs(actualStart)} - ${inga.bpmetrics.ui.graph.TimeUtils.formatMs(actualEnd)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val selectedPoints = r.dataPoints
                                    .filter { it.timestamp in actualStart..actualEnd }
                                    .map { it.copy(timestamp = it.timestamp - actualStart) } // Reset offset for new record
                                
                                if (selectedPoints.isNotEmpty()) {
                                    val newRecord = BpmWatchRecord(
                                        date = Date(r.metadata.startTime + actualStart),
                                        dataPoints = selectedPoints.map { inga.bpmetrics.core.BpmDataPoint(it.timestamp, it.bpm) },
                                        startTime = r.metadata.startTime + actualStart,
                                        endTime = r.metadata.startTime + actualEnd
                                    )
                                    viewModel.splitRecord(newRecord, "${r.metadata.title} (Split)")
                                    graphState.clearSelection()
                                    Toast.makeText(context, "New record created from split!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ContentCut, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Create New Record from Selection")
                        }
                    }
                }
            }
        }

        if (showImageExportDialog) {
            ImageExportDialog(
                record = r,
                onDismiss = { showImageExportDialog = false },
                onSave = { bitmap, title ->
                    pendingBitmap = bitmap
                    saveImageLauncher.launch("${title.replace(" ", "_")}.png")
                }
            )
        }
    } ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
