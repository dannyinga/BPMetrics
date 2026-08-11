package inga.bpmetrics.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import inga.bpmetrics.library.Cover
import inga.bpmetrics.library.CoverStore
import inga.bpmetrics.ui.theme.BpmPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decoded covers, kept so a scrolling list does not read the same file off disk on every frame.
 *
 * Small on purpose. Covers are already capped at 512px on the long edge by [CoverStore], so a
 * handful of them is a few megabytes — and a library has far fewer distinct covers than it has
 * recordings, which is the entire point of putting the picture on the event.
 */
private object CoverCache {
    private val cache = object : LruCache<String, ImageBitmap>(16) {}

    suspend fun load(context: Context, name: String): ImageBitmap? {
        cache.get(name)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = CoverStore.fileFor(context, name)
            if (!file.isFile) return@withContext null
            runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
                .getOrNull()
                ?.also { cache.put(name, it) }
        }
    }

    fun forget(name: String) {
        cache.remove(name)
    }

    fun forgetAll() {
        cache.evictAll()
    }
}

/**
 * The strongest blur the slider can ask for.
 *
 * Enough, at the top, to reduce a page of type to bands of colour — which is the whole point for a
 * flyer. Any further and every cover looks the same, which is the failure a cover exists to fix.
 */
private val MAX_BLUR = 26.dp

/** Drops a cover from the cache, so replacing one is visible immediately rather than next launch. */
fun invalidateCover(name: String?) {
    name?.let { CoverCache.forget(it) }
}

/** Drops every cached cover. For clearing them all from the storage section. */
fun invalidateAllCovers() = CoverCache.forgetAll()

/**
 * The pixel size of a stored image, once it has loaded.
 *
 * The crop UI needs it. A crop is fractions of the *source*, and the shape someone is framing it
 * into is almost never the shape of the photograph — so to open on a sensible window, rather than
 * on the whole image with the top and bottom about to be cut off by centre-fill, the dialog has to
 * know how the picture is proportioned.
 */
@Composable
fun rememberCoverSize(path: String?): androidx.compose.ui.unit.IntSize? {
    val context = LocalContext.current
    val size by produceState<androidx.compose.ui.unit.IntSize?>(initialValue = null, path) {
        value = path?.let { name ->
            CoverCache.load(context, name)?.let {
                androidx.compose.ui.unit.IntSize(it.width, it.height)
            }
        }
    }
    return size
}

/**
 * The shadow text wears when it sits on a photograph.
 *
 * This is the honest answer to "the writing is hard to read", and it is a better one than more
 * scrim. A scrim dims the *whole tile* to protect the fraction of it that has words on — which is
 * how every cover ends up looking like the same grey rectangle, the exact failure the picture was
 * meant to fix. A shadow is per-glyph: it darkens the two pixels around each letter and leaves the
 * rest of the photograph at full strength.
 *
 * Offset barely at all, blurred generously. What is wanted is a halo, not a drop shadow — the point
 * is a dark edge on every side of the letterform, so it holds against a bright sky above and a dark
 * jacket below, which a directional shadow does not.
 */
val CoverTextShadow: Shadow = Shadow(
    color = Color(BpmPalette.SURFACE).copy(alpha = 0.95f),
    offset = androidx.compose.ui.geometry.Offset(0f, 1f),
    blurRadius = 7f
)

/**
 * This style, protected if it is going to be drawn over a picture.
 *
 * A no-op when there is no cover, so a call site can ask for it unconditionally rather than
 * branching — and so a tile with no picture keeps the flat, clean text it has always had.
 *
 * A halo and nothing more. A stroked outline was tried and reverted: at small sizes it thickens
 * every letter into a smudge, and on a headline it reads as a cartoon. The legibility problem it was
 * meant to fix belongs to the *picture*, and is solved on the picture — see [Cover.dim].
 */
fun TextStyle.overCover(hasCover: Boolean): TextStyle =
    if (hasCover) copy(shadow = CoverTextShadow) else this

