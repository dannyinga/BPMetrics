package inga.bpmetrics.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import inga.bpmetrics.library.PersonEntity
import kotlin.math.roundToInt

/**
 * Who this is, as a face or as their colour.
 *
 * A photograph where one has been added, and their colour with their initial where one has not.
 * The fallback is not a placeholder to be tolerated until a photo arrives — most people will never
 * add one, and a coloured initial already identifies a wearer at a glance because the colour is the
 * same one their curve is drawn in everywhere else in the app.
 *
 * Always a circle, always the same size at a given call site. A row of avatars that change shape
 * depending on whether someone got round to adding a picture reads as broken.
 */
@Composable
fun PersonAvatar(
    person: PersonEntity?,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp
) {
    val tint = person?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.surfaceVariant
    val photo = person?.ownPhoto

    Box(
        modifier = modifier.size(size).clip(CircleShape).background(tint),
        contentAlignment = Alignment.Center
    ) {
        if (photo != null) {
            // The same drawing path a cover uses, with no scrim. One crop implementation rather
            // than a second one for circles that rounds its fractions slightly differently — the
            // recurring way two things in this app come to disagree.
            CoverBackground(
                cover = photo,
                modifier = Modifier.fillMaxSize(),
                scrim = CoverScrim.NONE
            ) {}
        } else {
            Text(
                text = person?.displayName?.trim()?.firstOrNull()?.uppercase() ?: "?",
                // Sized off the circle so one component works at 24dp in a tile and 72dp on a
                // profile page without a second set of numbers to keep in step.
                fontSize = (size.value * 0.44f).roundToInt().sp,
                fontWeight = FontWeight.Bold,
                color = onColour(tint)
            )
        }
    }
}

/**
 * Black or white, whichever can be read on [background].
 *
 * People pick their own colours and some of them pick yellow. A fixed white initial disappears on
 * it; this is the standard luminance test, which is cheaper and more reliable than asking anyone to
 * choose a readable colour.
 */
private fun onColour(background: Color): Color {
    val luminance =
        0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
    return if (luminance > 0.6f) Color.Black else Color.White
}

// Loading and decoding is CoverBackground's, through the shared cache — a second loader here would
// be a second thing to remember to invalidate when a photograph is replaced.
