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
import androidx.compose.foundation.clickable
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
    viewModel: VideoExportViewModel,
    onDismiss: () -> Unit,
    onExport: (VideoExporter.VideoExportConfig, Boolean) -> Unit
) {
    val context = LocalContext.current
    val record = viewModel.record

    // 1. Collect persistent states from ViewModel
    val savedW by viewModel.savedWidth.collectAsStateWithLifecycle()
    val savedH by viewModel.savedHeight.collectAsStateWithLifecycle()
    val savedWin by viewModel.savedWindowSize.collectAsStateWithLifecycle()
    val savedO by viewModel.savedOpacity.collectAsStateWithLifecycle()
    val savedAxes by viewModel.savedShowAxes.collectAsStateWithLifecycle()
    val savedLabels by viewModel.savedShowLabels.collectAsStateWithLifecycle()
    val savedGrid by viewModel.savedShowGrid.collectAsStateWithLifecycle()
    val savedTitle by viewModel.savedShowTitle.collectAsStateWithLifecycle()
    val savedStats by viewModel.savedShowStats.collectAsStateWithLifecycle()
    val savedLock by viewModel.savedLockAspect.collectAsStateWithLifecycle()
    val globalSyncOffset by viewModel.savedSyncOffset.collectAsStateWithLifecycle()
    val lastGraphRect by viewModel.savedGraphRect.collectAsStateWithLifecycle()

    val suggestedVideos by produceState(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) {
            VideoExporter.getOverlappingVideos(context, record)
        }
    }

    var hasPermission by remember { mutableStateOf(VideoExporter.hasVideoPermissions(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
    }

    // 2. UI States (Initialized from persistent values)
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

    var syncTrigger by remember { mutableIntStateOf(0) }
    var startInput by remember { mutableStateOf(TimeUtils.formatMs(0L)) }
    var endInput by remember { mutableStateOf(TimeUtils.formatMs(record.metadata.durationMs)) }
    var windowSizeSec by remember(savedWin) { mutableStateOf(savedWin) }
    var opacity by remember(savedO) { mutableFloatStateOf(savedO) }
    var overlayScale by remember { mutableFloatStateOf(1.0f) }

    var showAxes by remember(savedAxes) { mutableStateOf(savedAxes) }
    var showLabels by remember(savedLabels) { mutableStateOf(savedLabels) }
    var showGrid by remember(savedGrid) { mutableStateOf(savedGrid) }
    var showTitle by remember(savedTitle) { mutableStateOf(savedTitle) }
    var showCurrentStats by remember(savedStats) { mutableStateOf(savedStats) }
    var saveAsDefault by remember { mutableStateOf(false) }

    var overlayVideoUri by remember { mutableStateOf<Uri?>(null) }
    var previewFrame by remember { mutableStateOf<Bitmap?>(null) }
    var graphRect by remember {
        mutableStateOf(RectF(0f, 0f, 1f, 1f))
    }

    // Expandable states
    var showVideoSource by remember { mutableStateOf(true) }
    var showResSettings by remember { mutableStateOf(false) }
    var showOverlaySettings by remember { mutableStateOf(false) } // Keep preview visible
    var showTimingSettings by remember { mutableStateOf(false) }
    var showVisualSettings by remember { mutableStateOf(false) }


    val scrollState = rememberScrollState()

    val onVideoSelected = { pickedUri: Uri ->
        overlayVideoUri = pickedUri
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, pickedUri)
            previewFrame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

            // 1. Auto-Orientation logic (Keep your existing W/H swap code here)
            val vW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val vH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val isVidPortrait = if (rot == 90 || rot == 270) vW > vH else vH > vW
            val isUiPortrait = appliedHeight > appliedWidth
            if (isVidPortrait != isUiPortrait) {
                val tw = videoWidthPx; videoWidthPx = videoHeightPx; videoHeightPx = tw
                val taw = appliedWidth; appliedWidth = appliedHeight; appliedHeight = taw
                lockRatio = appliedWidth.toFloat() / appliedHeight.toFloat().coerceAtLeast(1f)
            }

            // 2. Smart Placement: Change from Full Screen to "Bottom Strip"
            // We use the lastGraphRect if available, otherwise calculate the standard bottom strip.
            if (!lastGraphRect.isEmpty && lastGraphRect != RectF(0f, 0f, 1f, 1f)) {
                graphRect = lastGraphRect
            } else {
                val targetW = 700f
                val targetH = 280f
                val nW = (700f * overlayScale / appliedWidth.toFloat()).coerceIn(0.1f, 0.9f)
                val nH = (280f * overlayScale / appliedHeight.toFloat()).coerceIn(0.1f, 0.9f)
                graphRect = RectF(0.5f - nW/2, 0.95f - nH, 0.5f + nW/2, 0.95f)
            }

        } catch (e: Exception) {
            Log.e("VideoExport", "Metadata failed", e)
        } finally { retriever.release() }
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
            val (startOffset, endOffset) = VideoExporter.calculateVideoAlignment(
                context,
                record,
                overlayVideoUri!!,
                globalSyncOffset
            )

            startInput = TimeUtils.formatMs(startOffset)
            endInput = TimeUtils.formatMs(endOffset)

            Toast.makeText(
                context,
                "Synced to video timeline",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            // Reset to default (0 to duration) if no video is present
            startInput = TimeUtils.formatMs(0L)
            endInput = TimeUtils.formatMs(record.metadata.durationMs)
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

                            // Normalize these pixels based on current resolution
                            val w = (targetW / appliedWidth.toFloat()).coerceIn(0.05f, 1f)
                            val h = (targetH / appliedHeight.toFloat()).coerceIn(0.05f, 1f)
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
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = startInput,
                                    onValueChange = { startInput = it },
                                    label = { Text("Start Time") },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                OutlinedTextField(
                                    value = endInput,
                                    onValueChange = { endInput = it },
                                    label = { Text("End Time") },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }

                            OutlinedTextField(
                                value = windowSizeSec,
                                onValueChange = { windowSizeSec = it },
                                label = { Text("Scrolling Window Size (Seconds)") },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
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
            TextButton(onClick = {
                // Prepare Video Config
                val config = prepareVideoConfig(
                    videoWidth = videoWidthPx,
                    videoHeight = videoHeightPx,
                    startInput = startInput,
                    endInput = endInput,
                    windowSizeSec = windowSizeSec,
                    opacity = opacity,
                    showAxes = showAxes,
                    showLabels = showLabels,
                    showGrid = showGrid,
                    showTitle = showTitle,
                    showCurrentStats = showCurrentStats,
                    overlayVideoUri = overlayVideoUri,
                    record = record,
                    graphRect = graphRect
                )

                if (saveAsDefault) {
                    viewModel.saveLastUsedSettings(config) // Save settings
                }
                onExport(config, true)            // Trigger export
                onDismiss()
            }) { Text("Export") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private enum class DragMode { MOVE, RESIZE_LT, RESIZE_RT, RESIZE_LB, RESIZE_RB, NONE }

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
    startInput: String,
    endInput: String,
    windowSizeSec: String,
    opacity: Float,
    showAxes: Boolean,
    showLabels: Boolean,
    showGrid: Boolean,
    showTitle: Boolean,
    showCurrentStats: Boolean,
    overlayVideoUri: Uri?,
    record: BpmRecord,
    graphRect: RectF
): VideoExporter.VideoExportConfig {
    val startTime = TimeUtils.parseToMs(startInput) ?: 0L
    val endTime = TimeUtils.parseToMs(endInput) ?: record.metadata.durationMs
    val windowMs = (windowSizeSec.toLongOrNull() ?: 30L) * 1000L
    
    val imageConfig = ImageExporter.ImageExportConfig(
        width = videoWidth.toIntOrNull() ?: 1280,
        height = videoHeight.toIntOrNull() ?: 720,
        startTimeMs = startTime,
        endTimeMs = endTime,
        backgroundOpacity = opacity.toInt(),
        showAxes = showAxes,
        showLabels = showLabels,
        showGrid = showGrid,
        showTitle = showTitle,
        showCurrentStats = showCurrentStats
    )
    
    return VideoExporter.VideoExportConfig(
        imageConfig = imageConfig,
        windowSizeMs = windowMs,
        overlayVideoUri = overlayVideoUri,
        graphRect = graphRect

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