/**
 * A cover at thumbnail size, or a placeholder where there is none.
 *
 * For the pickers — a filter narrowing to an event, an export choosing its source. A list of names
 * makes you read every row; the picture is how you find the right "Day 1" out of four without
 * reading any of them.
 *
 * Deliberately the owner's **own** cover rather than the inherited one. In a nested list the
 * inherited answer paints the festival's picture on all six of its sets, which says they are the
 * same thing when the list exists to tell them apart.
 */
@Composable
fun CoverThumbnail(
    cover: Cover?,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 34.dp,
    /** Drawn when there is no picture. A kind of thing, so the gap still says what the row is. */
    placeholder: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val shape = androidx.compose.material3.MaterialTheme.shapes.small
    if (cover == null) {
        Box(
            modifier
                .size(size)
                .clip(shape)
                .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            placeholder?.let {
                androidx.compose.material3.Icon(
                    it,
                    contentDescription = null,
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(size / 2)
                )
            }
        }
    } else {
        CoverBackground(
            cover = cover,
            modifier = modifier.size(size).clip(shape),
            scrim = CoverScrim.NONE
        ) {}
    }
}

/**
 * How hard the scrim works.
 *
 * The problem this exists for: a library tile carries a title, a time, a duration, a person, three
 * readings and tags. Over a bright photo none of that is readable, and the usual answer — a flat
 * dark sheet — makes every cover look like the same grey rectangle, which defeats having one.
 *
 * So the scrim is a gradient anchored where the writing is rather than over the whole tile, and how
 * strong it needs to be depends on how much writing there is.
 */
enum class CoverScrim {
    /** A dense list row: text across most of the tile, so most of it needs covering. */
    TILE,

    /** A header, where the writing is a title at the bottom and the top can stay clear. */
    HEADER,

    /** No text over it at all — a crop preview, a picker thumbnail. */
    NONE
}

/**
 * A cover drawn to fill its box, cropped as it was framed, with [content] over it.
 *
 * The crop is applied against the source image and then centre-fitted to whatever shape this box
 * happens to be. That second step is what stops the same cover looking stretched in a wide tile and
 * squashed in a square thumbnail — the crop says *which part of the photo*, and the box says how
 * much of that part fits.
 *
 * Falls back to drawing nothing but [content] when the file is missing, which is the state a
 * restored backup arrives in: the rows come back, the files do not. A tile with no picture is a
 * tile; a tile with a hole in it is a bug report.
 */
