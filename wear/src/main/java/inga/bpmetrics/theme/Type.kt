package inga.bpmetrics.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Typography

/**
 * The watch's type scale, matching the decisions the phone's makes.
 *
 * There was none: the theme passed no typography at all, so every screen took Wear Material's
 * stock scale while the phone used one built around this app's signature content. The two are not
 * the same scale and cannot be — a 21sp title is a heading on a phone and most of the width of a
 * watch — but the *decisions* carry across, which is what makes them look like one product:
 * semibold for anything titular, normal for prose, and tabular figures on every reading.
 *
 * Wear Material is Material 1, so the roles are `display`/`title`/`body`/`caption` rather than the
 * phone's `display`/`headline`/`title`/`body`/`label`.
 */
val WearTypography = Typography(
    // The live heart rate, and nothing else. It is the reason to look at the watch at all.
    display1 = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = WearMetricNumerals
    ),
    display2 = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        fontFeatureSettings = WearMetricNumerals
    ),
    display3 = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        fontFeatureSettings = WearMetricNumerals
    ),

    title1 = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 22.sp
    ),
    title2 = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp
    ),
    title3 = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),

    body1 = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp
    ),
    body2 = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp
    ),

    button = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),

    caption1 = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    ),
    caption2 = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.3.sp
    ),
    caption3 = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.3.sp
    )
)

/**
 * Numerals that do not move.
 *
 * The same reasoning as the phone's, and it matters more here: the watch shows a *live* heart rate,
 * so proportional digits make the number visibly jitter as it counts — a `1` is narrower than an
 * `8`, and at 38sp that shift is a wobble you cannot stop noticing. Baked into the three display
 * styles because on a watch those are only ever used for a reading.
 */
const val WearMetricNumerals = "tnum"
