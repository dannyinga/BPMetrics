package inga.bpmetrics.ui.graph

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import inga.bpmetrics.BPMetricsApp
import inga.bpmetrics.core.BpmDataPoint
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.export.BpmExportService
import inga.bpmetrics.export.VideoExporter
import inga.bpmetrics.ui.record.BpmRecordViewModel
import inga.bpmetrics.ui.export.ImageExportDialog
import inga.bpmetrics.ui.export.VideoExportDialog
import inga.bpmetrics.ui.export.VideoExportViewModel
import inga.bpmetrics.ui.record.BpmTrio
import java.sql.Date
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BpmGraphDetailScreen(
    viewModel: BpmRecordViewModel,
    onBack: () -> Unit
) {
    val record by viewModel.record.collectAsState()
    val context = LocalContext.current
    
    var showImageExportDialog by remember { mutableStateOf(false) }
    var showVideoExportDialog by remember { mutableStateOf(false) }
    var pendingBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    val isExportingVideo by BpmExportService.isExporting.collectAsState()
    val exportProgress by BpmExportService.exportProgress.collectAsState()

    // Notification Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Notification permission denied. You won't see export progress.", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

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

    // Video Save Launcher - used to choose location first
    var pendingExportConfig by remember { mutableStateOf<VideoExporter.VideoExportConfig?>(null) }
    val chooseVideoLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri: Uri? ->
        uri?.let { targetUri ->
            pendingExportConfig?.let { config ->
                BpmExportService.startExport(context, record?.metadata?.recordId ?: -1L, config, targetUri)
                Toast.makeText(context, "Export started in background...", Toast.LENGTH_SHORT).show()
            }
        }
        pendingExportConfig = null
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
                        IconButton(
                            onClick = { 
                                if (isExportingVideo) {
                                    Toast.makeText(context, "Export in progress...", Toast.LENGTH_SHORT).show()
                                } else {
                                    showVideoExportDialog = true 
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Videocam, 
                                contentDescription = "Export Video",
                                tint = if (isExportingVideo) Color.Gray else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = { showImageExportDialog = true }) {
                            Icon(Icons.Default.Image, contentDescription = "Export Image")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(16.dp))
                    
                    BpmTrio(
                        low = r.minDataPoint?.bpm?.toInt() ?: 0,
                        avg = r.metadata.avg?.toInt() ?: 0,
                        max = r.maxDataPoint?.bpm?.toInt() ?: 0,
                        onLowClick = { r.minDataPoint?.let { graphState.highlightTimestamp(it.timestamp) } },
                        onMaxClick = { r.maxDataPoint?.let { graphState.highlightTimestamp(it.timestamp) } },
                        iconSize = 32.dp,
                        fontSize = 24.sp
                    )
                    
                    Spacer(Modifier.height(16.dp))

                    BpmGraph(
                        record = r,
                        modifier = Modifier.weight(1f),
                        state = graphState
                    )

                    AnimatedVisibility(
                        visible = graphState.selectionStartMs != null && graphState.selectionEndMs != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
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
                                text = "Split Selection",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            
                            Spacer(Modifier.height(8.dp))

                            // Added manual controls for precise selection
                            GraphManualControls(
                                initialStart = TimeUtils.formatMs(actualStart),
                                initialEnd = TimeUtils.formatMs(actualEnd),
                                labelPrefix = "Split",
                                onApply = { s, e -> graphState.setSelection(s, e) }
                            )

                            Spacer(Modifier.height(12.dp))
                            
                            Button(
                                onClick = {
                                    val selectedPoints = r.dataPoints
                                        .filter { it.timestamp in actualStart..actualEnd }
                                        .map { it.copy(timestamp = it.timestamp - actualStart) } 
                                    
                                    if (selectedPoints.isNotEmpty()) {
                                        val newRecord = BpmWatchRecord(
                                            date = Date(r.metadata.startTime + actualStart),
                                            dataPoints = selectedPoints.map { BpmDataPoint(it.timestamp, it.bpm) },
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
        }

        // 1. Get the SettingsRepository
        val settingsRepository = remember { (context.applicationContext as BPMetricsApp).settingsRepository }

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

        if (showVideoExportDialog) {
            // Create the VideoExportViewModel specifically for this record
            val exportViewModel: VideoExportViewModel = viewModel(
                factory = VideoExportViewModel.Factory(r, settingsRepository),
                key = "export_${r.metadata.recordId}" // Keyed to record to prevent state leakage
            )

            VideoExportDialog(
                viewModel = exportViewModel,
                onDismiss = { showVideoExportDialog = false },
                onExport = { config, saveLocally ->
                    if (saveLocally) {
                        pendingExportConfig = config
                        chooseVideoLocationLauncher.launch(
                            "${r.metadata.title.replace(" ", "_")}.mp4"
                        )
                    } else {
                        // Pass recordId directly from metadata
                        BpmExportService.startExport(context, r.metadata.recordId, config, null)
                    }
                }
            )
        }
    } ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