@Composable
fun CoverBackground(
    cover: Cover?,
    modifier: Modifier = Modifier,
    scrim: CoverScrim = CoverScrim.TILE,
    content: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, cover?.path) {
        value = cover?.path?.let { CoverCache.load(context, it) }
    }

    Box(modifier) {
        val bitmap = image
        if (cover != null && bitmap != null) {
            // matchParentSize, not fillMaxSize. A tile lives in a LazyColumn item, where the height
            // constraint is unbounded — `fillMaxHeight` does nothing against an infinite maximum, so
            // the canvas measured to zero and the cover drew nothing at all while appearing, by
            // every other measure, to have been set correctly.
            //
            // matchParentSize sits out the size negotiation and takes the Box's final size, which
            // is what a background layer actually wants.
            // A real Gaussian, via RenderEffect, rather than the shrink-and-upscale this used to
            // do. That was a box blur got for free from the scaler, and at the strengths a flyer
            // needs it stopped looking blurred and started looking pixellated — because that is
            // exactly what it was, a 12-pixel-wide image stretched over a tile.
            //
            // `BlurredEdgeTreatment.Rectangle` clamps at the boundary instead of sampling the
            // nothing outside it; unbounded leaves a transparent fade all the way round, which on
            // a tile reads as a vignette nobody asked for.
            //
            // Per frame rather than cached, which is the cost of doing it properly. Cheap here:
            // this is hardware, a handful of tiles are on screen at once, and the covers behind
            // them are capped at 512px.
            val blurRadius = (cover.blur.coerceIn(0f, 1f) * MAX_BLUR.value).dp
            Canvas(
                Modifier
                    .matchParentSize()
                    .then(
                        if (blurRadius > 0.5.dp) {
                            Modifier.blur(blurRadius, BlurredEdgeTreatment.Rectangle)
                        } else {
                            Modifier
                        }
                    )
            ) {
                val srcLeft = (cover.cropLeft * bitmap.width)
                val srcTop = (cover.cropTop * bitmap.height)
                val srcWidth = (cover.cropWidth * bitmap.width).coerceAtLeast(1f)
                val srcHeight = (cover.cropHeight * bitmap.height).coerceAtLeast(1f)

                // Centre-fill: take the largest piece of the cropped region that has this box's
                // shape. Drawing the crop straight into the box would stretch it, and a stretched
                // face is the one distortion everybody notices.
                val boxAspect = size.width / size.height.coerceAtLeast(1f)
                val srcAspect = srcWidth / srcHeight

                var fitWidth = srcWidth
                var fitHeight = srcHeight
                if (srcAspect > boxAspect) {
                    fitWidth = srcHeight * boxAspect
                } else {
                    fitHeight = srcWidth / boxAspect.coerceAtLeast(0.0001f)
                }

                val fitLeft = srcLeft + (srcWidth - fitWidth) / 2f
                val fitTop = srcTop + (srcHeight - fitHeight) / 2f

                drawImage(
                    image = bitmap,
                    srcOffset = IntOffset(
                        fitLeft.toInt().coerceIn(0, bitmap.width - 1),
                        fitTop.toInt().coerceIn(0, bitmap.height - 1)
                    ),
                    srcSize = IntSize(
                        fitWidth.toInt().coerceIn(1, bitmap.width),
                        fitHeight.toInt().coerceIn(1, bitmap.height)
                    ),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )

                // Darkening before the scrim, and flat rather than as a gradient: this is a
                // correction to the *photograph* — it is too bright — not a device for protecting
                // one corner of it. The scrim on top still does its own gradient job.
                if (cover.dim > 0.005f) {
                    drawRect(color = Color.Black.copy(alpha = cover.dim.coerceIn(0f, 0.85f)))
                }

                drawScrim(scrim)
            }
        }
        content()
    }
}

/**
 * The gradient that makes text legible without flattening the photo.
 *
 * Two gradients rather than one sheet. The horizontal pass carries the weight where the writing is
 * — the left of a tile, where the title, the person and the readings all sit — and releases toward
 * the right, so a photo keeps a band of itself at full brightness. The vertical pass is a floor,
 * strong enough that a white sky behind the top line does not swallow it.
 *
 * A flat overlay strong enough for the worst case is strong enough to ruin the best one, and every
 * cover would end up the same colour. This is the compromise: the picture is dimmed where it is
 * being written on and left alone where it is not.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScrim(scrim: CoverScrim) {
    if (scrim == CoverScrim.NONE) return

    val surface = Color(BpmPalette.SURFACE)

    when (scrim) {
        CoverScrim.TILE -> {
            // Lighter than it once needed to be. The tile used to carry eleven pieces of writing
            // and the scrim had to be near-opaque across all of it, which made every cover the same
            // grey. With the tile down to an avatar, a title, one line of when, and the readings,
            // the right-hand third can be left almost clear — and that is the part of a photo
            // anyone actually sees.
            // Lighter again now that every piece of writing carries its own halo — see
            // [CoverTextShadow]. The scrim's job has changed: it no longer has to make the text
            // legible on its own, only to keep the tile from looking like a photograph with words
            // dropped on it. A third of the picture is now at nearly full strength.
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to surface.copy(alpha = 0.62f),
                    0.60f to surface.copy(alpha = 0.30f),
                    1f to surface.copy(alpha = 0.06f)
                )
            )
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to surface.copy(alpha = 0.22f)
                )
            )
        }

        CoverScrim.HEADER -> {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to surface.copy(alpha = 0.20f),
                    1f to surface.copy(alpha = 0.86f)
                )
            )
        }

        CoverScrim.NONE -> Unit
    }
}
