package inga.bpmetrics.ui.library

import com.google.gson.Gson

/**
 * A filter, to and from text.
 *
 * Saved views store the question rather than its answer, so the question has to survive a restart.
 * JSON rather than a column per dimension because the dimensions change — location arrived a sprint
 * after the rest — and a schema that grows a column each time is a migration each time.
 *
 * **Every field of [LibraryViewModel.FilterState] has a default, and that is load-bearing here for
 * the same reason it is in the backup format.** Gson does not run constructors: given a class with a
 * required parameter it allocates through `Unsafe`, so a key the JSON omits is left null — including
 * on a property declared non-null. A view saved before `query` or `selectedLocationIds` existed
 * would come back with nulls in them and throw on first use. With all parameters defaulted, Kotlin
 * emits a no-arg constructor, Gson calls it, and an older view simply reads as not narrowing on the
 * dimensions it predates.
 */
object FilterSerialisation {

    private val gson = Gson()

    fun toJson(filter: LibraryViewModel.FilterState): String = gson.toJson(filter)

    /**
     * Reads a stored filter, or an empty one if it cannot be read.
     *
     * A view that fails to parse becomes a view that selects everything, which is visibly wrong and
     * recoverable — the alternative is a crash on opening the library, which is neither.
     */
    fun fromJson(json: String): LibraryViewModel.FilterState =
        runCatching { gson.fromJson(json, LibraryViewModel.FilterState::class.java) }
            .getOrNull()
            ?: LibraryViewModel.FilterState()
}
