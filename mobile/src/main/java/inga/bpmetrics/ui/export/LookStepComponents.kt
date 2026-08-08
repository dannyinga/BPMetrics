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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import inga.bpmetrics.export.ExportPreset
import inga.bpmetrics.export.ImageExporter
import inga.bpmetrics.export.WordmarkCorner
import inga.bpmetrics.export.PillCorner
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
    photos: Map<Long, android.graphics.Bitmap> = emptyMap(),
    title: String?,
    at: Float,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val aspect = preset.width.toFloat() / preset.height.toFloat().coerceAtLeast(1f)

    // Saveable so a rotation, or a trip into the photo picker, does not reopen a preview someone
    // deliberately folded away to get at the settings underneath.
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    var fullScreen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    // Re-rendered whenever anything it depends on changes, off the main thread because it decodes
    // a video frame and draws every visible data point. Placement is a key too, so the rendered
    // graph follows the box — the box itself is drawn by the widget, so dragging stays responsive
    // while the frame catches up.
    //
    // Not while it is hidden. Every setting touched re-renders this, and half the reason to fold it
    // away is to stop that happening behind a slider being dragged — a preview nobody can see has
    // no business decoding a video frame. Nor while the full-screen copy is up, which would have
    // two renders racing for the same answer.
    val visible = expanded && !fullScreen
    val frame by produceState<Bitmap?>(
        initialValue = null,
        visible, records, preset, overlay, at, colours, photos, title, clip, placement
    ) {
        if (!visible) return@produceState
        value = withContext(Dispatchers.Default) {
            renderPreviewFrame(context, records, preset, clip, overlay, colours, photos, title, at, placement)
        }
    }

    if (fullScreen) {
        FullScreenPreview(
            records = records,
            preset = preset,
            clip = clip,
            overlay = overlay,
            colours = colours,
            photos = photos,
            title = title,
            at = at,
            onScrub = onScrub,
            placement = placement,
            onPlacementChange = onPlacementChange,
            onDismiss = { fullScreen = false }
        )
    }

    Column(modifier) {
        // The preview is sticky and takes a third of a short screen. That is right while framing
        // something and wrong while reading down a list of settings, so it folds — and the row
        // stays, because a preview with no way back is worse than one always in the way.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { expanded = !expanded }) {
                androidx.compose.material3.Icon(
                    if (expanded) {
                        androidx.compose.material.icons.Icons.Default.ExpandLess
                    } else {
                        androidx.compose.material.icons.Icons.Default.ExpandMore
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (expanded) "Hide preview" else "Show preview")
            }

            if (expanded) {
                androidx.compose.material3.IconButton(onClick = { fullScreen = true }) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Default.Fullscreen,
                        contentDescription = "Open the preview full screen"
                    )
                }
            }
        }

        if (!expanded) return@Column

        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // Height-capped rather than purely aspect-driven. A 9:16 canvas filling the width is
            // nearly twice as tall as it is wide, which pushed every setting off the bottom of the
            // screen — a preview with nothing visible to judge against is not doing its job.
            //
            // The cap is a share of the screen rather than a flat 240dp. That number was measured
            // on a tall phone, where it leaves plenty below it; on a short one the same 240dp is a
            // third of everything there is, and the settings it is meant to be previewed against go
            // back off the bottom. A third of the screen is the same *proportion* everywhere, and
            // the floor keeps it from shrinking to something not worth looking at.
            val safeAspect = aspect.coerceIn(0.4f, 2.5f)
            val screenHeight = LocalConfiguration.current.screenHeightDp.dp
            val cap = (screenHeight * 0.32f).coerceIn(140.dp, 260.dp)
            val previewHeight = minOf(maxWidth / safeAspect, cap)

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
 * The same frame, filling the screen.
 *
 * Exists because the preview is a few hundred dp of a canvas that is 1920 across, and the things
 * most worth checking on it — a wearer's name in a pill, an axis label, the wordmark — are small
 * text that simply cannot be read at that size. Judging them by squinting at a thumbnail is how a
 * setting gets called fine and then turns out not to be.
 *
 * Re-rendered at a higher resolution rather than scaling the small frame up: enlarging the thumbnail
 * would enlarge its softness too, and the whole reason for opening this is to look closely.
 *
 * The framing handles are here too, and this is the better place for them. Dragging a corner in a
 * preview a few hundred dp wide moves the graph in steps of about a percent of the canvas per pixel
 * of finger; at full size the same drag is fine adjustment. The small preview is for judging where
 * the graph sits against the whole frame, and this is for settling exactly where its edges land.
 */
