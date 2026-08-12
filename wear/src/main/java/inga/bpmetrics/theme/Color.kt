package inga.bpmetrics.theme

import androidx.compose.ui.graphics.Color
import inga.bpmetrics.core.BpmPalette

/**
 * The watch's colours, derived from the same definition the phone uses.
 *
 * This file used to hold its own copy of the heart rate ramp — `BpmAvg = 0xFF4CAF50`, a green
 * middle the phone deliberately moved away from because it fought the teal and read as "good"
 * rather than "middling", plus a different red and a different blue. Nothing drew with them, so
 * nothing was visibly wrong; they were simply the wrong answer sitting where the next person to
 * colour a heart rate on the wrist would have reached for it.
 *
 * They come from [BpmPalette] now, in `:core`, which both modules already depend on. That is the
 * only version of this fix that cannot drift back: there is nowhere for a second copy to live.
 */

/** The brand teal. The same seed the phone's whole scheme is built from. */
val BpmAccent = Color(BpmPalette.TEAL)

// The ramp. Identical to the phone's, because a heart rate of 170 is the same heart rate on both.
val BpmLow = Color(BpmPalette.LOW)
val BpmAvg = Color(BpmPalette.AVG)
val BpmHigh = Color(BpmPalette.HIGH)

/**
 * The heart glyph.
 *
 * The ramp's hot end rather than the pink it used to be: the icon sits beside a live heart rate, so
 * a colour that means "high" everywhere else in the app should not mean something different here.
 */
val HeartRed = Color(BpmPalette.HIGH)

/**
 * Recording, right now.
 *
 * A single unmistakable red, and the one colour on the watch that is allowed to shout. It was
 * `Color(0xFFD32F2F)` typed inline at the button — a Material palette red that belonged to no
 * scheme in this app.
 */
val RecordingRed = Color(0xFFE5484D)

// --- Surfaces, tinted the same faint teal as the phone's so the two feel like one product ---

val WearSurface = Color(BpmPalette.SURFACE)
val WearOnSurface = Color(BpmPalette.ON_SURFACE)
val WearSurfaceVariant = Color(0xFF3F4945)
val WearOnSurfaceVariant = Color(0xFFBEC9C4)
val WearOnAccent = Color(0xFF00382F)
val WearError = Color(0xFFFFB4AB)
val WearOnError = Color(0xFF690005)
