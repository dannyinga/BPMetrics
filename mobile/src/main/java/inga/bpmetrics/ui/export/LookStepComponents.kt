package inga.bpmetrics.ui.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.AlignHorizontalCenter
import androidx.compose.material.icons.filled.AlignVerticalCenter
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.Canvas
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import inga.bpmetrics.export.BpmScale
import inga.bpmetrics.export.Contrast
import inga.bpmetrics.export.ExportPreset
import inga.bpmetrics.export.VideoFrameRenderer
import inga.bpmetrics.export.PillCorner
import inga.bpmetrics.export.VideoExporter
import inga.bpmetrics.library.BpmRecordWithPoints
import inga.bpmetrics.ui.components.ExpandableSection
import inga.bpmetrics.ui.components.FlowRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The pending export, at whatever instant the slider is on.
 *
 * Rendered through the same call the encoder makes per frame — `renderAlignedRecordsOnCanvas` at a
 * given playhead, over a background frame pulled with `MediaMetadataRetriever`. So what is
 * previewed is what will be produced, and settings can be judged against the busiest moment of a
 * clip rather than whatever happens to be at its start.
 *
 * That distinction is not academic: the multi-wearer pill sizing was work that could only be
 * checked by exporting a video and watching it.
 *
 * Nothing moves here. Moving and sizing the graph and the Live BPM column is the Position screen's,
 * opened from Graph & BPM's Scale / Position and from the full-screen button on the corner: dragging
 * on a preview a few hundred dp wide was coarse, and a drag meant as a scroll or a tap could shift
 * the graph. Shown from here, because it draws the same frame, while [positioning] says what it's
 * open on.
 */
