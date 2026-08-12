package inga.bpmetrics.ui.theme

import inga.bpmetrics.core.BpmPalette
import inga.bpmetrics.core.BpmRamp

import androidx.compose.ui.graphics.Color

/**
 * The app's colour, and everything derived from it.
 *
 * `#00DCC2` is the chosen teal. It used to appear on one screen and the splash, while the theme
 * itself was still the purple from the Compose project template and dynamic colour was on by
 * default — so the app had three colour identities and showed none of them consistently.
 *
 * These are a Material 3 dark tonal palette seeded from that teal. Dark only: the charts, the
 * export panel and the whole metric ramp are designed against dark, and a half-supported light
 * theme is worse than none. See the UI and UX Cleanup document, §7.4.
 */

/** The seed. Kept as the brand mark and the splash background; not used raw as a surface. */
val BpmTeal = Color(BpmPalette.TEAL)

// --- Primary: the teal, at the tones Material wants for a dark scheme ---

val TealPrimary = Color(0xFF4EDFC6)
val TealOnPrimary = Color(0xFF00382F)
val TealPrimaryContainer = Color(0xFF005046)
val TealOnPrimaryContainer = Color(0xFF70FCE2)

/** Used on light surfaces the dark scheme still produces — snackbars, inverse cards. */
val TealInversePrimary = Color(0xFF006A5C)

// --- Secondary: the same hue with the chroma taken out, for supporting surfaces ---

val TealSecondary = Color(0xFFB0CCC5)
val TealOnSecondary = Color(0xFF1B3531)
val TealSecondaryContainer = Color(0xFF324B47)
val TealOnSecondaryContainer = Color(0xFFCCE8E1)

// --- Tertiary: a neighbouring blue, so an accent that is not the primary is still in family ---

val TealTertiary = Color(0xFFADCAE6)
val TealOnTertiary = Color(0xFF143349)
val TealTertiaryContainer = Color(0xFF2C4A61)
val TealOnTertiaryContainer = Color(0xFFCBE6FF)

// --- Neutrals, tinted very slightly teal so surfaces belong to the palette ---

// Surface and its ink come from the shared palette rather than being typed again here: the export
// renderers draw a panel meant to match the app's own, and two literals of the same colour is how
// the picture and the screen come to differ by a shade nobody can name.
val Surface = Color(BpmPalette.SURFACE)
val OnSurface = Color(BpmPalette.ON_SURFACE)
val SurfaceVariant = Color(0xFF3F4945)
val OnSurfaceVariant = Color(0xFFBEC9C4)
val SurfaceInverse = Color(0xFFDDE4E1)
val OnSurfaceInverse = Color(0xFF2B322F)
val Outline = Color(0xFF89938F)
val OutlineVariant = Color(0xFF3F4945)
val Scrim = Color(0xFF000000)

// --- Error, from the Material baseline dark scheme ---

val ErrorColor = Color(0xFFFFB4AB)
val OnError = Color(0xFF690005)
val ErrorContainer = Color(0xFF93000A)
val OnErrorContainer = Color(0xFFFFDAD6)

val White = Color(0xFFFFFFFF)
val Black = Color(0xFF000000)

/**
 * What a heart rate looks like, everywhere it is drawn.
 *
 * Cool to warm, so the ramp reads as effort without a legend: a curve climbing from blue to red is
 * obvious in a way that green-to-red is not. The middle used to be green, which fought the teal and
 * read as "good" rather than "middling"; amber sits between the ends instead of arguing with them.
 *
 * These are the *only* definition of metric colour. The analysis charts, the zone bars, the library
 * accents, the rendered video overlay and the exported image all take theirs from here — a colour
 * meaning "peak" has to mean peak in the picture someone posts as well as on the screen they read
 * it from. `ExportPreset` keeps its own copies because they are part of a saved look, but their
 * defaults come from these.
 */
val BpmLow = Color(BpmPalette.LOW)
val BpmAvg = Color(BpmPalette.AVG)
val BpmHigh = Color(BpmPalette.HIGH)

/**
 * The accent for a primary action.
 *
 * The same teal as the theme's primary, named for what it is used for. One accent per screen: a
 * screen with three teal things has no primary action.
 */
val BpmAccent = TealPrimary

/** Something finished, and went well. Distinct from the ramp, which describes heart rates. */
val BpmSuccess = Color(0xFF5FD68A)

/**
 * Chart gridlines: present enough to read a value against, quiet enough to ignore.
 *
 * Was `Color(0x1FBEC9C4)` — the same eight digits `BpmPalette.GRID` already held, typed a second
 * time twelve lines below the comment warning against exactly that. The chart and the exported
 * image of the same chart were reading two constants that happened to agree.
 */
val ChartGrid = Color(BpmPalette.GRID)

/** A lane that is neither a person nor a rate. See [BpmPalette.NEUTRAL]. */
fun neutralLaneColour(index: Int) = Color(BpmPalette.neutral(index))
