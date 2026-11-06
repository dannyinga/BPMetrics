package inga.bpmetrics.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition

/**
 * The frame every watch screen sits in: the time along the top edge, a scroll indicator, and a
 * list that curves with the round screen and scrolls with the crown or bezel.
 *
 * Every screen was a plain centred column (Audit §3 Watch). Anything taller than the circle was
 * cut off with no way to reach it, the time was hidden, and the crown did nothing.
 *
 * @param centerItem which item sits in the middle of the screen when it opens.
 */
@Composable
fun WearScreen(
    centerItem: Int = 0,
    content: ScalingLazyListScope.() -> Unit
) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = centerItem)
    val focusRequester = remember { FocusRequester() }

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .rotaryScrollable(
                    behavior = RotaryScrollableDefaults.behavior(scrollableState = listState),
                    focusRequester = focusRequester
                ),
            state = listState,
            autoCentering = AutoCenteringParams(itemIndex = centerItem),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }

    // The crown only scrolls what has focus.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}