@Composable
fun ExportPreview(
    records: List<BpmRecordWithPoints>,
    preset: ExportPreset,
    clip: VideoExporter.VideoClip?,
    placement: GraphPlacement,
    onPlacementChange: (GraphPlacement) -> Unit,
    overlay: Uri?,
    colours: Map<Long, Int>,
    photos: Map<Long, android.graphics.Bitmap> = emptyMap(),
    title: String?,
    at: Float,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * A tap on the preview, with what was tapped: null outside the panel. Lets the editor open the
     * settings for the thing pointed at (S14B.2).
     */
    onTapRegion: ((VideoFrameRenderer.Region?) -> Unit)? = null,
    /** Changes to the look made in Position: the column's corner and size, frames saved per cast. */
    onPresetChange: ((ExportPreset) -> Unit)? = null,
    /** What Position is open on, or null while it's closed. */
    positioning: PositionTarget? = null,
    /** Position closing. */
    onPositionDone: () -> Unit = {},
    /** Shown in Position's top bar: which clips a change reaches. */
    positionScope: (@Composable () -> Unit)? = null,
    /** Shown in Position under the picture: the way to the batch's other clips. */
    positionClips: (@Composable () -> Unit)? = null,
    /** Opens Position from the preview's corner. Null where there's no button for it. */
    onOpenPosition: (() -> Unit)? = null,
    /** Whether Position opens with its controls folded away, for the picture alone. */
    positionFull: Boolean = false
) {
    val context = LocalContext.current
    // The look as it is stored, for changes made in Position. What's drawn is sized to the clip.
    val look = preset
    // At the size it renders over this clip, so a canvas matched to the clip previews as the
    // clip's shape. Everything below, Position included, works from this.
    @Suppress("NAME_SHADOWING")
    val preset = clip?.canvasFor(preset) ?: preset
    val aspect = preset.width.toFloat() / preset.height.toFloat().coerceAtLeast(1f)

    // Saveable so a rotation, or a trip into the photo picker, does not reopen a preview someone
    // deliberately folded away to get at the settings underneath.
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    val inPosition = positioning != null && records.isNotEmpty()

    // Re-rendered whenever anything it depends on changes, off the main thread because it decodes
    // a video frame and draws every visible data point.
    //
    // Not while it is hidden. Every setting touched re-renders this, and half the reason to fold it
    // away is to stop that happening behind a slider being dragged. Nor while Position is up, which
    // would have two renders racing for the same answer.
    val visible = expanded && !inPosition
    val frame by produceState<Bitmap?>(
        initialValue = null,
        visible, records, preset, overlay, at, colours, photos, title, clip, placement
    ) {
        if (!visible) return@produceState
        value = withContext(Dispatchers.Default) {
            renderPreviewFrame(context, records, preset, clip, overlay, colours, photos, title, at, placement)
        }
        if (overlay == null) return@produceState
        delay(EXACT_FRAME_SETTLE_MS)
        value = withContext(Dispatchers.Default) {
            renderPreviewFrame(
                context, records, preset, clip, overlay, colours, photos, title, at, placement,
                exact = true
            )
        }
    }

    if (inPosition) {
        PositionScreen(
            opensOn = positioning ?: PositionTarget.GRAPH,
            startFull = positionFull,
            records = records,
            preset = preset,
            look = look,
            onPresetChange = onPresetChange,
            clip = clip,
            overlay = overlay,
            colours = colours,
            photos = photos,
            title = title,
            at = at,
            onScrub = onScrub,
            placement = placement,
            onPlacementChange = onPlacementChange,
            scope = positionScope,
            clips = positionClips,
            onDismiss = onPositionDone
        )
    }

    Column(modifier) {
        // The preview is sticky and takes a third of a short screen. That is right while judging
        // something and wrong while reading down a list of settings, so it folds. Folded, a slim
        // row brings it back; open, its buttons sit on its corner rather than on a row of their own.
        // On the right, where the button that hid it was.
        if (!expanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { expanded = true }) {
                    Text("Show preview")
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        androidx.compose.material.icons.Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            return@Column
        }

        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // Height-capped rather than purely aspect-driven. A 9:16 canvas filling the width is
            // nearly twice as tall as it is wide, which pushed every setting off the bottom of the
            // screen. A share of the screen rather than a flat height, so it is the same
            // proportion on a short phone as on a tall one, with a floor.
            val safeAspect = aspect.coerceIn(0.4f, 2.5f)
            val screenHeight = LocalConfiguration.current.screenHeightDp.dp
            val cap = (screenHeight * 0.32f).coerceIn(140.dp, 260.dp)
            val previewHeight = minOf(maxWidth / safeAspect, cap)

            val tapModifier = if (onTapRegion == null || records.isEmpty()) Modifier else {
                Modifier.pointerInput(records, preset, placement, title) {
                    detectTapGestures { offset ->
                        val x = offset.x / size.width * preset.width
                        val y = offset.y / size.height * preset.height
                        onTapRegion(
                            VideoFrameRenderer.regionAt(
                                preset.width, preset.height,
                                VideoFrameRenderer.alignRecords(records),
                                inga.bpmetrics.export.ExportContent(
                                    records = records, startMs = 0L, endMs = 1L, title = title,
                                    colours = colours, photos = photos
                                ),
                                preset, placement.toRectF(), x, y,
                                readouts = placement.readouts
                            )
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .height(previewHeight)
                    .width(previewHeight * safeAspect)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(tapModifier),
                contentAlignment = Alignment.Center
            ) {
                frame?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Export preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } ?: CircularProgressIndicator()

                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // The picture full screen, where the graph and Live BPM are moved and sized:
                    // the same screen as Scale / Position in Graph & BPM, from the thing it shows.
                    if (onOpenPosition != null && records.isNotEmpty()) {
                        PreviewCornerButton(
                            icon = androidx.compose.material.icons.Icons.Default.Fullscreen,
                            description = "Full screen: scale and position the graph",
                            onClick = onOpenPosition
                        )
                    }
                    PreviewCornerButton(
                        icon = androidx.compose.material.icons.Icons.Default.ExpandLess,
                        description = "Hide the preview",
                        onClick = { expanded = false }
                    )
                }
            }
        }

        Spacer(Modifier.height(2.dp))

        ScrubTimeline(
            at = at,
            durationMs = playableMs(records, clip, preset),
            onScrub = onScrub,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * A small button on the preview's corner. Dark and translucent, so it reads over bright footage
 * and dark alike without hiding much of either.
 */
@Composable
private fun PreviewCornerButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    androidx.compose.material3.FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
            containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
            contentColor = androidx.compose.ui.graphics.Color.White
        )
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

/** What Position is moving and sizing. It opens on the graph, from the Graph tab. */
enum class PositionTarget { GRAPH, LIVE_BPM }

/**
 * Where the graph and the Live BPM column go, and how big they are: the frame as large as the
 * screen allows, with the controls for both under it (beside it, turned sideways).
 *
 * The only place either moves. Dragging a corner on the small preview moved the graph about a
 * percent of the canvas per pixel of finger; here the same drag is fine adjustment, and the size,
 * the quick spots and the centering buttons sit with the thing they move instead of in two tabs.
 *
 * Re-rendered at a higher resolution rather than scaling the small frame up, so small text (a
 * name in a pill, an axis number) can be read here too.
 */
@Composable
private fun PositionScreen(
    /** The one first shown. The switch inside goes to the other. */
    opensOn: PositionTarget,
    /** Whether it opens with the controls folded away: from the preview's full-screen button. */
    startFull: Boolean,
    records: List<BpmRecordWithPoints>,
    /** Sized to the clip: what's drawn. */
    preset: ExportPreset,
    /** As stored: what changes are made to. */
    look: ExportPreset,
    onPresetChange: ((ExportPreset) -> Unit)?,
    clip: VideoExporter.VideoClip?,
    overlay: Uri?,
    colours: Map<Long, Int>,
    photos: Map<Long, android.graphics.Bitmap>,
    title: String?,
    at: Float,
    onScrub: (Float) -> Unit,
    placement: GraphPlacement,
    onPlacementChange: (GraphPlacement) -> Unit,
    scope: (@Composable () -> Unit)?,
    clips: (@Composable () -> Unit)?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val aspect = preset.width.toFloat() / preset.height.toFloat().coerceAtLeast(1f)

    // A landscape video turns the screen with it (UX Audit E1). Rotating recreates the activity,
    // so the orientation to go back to is kept across that, and only restored when this closes.
    val activity = remember(context) {
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>()
            .firstOrNull()
    }
    val originalOrientation = androidx.compose.runtime.saveable.rememberSaveable {
        activity?.requestedOrientation ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    androidx.compose.runtime.DisposableEffect(activity, aspect > 1f) {
        if (aspect > 1f) {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            if (activity != null && !activity.isChangingConfigurations) {
                activity.requestedOrientation = originalOrientation
            }
        }
    }

    val frame by produceState<Bitmap?>(
        initialValue = null,
        records, preset, overlay, at, colours, photos, title, clip, placement
    ) {
        // Capped rather than the export's full width: a 4K preset would decode a 4K frame per
        // scrub, and no phone screen can show the difference.
        val renderWidth = preset.width.coerceAtMost(1920)
        value = withContext(Dispatchers.Default) {
            renderPreviewFrame(
                context, records, preset, clip, overlay, colours, photos, title, at, placement,
                renderWidth = renderWidth
            )
        }
        if (overlay == null) return@produceState
        delay(EXACT_FRAME_SETTLE_MS)
        value = withContext(Dispatchers.Default) {
            renderPreviewFrame(
                context, records, preset, clip, overlay, colours, photos, title, at, placement,
                renderWidth = renderWidth,
                exact = true
            )
        }
    }

    val liveBpm = look.showCurrentStats && records.isNotEmpty()
    var chosen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(opensOn) }
    val target = if (liveBpm) chosen else PositionTarget.GRAPH
    val block = rememberReadoutBlock(records, preset, placement, title)
    var guides by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(Guides.OFF) }
    var guidesOpen by remember { mutableStateOf(false) }
    // Folded away for the picture alone, at its largest: the frame full screen, still draggable.
    var controlsShown by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(!startFull) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val sideways = maxWidth > maxHeight
                val controlsMax = maxHeight * 0.5f

                val picture: @Composable (Modifier) -> Unit = { boxModifier ->
                    BoxWithConstraints(
                        modifier = boxModifier.background(androidx.compose.ui.graphics.Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        // Fitted to the space by whichever side runs out first, so the frame is
                        // whole with bars on the other.
                        val safeAspect = aspect.coerceIn(0.4f, 2.5f)
                        val sizing = if (safeAspect > maxWidth / maxHeight) Modifier.fillMaxWidth() else Modifier.fillMaxHeight()
                        Box(
                            sizing
                                .aspectRatio(safeAspect)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            frame?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "Export preview, for positioning",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            } ?: CircularProgressIndicator()

                            GuideLines(guides, Modifier.fillMaxSize())

                            // Inside the frame's own box, so the fractions the overlay works in map
                            // onto the rectangle the renderer drew.
                            GraphFramingOverlay(
                                placement = placement,
                                onChange = onPlacementChange,
                                readoutBlock = block,
                                onGrab = { readouts ->
                                    chosen = if (readouts) PositionTarget.LIVE_BPM else PositionTarget.GRAPH
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                val controls: @Composable (Modifier) -> Unit = { columnModifier ->
                    Column(
                        columnModifier
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        if (liveBpm) {
                            Segmented(
                                options = PositionTarget.entries,
                                selected = target,
                                label = { if (it == PositionTarget.GRAPH) "Graph" else "Live BPM" },
                                onSelect = { chosen = it }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        when (target) {
                            PositionTarget.GRAPH -> GraphPositionControls(
                                placement = placement,
                                onPlacementChange = onPlacementChange,
                                look = look,
                                onPresetChange = onPresetChange,
                                aspect = aspect
                            )
                            PositionTarget.LIVE_BPM -> LiveBpmPositionControls(
                                placement = placement,
                                onPlacementChange = onPlacementChange,
                                block = block,
                                look = look,
                                onPresetChange = onPresetChange,
                                aspect = aspect
                            )
                        }
                    }
                }

                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Done")
                        }
                        Text("Scale / Position", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        // Lines to line things up against: halves, thirds, fifths.
                        Box {
                            IconButton(onClick = { guidesOpen = true }) {
                                Icon(
                                    if (guides == Guides.OFF) androidx.compose.material.icons.Icons.Default.GridOff
                                    else androidx.compose.material.icons.Icons.Default.GridOn,
                                    contentDescription = "Guides"
                                )
                            }
                            DropdownMenu(expanded = guidesOpen, onDismissRequest = { guidesOpen = false }) {
                                Text(
                                    "Guides",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                                Guides.entries.forEach { entry ->
                                    DropdownMenuItem(
                                        text = { Text(entry.label) },
                                        leadingIcon = {
                                            if (entry == guides) Icon(androidx.compose.material.icons.Icons.Default.Check, contentDescription = "Chosen")
                                            else Spacer(Modifier.size(24.dp))
                                        },
                                        onClick = { guides = entry; guidesOpen = false }
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { controlsShown = !controlsShown }) {
                            Icon(
                                if (controlsShown) androidx.compose.material.icons.Icons.Default.Fullscreen
                                else androidx.compose.material.icons.Icons.Default.FullscreenExit,
                                contentDescription = if (controlsShown) "Hide the controls" else "Show the controls"
                            )
                        }
                        scope?.invoke()
                        TextButton(onClick = onDismiss) { Text("Done") }
                    }
                    if (sideways) {
                        Row(Modifier.weight(1f)) {
                            Column(Modifier.weight(1f)) {
                                picture(Modifier.weight(1f).fillMaxWidth())
                                ScrubTimeline(
                                    at = at,
                                    durationMs = playableMs(records, clip, preset),
                                    onScrub = onScrub,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                            if (controlsShown) {
                                Column(Modifier.width(340.dp).fillMaxHeight()) {
                                    clips?.let { Box(Modifier.padding(horizontal = 8.dp)) { it() } }
                                    controls(Modifier.weight(1f))
                                }
                            }
                        }
                    } else {
                        picture(Modifier.weight(1f).fillMaxWidth())
                        ScrubTimeline(
                            at = at,
                            durationMs = playableMs(records, clip, preset),
                            onScrub = onScrub,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        if (controlsShown) {
                            clips?.let { Box(Modifier.padding(horizontal = 8.dp)) { it() } }
                            controls(Modifier.heightIn(max = controlsMax))
                        }
                    }
                }
            }
        }
    }
}

/** Where the graph goes and how big it is: a section for each. */
@Composable
private fun GraphPositionControls(
    placement: GraphPlacement,
    onPlacementChange: (GraphPlacement) -> Unit,
    look: ExportPreset,
    onPresetChange: ((ExportPreset) -> Unit)?,
    /** The canvas's width over its height, so the pad is the canvas's shape. */
    aspect: Float
) {
    SectionLabel("Position")
    PositionSection(
        anchor = placement.anchor(),
        onAnchor = { column, row -> onPlacementChange(placement.anchoredAt(column, row)) },
        aspect = aspect,
        x = placement.left,
        xRoom = 1f - placement.width,
        y = placement.top,
        yRoom = 1f - placement.height,
        onMove = { x, y -> onPlacementChange(placement.movedTo(x, y)) }
    )

    Spacer(Modifier.height(12.dp))
    // Keeping the shape is the usual case: a graph squashed by a slider nudged one way is rarely
    // what was meant.
    var keepShape by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Size", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        FilterChip(
            selected = keepShape,
            onClick = { keepShape = !keepShape },
            label = { Text("Keep shape") },
            leadingIcon = {
                Icon(
                    if (keepShape) androidx.compose.material.icons.Icons.Default.Lock
                    else androidx.compose.material.icons.Icons.Default.LockOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        )
    }
    val shape = placement.height / placement.width.coerceAtLeast(0.0001f)
    ValueSlider(
        label = "Width",
        value = placement.width,
        range = GraphPlacement.MIN_SIZE..1f,
        onValue = { w ->
            onPlacementChange(
                if (keepShape) {
                    // The height can veto the width: past where it would leave the canvas, the
                    // shape would have to change, which is the one thing this mode promises not to.
                    val width = minOf(w, 1f / shape)
                    placement.resizedTo(width, width * shape)
                } else placement.resizedTo(w, placement.height)
            )
        }
    )
    ValueSlider(
        label = "Height",
        value = placement.height,
        range = GraphPlacement.MIN_SIZE..1f,
        onValue = { h ->
            onPlacementChange(
                if (keepShape) {
                    val height = minOf(h, shape)
                    placement.resizedTo(height / shape, height)
                } else placement.resizedTo(placement.width, h)
            )
        }
    )
    Hint("You can drag on the picture too: the frame to move it, its corners and edges to resize it.")

    // Frames saved for a number of people (UX Audit E2): chips that remove themselves.
    if (onPresetChange != null && look.framesByLaneCount.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Saved for", style = MaterialTheme.typography.bodyMedium)
            look.framesByLaneCount.keys.sorted().forEach { count ->
                androidx.compose.material3.InputChip(
                    selected = false,
                    onClick = { onPresetChange(look.copy(framesByLaneCount = look.framesByLaneCount - count)) },
                    label = { Text(if (count == 1) "1 person" else "$count people") },
                    trailingIcon = {
                        Icon(
                            androidx.compose.material.icons.Icons.Default.Close,
                            contentDescription = "Clear the frame for $count",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
    }
}

/** Where the Live BPM column goes and how big it is. */
@Composable
private fun LiveBpmPositionControls(
    placement: GraphPlacement,
    onPlacementChange: (GraphPlacement) -> Unit,
    /** Where the column is now, as fractions of the canvas. */
    block: RectF?,
    look: ExportPreset,
    onPresetChange: ((ExportPreset) -> Unit)?,
    aspect: Float
) {
    val onItsOwn = placement.readouts != null
    // Placed by where its corner lands; the pin and the way it grows follow from that.
    fun placeAt(left: Float, top: Float, box: RectF) = onPlacementChange(
        placement.copy(
            readouts = inga.bpmetrics.export.ReadoutSpot.at(left, top, left + box.width(), top + box.height())
        )
    )
    Segmented(
        options = listOf(false, true),
        selected = onItsOwn,
        label = { if (it) "On its own" else "On the graph" },
        onSelect = { own ->
            when {
                own == onItsOwn -> Unit
                // Taken off where it already is, so choosing this moves nothing until it's placed.
                own -> block?.let { placeAt(it.left, it.top, it) }
                else -> onPlacementChange(placement.copy(readouts = null))
            }
        }
    )
    Spacer(Modifier.height(12.dp))
    if (!onItsOwn) {
        if (onPresetChange != null) {
            InlineChoice(
                label = "Corner of the graph",
                options = PillCorner.entries,
                selected = look.pillCorner,
                optionLabel = { it.label },
                onSelect = { onPresetChange(look.copy(pillCorner = it)) }
            )
        }
        Hint("It moves with the graph. Right sits over what hasn't happened yet.")
    } else if (block != null) {
        SectionLabel("Position")
        PositionSection(
            anchor = Anchor.indexOf(block.left, block.width()) to Anchor.indexOf(block.top, block.height()),
            onAnchor = { column, row ->
                placeAt(Anchor.start(column, block.width()), Anchor.start(row, block.height()), block)
            },
            aspect = aspect,
            x = block.left,
            xRoom = 1f - block.width(),
            y = block.top,
            yRoom = 1f - block.height(),
            onMove = { x, y -> placeAt(x, y, block) }
        )
        Hint("It grows away from the nearest corner, so more people never push it off the edge.")
    }
    if (onPresetChange != null) {
        Spacer(Modifier.height(12.dp))
        SectionLabel("Size")
        ToggleScaleRow(
            label = "Pills",
            checked = null,
            scale = look.pillScale,
            onScale = { onPresetChange(look.copy(pillScale = it)) }
        )
        // Each part with its size beside it: a size for something switched off means nothing.
        // Here rather than in a tab, because how big each part is gets judged against the picture.
        ToggleScaleRow(
            label = "Photo",
            checked = look.pillShowPhoto,
            onChecked = { onPresetChange(look.copy(pillShowPhoto = it)) },
            scale = look.pillPhotoScale,
            onScale = { onPresetChange(look.copy(pillPhotoScale = it)) }
        )
        ToggleScaleRow(
            label = "Name",
            checked = look.pillShowName,
            onChecked = { onPresetChange(look.copy(pillShowName = it)) },
            scale = look.pillNameScale,
            onScale = { onPresetChange(look.copy(pillNameScale = it)) }
        )
        ToggleScaleRow(
            label = "Reading",
            checked = null,
            scale = look.pillBpmScale,
            onScale = { onPresetChange(look.copy(pillBpmScale = it)) }
        )
        Hint("Tap a percentage to put it back to 100%.")
    }
}

/**
 * Where something sits: a pad of nine dots for the sides, the corners and the middle, beside
 * sliders for exactly where, and buttons that center one way only.
 *
 * @param anchor which dot it's on, each way, or null between them.
 * @param xRoom how far it can go across: the canvas less its own width. Likewise [yRoom].
 */
@Composable
private fun PositionSection(
    anchor: Pair<Int?, Int?>,
    onAnchor: (column: Int, row: Int) -> Unit,
    aspect: Float,
    x: Float,
    xRoom: Float,
    y: Float,
    yRoom: Float,
    onMove: (x: Float, y: Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnchorPad(anchor = anchor, onPick = onAnchor, aspect = aspect)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            ValueSlider(
                label = "X",
                value = x,
                range = 0f..xRoom.coerceAtLeast(0.0001f),
                enabled = xRoom > 0.001f,
                labelWidth = 24.dp,
                onValue = { onMove(it, y) }
            )
            ValueSlider(
                label = "Y",
                value = y,
                range = 0f..yRoom.coerceAtLeast(0.0001f),
                enabled = yRoom > 0.001f,
                labelWidth = 24.dp,
                onValue = { onMove(x, it) }
            )
        }
    }
    // One way only: the pad's middle dot centers both.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { onMove(xRoom / 2f, y) },
            enabled = xRoom > 0.001f,
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
            Icon(
                androidx.compose.material.icons.Icons.Default.AlignHorizontalCenter,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("Center across")
        }
        OutlinedButton(
            onClick = { onMove(x, yRoom / 2f) },
            enabled = yRoom > 0.001f,
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
            Icon(
                androidx.compose.material.icons.Icons.Default.AlignVerticalCenter,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("Center down")
        }
    }
}

/**
 * The canvas in miniature, with a dot at each corner, the middle of each side and the center.
 * The dot it's on is filled; one that matches only one way is ringed.
 */
@Composable
private fun AnchorPad(
    anchor: Pair<Int?, Int?>,
    onPick: (column: Int, row: Int) -> Unit,
    aspect: Float
) {
    val (onColumn, onRow) = anchor
    val longSide = 120.dp
    val padWidth = if (aspect >= 1f) longSide else (longSide * aspect).coerceAtLeast(72.dp)
    val padHeight = if (aspect >= 1f) (longSide / aspect).coerceAtLeast(72.dp) else longSide
    val rows = listOf("Top", "Middle", "Bottom")
    val columns = listOf("left", "center", "right")
    Box(
        Modifier
            .size(padWidth, padHeight)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
    ) {
        for (row in 0..2) for (column in 0..2) {
            val on = onColumn == column && onRow == row
            val name = when {
                row == 1 && column == 1 -> "Center"
                row == 1 -> "Middle ${columns[column]}"
                else -> "${rows[row]} ${columns[column]}"
            }
            Box(
                modifier = Modifier
                    .align(androidx.compose.ui.BiasAlignment(column - 1f, row - 1f))
                    .size(28.dp)
                    .clickable(onClickLabel = name) { onPick(column, row) }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(if (on) 14.dp else 10.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                )
            }
        }
    }
}

/**
 * A slider with its name on the left and its value, as a percentage of the canvas, on the right.
 */
@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
    enabled: Boolean = true,
    labelWidth: Dp = 56.dp
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(labelWidth))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValue,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        Text(
            "${kotlin.math.round(value * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp)
        )
    }
}

/** Lines over the picture in Position, at even divisions of the canvas. Never in the export. */
private enum class Guides(val label: String, val divisions: Int) {
    OFF("Off", 1),
    HALVES("Halves", 2),
    THIRDS("Thirds", 3),
    FIFTHS("Fifths", 5)
}

/** [guides] drawn over the picture, dashed and light so they read over footage of any kind. */
@Composable
private fun GuideLines(guides: Guides, modifier: Modifier = Modifier) {
    if (guides == Guides.OFF) return
    Canvas(modifier) {
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        val stroke = 1.dp.toPx()
        for (i in 1 until guides.divisions) {
            val x = size.width * i / guides.divisions
            val y = size.height * i / guides.divisions
            listOf(
                Offset(x, 0f) to Offset(x, size.height),
                Offset(0f, y) to Offset(size.width, y)
            ).forEach { (from, to) ->
                // A dark line under a light one, so it shows on bright footage as well as dark.
                drawLine(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f), from, to, stroke * 2.5f, pathEffect = dash)
                drawLine(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f), from, to, stroke, pathEffect = dash)
            }
        }
    }
}

/**
 * The scrub bar, drawn as a timeline rather than as a slider.
 *
 * A bare slider says nothing about what it is moving through: the same knob position means one
 * thing on a nine-second clip and another on a nine-minute one. This reads as footage — a filled
 * track for what has played, ticks at regular intervals, and the elapsed and total times spelled
 * out — so a position on it is a moment rather than a fraction.
 *
 * One line: the times sit at the bar's ends rather than on a row of their own underneath, which
 * gave the settings below another line.
 */
@Composable
private fun ScrubTimeline(
    at: Float,
    durationMs: Long,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val onScrubbed by rememberUpdatedState(onScrub)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val played = MaterialTheme.colorScheme.primary
    val tick = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (durationMs > 0L) TimeLabel(clipTimeLabel((durationMs * at.coerceIn(0f, 1f)).toLong()))
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .height(32.dp)
                .pointerInput(Unit) {
                    // Tap and drag both land on the same handler, so the playhead can be thrown to
                    // a moment as well as walked to it.
                    detectDragGestures(
                        onDragStart = { offset ->
                            onScrubbed((offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f))
                        }
                    ) { change, _ ->
                        change.consume()
                        onScrubbed((change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onScrubbed((offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f))
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val h = size.height
                val barTop = h * 0.28f
                val barHeight = h * 0.44f
                val radius = androidx.compose.ui.geometry.CornerRadius(barHeight / 2f, barHeight / 2f)

                drawRoundRect(
                    color = track,
                    topLeft = Offset(0f, barTop),
                    size = Size(size.width, barHeight),
                    cornerRadius = radius
                )

                // A tick every ten seconds while they stay legible, so the eye can measure the
                // clip rather than only scan it.
                if (durationMs > 0L) {
                    val step = tickStepMsFor(durationMs)
                    var t = step
                    while (t < durationMs) {
                        val x = size.width * (t.toFloat() / durationMs)
                        drawLine(
                            color = tick,
                            start = Offset(x, barTop),
                            end = Offset(x, barTop + barHeight),
                            strokeWidth = 2f
                        )
                        t += step
                    }
                }

                val headX = size.width * at.coerceIn(0f, 1f)
                drawRoundRect(
                    color = played,
                    topLeft = Offset(0f, barTop),
                    size = Size(headX, barHeight),
                    cornerRadius = radius
                )
                drawCircle(played, radius = h * 0.3f, center = Offset(headX, barTop + barHeight / 2f))
            }
        }

        if (durationMs > 0L) TimeLabel(clipTimeLabel(durationMs))
    }
}

@Composable
private fun TimeLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
    )
}

/** A tick spacing that stays readable: never more than about a dozen across the bar. */
private fun tickStepMsFor(durationMs: Long): Long =
    listOf(1_000L, 5_000L, 10_000L, 30_000L, 60_000L, 300_000L, 600_000L, 1_800_000L)
        .firstOrNull { durationMs / it <= 12 } ?: (durationMs / 8).coerceAtLeast(1L)

private fun clipTimeLabel(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * Where the readout column is on the preview, as fractions of the canvas, or null where none is
 * drawn. From the renderer's own layout, so the outline sits exactly on the pills.
 */
@Composable
private fun rememberReadoutBlock(
    records: List<BpmRecordWithPoints>,
    preset: ExportPreset,
    placement: GraphPlacement,
    title: String?
): RectF? = remember(records, preset, placement, title) {
    if (records.isEmpty()) return@remember null
    val content = inga.bpmetrics.export.ExportContent(records = records, startMs = 0L, endMs = 1L, title = title)
    VideoFrameRenderer.readoutBlock(
        preset.width, preset.height, records, content, preset, placement.toRectF(), placement.readouts
    )?.let { px ->
        val w = preset.width.toFloat().coerceAtLeast(1f)
        val h = preset.height.toFloat().coerceAtLeast(1f)
        RectF(px.left / w, px.top / h, px.right / w, px.bottom / h)
    }
}

/**
 * The graph's frame, as an outline that can be dragged and resized, and the readout column, as a
 * dashed outline that can be dragged on its own.
 *
 * Drawn over the preview rather than beside it because a rectangle's size only means anything
 * against the thing it sits on. Deliberately unfilled: the render underneath is what is being
 * judged, and a tint over it defeats the point.
 *
 * Dragging the column takes it off the graph (S14C): it stays where it's dropped, pinned to the
 * corner of the frame it's nearest, and the graph moves without it from then on. It has no handles,
 * because its size is the pills' size, set beside it in Position.
 */
@Composable
private fun GraphFramingOverlay(
    placement: GraphPlacement,
    onChange: (GraphPlacement) -> Unit,
    readoutBlock: RectF?,
    /** Told what a drag picked up: true for the column, false for the graph. */
    onGrab: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val current by rememberUpdatedState(placement)
    val grabbing by rememberUpdatedState(onGrab)
    val onChanged by rememberUpdatedState(onChange)
    val currentBlock by rememberUpdatedState(readoutBlock)
    val outline = MaterialTheme.colorScheme.primary
    val readoutOutline = MaterialTheme.colorScheme.tertiary
    val knob = MaterialTheme.colorScheme.onPrimary

    // What this gesture grabbed, remembered for its duration. Local to the composable — a shared
    // one would let two previews fight over the same handle.
    var grabbed by remember { mutableStateOf<GraphHandle?>(null) }
    var moving by remember { mutableStateOf(false) }
    // The column as the drag began, and how far it has come since, in fractions of the canvas.
    var readoutsFrom by remember { mutableStateOf<RectF?>(null) }
    var readoutsBy by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                // In dp, not raw pixels. The old 48-pixel radius was about 16dp on this phone —
                // well under half a fingertip — so a drag aimed at a corner usually missed it and
                // was treated as a drag of the whole frame instead. That is why resizing felt
                // impossible rather than merely fiddly.
                val slop = 28.dp.toPx()

                detectDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        // The column first: it sits over the graph, and a drag that starts on the
                        // pills is aimed at the pills.
                        readoutsFrom = currentBlock?.takeIf { it.contains(offset.x / w, offset.y / h) }
                        readoutsBy = Offset.Zero
                        grabbed = if (readoutsFrom != null) null else handleAt(offset, current, w, h, slop)
                        // Moving only from inside the frame. A drag that begins on the footage is
                        // aimed at the footage, and shoving the graph across the screen because a
                        // finger landed in open space is worse than doing nothing.
                        moving = readoutsFrom == null && grabbed == null &&
                            current.contains(offset.x / w, offset.y / h)
                        if (readoutsFrom != null) grabbing(true)
                        else if (grabbed != null || moving) grabbing(false)
                    },
                    onDragEnd = { grabbed = null; moving = false; readoutsFrom = null },
                    onDragCancel = { grabbed = null; moving = false; readoutsFrom = null }
                ) { change, drag ->
                    change.consume()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val dx = drag.x / w
                    val dy = drag.y / h
                    val from = readoutsFrom
                    when {
                        from != null -> {
                            readoutsBy += Offset(dx, dy)
                            // Kept whole on the canvas while it is carried.
                            val x = (from.left + readoutsBy.x).coerceIn(0f, (1f - from.width()).coerceAtLeast(0f))
                            val y = (from.top + readoutsBy.y).coerceIn(0f, (1f - from.height()).coerceAtLeast(0f))
                            onChanged(
                                current.copy(
                                    readouts = inga.bpmetrics.export.ReadoutSpot.at(
                                        x, y, x + from.width(), y + from.height()
                                    )
                                )
                            )
                        }
                        grabbed != null -> onChanged(current.resizedBy(grabbed!!, dx, dy))
                        moving -> onChanged(current.movedTo(current.left + dx, current.top + dy))
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val left = placement.left * size.width
            val top = placement.top * size.height
            val right = placement.right * size.width
            val bottom = placement.bottom * size.height

            drawRect(
                color = outline,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 2.dp.toPx())
            )

            // Dashed, and in another color, so it reads as a second thing that moves on its own
            // rather than as part of the graph's frame.
            readoutBlock?.let { block ->
                val pad = 3.dp.toPx()
                drawRoundRect(
                    color = readoutOutline,
                    topLeft = Offset(block.left * size.width - pad, block.top * size.height - pad),
                    size = Size(block.width() * size.width + pad * 2, block.height() * size.height + pad * 2),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(5.dp.toPx(), 3.dp.toPx())
                        )
                    )
                )
            }

            // Corners only, set just outside the frame where the preview has room. Knobs on the
            // edges sat on what the frame holds: the top one covered the centre of the title, the
            // left one the axis numbers (UX Audit E1). The edges still drag from their middles.
            // Kept inside the preview's own bounds, so a frame flush to the border keeps its knobs.
            val radius = 7.dp.toPx()
            val inset = radius + 1.dp.toPx()
            val out = radius * 0.6f

            listOf(
                left - out to top - out, right + out to top - out,
                left - out to bottom + out, right + out to bottom + out
            ).forEach { (x, y) ->
                val centre = Offset(
                    x.coerceIn(inset, (size.width - inset).coerceAtLeast(inset)),
                    y.coerceIn(inset, (size.height - inset).coerceAtLeast(inset))
                )
                // Filled, with a contrasting core, so a handle stays visible over bright footage
                // as well as dark.
                drawCircle(outline, radius = radius, center = centre)
                drawCircle(knob, radius = radius * 0.45f, center = centre)
            }
        }
    }
}

/**
 * Which handle a touch is on, or null if it is on neither.
 *
 * Corners are tested before edges, so where the two overlap the corner wins — a drag into the
 * angle of the frame almost always means "resize both ways".
 */
private fun handleAt(
    offset: Offset,
    placement: GraphPlacement,
    width: Float,
    height: Float,
    slop: Float
): GraphHandle? {
    val l = placement.left * width
    val t = placement.top * height
    val r = placement.right * width
    val b = placement.bottom * height
    val midX = (l + r) / 2f
    val midY = (t + b) / 2f

    return listOf(
        GraphHandle.TOP_LEFT to Offset(l, t),
        GraphHandle.TOP_RIGHT to Offset(r, t),
        GraphHandle.BOTTOM_LEFT to Offset(l, b),
        GraphHandle.BOTTOM_RIGHT to Offset(r, b),
        GraphHandle.LEFT to Offset(l, midY),
        GraphHandle.RIGHT to Offset(r, midY),
        GraphHandle.TOP to Offset(midX, t),
        GraphHandle.BOTTOM to Offset(midX, b)
    )
        .map { (handle, point) -> handle to kotlin.math.hypot(offset.x - point.x, offset.y - point.y) }
        .filter { it.second <= slop }
        .minByOrNull { it.second }
        ?.first
}

/**
 * Draws one frame exactly as the encoder would.
 *
 * The background comes from the chosen clip at the same playhead the overlay is drawn at, so the
 * two are in sync — a preview that drew frame zero under a curve from halfway through would be
 * showing something that will never exist.
 */
/**
 * Where a [frameWidth] × [frameHeight] picture goes on a [canvasWidth] × [canvasHeight] canvas:
 * whole and centred with bars, or [fill]ing it and cropped, as Media3's `LAYOUT_SCALE_TO_FIT` and
 * `LAYOUT_SCALE_TO_FIT_WITH_CROP` place it in the render.
 */
internal fun footageRect(frameWidth: Int, frameHeight: Int, canvasWidth: Int, canvasHeight: Int, fill: Boolean): RectF {
    if (frameWidth <= 0 || frameHeight <= 0) return RectF(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat())
    val sx = canvasWidth.toFloat() / frameWidth
    val sy = canvasHeight.toFloat() / frameHeight
    val s = if (fill) maxOf(sx, sy) else minOf(sx, sy)
    val w = frameWidth * s
    val h = frameHeight * s
    val left = (canvasWidth - w) / 2f
    val top = (canvasHeight - h) / 2f
    return RectF(left, top, left + w, top + h)
}

/** How long the playhead rests before the preview decodes the exact frame under it. */
private const val EXACT_FRAME_SETTLE_MS = 300L

/**
 * How long the preview's clip plays for: its window on the recording, which is what the render
 * makes. Footage filmed before the watch started is skipped, so this can be shorter than the file.
 */
private fun playableMs(
    records: List<BpmRecordWithPoints>,
    clip: VideoExporter.VideoClip?,
    preset: ExportPreset
): Long {
    if (clip == null) return 0L
    if (records.isEmpty()) return clip.durationMs
    return clip.windowOn(VideoFrameRenderer.timelineFor(records), preset.syncOffsetMs).spanMs
}

private fun renderPreviewFrame(
    context: android.content.Context,
    records: List<BpmRecordWithPoints>,
    preset: ExportPreset,
    clip: VideoExporter.VideoClip?,
    overlay: Uri?,
    colours: Map<Long, Int>,
    photos: Map<Long, android.graphics.Bitmap> = emptyMap(),
    title: String?,
    at: Float,
    placement: GraphPlacement,
    /**
     * How wide to render.
     *
     * A preview is a few hundred dp on screen, so rendering at the export.s own resolution would
     * decode a 4K frame to show it at 300dp. Full screen is the case that needs more: the point of
     * opening it is to read text that was too small, and enlarging the small render would enlarge
     * its blur along with it.
     */
    renderWidth: Int = 720,
    /**
     * Whether to decode the exact frame under the playhead rather than the nearest keyframe.
     *
     * Keyframes are a second apart in a phone's footage, so the nearest one can be half a second
     * from the curve drawn over it: too coarse to judge sync by. The exact frame means decoding
     * forward from the keyframe before it, which is slow on 4K, so it is only asked for once the
     * playhead has come to rest.
     */
    exact: Boolean = false
): Bitmap? {
    if (records.isEmpty()) return null

    // Rendered small. A preview is a few hundred pixels wide on screen and rendering it at the
    // export's own resolution would decode a 4K frame to show it at 300dp.
    val previewWidth = renderWidth
    val previewHeight = (previewWidth / (preset.width.toFloat() / preset.height)).toInt()
        .coerceAtLeast(1)

    // Clock-aligned whenever there is footage, matching what the export will do. Elapsed alignment
    // starts every recording at 0:00, which is only right when there is no video to agree with.
    // Always clock-aligned, matching the export.
    val aligned = VideoFrameRenderer.alignRecords(records)

    // The clip's own window on the shared timeline, worked out by the clip itself so this and the
    // render cannot reach different answers. Scrubbing moves through the *clip*, not through the
    // whole evening, so the frame and the curve stay describing the same instant.
    val window = clip?.windowOn(aligned.timeline, preset.syncOffsetMs)
    val windowStartMs = window?.startMs ?: 0L
    val windowEndMs = window?.endMs ?: aligned.timeline.durationMs
    val spanMs = window?.spanMs ?: (windowEndMs - windowStartMs).coerceAtLeast(1L)

    // The graph moves with the offset; the frame pulled from the file does not. That difference is
    // the whole point of the setting, and it is why the preview shows it working.
    val playheadMs = windowStartMs + spanMs * at.toDouble()
    // Where in the video file that instant falls, which is not the same number.
    // Past any footage filmed before the recording began, which the render skips too.
    val intoClipMs = (window?.intoClipMs ?: 0L) + (spanMs * at).toLong()

    // The same content and look the render gets, at preview size.
    val content = inga.bpmetrics.export.ExportContent(
        records = records,
        startMs = windowStartMs,
        endMs = windowEndMs.coerceAtLeast(windowStartMs + 1000L),
        title = title,
        colours = colours,
        photos = photos,
        // The footage's own clock, as the render reads it.
        clipZoneId = clip?.zoneId
    )
    val look = preset.copy(width = previewWidth, height = previewHeight)

    val bitmap = createBitmap(previewWidth, previewHeight)
    val canvas = Canvas(bitmap)

    overlay?.let { uri ->
        runCatching {
            android.media.MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(
                    intoClipMs * 1000,
                    if (exact) android.media.MediaMetadataRetriever.OPTION_CLOSEST
                    else android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
            }
        }.getOrNull()?.let { background ->
            // Fitted or filled the way the render does it. Stretched to the canvas, as it was, the
            // preview never showed the bars a clip of another shape gets in the export.
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawBitmap(
                background,
                null,
                footageRect(background.width, background.height, previewWidth, previewHeight, preset.fillCanvas),
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            )
        }
    }

    VideoFrameRenderer.renderAlignedRecordsOnCanvas(
        canvas = canvas,
        aligned = aligned,
        content = content,
        look = look,
        currentTimeMs = playheadMs,
        // Drawn where it will actually sit, so the preview shows the export rather than a
        // full-frame graph with an unrelated outline over it. Re-rendering on every drag frame
        // is why the outline itself is drawn by the preview widget and not by this.
        graphRect = placement.toRectF(),
        readouts = placement.readouts
    )
    return bitmap
}

/**
 * The editor's tabs, in the order they're offered (S14B.1).
 *
 * Each is about how something looks. Where the graph and the Live BPM column sit, how big they
 * are, and the sizes of the column's parts, are the Position screen's, opened from Graph: with
 * those in the tabs as well, every tab had a bit of placement in it and none had all of it. Live
 * BPM had little left but its switch, so that's in Graph too.
 *
 * Sync is the exception: when the clip was filmed and its BPM offset, which belong to the clip
 * rather than the look. A tab all the same, beside the rest, rather than a dialog behind a button
 * on the preview; only where there's a clip to sync.
 *
 * Short names, so all four fit across a phone without the row scrolling.
 */
enum class LookTab(val label: String) {
    CANVAS("Canvas"),
    SYNC("Sync"),
    GRAPH("Graph & BPM"),
    STYLE("Style");

    companion object {
        /** The tab with the settings for what was tapped on the preview (S14B.2). */
        fun of(region: VideoFrameRenderer.Region?): LookTab = when (region) {
            VideoFrameRenderer.Region.WORDMARK -> STYLE
            VideoFrameRenderer.Region.READOUTS, VideoFrameRenderer.Region.TITLE,
            VideoFrameRenderer.Region.CLOCK, VideoFrameRenderer.Region.PLOT -> GRAPH
            null -> CANVAS
        }
    }
}

/**
 * Who a change to the look applies to (S14B.3). The frame used to be written to the clip
 * being previewed without saying so, and saving a preset then baked that clip's frame into it.
 */
enum class FramingScope(val label: String) {
    CLIP("This clip"),
    CAST("Same number of people"),
    EVERY("Every clip")
}

/**
 * How an export looks: one editor, used by the export flow and by Settings (S14B.1).
 *
 * Tabs, where there were six accordions holding about 35 settings: the preview stays on screen
 * above, and each tab's controls get the rest of it (UX Audit E1). The tab is the caller's, so
 * tapping something in the preview can open its settings (S14B.2).
 *
 * Takes a bounded height: the tab row stays put and the tab's contents scroll under it.
 */
@Composable
fun LookSections(
    preset: ExportPreset,
    onChange: (ExportPreset) -> Unit,
    tab: LookTab,
    onTab: (LookTab) -> Unit,
    modifier: Modifier = Modifier,
    isImage: Boolean = false,
    /**
     * The heading drawn on the export, and a way to change it. Null where there is nothing to name:
     * the Settings editor previews a made-up subject.
     */
    title: String? = null,
    onTitleChange: ((String) -> Unit)? = null,
    /** Lays curves over a video picked by hand. Null where nothing can be picked. */
    onPickVideo: (() -> Unit)? = null,
    /** The size of the clip on the preview, to say what a canvas matching it comes out at. */
    clipSize: Pair<Int, Int>? = null,
    /** Opens Position, the graph and the Live BPM column. Null where there's nothing to place. */
    onPosition: ((PositionTarget) -> Unit)? = null,
    /** The Sync tab's contents: the previewed clip's time. Null, and no tab, where there's no clip. */
    sync: (@Composable () -> Unit)? = null,
    /** How many people are on the preview, for what the curves are colored by until it's chosen. */
    people: Int = 1
) {
    val tabs = LookTab.entries.filter { it != LookTab.SYNC || sync != null }
    val shown = if (tab in tabs) tab else tabs.first()

    Column(modifier) {
        androidx.compose.material3.SecondaryScrollableTabRow(
            selectedTabIndex = tabs.indexOf(shown),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            edgePadding = 12.dp
        ) {
            tabs.forEach { entry ->
                androidx.compose.material3.Tab(
                    selected = entry == shown,
                    onClick = { onTab(entry) },
                    text = { Text(entry.label) }
                )
            }
        }
        // One per tab visit, so each tab opens at its top rather than as far down as the last was.
        val scroll = remember(shown) { androidx.compose.foundation.ScrollState(0) }
        Column(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(16.dp)
        ) {
            when (shown) {
                LookTab.CANVAS -> CanvasTab(preset, onChange, isImage, onPickVideo, clipSize)
                LookTab.SYNC -> sync?.invoke()
                LookTab.GRAPH -> GraphTab(
                    preset, onChange, isImage, title, onTitleChange,
                    onPosition = onPosition?.let { open -> { open(PositionTarget.GRAPH) } }
                )
                LookTab.STYLE -> StyleTab(preset, onChange, people, isImage)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String, hint: String? = null) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    hint?.let { Hint(it) }
    Spacer(Modifier.height(6.dp))
}

/** One short line under a control saying what it does. */
@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The frame everything is drawn on: its resolution, how footage fills it, and how often it
 * changes.
 */
@Composable
private fun CanvasTab(
    preset: ExportPreset,
    onChange: (ExportPreset) -> Unit,
    isImage: Boolean,
    onPickVideo: (() -> Unit)?,
    clipSize: Pair<Int, Int>?
) {
    val matching = preset.matchClip && !isImage
    val named = CanvasSize.ratioOf(preset.width, preset.height)
    // Custom is a choice, not only a shape no other option names: picking it unlocks the sides
    // even while they still happen to be 16:9.
    var customChosen by rememberSaveable { mutableStateOf(false) }
    val custom = !matching && (customChosen || named == null)
    fun resized(size: Pair<Int, Int>) = preset.copy(width = size.first, height = size.second)

    // The size it comes out at, always in view: what the chips below add up to.
    val output = when {
        !matching -> preset.width to preset.height
        clipSize != null -> preset.forClip(clipSize.first, clipSize.second).let { it.width to it.height }
        else -> null
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Resolution", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(
            output?.let { (w, h) -> "$w × $h" } ?: "Each clip's own",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
    Spacer(Modifier.height(8.dp))

    Hint("Aspect ratio")
    Spacer(Modifier.height(4.dp))
    // A still has no clip to match.
    val ratioOptions = (if (isImage) emptyList() else listOf(CLIP)) + AspectRatio.entries.map { it.label } + CUSTOM
    Segmented(
        options = ratioOptions,
        selected = when {
            matching -> CLIP
            custom -> CUSTOM
            else -> named?.label
        },
        label = { it },
        onSelect = { choice ->
            customChosen = choice == CUSTOM
            when (choice) {
                CLIP -> onChange(preset.copy(matchClip = true))
                CUSTOM -> onChange(preset.copy(matchClip = false, lockAspectRatio = false))
                // Placement is fractional, so switching shape keeps the graph on the canvas, and
                // the resolution stays where it was: 1440p at 16:9 is 1440p at 9:16.
                else -> AspectRatio.entries.first { it.label == choice }.let { ratio ->
                    onChange(
                        resized(CanvasSize.shaped(preset.width, preset.height, ratio, DIMENSION_RANGE))
                            .copy(matchClip = false, lockAspectRatio = true)
                    )
                }
            }
        }
    )

    Spacer(Modifier.height(8.dp))
    Hint("Size")
    Spacer(Modifier.height(4.dp))
    Segmented(
        options = Resolution.entries,
        selected = CanvasSize.resolutionOf(preset.width, preset.height),
        label = { it.label },
        // Keeps the shape. Matching the clip, it is the most the clip is scaled to.
        onSelect = { onChange(resized(CanvasSize.at(preset.width, preset.height, it, DIMENSION_RANGE))) }
    )

    if (matching) {
        Hint(
            "Each clip keeps its own shape, with its short side at ${minOf(preset.width, preset.height)} px, " +
                "or the clip's own if that's smaller."
        )
    } else {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumberField(
                value = preset.width,
                label = "Width",
                range = DIMENSION_RANGE,
                modifier = Modifier.weight(1f),
                onValue = { width ->
                    onChange(
                        if (preset.lockAspectRatio) {
                            preset.copy(width = width, height = CanvasSize.follow(preset.height, preset.width, width, DIMENSION_RANGE))
                        } else preset.copy(width = width)
                    )
                }
            )
            // Between the two numbers it ties together, rather than a switch row of its own.
            androidx.compose.material3.IconToggleButton(
                checked = preset.lockAspectRatio,
                onCheckedChange = { onChange(preset.copy(lockAspectRatio = it)) }
            ) {
                Icon(
                    if (preset.lockAspectRatio) androidx.compose.material.icons.Icons.Default.Lock
                    else androidx.compose.material.icons.Icons.Default.LockOpen,
                    contentDescription = if (preset.lockAspectRatio) "Shape locked" else "Shape unlocked"
                )
            }
            NumberField(
                value = preset.height,
                label = "Height",
                range = DIMENSION_RANGE,
                modifier = Modifier.weight(1f),
                onValue = { height ->
                    onChange(
                        if (preset.lockAspectRatio) {
                            preset.copy(height = height, width = CanvasSize.follow(preset.width, preset.height, height, DIMENSION_RANGE))
                        } else preset.copy(height = height)
                    )
                }
            )
        }
    }

    // Only when the canvas can be a different shape from the footage.
    if (!isImage && !matching) {
        Spacer(Modifier.height(12.dp))
        InlineChoice(
            label = "Footage",
            options = listOf(false, true),
            selected = preset.fillCanvas,
            optionLabel = { if (it) "Fill" else "Fit" },
            onSelect = { onChange(preset.copy(fillCanvas = it)) }
        )
    }

    if (!isImage) {
        Spacer(Modifier.height(16.dp))
        SectionLabel("Frame rate")
        val rates = listOf(24, 25, 30, 50, 60)
        // Null is the clip's own rate.
        Segmented(
            options = listOf<Int?>(null) + rates,
            selected = if (preset.matchSourceFrameRate) null else preset.frameRate.takeIf { it in rates },
            label = { it?.toString() ?: CLIP },
            onSelect = { rate ->
                onChange(
                    if (rate == null) preset.copy(matchSourceFrameRate = true)
                    else preset.copy(frameRate = rate, matchSourceFrameRate = false)
                )
            }
        )
    }

    // The look-wide offset that clip-by-clip timing replaced. Not offered any more, but a preset
    // saved with one still applies it, so it is shown, with a way out, rather than acting unseen.
    if (!isImage && preset.syncOffsetMs != 0L) {
        Spacer(Modifier.height(16.dp))
        SectionLabel(
            "Older sync offset",
            "This look moves every clip by ${"%+.1f".format(preset.syncOffsetMs / 1000f)} s, from an " +
                "earlier version. Clips line up on their own now."
        )
        TextButton(onClick = { onChange(preset.copy(syncOffsetMs = 0L)) }) { Text("Clear it") }
    }

    onPickVideo?.let { pick ->
        Spacer(Modifier.height(12.dp))
        Hint("No clips: the curves are drawn on a plain background.")
        OutlinedButton(onClick = pick) { Text("Choose a video") }
    }
}

private const val CLIP = "Clip"

/** The aspect ratio for a shape no name covers: the sides typed by hand. */
private const val CUSTOM = "Custom"

/** A full-width segmented choice. No check mark: the fill already says which is chosen. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                label = { Text(label(option), style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            )
        }
    }
}

/** A label and a short segmented choice on one line. */
@Composable
private fun <T> InlineChoice(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Segmented(options, selected, optionLabel, onSelect, Modifier.width((options.size * 88).dp))
    }
}

/**
 * The way into Position from a tab: where the thing the tab is about sits on the canvas, and its
 * size. A button rather than controls, because both are judged against the picture, which Position
 * shows large.
 */
@Composable
private fun PositionButton(onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(
            androidx.compose.material.icons.Icons.Default.OpenWith,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text("Scale / Position")
    }
    Spacer(Modifier.height(12.dp))
}

/**
 * What the graph draws: its title, a clock and Live BPM, and how it holds up over footage. The
 * Live BPM column's place and the sizes of its parts are in Scale / Position.
 */
@Composable
private fun GraphTab(
    preset: ExportPreset,
    onChange: (ExportPreset) -> Unit,
    isImage: Boolean,
    title: String?,
    onTitleChange: ((String) -> Unit)?,
    onPosition: (() -> Unit)?
) {
    onPosition?.let { PositionButton(it) }

    // The graph's heading, drawn in its header.
    SwitchRow("Title", preset.showTitle) { onChange(preset.copy(showTitle = it)) }
    // Only where there is something to name, and only when it will be drawn.
    if (onTitleChange != null && preset.showTitle) {
        OutlinedTextField(
            value = title.orEmpty(),
            onValueChange = onTitleChange,
            // "Title" here and on the Contents step: they are the same field (UX Audit E8).
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Hint("Blank uses the name of what's exported. Every video in the batch gets it.")
        Spacer(Modifier.height(8.dp))
    }
    // In the header beside the title, so they cost no room on the graph. A still has no playhead.
    if (!isImage) ClockAndDate(preset, onChange)
    SwitchRow("Axis numbers", preset.showLabels) { onChange(preset.copy(showLabels = it)) }
    SwitchRow("Gridlines", preset.showGrid) { onChange(preset.copy(showGrid = it)) }
    if (isImage) {
        SwitchRow("Summary", preset.showCurrentStats) { onChange(preset.copy(showCurrentStats = it)) }
    } else {
        // Where the column goes and the size of its parts are in Scale / Position.
        SwitchRow("Show Live BPM", preset.showCurrentStats) { onChange(preset.copy(showCurrentStats = it)) }
    }
    Spacer(Modifier.height(4.dp))

    SliderRow(
        label = "Background",
        value = preset.backgroundOpacity / 100f,
        onValue = { onChange(preset.copy(backgroundOpacity = (it * 100).toInt())) }
    )
    if (isImage) {
        Hint("At 0% the PNG is see-through behind the curve, ready to lay over footage elsewhere.")
        return
    }

    SliderRow(
        label = "Fade what's ahead",
        value = preset.futureOpacity,
        onValue = { onChange(preset.copy(futureOpacity = it)) }
    )
    Spacer(Modifier.height(4.dp))
    NumberField(
        value = (preset.windowSizeMs / 1000).toInt(),
        label = "Seconds shown",
        range = WINDOW_RANGE,
        modifier = Modifier.fillMaxWidth(),
        onValue = { onChange(preset.copy(windowSizeMs = it * 1000L)) }
    )
    // The playhead is centered, so half of it is what you can see coming.
    Hint("The playhead is in the middle: ${(preset.windowSizeMs / 2000).toInt()} s of what's coming.")

    Spacer(Modifier.height(16.dp))
    // "BPM" so it isn't read as the size the Scale / Position button sets.
    SectionLabel("BPM scale")
    Segmented(
        options = BpmScale.entries,
        selected = preset.bpmScale,
        label = { it.label },
        onSelect = { onChange(preset.copy(bpmScale = it)) }
    )
    Hint(
        when (preset.bpmScale) {
            BpmScale.ON_SCREEN -> "Fits what's in view, so the curves have room to move."
            BpmScale.RECORDING -> "Fixed for the whole set, for comparing levels."
        }
    )
}

/** What keeps the curves and text readable over footage. */
@Composable
private fun ContrastSection(preset: ExportPreset, onChange: (ExportPreset) -> Unit) {
    SectionLabel("Contrast")
    Segmented(
        options = Contrast.entries,
        selected = preset.contrast,
        label = { if (it == Contrast.SHADOW) "Shadow" else it.label },
        onSelect = { onChange(preset.copy(contrast = it)) }
    )
    Hint(
        when (preset.contrast) {
            Contrast.AUTO -> "A shadow when the background is under ${Contrast.AUTO_PANEL_THRESHOLD}%."
            Contrast.OFF -> "Nothing behind the curves and text but the background."
            Contrast.SHADOW -> "A soft shadow behind the curves and text."
            Contrast.OUTLINE -> "A dark edge around the curves and text. Holds up over anything."
        }
    )
}

/**
 * What the graph's header says about when it is: a clock (the time of day, the time into the
 * session, or both) and the date, each a switch with its choices under it.
 */
@Composable
private fun ClockAndDate(preset: ExportPreset, onChange: (ExportPreset) -> Unit) {
    val none = inga.bpmetrics.export.ClockMode.NONE
    SwitchRow("Clock", preset.clockMode != none) { on ->
        onChange(preset.copy(clockMode = if (on) inga.bpmetrics.export.ClockMode.CLOCK else none))
    }
    if (preset.clockMode != none) {
        Segmented(
            options = inga.bpmetrics.export.ClockMode.entries.filter { it != none },
            selected = preset.clockMode,
            label = {
                when (it) {
                    inga.bpmetrics.export.ClockMode.ELAPSED -> "Elapsed"
                    else -> it.label
                }
            },
            onSelect = { onChange(preset.copy(clockMode = it)) }
        )
        if (preset.clockMode == inga.bpmetrics.export.ClockMode.BOTH) {
            Hint("The time of day, with the time into the session small above it.")
        }
        Spacer(Modifier.height(8.dp))
    }
    SwitchRow("Date", preset.showDate) { onChange(preset.copy(showDate = it)) }
    if (preset.showDate) {
        // Each shown as it reads, on a fixed day, so the choice is the format and not a date.
        val sample = remember { java.time.ZonedDateTime.of(2026, 10, 17, 0, 0, 0, 0, java.time.ZoneOffset.UTC) }
        Segmented(
            options = inga.bpmetrics.export.DateStyle.entries,
            selected = preset.dateStyle,
            label = { it.format(sample.toInstant().toEpochMilli(), java.time.ZoneOffset.UTC) },
            onSelect = { onChange(preset.copy(dateStyle = it)) }
        )
        Hint(if (preset.clockMode != none) "Small, above the clock." else "In the header, where the clock would be.")
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * A part that can be switched off, and its size, on one line. [checked] null for a part that's
 * always there.
 */
@Composable
private fun ToggleScaleRow(
    label: String,
    checked: Boolean?,
    onChecked: (Boolean) -> Unit = {},
    scale: Float,
    onScale: (Float) -> Unit
) {
    val on = checked != false
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(68.dp)
        )
        Slider(
            value = scale.coerceIn(SCALE_MIN, SCALE_MAX),
            onValueChange = onScale,
            valueRange = SCALE_MIN..SCALE_MAX,
            enabled = on,
            modifier = Modifier.weight(1f)
        )
        Text(
            "${kotlin.math.round(scale * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End,
            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(48.dp)
                .clickable(enabled = on, onClickLabel = "Back to 100%") { onScale(1f) }
                .padding(vertical = 8.dp)
        )
        Spacer(Modifier.width(8.dp))
        if (checked != null) {
            Switch(checked = checked, onCheckedChange = onChecked)
        } else {
            // Lines the sliders up with the rows that have a switch.
            Spacer(Modifier.width(52.dp))
        }
    }
}

/**
 * How the export's words and lines look: the text's size, how it all holds up over footage, the
 * watermark, and every color.
 */
@Composable
private fun StyleTab(
    preset: ExportPreset,
    onChange: (ExportPreset) -> Unit,
    people: Int,
    isImage: Boolean
) {
    ScaleRow("Text size", preset.textScale, range = 0.75f..2f) { onChange(preset.copy(textScale = it)) }
    Hint("The title, clock and axis numbers.")

    Spacer(Modifier.height(12.dp))
    val shipped = ExportPreset()
    ColourRow(
        "Text and numbers",
        preset.labelsColor,
        listOf(shipped.labelsColor, 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFD54F.toInt()),
        onPick = { onChange(preset.copy(labelsColor = it)) }
    )
    ColourRow(
        "Gridlines",
        preset.gridColor,
        listOf(shipped.gridColor, 0x33FFFFFF, 0x40000000, 0x3300DCC2),
        onPick = { onChange(preset.copy(gridColor = it)) },
        // Gridlines are faint by design; a custom one needs its faintness too.
        withOpacity = true
    )
    // What the curves are colored by. Shown as what this preview gets, which until it's chosen
    // depends on how many people are on it (see ExportPreset.curvesInPersonColor).
    val inPersonColor = VideoFrameRenderer.inPersonColor(preset, people)
    SwitchRow("Curves in each person's color", inPersonColor) {
        onChange(preset.copy(curvesInPersonColor = it))
    }
    if (inPersonColor) {
        Hint("Each curve, and its Live BPM reading, is its person's color.")
    } else {
        Hint("Every curve goes from low to high as the heart rate climbs.")
        Spacer(Modifier.height(6.dp))
        // Only while there's a ramp to color: with person colors it isn't drawn.
        GradientRow(preset, onChange)
    }

    // How the curves and text hold up over footage. A still has no footage under it.
    if (!isImage) {
        Spacer(Modifier.height(12.dp))
        ContrastSection(preset, onChange)
    }

    // The app's credit: words on the export, in the text color, so with the other colors.
    Spacer(Modifier.height(12.dp))
    SwitchRow("Watermark", preset.showWordmark) { onChange(preset.copy(showWordmark = it)) }
    if (preset.showWordmark) {
        Hint("BPMetrics, in the graph's top left, in the text color.")
        SliderRow(
            label = "Opacity",
            value = preset.wordmarkOpacity / 100f,
            onValue = { onChange(preset.copy(wordmarkOpacity = (it * 100).toInt())) }
        )
    }
}

/**
 * The colors one person's curve runs between, low heart rate to high, chosen as a pair.
 *
 * Shown as the ramp itself rather than two swatches, because what's being chosen is how the curve
 * reads as it climbs, and two dots don't show that: the blend goes the short way round the color
 * wheel, so two ends can pass through a color neither of them is.
 */
@Composable
private fun GradientRow(look: ExportPreset, onChange: (ExportPreset) -> Unit) {
    var customizing by remember { mutableStateOf(false) }
    val low = look.lowBpmColor
    val high = look.highBpmColor
    val builtIn = BUILT_IN_GRADIENTS.map { it.second to it.third }
    val options = (builtIn + (low to high)).distinct()
    // Choosing colors keeps where the blend runs; that's set in the custom dialog.
    val onPick: (Int, Int) -> Unit = { from, to -> onChange(look.copy(lowBpmColor = from, highBpmColor = to)) }

    Text("Low to high", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (from, to) ->
            val selected = from == low && to == high
            val name = BUILT_IN_GRADIENTS.firstOrNull { it.second == from && it.third == to }?.first ?: "Custom"
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 36.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(rampBrush(look.copy(lowBpmColor = from, highBpmColor = to)))
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
                    .clickable(onClickLabel = name) { onPick(from, to) }
            )
        }
        AddColourButton(description = "Custom low and high colors") { customizing = true }
    }

    if (customizing) {
        CustomGradientDialog(
            look = look,
            onDismiss = { customizing = false },
            onDone = { customizing = false; onChange(it) }
        )
    }
}

/**
 * [look]'s ramp as the renderer draws it, bottom of the scale on the left, sampled along the way
 * ([ExportPreset.rampStops]): a two-stop gradient would blend in RGB and show a muddy middle the
 * export never has, and wouldn't show where the blend starts and stops.
 */
private fun rampBrush(
    look: ExportPreset,
    startX: Float = 0f,
    endX: Float = Float.POSITIVE_INFINITY
): androidx.compose.ui.graphics.Brush =
    androidx.compose.ui.graphics.Brush.horizontalGradient(
        colorStops = look.rampStops()
            .map { it to androidx.compose.ui.graphics.Color(look.rampColor(it)) }
            .toTypedArray(),
        startX = startX,
        endX = endX
    )

/** The few ramps offered ready-made, the app's own first. */
private val BUILT_IN_GRADIENTS = listOf(
    Triple("Cool to hot", inga.bpmetrics.core.BpmPalette.LOW, inga.bpmetrics.core.BpmPalette.HIGH),
    Triple("Green to red", 0xFF00E676.toInt(), 0xFFFF5252.toInt()),
    Triple("Violet to orange", 0xFF7C4DFF.toInt(), 0xFFFF9100.toInt())
)

/** An empty circle with a plus: a color of your own. */
@Composable
private fun AddColourButton(description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.CircleShape)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            androidx.compose.material.icons.Icons.Default.Add,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** A color of your own: red, green and blue, and how see-through where [withOpacity]. */
@Composable
private fun CustomColourDialog(
    title: String,
    initial: Int,
    withOpacity: Boolean,
    onDismiss: () -> Unit,
    onDone: (Int) -> Unit
) {
    var colour by remember { mutableStateOf(if (withOpacity) initial else initial or 0xFF000000.toInt()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                ColourPreview(colour)
                Spacer(Modifier.height(12.dp))
                HsvPicker(colour) { colour = it }
                Spacer(Modifier.height(12.dp))
                ChannelSliders(colour, withOpacity) { colour = it }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(colour) }) { Text("Use") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Both ends of the ramp: their colors, one at a time, and where on the BPM scale each sits, as two
 * handles on the ramp itself. The handle tapped or dragged last is the one the picker below edits.
 */
@Composable
private fun CustomGradientDialog(
    look: ExportPreset,
    onDismiss: () -> Unit,
    onDone: (ExportPreset) -> Unit
) {
    var ramp by remember {
        mutableStateOf(
            look.copy(
                lowBpmColor = look.lowBpmColor or 0xFF000000.toInt(),
                highBpmColor = look.highBpmColor or 0xFF000000.toInt()
            )
        )
    }
    var editingHigh by remember { mutableStateOf(false) }
    val editing = if (editingHigh) ramp.highBpmColor else ramp.lowBpmColor
    val set: (Int) -> Unit = {
        ramp = if (editingHigh) ramp.copy(highBpmColor = it) else ramp.copy(lowBpmColor = it)
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Low to high") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                RampHandles(
                    ramp = ramp,
                    editingHigh = editingHigh,
                    onEditing = { editingHigh = it },
                    onRange = { start, end -> ramp = ramp.copy(rampStart = start, rampEnd = end) }
                )
                Text(
                    "Drag the ends to set where the blend starts and stops. Tap one to change its color.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (editingHigh) "High color" else "Low color",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                HsvPicker(editing, onChange = set)
                Spacer(Modifier.height(12.dp))
                // The person colors for speed, as quick starting points.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    inga.bpmetrics.library.PersonColors.PALETTE.forEach { option ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(androidx.compose.ui.graphics.Color(option))
                                .border(
                                    width = if (option == editing) 3.dp else 1.dp,
                                    color = if (option == editing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                                .clickable { set(option) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ChannelSliders(editing, withOpacity = false, onChange = set)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(ramp) }) { Text("Use") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The ramp as a bar, bottom of the BPM scale on the left and top on the right, with a handle at
 * each end of the blend. A touch takes the nearer handle, picks it for editing, and drags it;
 * neither can cross the other or squeeze the blend under [ExportPreset.MIN_RAMP_WIDTH].
 */
@Composable
private fun RampHandles(
    ramp: ExportPreset,
    editingHigh: Boolean,
    onEditing: (Boolean) -> Unit,
    onRange: (Float, Float) -> Unit
) {
    val latestRamp by rememberUpdatedState(ramp)
    val latestOnEditing by rememberUpdatedState(onEditing)
    val latestOnRange by rememberUpdatedState(onRange)
    val selectedRing = MaterialTheme.colorScheme.primary
    val handleRadius = 14.dp

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(Unit) {
                val r = handleRadius.toPx()
                val track = (size.width - 2 * r).coerceAtLeast(1f)
                fun fractionAt(x: Float) = ((x - r) / track).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val at = fractionAt(down.position.x)
                    val now = latestRamp
                    // The nearer end; where they're together, the side the touch is on.
                    val high = kotlin.math.abs(at - now.rampEnd) < kotlin.math.abs(at - now.rampStart) ||
                        (now.rampEnd - now.rampStart < 0.02f && at > now.rampEnd)
                    latestOnEditing(high)
                    down.consume()
                    drag(down.id) { change ->
                        val f = fractionAt(change.position.x)
                        val current = latestRamp
                        if (high) {
                            latestOnRange(current.rampStart, f.coerceAtLeast(current.rampStart + ExportPreset.MIN_RAMP_WIDTH))
                        } else {
                            latestOnRange(f.coerceAtMost(current.rampEnd - ExportPreset.MIN_RAMP_WIDTH), current.rampEnd)
                        }
                        change.consume()
                    }
                }
            }
    ) {
        val r = handleRadius.toPx()
        val track = size.width - 2 * r
        val barHeight = size.height * 0.6f
        val top = (size.height - barHeight) / 2f
        drawRoundRect(
            brush = rampBrush(ramp, startX = r, endX = size.width - r),
            topLeft = androidx.compose.ui.geometry.Offset(r, top),
            size = androidx.compose.ui.geometry.Size(track, barHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barHeight / 2f)
        )
        listOf(false to ramp.rampStart, true to ramp.rampEnd).forEach { (high, at) ->
            val centre = androidx.compose.ui.geometry.Offset(r + at * track, size.height / 2f)
            val colour = androidx.compose.ui.graphics.Color(if (high) ramp.highBpmColor else ramp.lowBpmColor)
            drawCircle(colour, radius = r, center = centre)
            drawCircle(
                if (high == editingHigh) selectedRing else androidx.compose.ui.graphics.Color.White,
                radius = r,
                center = centre,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = (if (high == editingHigh) 4.dp else 2.dp).toPx())
            )
        }
    }
}

/**
 * Picks a color by eye: a square of saturation across and brightness down, over a strip of hues.
 * The sliders under it are for exact values; this is for finding the color in the first place.
 *
 * Alpha is passed through untouched, so a faint gridline color stays faint while its hue changes.
 */
@Composable
private fun HsvPicker(colour: Int, onChange: (Int) -> Unit) {
    fun hsvOf(argb: Int) = FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }
    val hsv = hsvOf(colour)
    // The hue is kept here as well as read from the color: at no saturation or no brightness a
    // color has no hue, and dragging to a corner would otherwise throw away the one chosen.
    var hue by remember { mutableStateOf(hsv[0]) }
    var lastSent by remember { mutableStateOf(colour) }
    if (colour != lastSent) {
        // Changed from outside, by a slider or a swatch: follow it, where it has a hue to follow.
        if (hsv[1] > 0.01f && hsv[2] > 0.01f) hue = hsv[0]
        lastSent = colour
    }
    val alpha = colour and 0xFF000000.toInt()
    val send: (Float, Float, Float) -> Unit = { h, s, v ->
        val next = (android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)) and 0xFFFFFF) or alpha
        lastSent = next
        onChange(next)
    }
    val latestSend by rememberUpdatedState(send)
    val latestHue by rememberUpdatedState(hue)
    val latestHsv by rememberUpdatedState(hsv)
    val pure = androidx.compose.ui.graphics.Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    val outline = MaterialTheme.colorScheme.outline

    // Saturation across, brightness down.
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .clip(MaterialTheme.shapes.small)
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(androidx.compose.ui.graphics.Color.White, pure)
                )
            )
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Black)
                )
            )
            .border(1.dp, outline, MaterialTheme.shapes.small)
            .pointerInput(Unit) {
                fun at(p: androidx.compose.ui.geometry.Offset) = latestSend(
                    latestHue,
                    (p.x / size.width).coerceIn(0f, 1f),
                    1f - (p.y / size.height).coerceIn(0f, 1f)
                )
                awaitEachGesture {
                    val down = awaitFirstDown()
                    at(down.position)
                    down.consume()
                    drag(down.id) { change -> at(change.position); change.consume() }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val centre = androidx.compose.ui.geometry.Offset(hsv[1] * size.width, (1f - hsv[2]) * size.height)
            drawCircle(androidx.compose.ui.graphics.Color.Black, radius = 11.dp.toPx(), center = centre,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx()))
            drawCircle(androidx.compose.ui.graphics.Color.White, radius = 11.dp.toPx(), center = centre,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        }
    }
    Spacer(Modifier.height(12.dp))
    // Hue, the way round the wheel.
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    (0..12).map { androidx.compose.ui.graphics.Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 30f % 360f, 1f, 1f))) }
                )
            )
            .border(1.dp, outline, androidx.compose.foundation.shape.CircleShape)
            .pointerInput(Unit) {
                fun at(p: androidx.compose.ui.geometry.Offset) {
                    val h = (p.x / size.width).coerceIn(0f, 0.9999f) * 360f
                    hue = h
                    // A grey has no hue to show; give it some color so the change can be seen.
                    val s = latestHsv[1].takeIf { it > 0.01f } ?: 1f
                    val v = latestHsv[2].takeIf { it > 0.01f } ?: 1f
                    latestSend(h, s, v)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    at(down.position)
                    down.consume()
                    drag(down.id) { change -> at(change.position); change.consume() }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val x = hue / 360f * size.width
            val r = size.height / 2f - 2.dp.toPx()
            val centre = androidx.compose.ui.geometry.Offset(x.coerceIn(r, size.width - r), size.height / 2f)
            drawCircle(pure, radius = r, center = centre)
            drawCircle(androidx.compose.ui.graphics.Color.White, radius = r, center = centre,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
        }
    }
}

