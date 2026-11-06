package inga.bpmetrics.ui.components

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import inga.bpmetrics.library.Cover
import inga.bpmetrics.library.ItemRef
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.util.launchGuarded

// Setting, re-framing and clearing a cover, for any recording, event or collection, from whichever
// page's view model is to hand.
//
// These were written three times (Model and Export Consolidation §4): the library, the event page
// and the recording page each had their own three wrappers around the same repository calls,
// differing only in which item they named and how they found a name for the file.

/**
 * Gives [ref] the picture at [source]. [onResult] says whether it was stored.
 *
 * @param then runs after the change, for a page that holds its own copy of the item.
 */
fun ViewModel.setCoverOn(
    repository: LibraryRepository,
    context: Context,
    ref: ItemRef,
    source: Uri,
    then: suspend () -> Unit = {},
    onResult: (Boolean) -> Unit
) {
    launchGuarded {
        val stored = repository.setCover(context, ref, source)
        if (stored) then()
        onResult(stored)
    }
}

/** Re-frames the picture [ref] already has, leaving the file alone. */
fun ViewModel.cropCoverOn(
    repository: LibraryRepository,
    ref: ItemRef,
    cover: Cover,
    then: suspend () -> Unit = {}
) {
    launchGuarded {
        repository.setCoverCrop(ref, cover)
        then()
    }
}

/**
 * Takes [ref]'s own picture off. It falls back to what it inherits, which is why the column stores
 * null rather than an empty string.
 */
fun ViewModel.clearCoverOn(
    repository: LibraryRepository,
    context: Context,
    ref: ItemRef,
    then: suspend () -> Unit = {}
) {
    launchGuarded {
        repository.clearCover(context, ref)
        then()
    }
}
