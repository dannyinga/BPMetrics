package inga.bpmetrics.util

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A long-lived scope that logs what escapes it rather than taking the app down.
 *
 * ## Why this exists
 *
 * Four scopes in this module were built as `CoroutineScope(Dispatchers.IO + SupervisorJob())`, which
 * looks like it handles failure and does not. `SupervisorJob` decides only that one child's failure
 * will not cancel its siblings. An exception that escapes a `launch` still travels to the scope's
 * [CoroutineExceptionHandler], and with none installed it goes to the thread's default handler,
 * which kills the process.
 *
 * So the two receive paths from the watch, the export service and the render queue were each one
 * unexpected throw away from taking the phone app down — the two receive paths while *holding the
 * only copy of a recording that had not been saved yet*. `LibraryRepository` had already worked
 * this out and written the handler; the other four never got it. This is that handler, once.
 *
 * ## What it is not
 *
 * Not a way to ignore failure. Everything it catches is logged with the scope's own tag, and it is
 * a backstop for the unforeseen — the throw nobody wrote a `catch` for. Work that can fail in a way
 * the *user* needs to know about still has to catch it and say so; there is nothing this can put on
 * screen. Where losing an item matters, guard the item as well, so one bad record does not abandon
 * the nine behind it in the same loop.
 */
fun backgroundScope(
    /** Names the scope in the log, so a caught failure says which subsystem it came from. */
    tag: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
): CoroutineScope = CoroutineScope(
    SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, throwable ->
        Log.e(tag, "Unhandled failure in $tag scope", throwable)
    }
)

/**
 * `viewModelScope.launch`, with a handler on it.
 *
 * `viewModelScope` carries no [CoroutineExceptionHandler]. Every one of the 113 launches across
 * this app's ViewModels therefore ended at the thread's default handler if anything escaped it —
 * and what these blocks mostly do is call the repository, which means Room, which throws on a
 * constraint violation, a full disk, or a database that has gone away underneath a long operation.
 * Renaming a person and having the app vanish is not an acceptable answer to a full disk.
 *
 * A handler passed to `launch` applies to that coroutine as its root, which is what makes this
 * work without replacing the scope the framework gives us — and it keeps `viewModelScope`'s
 * cancellation on `onCleared`, which is the whole reason to use it.
 *
 * This is a backstop, not an excuse. It cannot tell the user anything, so an operation whose
 * failure the user needs to hear about still has to catch it and say so. What it guarantees is
 * that the *unforeseen* failure degrades into a log line and a screen that does not update, rather
 * than a process death.
 */
fun ViewModel.launchGuarded(block: suspend CoroutineScope.() -> Unit): Job =
    viewModelScope.launch(
        CoroutineExceptionHandler { _, throwable ->
            Log.e(this::class.java.simpleName, "Unhandled failure", throwable)
        },
        block = block
    )

/**
 * Runs [block], logging and swallowing anything it throws.
 *
 * For loops over items that arrived from somewhere else — the watch, the filesystem, a saved queue
 * — where one unreadable item must not abandon the rest. The failure mode this prevents is quiet
 * and expensive: a `forEach` that throws on item three drops items four onward with no record that
 * they existed, and on the sync path those items are recordings still only held on a watch.
 *
 * Cancellation is rethrown. Swallowing it would leave the coroutine machinery believing a cancelled
 * job ran to completion, which turns a tidy shutdown into a hang.
 */
inline fun <T> guarded(tag: String, what: String, block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    Log.e(tag, "Failed: $what", t)
    null
}