/** The color as a swatch over the panel's surface, with its hex code. */
@Composable
private fun ColourPreview(colour: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(48.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(androidx.compose.ui.graphics.Color(inga.bpmetrics.core.BpmPalette.SURFACE))
                .background(androidx.compose.ui.graphics.Color(colour))
                .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "#%06X".format(colour and 0xFFFFFF),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Red, green and blue sliders, and opacity where [withOpacity]. */
@Composable
private fun ChannelSliders(colour: Int, withOpacity: Boolean, onChange: (Int) -> Unit) {
    fun channel(shift: Int) = (colour ushr shift) and 0xFF
    fun with(shift: Int, value: Int) = (colour and (0xFF shl shift).inv()) or (value.coerceIn(0, 255) shl shift)
    listOf("Red" to 16, "Green" to 8, "Blue" to 0).forEach { (label, shift) ->
        ChannelSlider(label, channel(shift), 255) { onChange(with(shift, it)) }
    }
    if (withOpacity) {
        val percent = kotlin.math.round(channel(24) * 100f / 255f).toInt()
        ChannelSlider("Opacity", percent, 100) { onChange(with(24, kotlin.math.round(it * 255f / 100f).toInt())) }
    }
}

@Composable
private fun ChannelSlider(label: String, value: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(56.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(kotlin.math.round(it).toInt()) },
            valueRange = 0f..max.toFloat(),
            modifier = Modifier.weight(1f)
        )
        Text(
            if (max == 100) "$value%" else value.toString(),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp)
        )
    }
}

