package inga.bpmetrics.ui.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.Canvas
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import inga.bpmetrics.export.ExportPreset
import inga.bpmetrics.export.ImageExporter
import inga.bpmetrics.export.VideoExporter
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.ui.components.ExpandableSection
import inga.bpmetrics.ui.components.FlowRow
import kotlinx.coroutines.Dispatchers
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
 */
@Composable
fun ExportPreview(
    records: List<BpmRecord>,
    preset: ExportPreset,
    clip: VideoExporter.VideoClip?,
    placement: GraphPlacement,
    onPlacementChange: (GraphPlacement) -> Unit,
    overlay: Uri?,
    colours: Map<Long, Int>,
    title: String?,
    at: Float,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val aspect = preset.width.toFloat() / preset.height.toFloat().coerceAtLeast(1f)

    // Re-rendered whenever anything it depends on changes, off the main thread because it decodes
    // a video frame and draws every visible data point. Placement is a key too, so the rendered
    // graph follows the box — the box itself is drawn by the widget, so dragging stays responsive
    // while the frame catches up.
    val frame by produceState<Bitmap?>(
        initialValue = null,
        records, preset, overlay, at, colours, title, clip, placement
    ) {
        value = withContext(Dispatchers.Default) {
            renderPreviewFrame(context, records, preset, clip, overlay, colours, title, at, placement)
        }
    }

    Column(modifier) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // Height-capped rather than purely aspect-driven. A 9:16 canvas filling the width is
            // nearly twice as tall as it is wide, which pushed every setting off the bottom of the
            // screen — a preview with nothing visible to judge against is not doing its job.
            val safeAspect = aspect.coerceIn(0.4f, 2.5f)
            val previewHeight = minOf(maxWidth / safeAspect, 240.dp)

            Box(
                modifier = Modifier
                    .height(previewHeight)
                    .width(previewHeight * safeAspect)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
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

                // An outline and handles, no fill. A tinted rectangle over the render hides the
                // thing being judged, which is the one job the preview has.
                GraphFramingOverlay(
                    placement = placement,
                    onChange = onPlacementChange,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        ScrubTimeline(
            at = at,
            durationMs = clip?.durationMs ?: 0L,
            onScrub = onScrub,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * The scrub bar, drawn as a timeline rather than as a slider.
 *
 * A bare slider says nothing about what it is moving through: the same knob position means one
 * thing on a nine-second clip and another on a nine-minute one. This reads as footage — a filled
 * track for what has played, ticks at regular intervals, and the elapsed and total times spelled
 * out — so a position on it is a moment rather than a fraction.
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

    Column(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
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

        if (durationMs > 0L) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    clipTimeLabel((durationMs * at.coerceIn(0f, 1f)).toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    clipTimeLabel(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
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
 * The graph's frame, as an outline that can be dragged and resized.
 *
 * Drawn over the preview rather than beside it because a rectangle's size only means anything
 * against the thing it sits on. Deliberately unfilled: the render underneath is what is being
 * judged, and a tint over it defeats the point.
 */
@Composable
private fun GraphFramingOverlay(
    placement: GraphPlacement,
    onChange: (GraphPlacement) -> Unit,
    modifier: Modifier = Modifier
) {
    val current by rememberUpdatedState(placement)
    val onChanged by rememberUpdatedState(onChange)
    val outline = MaterialTheme.colorScheme.primary
    val knob = MaterialTheme.colorScheme.onPrimary

    // What this gesture grabbed, remembered for its duration. Local to the composable — a shared
    // one would let two previews fight over the same handle.
    var grabbed by remember { mutableStateOf<GraphHandle?>(null) }
    var moving by remember { mutableStateOf(false) }

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
                        grabbed = handleAt(offset, current, w, h, slop)
                        // Moving only from inside the frame. A drag that begins on the footage is
                        // aimed at the footage, and shoving the graph across the screen because a
                        // finger landed in open space is worse than doing nothing.
                        moving = grabbed == null && current.contains(offset.x / w, offset.y / h)
                    },
                    onDragEnd = { grabbed = null; moving = false },
                    onDragCancel = { grabbed = null; moving = false }
                ) { change, drag ->
                    change.consume()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val dx = drag.x / w
                    val dy = drag.y / h
                    when {
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

            // Handles pulled inside the frame edge rather than centred on it, so one dragged flush
            // to the border stays wholly visible — and wholly grabbable — instead of being half
            // clipped away by the preview's own bounds.
            val radius = 7.dp.toPx()
            val inset = radius + 1.dp.toPx()
            val midX = (left + right) / 2f
            val midY = (top + bottom) / 2f

            listOf(
                left to top, right to top, left to bottom, right to bottom,
                left to midY, right to midY, midX to top, midX to bottom
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
private fun renderPreviewFrame(
    context: android.content.Context,
    records: List<BpmRecord>,
    preset: ExportPreset,
    clip: VideoExporter.VideoClip?,
    overlay: Uri?,
    colours: Map<Long, Int>,
    title: String?,
    at: Float,
    placement: GraphPlacement
): Bitmap? {
    if (records.isEmpty()) return null

    // Rendered small. A preview is a few hundred pixels wide on screen and rendering it at the
    // export's own resolution would decode a 4K frame to show it at 300dp.
    val previewWidth = 720
    val previewHeight = (previewWidth / (preset.width.toFloat() / preset.height)).toInt()
        .coerceAtLeast(1)

    // Clock-aligned whenever there is footage, matching what the export will do. Elapsed alignment
    // starts every recording at 0:00, which is only right when there is no video to agree with.
    // Always clock-aligned, matching the export.
    val aligned = ImageExporter.alignRecords(records, false)

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
    val intoClipMs = (spanMs * at).toLong()

    val config = preset.applyTo(
        VideoExporter.VideoExportConfig(
            imageConfig = ImageExporter.ImageExportConfig(
                startTimeMs = windowStartMs,
                endTimeMs = windowEndMs.coerceAtLeast(windowStartMs + 1000L),
                customRecordColors = colours,
                graphTitle = title,
                alignByElapsedTime = false
            ),
            records = records
        )
    ).imageConfig.copy(
        width = previewWidth,
        height = previewHeight,
        alignByElapsedTime = false
    )

    val bitmap = createBitmap(previewWidth, previewHeight)
    val canvas = Canvas(bitmap)

    overlay?.let { uri ->
        runCatching {
            android.media.MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(
                    intoClipMs * 1000,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
            }
        }.getOrNull()?.let { background ->
            canvas.drawBitmap(
                background,
                null,
                RectF(0f, 0f, previewWidth.toFloat(), previewHeight.toFloat()),
                null
            )
        }
    }

    ImageExporter.renderAlignedRecordsOnCanvas(
        canvas = canvas,
        aligned = aligned,
        config = config,
        currentTimeMs = playheadMs,
        windowSizeMs = preset.windowSizeMs,
        // Drawn where it will actually sit, so the preview shows the export rather than a
        // full-frame graph with an unrelated outline over it. Re-rendering on every drag frame
        // is why the outline itself is drawn by the preview widget and not by this.
        graphRect = placement.toRectF()
    )
    return bitmap
}

/**
 * Step 3's settings, in four sections.
 *
 * The old dialog asked everything at once in one scroll — canvas size beside which recordings
 * beside where the graph sits — so finding one option meant reading all of them. Grouping by what
 * the option is *about* is the whole change: same options, findable.
 */
@Composable
fun LookSections(
    preset: ExportPreset,
    onChange: (ExportPreset) -> Unit,
    overlay: Uri?,
    onPickOverlay: () -> Unit,
    onClearOverlay: () -> Unit,
    hasClips: Boolean,
    syncOffsetMs: Long,
    onSyncOffsetChange: (Long) -> Unit,
    framing: GraphPlacement,
    onFramingChange: (GraphPlacement) -> Unit,
    presetBar: @Composable () -> Unit = {}
) {
    Column(Modifier.fillMaxWidth()) {
        SettingsSection("Presets", "Saved looks to apply, update or share") {
            presetBar()
        }

        SettingsSection("Canvas", "The shape and size of the finished video") {
            val presets = listOf(
                "16:9" to (1920 to 1080),
                "9:16" to (1080 to 1920),
                "1:1" to (1080 to 1080),
                "4:5" to (1080 to 1350)
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                presets.forEach { (label, size) ->
                    FilterChip(
                        selected = preset.width == size.first && preset.height == size.second,
                        onClick = {
                            // Placement is fractional, so switching aspect keeps the graph on
                            // canvas instead of leaving it off the bottom — EXP-4.2.
                            onChange(preset.copy(width = size.first, height = size.second))
                        },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    value = preset.width,
                    label = "Width",
                    modifier = Modifier.weight(1f),
                    onValue = { onChange(preset.copy(width = it)) }
                )
                NumberField(
                    value = preset.height,
                    label = "Height",
                    modifier = Modifier.weight(1f),
                    onValue = { onChange(preset.copy(height = it)) }
                )
            }
            SwitchRow("Lock aspect ratio", preset.lockAspectRatio) {
                onChange(preset.copy(lockAspectRatio = it))
            }
        }

        SettingsSection("Graph", "Where the chart sits, and what is drawn on it") {
            Text(
                "Placement",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Drag the outline on the preview, or start from one of these.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                GraphPlacement.PRESETS.forEach { (label, placement) ->
                    FilterChip(
                        selected = framing.matches(placement),
                        onClick = { onFramingChange(placement) },
                        label = { Text(label) }
                    )
                }
            }
            Row {
                // Centring by eye is guesswork that never quite lands, and "nearly centred" is
                // more obviously wrong than off-centre on purpose.
                TextButton(onClick = { onFramingChange(framing.centredHorizontally()) }) {
                    Text("Center across", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { onFramingChange(framing.centredVertically()) }) {
                    Text("Center down", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(Modifier.height(12.dp))
            SwitchRow("Labels", preset.showLabels) { onChange(preset.copy(showLabels = it)) }
            SwitchRow("Grid", preset.showGrid) { onChange(preset.copy(showGrid = it)) }
            SwitchRow("Title", preset.showTitle) { onChange(preset.copy(showTitle = it)) }
            SwitchRow("Live stats", preset.showCurrentStats) {
                onChange(preset.copy(showCurrentStats = it))
            }
            Spacer(Modifier.height(8.dp))
            SliderRow(
                label = "Trailing opacity",
                value = preset.futureOpacity,
                onValue = { onChange(preset.copy(futureOpacity = it)) }
            )
            // Opacity of the panel the graph sits on, which is a property of the graph rather than
            // of the video behind it — it lives here rather than in a section of its own.
            SliderRow(
                label = "Panel opacity",
                value = preset.backgroundOpacity / 100f,
                onValue = { onChange(preset.copy(backgroundOpacity = (it * 100).toInt())) }
            )

            if (!hasClips) {
                Spacer(Modifier.height(12.dp))
                Text(
                    overlay?.lastPathSegment?.let { "Video: $it" }
                        ?: "No video — the curves are drawn on a plain canvas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickOverlay) { Text("Choose a video") }
                    if (overlay != null) {
                        OutlinedButton(onClick = onClearOverlay) { Text("Remove") }
                    }
                }
            }
        }

        SettingsSection("Time", "How the graph is placed against the footage") {
            NumberField(
                value = (preset.windowSizeMs / 1000).toInt(),
                label = "Visible window (seconds)",
                modifier = Modifier.fillMaxWidth(),
                onValue = { onChange(preset.copy(windowSizeMs = it * 1000L)) }
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Frame rate",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                // Resolved per clip rather than once for the batch: a set of clips off one phone
                // can still mix 30 and 60, and re-encoding 60fps footage to 30 throws away half
                // the frames it was filmed for.
                "Match source keeps each clip at the rate it was filmed at.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = preset.matchSourceFrameRate,
                    onClick = { onChange(preset.copy(matchSourceFrameRate = true)) },
                    label = { Text("Match source") }
                )
                // The rates footage actually comes in: film, PAL, the two Android defaults, and
                // the two the slow-motion modes record at.
                listOf(24, 25, 30, 50, 60).forEach { rate ->
                    FilterChip(
                        selected = !preset.matchSourceFrameRate && preset.frameRate == rate,
                        onClick = {
                            onChange(preset.copy(frameRate = rate, matchSourceFrameRate = false))
                        },
                        label = { Text("$rate") }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Sync offset",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                // The escape hatch for the cases the automatic correction cannot settle. A phone
                // that stamps its videos in local time, a watch whose clock had drifted — both
                // show up as a constant shift, and a constant shift is what this cancels.
                "Nudge the curves if they run ahead of or behind the footage. Watch the preview " +
                    "while you change it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = syncOffsetMs.toFloat().coerceIn(-30_000f, 30_000f),
                    valueRange = -30_000f..30_000f,
                    // To the tenth of a second. A frame is 33ms, so finer than this is below what
                    // the export can act on, and the field is there for anyone who disagrees.
                    onValueChange = { onSyncOffsetChange((it / 100f).toLong() * 100L) },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                TypedValueField(
                    text = "%.1f".format(syncOffsetMs / 1000f),
                    suffix = "s",
                    width = 88.dp,
                    onCommit = { typed ->
                        typed.toFloatOrNull()?.let { onSyncOffsetChange((it * 1000f).toLong()) }
                    }
                )
            }
            if (syncOffsetMs != 0L) {
                TextButton(onClick = { onSyncOffsetChange(0L) }) { Text("Reset to automatic") }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        ExpandableSection(
            title = title,
            isExpanded = expanded,
            onToggle = { expanded = !expanded },
            titleStyle = MaterialTheme.typography.titleSmall
        ) {
            Column {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
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

@Composable
private fun NumberField(
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
    onValue: (Int) -> Unit
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text ->
            // Ignores anything unparseable rather than resetting to zero, so deleting the last
            // digit mid-edit does not silently make the canvas one pixel wide.
            text.toIntOrNull()?.takeIf { it > 0 }?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier
    )
}

/**
 * Which clip step 3 is previewing.
 *
 * Each clip is its own shot with its own framing, so each gets its own graph placement and its own
 * scrub position. A strip rather than a dropdown because the thumbnails are what makes one clip
 * recognisable from another.
 */
@Composable
fun ClipSelectorStrip(
    clips: List<ClipSelection>,
    selectedUri: Uri?,
    onSelect: (Uri) -> Unit
) {
    if (clips.size < 2) return

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
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
                        selection.clip.startedAtMs
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (chosen) FontWeight.Bold else null,
                    maxLines = 1
                )
                // Says which clips have been framed and which are still on the default, so
                // "apply to all" has something visible to act on.
                if (selection.graph != null) {
                    Text(
                        "framed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
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
