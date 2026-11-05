package inga.bpmetrics.export

import androidx.media3.transformer.ExportException
import java.io.IOException

/**
 * Why a render failed, in a sentence for the queue and the notification.
 *
 * The queue used to show the raw exception, in monospace and behind a tap, and the notification
 * said only "There was an error encoding" (Audit §3 Export, §6). The app's own failures already
 * read as sentences; this keeps them, and gives the encoder's and the system's a sentence too.
 */
object RenderFailure {

    fun describe(error: Throwable): String = when (error) {
        is ExportException ->
            "The phone's video encoder stopped (${ExportException.getErrorCodeName(error.errorCode)}). " +
                "Retrying sometimes works; a smaller canvas or a lower frame rate usually does."
        is SecurityException ->
            "BPMetrics can no longer open the clip it was made from. Check its access to your videos."
        is IOException -> error.message?.takeIf { it.isNotBlank() }
            ?: "The video couldn't be written. Check there's space, and retry."
        is OutOfMemoryError ->
            "The phone ran out of memory. Try a smaller canvas, or close other apps and retry."
        else -> error.message?.takeIf { it.isNotBlank() }?.let { "Something went wrong: $it" }
            ?: "Something went wrong while rendering."
    }
}