/**
 * A row of swatches choosing one color, the first being the app's own, and a way to make one. A
 * color made that way joins the row while it's in use, so it shows as chosen.
 */
@Composable
private fun ColourRow(
    label: String,
    value: Int,
    options: List<Int>,
    onPick: (Int) -> Unit,
    withOpacity: Boolean = false
) {
    var customizing by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        (options + value).distinct().forEachIndexed { index, colour ->
            val selected = colour == value
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    // A checkerboard-free stand-in for transparency: faint colors are shown over
                    // the panel's own surface, which is what they'll be drawn over.
                    .background(androidx.compose.ui.graphics.Color(inga.bpmetrics.core.BpmPalette.SURFACE))
                    .background(androidx.compose.ui.graphics.Color(colour))
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
                    .clickable(onClickLabel = if (index == 0) "App default" else null) { onPick(colour) }
            )
        }
        AddColourButton(description = "Custom ${label.lowercase()} color") { customizing = true }
    }

    if (customizing) {
        CustomColourDialog(
            title = label,
            initial = value,
            withOpacity = withOpacity,
            onDismiss = { customizing = false },
            onDone = { customizing = false; onPick(it) }
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * A size multiplier, shown as a percentage of what the renderer would pick on its own.
 *
 * Separate from [SliderRow] because that one is bounded 0..1 and means "how much of a maximum".
 * This means "how much larger or smaller than the automatic size", so its range straddles 100% and
 * the middle of the track is the default rather than half of it. Sharing one component would have
 * meant a slider whose centre meant something different depending on which row it was.
 */
@Composable
private fun ScaleRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float> = SCALE_MIN..SCALE_MAX,
    onValue: (Float) -> Unit
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { onValue(1f) },
                enabled = kotlin.math.abs(value - 1f) > 0.01f
            ) { Text("Reset") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onValue,
                valueRange = range,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TypedValueField(
                text = kotlin.math.round(value * 100).toInt().toString(),
                suffix = "%",
                onCommit = { typed ->
                    typed.toIntOrNull()?.let {
                        onValue((it / 100f).coerceIn(range.start, range.endInclusive))
                    }
                }
            )
        }
    }
}

