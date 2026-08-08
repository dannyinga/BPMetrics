package inga.bpmetrics.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import inga.bpmetrics.ui.theme.Black

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = DarkGray,
    onPrimary = White,
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = LightGray,
    onPrimary = Black,

    /* Other default colors to override
    onSecondary = Color.White,
    surface = Color(0xFFFFFBFE),
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun BPMetricsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Wallpaper colours, where the platform offers them.
     *
     * Previously true with no way to change it — and taken unconditionally, so on Android 11 and
     * below `dynamicDarkColorScheme` was being called on a platform that has no such thing. Now a
     * setting, and gated on the version that actually supports it.
     */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
      dynamicColor && supportsDynamic -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

    MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      content = content
    )
}