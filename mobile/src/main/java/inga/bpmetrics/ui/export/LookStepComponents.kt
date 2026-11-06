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
import inga.bpmetrics.export.BpmScale
import inga.bpmetrics.export.Contrast
import inga.bpmetrics.export.ExportPreset
import inga.bpmetrics.export.VideoFrameRenderer
import inga.bpmetrics.export.WordmarkCorner
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
    onTapRegion: ((VideoFrameRenderer.Region?) -> Unit)? = null
) {
    val context = LocalContext.current
    // At the size it renders over this clip, so a canvas matched to the clip previews as the
    // clip's shape. Everything below, the full-screen copy included, works from this.
    @Suppress("NAME_SHADOWING")
    val preset = clip?.canvasFor(preset) ?: preset
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
        if (overlay == null) return@produceState
        delay(EXACT_FRAME_SETTLE_MS)
        value = withContext(Dispatchers.Default) {
            renderPreviewFrame(
                context, records, preset, clip, overlay, colours, photos, title, at, placement,
                exact = true
            )
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

            val tapModifier = if (onTapRegion == null || records.isEmpty()) Modifier else {
                Modifier.pointerInput(records, preset, placement, title) {
                    // Underneath the framing overlay, so a drag of the frame never reads as a tap.
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
                                preset, placement.toRectF(), x, y
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
            durationMs = playableMs(records, clip, preset),
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
    records: List<BpmRecordWithPoints>,
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

    // A landscape video turns the screen with it. Held upright, a 16:9 preview used a quarter of
    // the screen with black above and below, which is no better than the inline one (UX Audit E1).
    // Rotating recreates the activity, so the orientation to go back to is kept across that, and
    // only restored when the preview is closed, not when the rotation itself tears this down.
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
                    durationMs = playableMs(records, clip, preset),
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

/** A titled row of chips choosing one of [options], with a line saying what the choice does. */
@Composable
private fun <T> ChoiceRow(
    title: String,
    hint: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) }
            )
        }
    }
    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
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
        graphRect = placement.toRectF()
    )
    return bitmap
}

/** The editor's tabs, in the order they're offered (S14B.1). */
enum class LookTab(val label: String) {
    // Only where there are presets to choose: the export flow. Settings is where they are managed.
    PRESETS("Presets"),
    LAYOUT("Layout"),
    GRAPH("Graph"),
    READOUTS("Readouts"),
    TEXT("Text & color"),
    TIME("Time"),
    SIGNATURE("Signature");

    companion object {
        /** The tabs a still offers: it has no live readouts and no playhead. */
        val FOR_IMAGES = listOf(LAYOUT, GRAPH, TEXT, SIGNATURE)
    }
}

/**
 * Who a change to the graph's frame applies to (S14B.3). The frame used to be written to the clip
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
 * above, and each tab's controls get the rest of it, rather than a quarter of the screen between
 * the preview and the step bar (UX Audit E1). The tab is the caller's, so tapping something in
 * the preview can open its settings (S14B.2).
 *
 * Takes a bounded height: the tab row stays put and the tab's contents scroll under it.
 */