/** The bounds a size multiplier may take, matching what `ExportPreset.sanitised` enforces. */
private const val SCALE_MIN = 0.5f
private const val SCALE_MAX = 2f

/**
 * A percentage, settable by dragging or by typing.
 *
 * The slider is for finding a value and the field is for stating one. Wanting exactly 40% and
 * having to hunt for it with a thumb a few hundred pixels wide is the case that needs the field;
 * wanting "a bit less" is the case that needs the slider. Neither substitutes for the other.
 */
@Composable
private fun SliderRow(label: String, value: Float, onValue: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value.coerceIn(0f, 1f),
                onValueChange = onValue,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TypedValueField(
                // Rounded, not truncated: dragging to 40% and reading back 39 looks like the drag
                // missed when it is the display that dropped the fraction.
                text = kotlin.math.round(value * 100).toInt().toString(),
                suffix = "%",
                onCommit = { typed -> typed.toIntOrNull()?.let { onValue(it.coerceIn(0, 100) / 100f) } }
            )
        }
    }
}

/**
 * The typed half of a slider.
 *
 * Holds its own text while being edited so a half-typed "4" on the way to "40" does not snap the
 * slider to 4% and take the rest of the keystroke with it. What is typed is applied on every
 * change that parses, but the field keeps showing the characters rather than the parsed value.
 */
