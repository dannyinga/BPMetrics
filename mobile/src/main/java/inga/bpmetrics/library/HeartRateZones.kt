package inga.bpmetrics.library

/**
 * Which resting and maximum rate apply to a given person.
 *
 * The rule is one sentence — their own figures if they have them, the app-wide ones otherwise — and
 * it exists as a function because it would otherwise be written out at every call site that needs
 * zones, and one of those would eventually forget the fallback and divide by a null.
 *
 * The figures belong on the person because they *are* facts about a person: a runner's resting rate
 * and a stranger's are different numbers, and a single app-wide value would make time-in-zone say
 * something false about whichever of them it did not describe. Settings holds what to use for
 * anyone who has not given their own.
 */
object HeartRateZones {

    /** The range zones are measured across. */
    data class Range(val restingBpm: Int, val maxBpm: Int) {
        /** Never zero, so a percentage of it is always answerable. */
        val span: Int get() = (maxBpm - restingBpm).coerceAtLeast(1)

        /** Where a reading sits between resting and maximum, 0..1. */
        fun fractionOf(bpm: Double): Float =
            ((bpm - restingBpm) / span).toFloat().coerceIn(0f, 1f)
    }

    /**
     * @param person whose figures to prefer, or null for someone unattributed.
     * @param defaultResting the app-wide resting rate.
     * @param defaultMax the app-wide maximum.
     */
    fun forPerson(person: PersonEntity?, defaultResting: Int, defaultMax: Int): Range {
        val resting = person?.restingBpm ?: defaultResting
        val max = person?.maxBpm ?: defaultMax
        // Guarded even though both are clamped on the way in: a backup restored from an older
        // build, or a hand-edited row, can still arrive the wrong way round, and a negative span
        // turns every zone percentage inside out rather than failing visibly.
        return if (resting >= max) Range(max - 1, max) else Range(resting, max)
    }
}