@Composable
fun LookSections(
    preset: ExportPreset,
    onChange: (ExportPreset) -> Unit,
    tab: LookTab,
    onTab: (LookTab) -> Unit,
    framing: GraphPlacement,
    onFramingChange: (GraphPlacement) -> Unit,
    modifier: Modifier = Modifier,
    isImage: Boolean = false,
    /** False where the graph frame cannot be dragged, so the hint offers the chips instead. */
    hasPreview: Boolean = true,
    /**
     * The heading drawn on the export, and a way to change it. Null where there is nothing to name:
     * the Settings editor previews a made-up subject.
     */
    title: String? = null,
    onTitleChange: ((String) -> Unit)? = null,
    /** Who a frame change applies to, at the top of Layout. Null where there's no clip. */
    framingScope: (@Composable () -> Unit)? = null,
    /** When this clip was filmed, and the nudge, at the top of Time. Null where there's no clip. */
    clipTime: (@Composable () -> Unit)? = null,
    /** Lays curves over a video picked by hand. Null where nothing can be picked. */
    onPickVideo: (() -> Unit)? = null,
    /** The saved looks, as a tab of their own. Null where they are managed elsewhere. */
    presets: (@Composable () -> Unit)? = null
) {
    val base = if (isImage) LookTab.FOR_IMAGES else LookTab.entries.filter { it != LookTab.PRESETS }
    val tabs = if (presets != null) listOf(LookTab.PRESETS) + base else base
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
        Column(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(16.dp)
        ) {
            when (shown) {
                LookTab.PRESETS -> presets?.invoke()
                LookTab.LAYOUT -> LayoutTab(preset, onChange, framing, onFramingChange, isImage, hasPreview, framingScope, onPickVideo)
                LookTab.GRAPH -> GraphTab(preset, onChange, isImage)
                LookTab.READOUTS -> ReadoutsTab(preset, onChange)
                LookTab.TEXT -> TextTab(preset, onChange, title, onTitleChange)
                LookTab.TIME -> TimeTab(preset, onChange, clipTime)
                LookTab.SIGNATURE -> SignatureTab(preset, onChange)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String, hint: String? = null) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    hint?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun LayoutTab(
    preset: ExportPreset,
    onChange: (ExportPreset) -> Unit,
    framing: GraphPlacement,
    onFramingChange: (GraphPlacement) -> Unit,
    isImage: Boolean,
    hasPreview: Boolean,
    framingScope: (@Composable () -> Unit)?,
    onPickVideo: (() -> Unit)?
) {
    SectionLabel("Canvas", "The shape and size of the finished " + if (isImage) "picture." else "video.")
    val sizes = listOf(
        "16:9" to (1920 to 1080),
        "9:16" to (1080 to 1920),
        "1:1" to (1080 to 1080),
        "4:5" to (1080 to 1350)
    )
    val matching = preset.matchClip && !isImage
    val named = sizes.any { it.second == (preset.width to preset.height) }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // A still has no clip to match.
        if (!isImage) {
            FilterChip(
                selected = matching,
                onClick = { onChange(preset.copy(matchClip = true)) },
                label = { Text("Match the clip") }
            )
        }
        sizes.forEach { (label, size) ->
            FilterChip(
                selected = !matching && preset.width == size.first && preset.height == size.second,
                // Placement is fractional, so switching aspect keeps the graph on canvas.
                onClick = { onChange(preset.copy(width = size.first, height = size.second, matchClip = false)) },
                label = { Text(label) }
            )
        }
        // A size no chip names still shows as chosen, rather than leaving nothing selected (E8).
        if (!matching && !named) {
            FilterChip(selected = true, onClick = {}, label = { Text("${preset.width}×${preset.height}") })
        }
    }
    Spacer(Modifier.height(8.dp))
    if (matching) {
        Text(
            "Each video takes its clip's shape, upright or sideways, at up to " +
                "${minOf(preset.width, preset.height)} px on the short side.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        SwitchRow("Lock aspect ratio", preset.lockAspectRatio) { onChange(preset.copy(lockAspectRatio = it)) }
        if (!isImage) {
            SwitchRow("Fill the frame", preset.fillCanvas) { onChange(preset.copy(fillCanvas = it)) }
            Text(
                if (preset.fillCanvas) "Footage of another shape is cropped to fill the video."
                else "Footage of another shape is shown whole, with bars at the sides or top.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // Placement is a video question. An image *is* the graph, so insetting it would leave a border
    // of nothing around the only thing in the frame.
    if (isImage) return

    Spacer(Modifier.height(16.dp))
    SectionLabel(
        "Where the graph sits",
        if (hasPreview) "Drag the outline on the preview, or start from one of these."
        else "Pick a starting arrangement. Fine-tune it against footage in the export."
    )
    framingScope?.invoke()
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
        // Centring by eye is guesswork that never quite lands.
        TextButton(onClick = { onFramingChange(framing.centredHorizontally()) }) { Text("Center across") }
        TextButton(onClick = { onFramingChange(framing.centredVertically()) }) { Text("Center down") }
    }
    // Absolute, not a nudge: the value *is* how wide the panel is, so the slider always reads where
    // the panel actually is.
    SliderRow(label = "Panel width", value = framing.width, onValue = { onFramingChange(framing.scaledTo(it)) })
    Text(
        "Keeps the shape and the position. Get the framing right once, then make it as large or " +
            "small as you want.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Frames pinned for a number of people were saved with the look and had no list and no way to
    // be cleared (UX Audit E2).
    if (preset.framesByLaneCount.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        SectionLabel("Frames for a number of people", "These win over the look's own frame for clips with that many people.")
        preset.framesByLaneCount.keys.sorted().forEach { count ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (count == 1) "1 person" else "$count people",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onChange(preset.copy(framesByLaneCount = preset.framesByLaneCount - count)) }) {
                    Text("Clear")
                }
            }
        }
    }

    onPickVideo?.let { pick ->
        Spacer(Modifier.height(12.dp))
        Text(
            "No clips: the curves are drawn on a plain background.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = pick) { Text("Choose a video") }
    }
}

@Composable
private fun GraphTab(preset: ExportPreset, onChange: (ExportPreset) -> Unit, isImage: Boolean) {
    // Named for what they draw: "Labels" was the axis numbers, and read as names (UX Audit E6).
    SwitchRow("Heart rate numbers", preset.showLabels) { onChange(preset.copy(showLabels = it)) }
    SwitchRow("Gridlines", preset.showGrid) { onChange(preset.copy(showGrid = it)) }
    if (isImage) {
        // On a still, the same field is everyone's summary for the whole span.
        SwitchRow("Summary", preset.showCurrentStats) { onChange(preset.copy(showCurrentStats = it)) }
    }
    Spacer(Modifier.height(8.dp))

    // Opacity of the panel the graph sits on, which is a property of the graph.
    SliderRow(
        label = "Panel opacity",
        value = preset.backgroundOpacity / 100f,
        onValue = { onChange(preset.copy(backgroundOpacity = (it * 100).toInt())) }
    )
    if (isImage) {
        Text(
            "At 0% the saved PNG is transparent behind the curve, ready to lay over footage in " +
                "another app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    // It fades what *hasn't happened yet*, which "trailing" said backwards (UX Audit E6).
    SliderRow(
        label = "Fade what's ahead",
        value = preset.futureOpacity,
        onValue = { onChange(preset.copy(futureOpacity = it)) }
    )
    Spacer(Modifier.height(8.dp))
    ChoiceRow(
        title = "Scale",
        hint = when (preset.bpmScale) {
            BpmScale.ON_SCREEN -> "Fits the heart rates in view, so the curves have room to move."
            BpmScale.RECORDING -> "The same scale throughout, for comparing levels across a set."
        },
        options = BpmScale.entries,
        selected = preset.bpmScale,
        label = { it.label },
        onSelect = { onChange(preset.copy(bpmScale = it)) }
    )
    ChoiceRow(
        title = "Contrast",
        hint = when (preset.contrast) {
            Contrast.AUTO -> "A soft shadow behind the curves and text when the panel is under " +
                "${Contrast.AUTO_PANEL_THRESHOLD}%, so they hold up over bright footage."
            Contrast.OFF -> "Nothing behind the curves and text but the panel."
            Contrast.SHADOW -> "A soft shadow behind the curves and text."
            Contrast.OUTLINE -> "A dark edge round the curves and text. Holds up over anything."
        },
        options = Contrast.entries,
        selected = preset.contrast,
        label = { it.label },
        onSelect = { onChange(preset.copy(contrast = it)) }
    )
}

@Composable
private fun ReadoutsTab(preset: ExportPreset, onChange: (ExportPreset) -> Unit) {
    // The switch lives with what it switches. It sat in Graph, turning off this whole section while
    // the section stayed fully editable (UX Audit E6).
    SwitchRow("Show readouts", preset.showCurrentStats) { onChange(preset.copy(showCurrentStats = it)) }
    Text(
        if (preset.showCurrentStats) "A live pill for each person over the footage. Two people on a " +
            "story can carry faces and names; six on a landscape shot read better as numbers."
        else "Off: each person's name is listed in the corner instead.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (!preset.showCurrentStats) return
    Spacer(Modifier.height(8.dp))

    SwitchRow("Photo", preset.pillShowPhoto) { onChange(preset.copy(pillShowPhoto = it)) }
    SwitchRow("Name", preset.pillShowName) { onChange(preset.copy(pillShowName = it)) }
    SwitchRow("Reading in their color", preset.pillBpmInPersonColor) {
        onChange(preset.copy(pillBpmInPersonColor = it))
    }

    Spacer(Modifier.height(8.dp))
    // The clock is in the header whichever side this is; the hint used to say it took the other side.
    SectionLabel("Side", "Right sits over the faded part of the graph, what hasn't happened yet.")
    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PillCorner.entries.forEach { corner ->
            FilterChip(
                selected = preset.pillCorner == corner,
                onClick = { onChange(preset.copy(pillCorner = corner)) },
                label = { Text(corner.label) }
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    // Multipliers on what the renderer works out, so no combination can produce pills that don't fit.
    ScaleRow("Pill size", preset.pillScale) { onChange(preset.copy(pillScale = it)) }
    if (preset.pillShowPhoto) {
        ScaleRow("Photo size", preset.pillPhotoScale) { onChange(preset.copy(pillPhotoScale = it)) }
    }
    ScaleRow("Reading size", preset.pillBpmScale) { onChange(preset.copy(pillBpmScale = it)) }
    if (preset.pillShowName) {
        ScaleRow("Name size", preset.pillNameScale) { onChange(preset.copy(pillNameScale = it)) }
    }
}

@Composable
private fun TextTab(
    preset: ExportPreset,
    onChange: (ExportPreset) -> Unit,
    title: String?,
    onTitleChange: ((String) -> Unit)?
) {
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
        Text(
            "Leave blank to use the name of what's being exported. Every video in this batch " +
                "gets this title.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(Modifier.height(12.dp))
    ScaleRow("Text size", preset.textScale, range = 0.75f..2f) { onChange(preset.copy(textScale = it)) }
    Text(
        "The title, clock and axis numbers. Never smaller than what reads on a phone.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

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
        onPick = { onChange(preset.copy(gridColor = it)) }
    )
    Text(
        "One person's curve shades from the first color to the second as their heart rate climbs. " +
            "With several people, each is drawn in their own color.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    ColourRow(
        "Low",
        preset.lowBpmColor,
        listOf(shipped.lowBpmColor) + inga.bpmetrics.library.PersonColors.PALETTE,
        onPick = { onChange(preset.copy(lowBpmColor = it)) }
    )
    ColourRow(
        "High",
        preset.highBpmColor,
        listOf(shipped.highBpmColor) + inga.bpmetrics.library.PersonColors.PALETTE,
        onPick = { onChange(preset.copy(highBpmColor = it)) }
    )
}

/** A row of swatches choosing one colour, the first being the app's own. */
@Composable
private fun ColourRow(label: String, value: Int, options: List<Int>, onPick: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.distinct().forEachIndexed { index, colour ->
            val selected = colour == value
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    // A checkerboard-free stand-in for transparency: faint colours are shown over
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
    }
}

@Composable
private fun TimeTab(
    preset: ExportPreset,
    onChange: (ExportPreset) -> Unit,
    clipTime: (@Composable () -> Unit)?
) {
    // When the clip was filmed and the nudge, together, per clip (UX Audit E7).
    clipTime?.let {
        it()
        Spacer(Modifier.height(16.dp))
    }

    NumberField(
        value = (preset.windowSizeMs / 1000).toInt(),
        label = "Visible window (seconds)",
        range = WINDOW_RANGE,
        modifier = Modifier.fillMaxWidth(),
        onValue = { onChange(preset.copy(windowSizeMs = it * 1000L)) }
    )
    Text(
        // The playhead is centred, so half of it is what you can see coming.
        "The playhead sits in the middle, so this shows ${(preset.windowSizeMs / 2000).toInt()} " +
            "seconds of what's coming.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(12.dp))
    SectionLabel("Clock", "In the header beside the title, where it costs no room on the graph.")
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
    SectionLabel("Frame rate", "Match source keeps each clip at the rate it was filmed at.")
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
        listOf(24, 25, 30, 50, 60).forEach { rate ->
            FilterChip(
                selected = !preset.matchSourceFrameRate && preset.frameRate == rate,
                onClick = { onChange(preset.copy(frameRate = rate, matchSourceFrameRate = false)) },
                label = { Text("$rate") }
            )
        }
    }

    // The look-wide offset that clip-by-clip timing replaced. Not offered any more, but a preset
    // saved with one still applies it, so it is shown, with a way out, rather than acting unseen.
    if (preset.syncOffsetMs != 0L) {
        Spacer(Modifier.height(16.dp))
        SectionLabel(
            "Older sync offset",
            "This look also moves every clip by ${"%+.1f".format(preset.syncOffsetMs / 1000f)} s, from " +
                "an earlier version. Clips now line up on their own; nudge one under its time if it's off."
        )
        TextButton(onClick = { onChange(preset.copy(syncOffsetMs = 0L)) }) { Text("Clear it") }
    }
}

@Composable
private fun SignatureTab(preset: ExportPreset, onChange: (ExportPreset) -> Unit) {
    SwitchRow("Show wordmark", preset.showWordmark) { onChange(preset.copy(showWordmark = it)) }
    Text(
        "A small credit linking the export back to the app.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (!preset.showWordmark) return
    Spacer(Modifier.height(8.dp))
    SectionLabel("Corner")
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

/** The canvas's other side, when one side is typed with the aspect ratio locked. */
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
}

/** Seconds of graph across the frame. */
private val WINDOW_RANGE = 1..600

/**
 * Which clip step 3 is previewing.
 *
 * Each clip is its own shot with its own framing, so each gets its own graph placement and its own
 * scrub position. A strip rather than a dropdown because the thumbnails are what makes one clip
 * recognisable from another.
 */
@Composable
fun ClipSelectorStrip(
    /** The clock each clip's time is shown in — see [ClipRetiming.clockFor]. */
    clockFor: (inga.bpmetrics.export.VideoExporter.VideoClip) -> java.time.ZoneId,
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
                        selection.clip.startedAtMs,
                        clockFor(selection.clip)
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
