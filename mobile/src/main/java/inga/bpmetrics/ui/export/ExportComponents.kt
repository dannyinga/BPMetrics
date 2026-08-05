package inga.bpmetrics.ui.export

import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import java.time.ZoneId
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import inga.bpmetrics.BPMetricsApp
import inga.bpmetrics.export.ImageExporter
import inga.bpmetrics.export.VideoExporter
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.ui.components.ExpandableSection
import inga.bpmetrics.ui.graph.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
fun VerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    if (scrollState.maxValue == 0) return

    val density = LocalDensity.current
    val scrollbarWidth = 4.dp
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)

    BoxWithConstraints(modifier = modifier
        .fillMaxHeight()
        .width(scrollbarWidth)
        .background(trackColor, RoundedCornerShape(scrollbarWidth))) {
        val viewPortHeight = constraints.maxHeight.toFloat()
        val contentHeight = viewPortHeight + scrollState.maxValue
        
        val thumbHeight = (viewPortHeight / contentHeight) * viewPortHeight
        val thumbOffset = (scrollState.value.toFloat() / contentHeight) * viewPortHeight
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { thumbHeight.toDp() })
                .padding(top = with(density) { thumbOffset.toDp() })
                .background(thumbColor, RoundedCornerShape(scrollbarWidth))
        )
    }
}

@Composable
fun ImageExportDialog(
    record: BpmRecord,
    onDismiss: () -> Unit,
    onSave: (Bitmap, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepository = (context.applicationContext as BPMetricsApp).settingsRepository
    
    val savedW by settingsRepository.imgWidth.collectAsStateWithLifecycle(initialValue = "1920")
    val savedH by settingsRepository.imgHeight.collectAsStateWithLifecycle(initialValue = "1080")
    val savedO by settingsRepository.imgOpacity.collectAsStateWithLifecycle(initialValue = 100f)
    val savedAxes by settingsRepository.imgShowAxes.collectAsStateWithLifecycle(initialValue = true)
    val savedLabels by settingsRepository.imgShowLabels.collectAsStateWithLifecycle(initialValue = true)
    val savedGrid by settingsRepository.imgShowGrid.collectAsStateWithLifecycle(initialValue = true)
    val savedTitle by settingsRepository.imgShowTitle.collectAsStateWithLifecycle(initialValue = true)

    var widthPx by remember(savedW) { mutableStateOf(savedW) }
    var heightPx by remember(savedH) { mutableStateOf(savedH) }
    var startInput by remember { mutableStateOf(TimeUtils.formatMs(0L)) }
    var endInput by remember { mutableStateOf(TimeUtils.formatMs(record.metadata.durationMs)) }
    var opacity by remember(savedO) { mutableFloatStateOf(savedO) }
    var showAxes by remember(savedAxes) { mutableStateOf(savedAxes) }
    var showLabels by remember(savedLabels) { mutableStateOf(savedLabels) }
    var showGrid by remember(savedGrid) { mutableStateOf(savedGrid) }
    var showTitle by remember(savedTitle) { mutableStateOf(savedTitle) }
    var saveAsDefault by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Graph as Image", fontWeight = FontWeight.Bold) },
        text = {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .verticalScroll(scrollState)
                        .padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Resolution (Pixels)", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = widthPx, onValueChange = { widthPx = it }, label = { Text("Width", fontSize = 12.sp) }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = heightPx, onValueChange = { heightPx = it }, label = { Text("Height", fontSize = 12.sp) }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Text("Time Window", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = startInput, onValueChange = { startInput = it }, label = { Text("Start (H:M:S)", fontSize = 12.sp) }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = endInput, onValueChange = { endInput = it }, label = { Text("End (H:M:S)", fontSize = 12.sp) }, modifier = Modifier.weight(1f))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExportToggle("Show Title", showTitle) { showTitle = it }
                        ExportToggle("Show Axes", showAxes) { showAxes = it }
                        ExportToggle("Show Labels", showLabels) { showLabels = it }
                        ExportToggle("Show Grid", showGrid) { showGrid = it }
                    }
                    Column {
                        Text("Background Opacity: ${opacity.toInt()}%", style = MaterialTheme.typography.titleSmall)
                        Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0f..100f)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { saveAsDefault = !saveAsDefault },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = saveAsDefault, onCheckedChange = { saveAsDefault = it })
                        Text("Save these settings as default", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                VerticalScrollbar(
                    scrollState = scrollState,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val config = createConfig(widthPx, heightPx, startInput, endInput, record, opacity, showAxes, showLabels, showGrid, showTitle)
                    if (saveAsDefault) {
                        scope.launch { settingsRepository.setImageDefaults(config) }
                    }
                    onSave(ImageExporter.renderGraphToBitmap(record, config), record.metadata.title)
                    onDismiss()
                }) { Text("Save") }
//                TextButton(onClick = {
//                    val config = createConfig(widthPx, heightPx, startInput, endInput, record, opacity, showAxes, showLabels, showGrid, showTitle)
//                    if (saveAsDefault) {
//                        scope.launch { settingsRepository.setImageDefaults(config) }
//                    }
//                    ImageExporter.shareBitmap(context, ImageExporter.renderGraphToBitmap(record, config), record.metadata.title)
//                    onDismiss()
//                }) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun VideoExportDialog(
    record: BpmRecord,
    records: List<BpmRecord> = listOf(record),
    graphTitle: String? = null,
    onDismiss: () -> Unit,
    onExport: (VideoExporter.VideoExportConfig, Boolean) -> Unit
) {
    val context = LocalContext.current
    val settingsRepository = remember { inga.bpmetrics.ui.settings.SettingsRepository(context) }
    val viewModel = remember(record) { VideoExportViewModel(record, settingsRepository) }

    VideoExportDialog(
        viewModel = viewModel,
        records = records,
        graphTitle = graphTitle,
        onDismiss = onDismiss,
        onExport = onExport
    )
}

