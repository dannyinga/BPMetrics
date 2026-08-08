package inga.bpmetrics.ui.theme

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
val BpmTeal = Color(0xFF00DCC2)

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

val Surface = Color(0xFF0E1513)
val OnSurface = Color(0xFFDDE4E1)
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
 * The same colours as plain ARGB, for the renderers.
 *
 * `ImageExporter`, `TimelineImageExporter` and `ExportPreset` all draw with `android.graphics` and
 * take `Int`s, and each of them used to carry its own copy of these values. Three copies of one
 * palette is three chances for the picture and the screen to disagree — which is the failure this
 * whole document is about, and the same shape of bug that put the video and its preview a
 * clip-length apart earlier.
 *
 * One definition; the Compose colours above are derived from it.
 */
object BpmPalette {
    const val LOW = 0xFF6FC3FF.toInt()
    const val AVG = 0xFFFFC46B.toInt()
    const val HIGH = 0xFFFF6B6B.toInt()

    /** The panel an export is drawn on, matching the app's surface. */
    const val SURFACE = 0xFF0E1513.toInt()
    const val ON_SURFACE = 0xFFDDE4E1.toInt()
    const val GRID = 0x1FBEC9C4
}

/**
 * The accent for a primary action.
 *
 * The same teal as the theme's primary, named for what it is used for. One accent per screen: a
 * screen with three teal things has no primary action.
 */
val BpmAccent = TealPrimary

/** Something finished, and went well. Distinct from the ramp, which describes heart rates. */
val BpmSuccess = Color(0xFF5FD68A)

/** Chart gridlines: present enough to read a value against, quiet enough to ignore. */
val ChartGrid = Color(0x1FBEC9C4)