@Composable
private fun FullScreenPreview(
    records: List<BpmRecord>,
    preset: ExportPreset,
    clip: VideoExporter.VideoClip?,
    overlay: Uri?,
    colours: Map<Long, Int>,
    photos: Map<Long, android.graphics.Bitmap>,
    title: String?,
    at: Float,
    onScrub: (Float) -> Unit,
    placement: GraphPlacement,
    onPlacementChange: (GraphPlacement) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val aspect = preset.width.toFloat() / preset.height.toFloat().coerceAtLeast(1f)

    val frame by produceState<Bitmap?>(
        initialValue = null,
        records, preset, overlay, at, colours, photos, title, clip, placement
    ) {
        value = withContext(Dispatchers.Default) {
            renderPreviewFrame(
                context, records, preset, clip, overlay, colours, photos, title, at, placement,
                // Capped rather than the export's full width: a 4K preset would decode a 4K frame
                // per scrub, and no phone screen can show the difference.
                renderWidth = preset.width.coerceAtMost(1920)
            )
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black)
        ) {
            Column(Modifier.fillMaxSize()) {
                BoxWithConstraints(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    // Fitted to the space, not stretched across the width. Forcing full width means
                    // the height follows from the aspect and can exceed the screen — which in
                    // landscape it does, so the frame filled the display edge to edge and the
                    // controls had nowhere to go but on top of it. Constraining by whichever axis
                    // runs out first leaves black bars on the other, which is where they belong.
                    val safeAspect = aspect.coerceIn(0.4f, 2.5f)
                    val boxAspect = maxWidth / maxHeight
                    val sizing = if (safeAspect > boxAspect) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.fillMaxHeight()
                    }

                    Box(
                        sizing
                            .aspectRatio(safeAspect)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        frame?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Export preview, full screen",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        } ?: CircularProgressIndicator()

                        // Inside the frame's own box, not the screen's, so the fractions the
                        // overlay works in map onto the rectangle the renderer drew. Anchored to
                        // the screen, a drag would move the graph somewhere other than the finger.
                        GraphFramingOverlay(
                            placement = placement,
                            onChange = onPlacementChange,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Below the picture, in space of its own. It was floating over the bottom of the
                // frame, which is exactly where a graph framed as a lower third sits — so the one
                // control that lets you find a frame worth judging was covering the thing being
                // judged. Nothing is gained by overlapping it: the room it takes comes out of the
                // letterbox, not out of the picture.
                ScrubTimeline(
                    at = at,
                    durationMs = clip?.durationMs ?: 0L,
                    onScrub = onScrub,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 16.dp)
                )
            }

            androidx.compose.material3.IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Default.Close,
                    contentDescription = "Close",
                    tint = androidx.compose.ui.graphics.Color.White
                )
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
    renderWidth: Int = 720
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
                recordPhotos = photos,
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
    isImage: Boolean = false,
    /** False where the graph frame cannot be dragged, so the hint offers the chips instead. */
    hasPreview: Boolean = true,
    /**
     * Whether to offer switching to a different preset.
     *
     * False in the Settings editor, where the preset *is* the subject and a control for swapping
     * it out would only be a way to lose your work.
     */
    /**
     * The heading drawn on the export, and a way to change it.
     *
     * Null where there is nothing to name — the preset editor in Settings previews a made-up
     * subject, so a title field there would be editing a caption for a recording that does not
     * exist.
     */
    title: String? = null,
    onTitleChange: ((String) -> Unit)? = null,
    showPresetBar: Boolean = true,
    presetBar: @Composable () -> Unit = {}
) {
    Column(Modifier.fillMaxWidth()) {
        if (showPresetBar) {
            SettingsSection("Presets", "Saved looks to apply, update or share") {
                presetBar()
            }
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
            // Placement is a video question. An image *is* the graph, so insetting it would leave a
            // border of nothing around the only thing in the frame.
            if (!isImage) {
                Text(
                    "Placement",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (hasPreview) {
                        "Drag the outline on the preview, or start from one of these."
                    } else {
                        "Pick a starting arrangement. Fine-tune it against footage in the export."
                    },
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

                Spacer(Modifier.height(8.dp))
                Text(
                    "Size",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    // What it is for, since "size" alone does not say that it holds the shape.
                    "Keeps the shape and the position. Get the framing right once, then make it " +
                        "as large or small as you want.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                // Absolute, not a nudge: the value *is* how wide the graph is, so the slider always
                // reads where the graph actually is. A relative control would have to snap back to
                // the middle after every drag, and could not answer "how big is it now" at all.
                SliderRow(
                    label = "Width of the frame",
                    value = framing.width,
                    onValue = { onFramingChange(framing.scaledTo(it)) }
                )

                Spacer(Modifier.height(12.dp))
            }

            SwitchRow("Labels", preset.showLabels) { onChange(preset.copy(showLabels = it)) }
            SwitchRow("Grid", preset.showGrid) { onChange(preset.copy(showGrid = it)) }
            SwitchRow("Title", preset.showTitle) { onChange(preset.copy(showTitle = it)) }

            // Only where there is something to name, and only when it will be drawn — a field
            // editing text that the switch above has just turned off is a control over nothing.
            if (onTitleChange != null && preset.showTitle) {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = title.orEmpty(),
                    onValueChange = onTitleChange,
                    label = { Text("Heading") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    // The default is the source's own name, so an empty field is not "no title".
                    "Leave blank to use the name of what is being exported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
            }
            SwitchRow(
                // The same field means different things either side of this: a running readout on
                // a video, and everyone's summary for the whole span on a still. Both are "the
                // numbers beside the curve", which is why one switch is right for both.
                if (isImage) "Summary" else "Live stats",
                preset.showCurrentStats
            ) {
                onChange(preset.copy(showCurrentStats = it))
            }
            Spacer(Modifier.height(8.dp))

            // Trailing opacity fades what has not happened yet, which needs a "yet". A still has
            // no playhead, so every curve on it is drawn at full strength and this would do
            // nothing but confuse.
            if (!isImage) {
                SliderRow(
                    label = "Trailing opacity",
                    value = preset.futureOpacity,
                    onValue = { onChange(preset.copy(futureOpacity = it)) }
                )
            }
            // Opacity of the panel the graph sits on, which is a property of the graph rather than
            // of the video behind it — it lives here rather than in a section of its own.
            SliderRow(
                label = "Panel opacity",
                value = preset.backgroundOpacity / 100f,
                onValue = { onChange(preset.copy(backgroundOpacity = (it * 100).toInt())) }
            )
            if (isImage) {
                Text(
                    // The reason the setting matters more here than on a video: nothing is
                    // composited in this app, so the alpha is for whatever does the compositing.
                    "At 0% the saved PNG is transparent behind the curve, ready to lay over " +
                        "footage in another app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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

        // Video only: a still has no playhead, so there is no live reading for a pill to show.
        if (!isImage) {
            SettingsSection("Readouts", "The live pill for each person, over the footage") {
                Text(
                    // The reason this is a setting rather than one fixed design. Both answers are
                    // right, for different exports.
                    "Two people on a story clip can carry faces and names. Six on a landscape " +
                        "shot cannot — the column would take a third of the frame.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                SwitchRow("Photo", preset.pillShowPhoto) {
                    onChange(preset.copy(pillShowPhoto = it))
                }
                SwitchRow("Name", preset.pillShowName) {
                    onChange(preset.copy(pillShowName = it))
                }
                SwitchRow("Reading in their colour", preset.pillBpmInPersonColor) {
                    onChange(preset.copy(pillBpmInPersonColor = it))
                }

                Spacer(Modifier.height(8.dp))
                Text("Side", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(
                    // Worth saying, because the reason is not visible from the setting: the
                    // playhead sits in the middle, so the right of the graph is what has not
                    // happened yet — and is drawn faded, which is why the readouts sit there.
                    "Right sits over the faded part of the graph. The clock takes the other side.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PillCorner.entries.forEach { corner ->
                        FilterChip(
                            selected = preset.pillCorner == corner,
                            onClick = { onChange(preset.copy(pillCorner = corner)) },
                            label = { Text(corner.label) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                // Multipliers on what the renderer works out for the graph, so no combination of
                // these can produce pills that do not fit.
                ScaleRow("Pill size", preset.pillScale) {
                    onChange(preset.copy(pillScale = it))
                }
                if (preset.pillShowPhoto) {
                    ScaleRow("Photo size", preset.pillPhotoScale) {
                        onChange(preset.copy(pillPhotoScale = it))
                    }
                }
                ScaleRow("Reading size", preset.pillBpmScale) {
                    onChange(preset.copy(pillBpmScale = it))
                }
                if (preset.pillShowName) {
                    ScaleRow("Name size", preset.pillNameScale) {
                        onChange(preset.copy(pillNameScale = it))
                    }
                }
            }
        }

        // Above the video-only cut-off: a still is signed the same way a video is.
        SettingsSection("Signature", "A small credit linking the export back to the app") {
            SwitchRow("Show wordmark", preset.showWordmark) {
                onChange(preset.copy(showWordmark = it))
            }
            if (preset.showWordmark) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Corner",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    WordmarkCorner.entries.forEach { corner ->
                        FilterChip(
                            selected = preset.wordmarkCorner == corner,
                            onClick = { onChange(preset.copy(wordmarkCorner = corner)) },
                            label = { Text(corner.label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                SliderRow(
                    label = "Wordmark opacity",
                    value = preset.wordmarkOpacity / 100f,
                    onValue = { onChange(preset.copy(wordmarkOpacity = (it * 100).toInt())) }
                )
            }
        }

        // Every option in here describes motion against footage — a visible window, a frame rate, a
        // sync offset. An image has none of those: it shows the whole timeline in one frame, at
        // once, over nothing.
        if (isImage) return@Column

        SettingsSection("Time", "How the graph is placed against the footage") {
            NumberField(
                value = (preset.windowSizeMs / 1000).toInt(),
                label = "Visible window (seconds)",
                modifier = Modifier.fillMaxWidth(),
                onValue = { onChange(preset.copy(windowSizeMs = it * 1000L)) }
            )
            Text(
                // What the number actually buys, which is not obvious from "visible window": the
                // playhead is centred, so half of it is what you can see coming.
                "The playhead sits in the middle, so this shows " +
                    "${(preset.windowSizeMs / 2000).toInt()} seconds of what is coming.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "Clock",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Drawn in the header beside the title, where it costs no room on the graph.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                inga.bpmetrics.export.ClockMode.entries.forEach { mode ->
                    FilterChip(
                        selected = preset.clockMode == mode,
                        onClick = { onChange(preset.copy(clockMode = mode)) },
                        label = { Text(mode.label) }
                    )
                }
            }
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
 * A size multiplier, shown as a percentage of what the renderer would pick on its own.
 *
 * Separate from [SliderRow] because that one is bounded 0..1 and means "how much of a maximum".
 * This means "how much larger or smaller than the automatic size", so its range straddles 100% and
 * the middle of the track is the default rather than half of it. Sharing one component would have
 * meant a slider whose centre meant something different depending on which row it was.
 */
@Composable
private fun ScaleRow(label: String, value: Float, onValue: (Float) -> Unit) {
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
                value = value.coerceIn(SCALE_MIN, SCALE_MAX),
                onValueChange = onValue,
                valueRange = SCALE_MIN..SCALE_MAX,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TypedValueField(
                text = kotlin.math.round(value * 100).toInt().toString(),
                suffix = "%",
                onCommit = { typed ->
                    typed.toIntOrNull()?.let {
                        onValue((it / 100f).coerceIn(SCALE_MIN, SCALE_MAX))
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