@Composable
fun VideoExportDialog(
    viewModel: VideoExportViewModel,
    records: List<BpmRecord> = listOf(viewModel.record),
    /** Heading for the exported graph. A named analysis passes its name; null keeps the default. */
    graphTitle: String? = null,
    onDismiss: () -> Unit,
    onExport: (VideoExporter.VideoExportConfig, Boolean) -> Unit
) {
    val context = LocalContext.current
    val record = viewModel.record

    // 1. Collect persistent states from ViewModel
    val savedW by viewModel.savedWidth.collectAsStateWithLifecycle()
    val savedH by viewModel.savedHeight.collectAsStateWithLifecycle()
    val savedWin by viewModel.savedWindowSize.collectAsStateWithLifecycle()
    val savedFPS by viewModel.savedFrameRate.collectAsStateWithLifecycle()
    val savedO by viewModel.savedOpacity.collectAsStateWithLifecycle()
    val savedAxes by viewModel.savedShowAxes.collectAsStateWithLifecycle()
    val savedLabels by viewModel.savedShowLabels.collectAsStateWithLifecycle()
    val savedGrid by viewModel.savedShowGrid.collectAsStateWithLifecycle()
    val savedTitle by viewModel.savedShowTitle.collectAsStateWithLifecycle()
    val savedStats by viewModel.savedShowStats.collectAsStateWithLifecycle()
    val savedLock by viewModel.savedLockAspect.collectAsStateWithLifecycle()
    val globalSyncOffset by viewModel.savedSyncOffset.collectAsStateWithLifecycle()
    val lastGraphRect by viewModel.savedGraphRect.collectAsStateWithLifecycle()
    val defaultTz by viewModel.defaultTimeZone.collectAsStateWithLifecycle()

    val suggestedVideos by produceState(initialValue = emptyList(), records) {
        value = withContext(Dispatchers.IO) {
            VideoExporter.getOverlappingVideos(context, records)
        }.reversed()
    }

    var hasPermission by remember { mutableStateOf(VideoExporter.hasVideoPermissions(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
    }

    // 2. UI States (Initialized from persistent values)
    var overlayVideoUri by remember { mutableStateOf<Uri?>(null) }
    var previewFrame by remember { mutableStateOf<Bitmap?>(null) }
    var selectedTimeZoneId by remember(defaultTz) { mutableStateOf(defaultTz) }
    var showTzDialog by remember { mutableStateOf(false) }
    var customRecordColors by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    // Multiple records default to clock time so a single video stays in sync with all of them.
    var alignByElapsedTime by remember(records) { mutableStateOf(records.size <= 1) }
    var showMultiWatchSettings by remember { mutableStateOf(true) }
    var graphRect by remember {
        mutableStateOf(RectF(0f, 0f, 1f, 1f))
    }

    var videoWidthPx by remember(savedW) { mutableStateOf(savedW) }
    var videoHeightPx by remember(savedH) { mutableStateOf(savedH) }
    var appliedWidth by remember(savedW) { mutableIntStateOf(savedW.toIntOrNull() ?: 1280) }
    var appliedHeight by remember(savedH) { mutableIntStateOf(savedH.toIntOrNull() ?: 720) }

    var lockAspectRatio by remember(savedLock) { mutableStateOf(savedLock) }
    var lockRatio by remember(savedW, savedH) {
        val w = savedW.toFloatOrNull() ?: 1280f
        val h = savedH.toFloatOrNull() ?: 720f
        mutableFloatStateOf(w / h.coerceAtLeast(1f))
    }

    // The axis every time field in this dialog is expressed against. With one record it is that
    // record's own elapsed time; with several it spans from the earliest session start to the
    // last sample of whichever session ended last.
    val timeline by remember(records, alignByElapsedTime) {
        mutableStateOf(ImageExporter.timelineFor(records, alignByElapsedTime))
    }

    var syncTrigger by remember { mutableIntStateOf(0) }
    var videoAlignStartMs by remember { mutableStateOf(0L) }
    var videoAlignEndMs by remember(timeline) { mutableStateOf(timeline.durationMs) }
    var cropStartMs by remember { mutableStateOf(0L) }
    var cropEndMs by remember(timeline) { mutableStateOf(timeline.durationMs) }

    var inputMode by remember {
        mutableStateOf(
            if (overlayVideoUri != null) TimeInputMode.VIDEO_TIME else TimeInputMode.RECORD_TIME
        )
    }

    var startInput by remember { mutableStateOf("") }
    var endInput by remember { mutableStateOf("") }

    val formatTimeForMode = { offsetMs: Long, mode: TimeInputMode ->
        when (mode) {
            TimeInputMode.RECORD_TIME -> TimeUtils.formatMs(offsetMs)
            TimeInputMode.CLOCK_TIME -> TimeUtils.formatClockTime(record.metadata.startTime + offsetMs, java.time.ZoneId.of(selectedTimeZoneId))
            TimeInputMode.VIDEO_TIME -> {
                if (overlayVideoUri != null) {
                    TimeUtils.formatMs(offsetMs - videoAlignStartMs)
                } else {
                    TimeUtils.formatMs(offsetMs)
                }
            }
        }
    }

    val parseTimeForMode = { input: String, mode: TimeInputMode ->
        when (mode) {
            TimeInputMode.RECORD_TIME -> TimeUtils.parseToMs(input)
            TimeInputMode.CLOCK_TIME -> TimeUtils.parseClockTimeToRelativeMs(input, record.metadata.startTime, java.time.ZoneId.of(selectedTimeZoneId))
            TimeInputMode.VIDEO_TIME -> {
                if (overlayVideoUri != null) {
                    TimeUtils.parseToMs(input)?.let { it + videoAlignStartMs }
                } else {
                    TimeUtils.parseToMs(input)
                }
            }
        }
    }

    val onInputModeChanged = { newMode: TimeInputMode ->
        inputMode = newMode
        startInput = formatTimeForMode(cropStartMs, newMode)
        endInput = formatTimeForMode(cropEndMs, newMode)
    }

    LaunchedEffect(selectedTimeZoneId) {
        startInput = formatTimeForMode(cropStartMs, inputMode)
        endInput = formatTimeForMode(cropEndMs, inputMode)
    }

    val parsedStart = parseTimeForMode(startInput, inputMode)
    val parsedEnd = parseTimeForMode(endInput, inputMode)
    val isTimeRangeValid = parsedStart != null && parsedEnd != null && parsedStart < parsedEnd

    var windowSizeSec by remember(savedWin) { mutableStateOf(savedWin) }
    var frameRateInput by remember(savedFPS) { mutableStateOf(savedFPS) }
    var opacity by remember(savedO) { mutableFloatStateOf(savedO) }
    var overlayScale by remember { mutableFloatStateOf(1.0f) }

    var showAxes by remember(savedAxes) { mutableStateOf(savedAxes) }
    var showLabels by remember(savedLabels) { mutableStateOf(savedLabels) }
    var showGrid by remember(savedGrid) { mutableStateOf(savedGrid) }
    var showTitle by remember(savedTitle) { mutableStateOf(savedTitle) }
    var showCurrentStats by remember(savedStats) { mutableStateOf(savedStats) }
    var saveAsDefault by remember { mutableStateOf(false) }

    // Expandable states
    var showVideoSource by remember { mutableStateOf(true) }
    var showResSettings by remember { mutableStateOf(false) }
    var showOverlaySettings by remember { mutableStateOf(false) } // Keep preview visible
    var showTimingSettings by remember { mutableStateOf(false) }
    var showVisualSettings by remember { mutableStateOf(false) }


    val scrollState = rememberScrollState()

    val scope = rememberCoroutineScope()

    val onVideoSelected = { pickedUri: Uri ->
        overlayVideoUri = pickedUri
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, pickedUri)
                    val frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

                    // 1. Auto-Orientation logic
                    val vW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val vH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    val fps = VideoExporter.getVideoFrameRate(context, pickedUri)
                    Triple(frame, Triple(vW, vH, rot), fps)
                } catch (e: Exception) {
                    Log.e("VideoExport", "Metadata failed", e)
                    null
                } finally {
                    retriever.release()
                }
            }

            result?.let { (frame, dimensions, fps) ->
                previewFrame = frame
                val (vW, vH, rot) = dimensions
                val isVidPortrait = if (rot == 90 || rot == 270) vW > vH else vH > vW
                val isUiPortrait = appliedHeight > appliedWidth
                if (isVidPortrait != isUiPortrait) {
                    val tw = videoWidthPx; videoWidthPx = videoHeightPx; videoHeightPx = tw
                    val taw = appliedWidth; appliedWidth = appliedHeight; appliedHeight = taw
                    lockRatio = appliedWidth.toFloat() / appliedHeight.toFloat().coerceAtLeast(1f)
                }

                // Auto-detect frame rate
                fps?.let {
                    frameRateInput = it.toString()
                }

                // 2. Smart Placement: Change from Full Screen to "Bottom Strip"
                if (!lastGraphRect.isEmpty && lastGraphRect != RectF(0f, 0f, 1f, 1f)) {
                    graphRect = lastGraphRect
                } else {
                    val isPortrait = appliedHeight > appliedWidth
                    val refWidth = if (isPortrait) 1080f else 1920f
                    val refHeight = if (isPortrait) 1920f else 1080f
                    val nW = (700f * overlayScale / refWidth).coerceIn(0.1f, 0.9f)
                    val nH = (280f * overlayScale / refHeight).coerceIn(0.1f, 0.9f)
                    graphRect = RectF(0.5f - nW/2, 0.95f - nH, 0.5f + nW/2, 0.95f)
                }
            }
        }
    }

    // --- Video Picker Launcher ---
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onVideoSelected(it) }}

    // --- AUTO-SYNC LOGIC ---
    // This effect runs when video changes or button is clicked
    LaunchedEffect(overlayVideoUri, syncTrigger) {
        if (overlayVideoUri != null) {
            val (startOffset, endOffset) = withContext(Dispatchers.IO) {
                VideoExporter.calculateVideoAlignment(
                    context,
                    overlayVideoUri!!,
                    timeline.originWallClockMs,
                    globalSyncOffset
                )
            }
            videoAlignStartMs = startOffset
            videoAlignEndMs = endOffset
            cropStartMs = startOffset
            cropEndMs = endOffset
            inputMode = TimeInputMode.VIDEO_TIME
            startInput = formatTimeForMode(startOffset, TimeInputMode.VIDEO_TIME)
            endInput = formatTimeForMode(endOffset, TimeInputMode.VIDEO_TIME)

            Toast.makeText(
                context,
                "Synced to video timeline",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            // Reset to default (0 to duration) if no video is present
            videoAlignStartMs = 0L
            videoAlignEndMs = timeline.durationMs
            cropStartMs = 0L
            cropEndMs = timeline.durationMs
            inputMode = TimeInputMode.RECORD_TIME
            startInput = formatTimeForMode(0L, TimeInputMode.RECORD_TIME)
            endInput = formatTimeForMode(timeline.durationMs, TimeInputMode.RECORD_TIME)
        }
    }

    // Only seed the graphRect if we already have a video selected
    // (e.g. if the dialog was recreated) or if we want to honor
    // the user's explicit request to start at full screen.
    LaunchedEffect(lastGraphRect) {
        // If we already have a video picked (from a previous action)
        // and a valid saved rect exists, use it.
        if (overlayVideoUri != null && !lastGraphRect.isEmpty) {
            graphRect = lastGraphRect
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Video Overlay", fontWeight = FontWeight.Bold) },
        text = {
            // Use a Column for the whole dialog content
            Column(modifier = Modifier.fillMaxWidth()) {

                // --- PERMANENT PREVIEW (Pinned at top) ---
                // We wrap this in a Box with a fixed height so Portrait videos
                // don't push all the settings off the screen.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    contentAlignment = Alignment.Center
                ) {
                    VideoOverlayPreview(
                        modifier = Modifier.fillMaxHeight(),
                        previewFrame = previewFrame,
                        graphRect = graphRect,
                        onRectChange = { graphRect = it },
                        aspectRatio = appliedWidth.toFloat() / appliedHeight.toFloat().coerceAtLeast(1f)
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()

                // --- SCROLLABLE SETTINGS ---
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // --- VIDEO SOURCE SECTION ---
                        ExpandableSection(
                            title = "Video Source",
                            isExpanded = showVideoSource,
                            onToggle = { showVideoSource = !showVideoSource }
                        ) {
                            // 1. Browse Button
                            Button(
                                onClick = { launcher.launch("video/*") },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.VideoLibrary, null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (overlayVideoUri == null) "Browse All Videos" else "Change Video")
                            }

                            // 2. Suggested Videos
                            if (!hasPermission) {
                                Button(onClick = { permissionLauncher.launch(VideoExporter.getVideoPermissionString()) }) {
                                    Text("Enable Permissions for Suggestions")
                                }
                            } else {
                                if (suggestedVideos.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Suggested (Matching Time)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = PaddingValues(vertical = 4.dp)
                                    ) {
                                        items(suggestedVideos) { uri ->
                                            VideoThumbnailCard(uri) {
                                                onVideoSelected(uri)
                                                // Auto-close section once a video is picked
                                                showVideoSource = false
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 3. Graph Placement Section
                        ExpandableSection(
                            title = "Graph Placement",
                            isExpanded = showOverlaySettings,
                            onToggle = { showOverlaySettings = !showOverlaySettings }
                        ) {
                            // DYNAMIC SCALING LOGIC:
                            val baseTargetW = 700f
                            val baseTargetH = 280f

                            // Apply the user's scale slider to the base pixel targets
                            val targetW = baseTargetW * overlayScale
                            val targetH = baseTargetH * overlayScale

                            // Normalize these pixels based on orientation-aware reference resolution
                            val isPortrait = appliedHeight > appliedWidth
                            val refWidth = if (isPortrait) 1080f else 1920f
                            val refHeight = if (isPortrait) 1920f else 1080f
                            val w = (targetW / refWidth).coerceIn(0.05f, 1f)
                            val h = (targetH / refHeight).coerceIn(0.05f, 1f)
                            val m = 0.05f // 5% margin from edges

                            // --- Size Slider ---
                            Text(
                                "Overlay Size: ${(overlayScale * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Slider(
                                value = overlayScale,
                                onValueChange = { overlayScale = it },
                                valueRange = 0.25f..2.5f, // Range from 25% to 250% size
                                modifier = Modifier.height(24.dp)
                            )

                            // NEW: Sync the current graphRect to the new scale in real-time
                            LaunchedEffect(overlayScale) {
                                // Don't rescale if it's currently in "Full Screen" mode
                                if (graphRect.left != 0f || graphRect.top != 0f ||
                                    graphRect.right != 1f || graphRect.bottom != 1f) {

                                    val centerX = graphRect.centerX()
                                    val centerY = graphRect.centerY()

                                    // Update the rect while keeping it centered where it currently is
                                    graphRect = RectF(
                                        (centerX - w / 2).coerceAtLeast(0f),
                                        (centerY - h / 2).coerceAtLeast(0f),
                                        (centerX + w / 2).coerceAtMost(1f),
                                        (centerY + h / 2).coerceAtMost(1f)
                                    )
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            OutlinedButton(
                                onClick = {
                                    graphRect = RectF(0f, 0f, 1f, 1f)
                                    overlayScale = 1.0f // Reset scale too
                                },
                                modifier = Modifier.fillMaxWidth().height(36.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Fullscreen, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Reset to Full Screen", fontSize = 12.sp)
                            }

                            Spacer(Modifier.height(8.dp))

                            Text("Edge Anchors", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                PresetButton("Top") { graphRect = RectF(0.5f - w/2, m, 0.5f + w/2, m + h) }
                                PresetButton("Bottom") { graphRect = RectF(0.5f - w/2, 1f - h - m, 0.5f + w/2, 1f - m) }
                                PresetButton("Left") { graphRect = RectF(m, 0.5f - h/2, m + w, 0.5f + h/2) }
                                PresetButton("Right") { graphRect = RectF(1f - w - m, 0.5f - h/2, 1f - m, 0.5f + h/2) }
                            }

                            Spacer(Modifier.height(4.dp))

                            Text("Corner Anchors", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                PresetButton("TL") { graphRect = RectF(m, m, m + w, m + h) }
                                PresetButton("TR") { graphRect = RectF(1f - w - m, m, 1f - m, m + h) }
                                PresetButton("BL") { graphRect = RectF(m, 1f - h - m, m + w, 1f - m) }
                                PresetButton("BR") { graphRect = RectF(1f - w - m, 1f - h - m, 1f - m, 1f - m) }
                            }
                        }

                        // 4. Resolution Section
                        ExpandableSection(
                            title = "Video Resolution",
                            isExpanded = showResSettings,
                            onToggle = { showResSettings = !showResSettings }
                        ) {
                            Text("Resolution Presets", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf("360p" to 360, "720p" to 720, "1080p" to 1080, "4K" to 2160).forEach { (label, size) ->
                                    PresetButton(label) {
                                        val isPortrait = appliedHeight > appliedWidth
                                        if (isPortrait) {
                                            appliedWidth = size
                                            appliedHeight = (size * (16f / 9f)).toInt()
                                        } else {
                                            appliedWidth = (size * (16f / 9f)).toInt()
                                            appliedHeight = size
                                        }
                                        videoWidthPx = appliedWidth.toString()
                                        videoHeightPx = appliedHeight.toString()
                                        lockRatio = appliedWidth.toFloat() / appliedHeight.toFloat().coerceAtLeast(1f)
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = videoWidthPx,
                                    onValueChange = { newValue ->
                                        videoWidthPx = newValue
                                        newValue.toIntOrNull()?.let { w ->
                                            appliedWidth = w
                                            if (lockAspectRatio) {
                                                val h = (w / lockRatio).toInt()
                                                videoHeightPx = h.toString()
                                                appliedHeight = h
                                            }
                                        }
                                    },
                                    label = { Text("Width") },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )

                                IconButton(onClick = {
                                    val tw = videoWidthPx; videoWidthPx = videoHeightPx; videoHeightPx = tw
                                    val taw = appliedWidth; appliedWidth = appliedHeight; appliedHeight = taw
                                    lockRatio = appliedWidth.toFloat() / appliedHeight.toFloat().coerceAtLeast(1f)
                                }) { Icon(Icons.Default.SyncAlt, null) }

                                OutlinedTextField(
                                    value = videoHeightPx,
                                    onValueChange = { newValue ->
                                        videoHeightPx = newValue
                                        newValue.toIntOrNull()?.let { h ->
                                            appliedHeight = h
                                            if (lockAspectRatio) {
                                                val w = (h * lockRatio).toInt()
                                                videoWidthPx = w.toString()
                                                appliedWidth = w
                                            }
                                        }
                                    },
                                    label = { Text("Height") },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = lockAspectRatio, onCheckedChange = { lockAspectRatio = it })
                                Text("Lock Aspect Ratio", style = MaterialTheme.typography.bodySmall)
                            }
                        }

// 5. Timing & Sync Section
                        ExpandableSection(
                            title = "Timing & Sync",
                            isExpanded = showTimingSettings,
                            onToggle = { showTimingSettings = !showTimingSettings }
                        ) {
                            if (overlayVideoUri != null) {
                                Button(
                                    onClick = { syncTrigger++ },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                ) {
                                    Icon(Icons.Default.Sync, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Auto-sync Graph to Video Start", fontSize = 12.sp)
                                }

                                Spacer(Modifier.height(8.dp))
                            }

                            Text(
                                text = "Input Mode",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 2.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val modes = if (overlayVideoUri != null) {
                                    listOf(
                                        TimeInputMode.VIDEO_TIME to "Video Time",
                                        TimeInputMode.CLOCK_TIME to "Clock Time",
                                        TimeInputMode.RECORD_TIME to "Record Time"
                                    )
                                } else {
                                    listOf(
                                        TimeInputMode.CLOCK_TIME to "Clock Time",
                                        TimeInputMode.RECORD_TIME to "Record Time"
                                    )
                                }

                                modes.forEach { (mode, label) ->
                                    val isSelected = inputMode == mode
                                    OutlinedButton(
                                        onClick = { onInputModeChanged(mode) },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(label, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = startInput,
                                    onValueChange = { 
                                        startInput = it
                                        parseTimeForMode(it, inputMode)?.let { ms ->
                                            cropStartMs = ms
                                        }
                                    },
                                    label = { Text("Start Time") },
                                    isError = parsedStart == null,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = endInput,
                                    onValueChange = { 
                                        endInput = it
                                        parseTimeForMode(it, inputMode)?.let { ms ->
                                            cropEndMs = ms
                                        }
                                    },
                                    label = { Text("End Time") },
                                    isError = parsedEnd == null,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (!isTimeRangeValid) {
                                val errorMsg = when {
                                    parsedStart == null -> "Invalid start time format"
                                    parsedEnd == null -> "Invalid end time format"
                                    parsedStart >= parsedEnd -> "Start time must be before end time"
                                    else -> ""
                                }
                                Text(
                                    text = errorMsg,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            OutlinedTextField(
                                value = windowSizeSec,
                                onValueChange = { windowSizeSec = it },
                                label = { Text("Scrolling Window Size (Seconds)") },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            
                            OutlinedTextField(
                                value = frameRateInput,
                                onValueChange = { frameRateInput = it },
                                label = { Text("Frame Rate (FPS)") },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )

                            Spacer(Modifier.height(8.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showTzDialog = true }
                            ) {
                                OutlinedTextField(
                                    value = selectedTimeZoneId,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Display Time Zone") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.AccessTime,
                                            contentDescription = null
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = false,
                                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }

                        // --- VISUALS SECTION ---
                        ExpandableSection(
                            "Visuals",
                            showVisualSettings,
                            { showVisualSettings = !showVisualSettings }) {
                            ExportToggle("Show HUD Stats", showCurrentStats) {
                                showCurrentStats = it
                            }
                            ExportToggle("Show Axes", showAxes) { showAxes = it }
                            ExportToggle("Show Labels", showLabels) { showLabels = it }
                            ExportToggle("Show Grid", showGrid) { showGrid = it }
                            ExportToggle("Show Title", showTitle) { showTitle = it }

                            Text(
                                "Opacity: ${opacity.toInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Slider(
                                value = opacity,
                                onValueChange = { opacity = it },
                                valueRange = 0f..100f
                            )
                        }

                        if (records.size > 1) {
                            ExpandableSection(
                                "Multi-Watch Colors & Alignment (${records.size} wearers)",
                                showMultiWatchSettings,
                                { showMultiWatchSettings = !showMultiWatchSettings }
                            ) {
                                ExportToggle(
                                    label = "Stack all timelines from 0:00",
                                    checked = alignByElapsedTime,
                                    onCheckedChange = { alignByElapsedTime = it }
                                )
                                Text(
                                    if (alignByElapsedTime) {
                                        "Every wearer starts at 0:00, for comparing shapes. " +
                                            "A video can only stay in sync with one of them."
                                    } else {
                                        "Wearers sit at the time they were actually recorded, so " +
                                            "one video stays in sync with all of them."
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Tap a color swatch to customize each wearer's graph line:",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(4.dp))

                                val paletteOptions = listOf(
                                    0xFF00E5FF.toInt(), // Cyan
                                    0xFFFF5252.toInt(), // Coral Red
                                    0xFF00E676.toInt(), // Emerald Green
                                    0xFFE040FB.toInt(), // Purple
                                    0xFFFFD700.toInt(), // Amber Gold
                                    0xFF2979FF.toInt(), // Electric Blue
                                    0xFFFF9100.toInt(), // Orange
                                    0xFFFF4081.toInt()  // Pink
                                )

                                records.forEachIndexed { index, rec ->
                                    val wearerLabel = rec.metadata.wearerName.ifBlank { rec.metadata.deviceId.ifBlank { rec.metadata.title } }
                                    val defaultColor = ImageExporter.MULTI_WATCH_PALETTES[index % ImageExporter.MULTI_WATCH_PALETTES.size][0]
                                    val currentColor = customRecordColors[rec.metadata.recordId] ?: defaultColor

                                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                        Text(text = "👤 $wearerLabel", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            paletteOptions.forEach { colorInt ->
                                                val isSelected = colorInt == currentColor
                                                Box(
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(colorInt))
                                                        .border(
                                                            width = if (isSelected) 2.dp else 0.dp,
                                                            color = if (isSelected) Color.White else Color.Transparent,
                                                            shape = CircleShape
                                                        )
                                                        .clickable {
                                                            customRecordColors = customRecordColors + (rec.metadata.recordId to colorInt)
                                                        }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { saveAsDefault = !saveAsDefault },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = saveAsDefault, onCheckedChange = { saveAsDefault = it })
                            Text("Save these settings as default", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    VerticalScrollbar(scrollState, Modifier.align(Alignment.CenterEnd))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Prepare Video Config
                    val config = prepareVideoConfig(
                        videoWidth = videoWidthPx,
                        videoHeight = videoHeightPx,
                        startTimeMs = cropStartMs,
                        endTimeMs = cropEndMs,
                        windowSizeSec = windowSizeSec,
                        frameRate = frameRateInput,
                        opacity = opacity,
                        showAxes = showAxes,
                        showLabels = showLabels,
                        showGrid = showGrid,
                        showTitle = showTitle,
                        showCurrentStats = showCurrentStats,
                        overlayVideoUri = overlayVideoUri,
                        record = record,
                        graphRect = graphRect,
                        syncOffsetMs = globalSyncOffset,
                        timeZoneId = selectedTimeZoneId,
                        records = records,
                        customRecordColors = customRecordColors,
                        alignByElapsedTime = alignByElapsedTime,
                        graphTitle = graphTitle
                    )

                    if (saveAsDefault) {
                        viewModel.saveLastUsedSettings(config) // Save settings
                    }
                    onExport(config, true)            // Trigger export
                    onDismiss()
                },
                enabled = isTimeRangeValid
            ) { Text("Export") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (showTzDialog) {
        var tzSearchQuery by remember { mutableStateOf("") }
        val availableZones = remember { java.time.ZoneId.getAvailableZoneIds().sorted() }
        val filteredZones = remember(tzSearchQuery) {
            if (tzSearchQuery.isBlank()) {
                val priorityZones = listOf(java.time.ZoneId.systemDefault().id, "UTC", "GMT")
                (priorityZones + availableZones.filter { it !in priorityZones }).take(50)
            } else {
                availableZones.filter { it.contains(tzSearchQuery, ignoreCase = true) }.take(50)
            }
        }

        AlertDialog(
            onDismissRequest = { showTzDialog = false },
            title = { Text("Select Time Zone") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = tzSearchQuery,
                        onValueChange = { tzSearchQuery = it },
                        label = { Text("Search Time Zone") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.height(200.dp).fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            filteredZones.forEach { zoneId ->
                                Text(
                                    text = zoneId,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedTimeZoneId = zoneId
                                            showTzDialog = false
                                        }
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                HorizontalDivider()
                            }
                            if (filteredZones.isEmpty()) {
                                Text(
                                    text = "No time zones match search",
                                    color = Color.Gray,
                                    modifier = Modifier.padding(8.dp)
                                        .align(Alignment.CenterHorizontally)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTzDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

private enum class DragMode { MOVE, RESIZE_LT, RESIZE_RT, RESIZE_LB, RESIZE_RB, NONE }
private enum class TimeInputMode { VIDEO_TIME, CLOCK_TIME, RECORD_TIME }

@Composable
fun VideoOverlayPreview(
    modifier: Modifier = Modifier,
    previewFrame: Bitmap?,
    graphRect: RectF,
    onRectChange: (RectF) -> Unit,
    aspectRatio: Float = 16f / 9f
) {

    val currentRect by rememberUpdatedState(graphRect)
    val currentOnRectChange by rememberUpdatedState(onRectChange)
    val density = LocalDensity.current
    val touchSlop = with(density) { 24.dp.toPx() } // Slightly tighter slop


    Column(modifier = modifier.aspectRatio(aspectRatio)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
        ) {
            if (previewFrame != null) {
                Image(
                    bitmap = previewFrame.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            var dragMode by remember { mutableStateOf(DragMode.NONE) }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val l = currentRect.left * size.width
                                val t = currentRect.top * size.height
                                val r = currentRect.right * size.width
                                val b = currentRect.bottom * size.height

                                val nearL = abs(offset.x - l) < touchSlop
                                val nearR = abs(offset.x - r) < touchSlop
                                val nearT = abs(offset.y - t) < touchSlop
                                val nearB = abs(offset.y - b) < touchSlop

                                dragMode = when {
                                    nearL && nearT -> DragMode.RESIZE_LT
                                    nearR && nearT -> DragMode.RESIZE_RT
                                    nearL && nearB -> DragMode.RESIZE_LB
                                    nearR && nearB -> DragMode.RESIZE_RB
                                    offset.x in l..r && offset.y in t..b -> DragMode.MOVE
                                    else -> DragMode.NONE
                                }
                            },
                            onDrag = { change, dragAmount ->
                                if (dragMode == DragMode.NONE) return@detectDragGestures
                                change.consume()

                                val dx = dragAmount.x / size.width
                                val dy = dragAmount.y / size.height
                                val nr = RectF(currentRect)

                                when (dragMode) {
                                    DragMode.MOVE -> {
                                        nr.offset(dx, dy)
                                        // Robust Boundary Clamping
                                        if (nr.left < 0) nr.offset(-nr.left, 0f)
                                        if (nr.top < 0) nr.offset(0f, -nr.top)
                                        if (nr.right > 1) nr.offset(1f - nr.right, 0f)
                                        if (nr.bottom > 1) nr.offset(0f, 1f - nr.bottom)
                                    }

                                    DragMode.RESIZE_LT -> {
                                        nr.left = (nr.left + dx).coerceIn(0f, nr.right - 0.15f)
                                        nr.top = (nr.top + dy).coerceIn(0f, nr.bottom - 0.1f)
                                    }

                                    DragMode.RESIZE_RT -> {
                                        nr.right = (nr.right + dx).coerceIn(nr.left + 0.15f, 1f)
                                        nr.top = (nr.top + dy).coerceIn(0f, nr.bottom - 0.1f)
                                    }

                                    DragMode.RESIZE_LB -> {
                                        nr.left = (nr.left + dx).coerceIn(0f, nr.right - 0.15f)
                                        nr.bottom = (nr.bottom + dy).coerceIn(nr.top + 0.1f, 1f)
                                    }

                                    DragMode.RESIZE_RB -> {
                                        nr.right = (nr.right + dx).coerceIn(nr.left + 0.15f, 1f)
                                        nr.bottom = (nr.bottom + dy).coerceIn(nr.top + 0.1f, 1f)
                                    }

                                    else -> {}
                                }
                                currentOnRectChange(nr)
                            },
                            onDragEnd = { dragMode = DragMode.NONE }
                        )
                    }
            ) {
                // Drawing logic (The Cyan box) remains similar but uses stroke for better visibility
                val drawLeft = graphRect.left * size.width
                val drawTop = graphRect.top * size.height
                val drawWidth = graphRect.width() * size.width
                val drawHeight = graphRect.height() * size.height

                // Main Rect
                drawRect(
                    color = Color.Cyan.copy(alpha = 0.15f),
                    topLeft = Offset(drawLeft, drawTop),
                    size = Size(drawWidth, drawHeight)
                )
                drawRect(
                    color = Color.Cyan,
                    topLeft = Offset(drawLeft, drawTop),
                    size = Size(drawWidth, drawHeight),
                    style = Stroke(width = 2.dp.toPx())
                )

                // Corner Handles (Enlarged for touch feedback)
                val hSize = 14.dp.toPx()
                val handleColor = Color.White
                listOf(
                    Offset(drawLeft, drawTop),
                    Offset(drawLeft + drawWidth, drawTop),
                    Offset(drawLeft, drawTop + drawHeight),
                    Offset(drawLeft + drawWidth, drawTop + drawHeight)
                ).forEach { pos ->
                    drawCircle(Color.Cyan, radius = hSize / 2, center = pos)
                    drawCircle(handleColor, radius = hSize / 3, center = pos)
                }
            }
        }
    }
}


@Composable
private fun RowScope.PresetButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f).height(32.dp),
        contentPadding = PaddingValues(0.dp),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

private fun prepareVideoConfig(
    videoWidth: String,
    videoHeight: String,
    startTimeMs: Long,
    endTimeMs: Long,
    windowSizeSec: String,
    frameRate: String,
    opacity: Float,
    showAxes: Boolean,
    showLabels: Boolean,
    showGrid: Boolean,
    showTitle: Boolean,
    showCurrentStats: Boolean,
    overlayVideoUri: Uri?,
    record: BpmRecord,
    graphRect: RectF,
    syncOffsetMs: Long,
    timeZoneId: String,
    records: List<BpmRecord> = emptyList(),
    customRecordColors: Map<Long, Int> = emptyMap(),
    alignByElapsedTime: Boolean = true,
    graphTitle: String? = null
): VideoExporter.VideoExportConfig {
    val windowMs = (windowSizeSec.toLongOrNull() ?: 30L) * 1000L
    val fps = frameRate.toIntOrNull() ?: 30
    
    val imageConfig = ImageExporter.ImageExportConfig(
        width = videoWidth.toIntOrNull() ?: 1280,
        height = videoHeight.toIntOrNull() ?: 720,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        backgroundOpacity = opacity.toInt(),
        showAxes = showAxes,
        showLabels = showLabels,
        showGrid = showGrid,
        showTitle = showTitle,
        showCurrentStats = showCurrentStats,
        timeZoneId = timeZoneId,
        customRecordColors = customRecordColors,
        alignByElapsedTime = alignByElapsedTime,
        graphTitle = graphTitle
    )
    
    return VideoExporter.VideoExportConfig(
        imageConfig = imageConfig,
        windowSizeMs = windowMs,
        frameRate = fps,
        overlayVideoUri = overlayVideoUri,
        graphRect = graphRect,
        syncOffsetMs = syncOffsetMs,
        records = records
    )
}

@Composable
private fun ExportToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.8f) // Smaller switch looks better in dialogs
        )
    }
}

private fun createConfig(w: String, h: String, s: String, e: String, r: BpmRecord, o: Float, ax: Boolean, l: Boolean, g: Boolean, t: Boolean) : ImageExporter.ImageExportConfig {
    val start = TimeUtils.parseToMs(s) ?: 0L
    val end = TimeUtils.parseToMs(e) ?: r.metadata.durationMs
    return ImageExporter.ImageExportConfig(
        width = w.toIntOrNull() ?: 1920, 
        height = h.toIntOrNull() ?: 1080,
        startTimeMs = start,
        endTimeMs = end,
        backgroundOpacity = o.toInt(), 
        showAxes = ax, 
        showLabels = l, 
        showGrid = g, 
        showTitle = t
    )
}

@Composable
fun VideoThumbnailCard(
    uri: Uri,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    // Asynchronously load the thumbnail to prevent UI jank
    val thumbnail by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.loadThumbnail(uri, android.util.Size(300, 200), null)
            } catch (e: Exception) {
                null
            }
        }
    }

    Card(
        modifier = Modifier
            .size(width = 120.dp, height = 80.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.DarkGray),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Color.White)
                }
            }
        }
    }
}
