package inga.bpmetrics.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.Cover

/**
 * Opens the gallery and hands back what was chosen.
 *
 * `PickVisualMedia` rather than `OpenDocument`: the photo picker needs no storage permission at
 * all, and the app takes no persistable grant because nothing is kept pointing at the original —
 * `CoverStore` copies it and the URI is dropped the moment it has.
 */
@Composable
fun rememberCoverPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(onPicked) }

    return {
        launcher.launch(
            androidx.activity.result.PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageOnly
            )
        )
    }
}

/**
 * Frames a cover, against a real tile at real size.
 *
 * Deliberately not an abstract crop box. A cover is judged by whether the library reads well with
 * it, and a square preview cannot answer that — the same photo that looks fine in a crop dialog can
 * put a face directly behind the title. So what is being dragged is shown as the thing it will
 * become.
 *
 * The crop is stored as fractions of the source image for the same reason the export stores graph
 * placement that way: a tile is wide, a thumbnail is square and a header is wider still, and
 * fractions are what survives all three.
 */
@Composable
fun CoverCropDialog(
    cover: Cover,
    /** Drawn inside the preview, so the crop is judged against the words that will sit on it. */
    previewContent: @Composable () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Cover) -> Unit,
    onRemove: (() -> Unit)? = null,
    /**
     * The shape the result will actually be drawn in.
     *
     * A person's photograph ends up in a small circle, and framing it against a wide rectangle
     * would be framing something they will never see — the whole reason this previews a real tile
     * rather than an abstract box.
     */
    shape: CoverCropShape = CoverCropShape.TILE,
    title: String = "Frame the cover",
    hint: String = "Drag to move the picture, pinch to zoom."
) {
    // The shape this will be drawn in, which is what the crop has to match.
    val targetAspect = if (shape == CoverCropShape.CIRCLE) 1f else TILE_ASPECT

    // The photograph's own proportions, needed to open on a window that fits the target rather than
    // on the whole image. Null until it has loaded.
    val imageSize = rememberCoverSize(cover.path)
    val imageAspect = imageSize
        ?.takeIf { it.height > 0 }
        ?.let { it.width.toFloat() / it.height.toFloat() }

    // A crop of the whole image is what an imported photograph starts with, and it is not a
    // *frame* — drawn into a wide tile or a circle, centre-fill immediately cuts off whatever does
    // not fit, and because the window already covers everything there is nowhere left to pan to.
    // That is why dragging did nothing at all: the crop was pinned at its own limits from the
    // moment it was created.
    //
    // So the window opens matched to the target shape, centred. Now it can be moved, and there is
    // somewhere for it to move to.
    var crop by remember(cover.path, imageAspect) {
        mutableStateOf(
            if (cover.isWholeImage && imageAspect != null) {
                cover.fittedTo(targetAspect, imageAspect)
            } else {
                cover
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = if (shape == CoverCropShape.CIRCLE) {
                    Alignment.CenterHorizontally
                } else {
                    Alignment.Start
                }
            ) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(BpmSpacing.Medium))

                // The proportions and shape of the thing being made, so what is framed here is what
                // will be seen. A wide preview of something destined for a small circle would put
                // the face nowhere near where it ends up.
                Box(
                    modifier = Modifier
                        .then(
                            if (shape == CoverCropShape.CIRCLE) {
                                Modifier.size(200.dp).clip(CircleShape)
                            } else {
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(TILE_ASPECT)
                                    .clip(MaterialTheme.shapes.medium)
                            }
                        )
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        // Pinch and drag, which is what anyone reaches for on a photograph. The
                        // corner-handle model this replaces asked people to find a 28dp target they
                        // could not see, and did nothing at all anywhere else on the image.
                        .pointerInput(cover.path, imageAspect) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                crop = crop.transformed(
                                    zoom = zoom,
                                    // Dragging the picture right shows what was off to its left, so
                                    // the window moves opposite to the finger.
                                    panX = -pan.x / size.width.toFloat(),
                                    panY = -pan.y / size.height.toFloat(),
                                    aspect = targetAspect,
                                    imageAspect = imageAspect
                                )
                            }
                        }
                ) {
                    CoverBackground(
                        cover = crop,
                        modifier = Modifier.fillMaxSize(),
                        scrim = if (shape == CoverCropShape.CIRCLE) {
                            CoverScrim.NONE
                        } else {
                            CoverScrim.TILE
                        }
                    ) {
                        Box(Modifier.fillMaxSize().padding(BpmSpacing.Medium)) { previewContent() }
                    }
                }

                Spacer(Modifier.height(BpmSpacing.Small))
                Text(
                    "Showing ${(crop.cropWidth * 100).toInt()}% of the width " +
                        "and ${(crop.cropHeight * 100).toInt()}% of the height.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Only where writing will sit on it. A person's photograph is drawn in a circle
                // with nothing over it, so softening it would be softening it for no reason.
                if (shape != CoverCropShape.CIRCLE) {
                    Spacer(Modifier.height(BpmSpacing.Medium))
                    Text(
                        "Soften",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Text(
                        // The case this exists for, named — because it is not obvious that a
                        // setting called "soften" is the answer to "my cover is a poster".
                        "For a cover that is itself made of type — an event flyer, a poster. " +
                            "Blurring keeps its colour and lets the writing above it be read.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(BpmSpacing.Tiny))
                    androidx.compose.material3.Slider(
                        value = crop.blur,
                        onValueChange = { crop = crop.copy(blur = it) },
                        // Continuous. It was stepped because each strength used to be a separately
                        // decoded bitmap and a free-running slider would have rebuilt one per pixel
                        // of travel. The blur is a render effect now, so there is nothing to
                        // rebuild and nothing to snap to — and finding the point where a flyer's
                        // type dissolves but its artwork survives is exactly the kind of judgement
                        // that wants a smooth control.
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(BpmSpacing.Small))
                Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.Small)) {
                    TextButton(
                        onClick = {
                            // As much of the picture as this shape can hold, not literally all of
                            // it — a window wider than the target gets centre-cropped on the way
                            // out anyway, so "whole picture" would show something the library then
                            // trims.
                            crop = imageAspect
                                ?.let { crop.fittedTo(targetAspect, it) }
                                ?: crop.copy(
                                    cropLeft = 0f,
                                    cropTop = 0f,
                                    cropRight = 1f,
                                    cropBottom = 1f
                                )
                        }
                    ) {
                        Text("Fit picture")
                    }
                    onRemove?.let {
                        TextButton(
                            onClick = it,
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("Remove cover") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(crop) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** What the framed image will be drawn as, so the preview can be that. */
enum class CoverCropShape { TILE, CIRCLE }

/** A library tile is roughly three times as wide as it is tall. */
private const val TILE_ASPECT = 2.9f

/** The smallest crop worth having. Below this the cover is a few pixels stretched across a tile. */
internal const val MIN_CROP = 0.08f

/** Whether this crop is the untouched whole image, which is what an import starts as. */
internal val Cover.isWholeImage: Boolean
    get() = cropLeft <= 0.001f && cropTop <= 0.001f &&
        cropRight >= 0.999f && cropBottom >= 0.999f

/**
 * The largest window of this image with [targetAspect], centred.
 *
 * Both aspects are needed because the crop is in fractions of the source: a window that is
 * *fractionally* square is only actually square on a square photograph. [imageAspect] converts
 * between the two.
 */
internal fun Cover.fittedTo(targetAspect: Float, imageAspect: Float): Cover {
    // How wide the window must be, in fractions, to have targetAspect on this image.
    var width = 1f
    var height = imageAspect / targetAspect
    if (height > 1f) {
        // Too tall to fit: bound by height instead and narrow the window.
        width = targetAspect / imageAspect
        height = 1f
    }
    val left = (1f - width) / 2f
    val top = (1f - height) / 2f
    return copy(
        cropLeft = left,
        cropTop = top,
        cropRight = left + width,
        cropBottom = top + height
    )
}

/**
 * This crop after a pinch of [zoom] and a drag of [panX], [panY] in fractions of the view.
 *
 * Zoom is about the centre of the window rather than the pinch centroid. Centroid-anchored zoom is
 * nicer on a large canvas, but this preview is a tile a couple of centimetres tall — two fingers
 * cover most of it, and anchoring to where they happen to be makes the picture lurch.
 *
 * The window keeps the target's shape throughout. Letting a pinch change its proportions would let
 * someone frame something that cannot be drawn: the renderer centre-fills, so the parts of a
 * mismatched window outside the target shape are cut off again on the way out — the crop would show
 * one thing here and another in the library.
 */
internal fun Cover.transformed(
    zoom: Float,
    panX: Float,
    panY: Float,
    aspect: Float,
    imageAspect: Float?
): Cover {
    val centreX = (cropLeft + cropRight) / 2f
    val centreY = (cropTop + cropBottom) / 2f

    // The widest this window may be and still fit inside the image at the target shape.
    val fullest = imageAspect?.let { copy().fittedTo(aspect, it) }
    val maxWidth = fullest?.cropWidth ?: 1f
    val maxHeight = fullest?.cropHeight ?: 1f

    // A zoom above 1 means fingers moving apart, which shows less of the picture.
    val scale = if (zoom > 0f) 1f / zoom else 1f
    var width = (cropWidth * scale).coerceIn(MIN_CROP * maxWidth, maxWidth)
    var height = width / maxWidth * maxHeight

    // Guard the degenerate case where the image failed to load and there is no aspect to hold to.
    if (!width.isFinite() || !height.isFinite()) {
        width = cropWidth
        height = cropHeight
    }

    val left = (centreX - width / 2f + panX * width).coerceIn(0f, 1f - width)
    val top = (centreY - height / 2f + panY * height).coerceIn(0f, 1f - height)

    return copy(
        cropLeft = left,
        cropTop = top,
        cropRight = left + width,
        cropBottom = top + height
    )
}
