package inga.bpmetrics.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * The app's scheme, seeded from the teal in [Color.kt].
 *
 * Replaces the Compose template's purple, which was never changed and was being overridden by
 * dynamic colour on most phones anyway — so the app had no appearance of its own.
 */
private val BpmDarkScheme = darkColorScheme(
    primary = TealPrimary,
    onPrimary = TealOnPrimary,
    primaryContainer = TealPrimaryContainer,
    onPrimaryContainer = TealOnPrimaryContainer,
    inversePrimary = TealInversePrimary,

    secondary = TealSecondary,
    onSecondary = TealOnSecondary,
    secondaryContainer = TealSecondaryContainer,
    onSecondaryContainer = TealOnSecondaryContainer,

    tertiary = TealTertiary,
    onTertiary = TealOnTertiary,
    tertiaryContainer = TealTertiaryContainer,
    onTertiaryContainer = TealOnTertiaryContainer,

    background = Surface,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    surfaceTint = TealPrimary,
    inverseSurface = SurfaceInverse,
    inverseOnSurface = OnSurfaceInverse,

    error = ErrorColor,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,

    outline = Outline,
    outlineVariant = OutlineVariant,
    scrim = Scrim
)

/**
 * The app's theme. Always dark.
 *
 * Light is not supported, and that is a decision rather than an omission: the charts, the export
 * panel and the metric ramp are all designed against dark, and supporting light *properly* means a
 * second set of contrast decisions on every surface. A half-supported light theme is worse than
 * none. See the UI and UX Cleanup document, §7.4.
 *
 * @param dynamicColor take the palette from the wallpaper instead. Off by default now — a product
 *   with a chosen colour should show it — and available in Settings for anyone who prefers theirs.
 */
@Composable
fun BPMetricsTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // Gated on the version that actually has it. The previous code called the dynamic scheme
    // unconditionally, including on Android 11 and below where there is no such thing.
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        BpmDarkScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
