package inga.bpmetrics.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
    private val cache = object : LruCache<String, ImageBitmap>(12) {}

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
            Canvas(Modifier.matchParentSize()) {
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
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to surface.copy(alpha = 0.80f),
                    0.62f to surface.copy(alpha = 0.46f),
                    1f to surface.copy(alpha = 0.12f)
                )
            )
            // A floor under the readings, which sit low and left and are the one thing on the tile
            // that has to be readable over a bright sky.
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to surface.copy(alpha = 0.06f),
                    1f to surface.copy(alpha = 0.34f)
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