@Composable
private fun TypedValueField(
    text: String,
    suffix: String,
    width: Dp = 76.dp,
    onCommit: (String) -> Unit
) {
    var editing by remember { mutableStateOf<String?>(null) }
    val shown = editing ?: text

    OutlinedTextField(
        value = shown,
        onValueChange = { typed ->
            val cleaned = typed.filter { it.isDigit() || it == '-' || it == '.' }.take(7)
            editing = cleaned
            onCommit(cleaned)
        },
        suffix = { Text(suffix, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .width(width)
            .onFocusChanged { if (!it.isFocused) editing = null }
    )
}

/**
 * A whole number, edited as text and applied when the typing is done.
 *
 * This showed the stored value and applied every keystroke that parsed, so the field couldn't be
 * cleared to type a fresh number (it snapped back), and each digit on the way re-laid the preview.
 * Now it keeps what is typed and commits on Done or when focus leaves, through [NumberEntry],
 * saying under the field when it had to change the number.
 */
@Composable
private fun NumberField(
    value: Int,
    label: String,
    range: IntRange,
    modifier: Modifier = Modifier,
    onValue: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    var note by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    fun commit() {
        val result = inga.bpmetrics.ui.components.NumberEntry.commit(text, value, range)
        text = result.value.toString()
        note = result.note
        if (result.value != value) onValue(result.value)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed.filter { it.isDigit() }.take(6)
            note = null
        },
        label = { Text(label) },
        supportingText = note?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onDone = {
                commit()
                focusManager.clearFocus()
            }
        ),
        modifier = modifier.onFocusChanged {
            if (focused && !it.isFocused) commit()
            focused = it.isFocused
        }
    )
}

