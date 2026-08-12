package inga.bpmetrics.library

import com.google.gson.Gson

/**
 * A filter, to and from text.
 *
 * A selection stores the *question*, so the question has to survive a restart. JSON rather than a
 * column per dimension because the dimensions change — location arrived a sprint after the rest —
 * and a schema that grows a column each time is a migration each time.
 *
 * **Every field of [FilterState] has a default, and that is load-bearing here for the same reason
 * it is in the backup format.** Gson does not run constructors: given a class with a required
 * parameter it allocates through `Unsafe`, so a key the JSON omits is left null — including on a
 * property declared non-null. A rule saved before `query` or `selectedLocationIds` existed would
 * come back with nulls in it and throw on first use. With all parameters defaulted, Kotlin emits a
 * no-arg constructor, Gson calls it, and an older rule reads as not narrowing on what it predates.
 */
object FilterCodec {

    private val gson = Gson()

    fun toJson(filter: FilterState): String = gson.toJson(filter)

    /**
     * Reads a stored filter, or an empty one if it cannot be read.
     *
     * A pinned selection that fails to parse becomes one that selects everything, which is visibly
     * wrong and recoverable — the alternative is a crash on opening the library, which is neither.
     * [rule] takes the stricter line for a collection, where "everything" would be the more
     * damaging answer.
     */
    fun fromJson(json: String): FilterState = parseOrNull(json) ?: FilterState()

    /**
     * Reads a stored filter, or null if it cannot be read.
     *
     * What a collection's rule uses. There, falling back to an empty filter would be the *worse*
     * failure: an empty [FilterState] selects the whole library, so an unreadable rule would
     * silently turn "every Subtronics recording" into "everything" — and a set that has quietly
     * grown is much harder to notice than one that has quietly shrunk.
     */
    fun parseOrNull(json: String): FilterState? =
        runCatching { gson.fromJson(json, FilterState::class.java) }.getOrNull()
}
