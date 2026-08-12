package inga.bpmetrics.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * How round things are, decided once.
 *
 * The theme declared no shapes at all, so `MaterialTheme.shapes` was the Material baseline — which
 * is a perfectly good scale, and the problem was never the numbers. It was that half the app used
 * it and the other half typed a radius inline: `RoundedCornerShape(14.dp)` on one card,
 * `RoundedCornerShape(16.dp)` on the panel behind it, `4.dp` and `8.dp` on two thumbnails in the
 * same column. Nothing there was a decision; they were six people's guesses, and the queue screen
 * ended up visibly rounder than every other screen in the app.
 *
 * The scale below *is* the Material baseline, written down. Making it explicit is the point: a
 * default you cannot see is a default nobody can be asked to follow, and it gives the odd corners
 * somewhere to be converted to.
 *
 * Two things deliberately stay outside it:
 *
 * - **Circles.** A person's avatar, a legend dot, a colour swatch — `CircleShape`, always, because
 *   a circle is a different kind of thing from a rounded rectangle rather than the extreme of one.
 * - **Bars a few pixels tall.** The comparison bar and the zone strip clip at 3–5dp, which is not a
 *   corner style but the largest radius that still leaves a 6dp bar looking like a bar. Tying them
 *   to [Shapes.extraSmall] would round them into lozenges the moment that value changed.
 */
val BpmShapes = Shapes(
    /** Chips, thumbnails, the zone strip — anything small enough that 8dp would swallow it. */
    extraSmall = RoundedCornerShape(4.dp),

    /** The default for a tile in a list: a recording, a clip card, a source row. */
    small = RoundedCornerShape(8.dp),

    /** Cards that hold other things — an event with its contents, a step panel. */
    medium = RoundedCornerShape(12.dp),

    /** Dialogs, sheets, and the containers that sit above the page rather than in it. */
    large = RoundedCornerShape(16.dp),

    /** Reserved for full-bleed surfaces. Nothing in the app uses it yet; Material's default. */
    extraLarge = RoundedCornerShape(28.dp)
)
