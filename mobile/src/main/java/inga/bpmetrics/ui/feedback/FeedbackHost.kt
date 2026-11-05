package inga.bpmetrics.ui.feedback

import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A snackbar's content, plus whether to draw it as a failure. */
private class FeedbackVisuals(
    override val message: String,
    override val actionLabel: String?,
    override val duration: SnackbarDuration,
    val isFailure: Boolean
) : SnackbarVisuals {
    override val withDismissAction: Boolean = false
}

/**
 * Shows everything posted to [Feedback], one at a time, in the order it arrived.
 *
 * Placed once, in the app shell's `Scaffold`, so it sits above the bottom bar on top-level screens
 * and at the bottom edge on pages pushed on top of them. Screens don't keep a host of their own.
 *
 * How long each stays up follows from what it asks of the user: a plain confirmation goes after
 * the short interval; a failure, or anything offering Undo or Open, gets the long one, because
 * it has to be read or acted on.
 */
@Composable
fun FeedbackHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    // The shell's scope, not the collector's. An action started from a snackbar has to finish even
    // if the next message replaces it, or the user leaves the screen it was posted from.
    val scope = rememberCoroutineScope()

    LaunchedEffect(hostState) {
        Feedback.messages.collect { message ->
            val isFailure = message.kind == FeedbackMessage.Kind.FAILURE
            val result = hostState.showSnackbar(
                FeedbackVisuals(
                    message = message.text,
                    actionLabel = message.actionLabel,
                    duration = if (isFailure || message.action != null) {
                        SnackbarDuration.Long
                    } else {
                        SnackbarDuration.Short
                    },
                    isFailure = isFailure
                )
            )
            val action = message.action
            if (result == SnackbarResult.ActionPerformed && action != null) {
                scope.launch {
                    try {
                        action()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.e("Feedback", "Action '${message.actionLabel}' failed", t)
                        Feedback.failed(
                            if (message.actionLabel == "Undo") "That couldn't be undone."
                            else "That couldn't be opened."
                        )
                    }
                }
            }
        }
    }

    SnackbarHost(hostState, modifier) { data ->
        val isFailure = (data.visuals as? FeedbackVisuals)?.isFailure == true
        val colors = MaterialTheme.colorScheme
        if (isFailure) {
            Snackbar(
                snackbarData = data,
                containerColor = colors.errorContainer,
                contentColor = colors.onErrorContainer,
                actionColor = colors.onErrorContainer
            )
        } else {
            Snackbar(snackbarData = data)
        }
    }
}