/** Pixels. Below 16 there's no graph to draw; above 4096 most phones' encoders refuse. */
private val DIMENSION_RANGE = 16..4096

/** An aspect ratio offered by name, [across] by [down]. */
internal enum class AspectRatio(val label: String, val across: Int, val down: Int) {
    WIDE("16:9", 16, 9),
    TALL("9:16", 9, 16),
    SQUARE("1:1", 1, 1),
    PORTRAIT("4:5", 4, 5)
}

/** A resolution offered by name: the canvas's short edge, whatever its shape. */
internal enum class Resolution(val label: String, val shortEdge: Int) {
    HD("720p", 720),
    FULL_HD("1080p", 1080),
    QHD("1440p", 1440),
    UHD("4K", 2160)
}

/** The canvas's size: its other side when one is typed, and the named ratios and resolutions. */
internal object CanvasSize {
    /**
     * [other] scaled by the factor [oldSide] went to [newSide], to the nearest even number
     * (video encoders want even sides), kept in [range].
     */
    fun follow(other: Int, oldSide: Int, newSide: Int, range: IntRange): Int {
        if (oldSide <= 0) return other
        val scaled = other.toDouble() * newSide / oldSide
        val even = (Math.round(scaled / 2.0) * 2).toInt()
        return even.coerceIn(range)
    }

