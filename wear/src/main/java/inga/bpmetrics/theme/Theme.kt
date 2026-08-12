package inga.bpmetrics.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

/**
 * The watch's theme.
 *
 * This was the Compose template, verbatim, comment and all — *"Empty theme to customize for your
 * app"* — so every screen on the wrist rendered in Wear Material's stock blues while the phone
 * rendered in teal. The same thing had happened to the phone and was fixed there; the watch was
 * simply never looked at again.
 *
 * Wear Material is Material 1, not 3, so this is a [Colors] rather than a `ColorScheme` and there
 * is no dynamic-colour path to gate. The values come from the shared palette, so the two halves of
 * the product are the same colour by construction rather than by somebody remembering.
 *
 * Dark only, and not as a decision that needed making: a watch face is drawn on an OLED panel that
 * is off wherever it is black, and a light theme on the wrist would cost battery for nothing.
 */
private val BpmWearColors = Colors(
    primary = BpmAccent,
    primaryVariant = BpmAvg,
    onPrimary = WearOnAccent,

    secondary = WearOnSurfaceVariant,
    secondaryVariant = WearSurfaceVariant,
    onSecondary = WearSurface,

    // Black rather than the phone's near-black. The phone's surface is a tinted dark grey because
    // it sits in a lit room behind a lot of chrome; a watch is mostly black pixels the panel does
    // not light at all, and matching the phone's grey here would cost battery to draw nothing.
    background = androidx.compose.ui.graphics.Color.Black,
    onBackground = WearOnSurface,
    surface = WearSurface,
    onSurface = WearOnSurface,
    onSurfaceVariant = WearOnSurfaceVariant,

    error = WearError,
    onError = WearOnError
)

@Composable
fun BPMetricsTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colors = BpmWearColors,
        typography = WearTypography,
        content = content
    )
}
