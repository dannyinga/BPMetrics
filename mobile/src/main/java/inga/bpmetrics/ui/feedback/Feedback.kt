package inga.bpmetrics.ui.feedback

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * One thing to tell the user, and optionally one thing they can do about it.
 *
 * [action] runs only if they tap [actionLabel]. It is suspending so an Undo can write to the
 * database directly, and it runs in the app shell's scope rather than the screen's, so leaving the
 * screen while the snackbar is up doesn't cancel an Undo halfway.
 */
data class FeedbackMessage(
    val text: String,
    val kind: Kind = Kind.INFO,
    val actionLabel: String? = null,
    val action: (suspend () -> Unit)? = null
) {
    enum class Kind {
        /** Something happened as asked. */
        INFO,

        /** Something the user asked for didn't happen, or only partly did. */
        FAILURE
    }
}

/**
 * The app's one way of saying what just happened.
 *
 * Anything (a screen, a view model, a background helper) posts here, and the single
 * [FeedbackHost] in the app shell shows it as a snackbar. Before this there were Toasts of two
 * lengths, one screen with its own snackbar, and failures that said nothing at all. A Toast can't
 * carry Undo or Open, and those are what deletes, splits and saves need.
 *
 * ## Which verb
 *
 * - [say]: it worked, and there is nothing more to do.
 * - [failed]: it didn't. Say what didn't happen in words the user would use, not why the code
 *   threw. These stay up longer and look different.
 * - [undoable]: it worked, it destroyed something, and it can be put back.
 * - [created]: it worked and made something new that the user will probably want to look at.
 *
 * ## Delivery
 *
 * A buffered channel rather than a `SharedFlow`. A `SharedFlow` with no subscriber drops what is
 * emitted, so a message posted while the shell was being recreated (rotation, or a return from
 * the photo picker) would vanish. The channel holds the last few until the host collects them, and
 * drops the oldest if a burst outruns it. A message is about something just done, so the newest
 * matters most.
 */
object Feedback {

    private const val BUFFER = 8

    private val channel = Channel<FeedbackMessage>(
        capacity = BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** What the host shows, in order. Collect from exactly one place: [FeedbackHost]. */
    val messages: Flow<FeedbackMessage> = channel.receiveAsFlow()

    fun post(message: FeedbackMessage) {
        channel.trySend(message)
    }

    fun say(text: String) = post(FeedbackMessage(text))

    fun failed(text: String) = post(FeedbackMessage(text, FeedbackMessage.Kind.FAILURE))

    fun undoable(text: String, undo: suspend () -> Unit) =
        post(FeedbackMessage(text, actionLabel = "Undo", action = undo))

    fun created(text: String, open: () -> Unit) =
        post(FeedbackMessage(text, actionLabel = "Open", action = { open() }))
}