    /** The named ratio a [width] × [height] canvas is, or null for one no name covers. */
    fun ratioOf(width: Int, height: Int): AspectRatio? {
        if (width <= 0 || height <= 0) return null
        val ratio = width.toDouble() / height
        return AspectRatio.entries.firstOrNull { kotlin.math.abs(ratio - it.across.toDouble() / it.down) < 0.01 }
    }

    /** The named resolution a [width] × [height] canvas is at, by its short edge. */
    fun resolutionOf(width: Int, height: Int): Resolution? =
        Resolution.entries.firstOrNull { it.shortEdge == minOf(width, height) }

    /** A canvas [across] by [down] in shape with its short edge at [shortEdge], within [range]. */
    fun sized(across: Int, down: Int, shortEdge: Int, range: IntRange): Pair<Int, Int> {
        if (across <= 0 || down <= 0) return shortEdge to shortEdge
        fun even(px: Double) = (Math.round(px / 2.0) * 2).toInt()
        var short = shortEdge.toDouble()
        var long = short * maxOf(across, down) / minOf(across, down)
        // A long edge past the encoders' limit brings the whole canvas down, keeping its shape,
        // rather than squashing it: a 4K canvas of a wide custom shape comes out a little under 4K.
        if (long > range.last) {
            short *= range.last / long
            long = range.last.toDouble()
        }
        val s = even(short).coerceIn(range)
        val l = even(long).coerceIn(range)
        return if (across >= down) l to s else s to l
    }

    /** The canvas at [resolution], keeping its shape. */
    fun at(width: Int, height: Int, resolution: Resolution, range: IntRange): Pair<Int, Int> =
        sized(width, height, resolution.shortEdge, range)

    /** The canvas in [ratio]'s shape, keeping its resolution (its short edge). */
    fun shaped(width: Int, height: Int, ratio: AspectRatio, range: IntRange): Pair<Int, Int> =
        sized(ratio.across, ratio.down, minOf(width, height), range)
}

/** Seconds of graph across the frame. */
private val WINDOW_RANGE = 1..600

/**
 * Which clip step 3 is previewing.
 *
 * Each clip is its own shot with its own framing, so each gets its own graph placement and its own
 * scrub position. A strip rather than a dropdown because the thumbnails are what makes one clip
 * recognizable from another.
 *
 * It can be folded to one line, the clip's place in the batch and its time with arrows either
 * side, for when the settings need the room more than the thumbnails do.
 */
@Composable
fun ClipSelectorStrip(
    /** The clock each clip's time is shown in — see [ClipRetiming.clockFor]. */
    clockFor: (inga.bpmetrics.export.VideoExporter.VideoClip) -> java.time.ZoneId,
    clips: List<ClipSelection>,
    selectedUri: Uri?,
    onSelect: (Uri) -> Unit,
    collapsed: Boolean = false,
    onCollapse: (Boolean) -> Unit = {},
    /** False where only the one line is wanted, as in Position. */
    foldable: Boolean = true
) {
    if (clips.size < 2) return

    if (collapsed) {
        val index = clips.indexOfFirst { it.clip.uri == selectedUri }.coerceAtLeast(0)
        val current = clips[index]
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onSelect(clips[index - 1].clip.uri) },
                enabled = index > 0,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous clip")
            }
            Text(
                "Clip ${index + 1} of ${clips.size} · " +
                    inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString(
                        current.clip.startedAtMs,
                        clockFor(current.clip)
                    ) + when {
                        current.look != null -> " · own look"
                        current.graph != null -> " · framed"
                        else -> ""
                    },
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { onSelect(clips[index + 1].clip.uri) },
                enabled = index < clips.lastIndex,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next clip")
            }
            if (foldable) {
                IconButton(onClick = { onCollapse(false) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.ExpandMore, contentDescription = "Show clip thumbnails")
                }
            }
        }
        return
    }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(clips, key = { it.clip.uri.toString() }) { selection ->
                val chosen = selection.clip.uri == selectedUri
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(84.dp)
                        .clickable { onSelect(selection.clip.uri) }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (chosen) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        ClipThumb(selection.clip.uri)
                    }
                    Text(
                        inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString(
                            selection.clip.startedAtMs,
                            clockFor(selection.clip)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (chosen) FontWeight.Bold else null,
                        maxLines = 1
                    )
                    // Says which clips have been framed and which are still on the default, so
                    // "apply to all" has something visible to act on.
                    if (selection.look != null || selection.graph != null) {
                        Text(
                            if (selection.look != null) "own look" else "framed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        IconButton(onClick = { onCollapse(true) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.ExpandLess, contentDescription = "Fold the clips to one line")
        }
    }
}

@Composable
private fun ClipThumb(uri: Uri) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, android.util.Size(160, 160), null)
            }.getOrNull()
        }
    }
    thumbnail?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}
