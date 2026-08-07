package inga.bpmetrics.library

/**
 * A band of heart rates, and what it means.
 *
 * Deliberately absolute BPM rather than a percentage of maximum heart rate. The usual formula
 * needs an age the app does not have and would not be right anyway for the thing this measures —
 * these recordings are of people at gigs, not athletes on a plan. Absolute bands are honest about
 * being a rough description rather than a physiological claim.
 */
data class BpmZone(
    val name: String,
    val lowerBpm: Int,
    /** Exclusive. Null on the top band, which has no ceiling. */
    val upperBpm: Int?
) {
    operator fun contains(bpm: Double): Boolean =
        bpm >= lowerBpm && (upperBpm == null || bpm < upperBpm)
}

/**
 * How long a set of readings spent in each band.
 *
 * @property zone The band.
 * @property durationMs Measured time inside it, dropouts excluded.
 * @property share Fraction of the total measured time, 0..1.
 */
data class ZoneTime(
    val zone: BpmZone,
    val durationMs: Long,
    val share: Float
)

/**
 * Splits measured time across heart rate bands.
 *
 * This is what turns "max 186" into something with a shape: whether someone touched 186 once or
 * sat above 160 for half an hour are very different evenings, and min/avg/max cannot tell them
 * apart.
 *
 * Time is attributed to the band the *earlier* of each pair of readings falls in, and gaps wider
 * than the sensor threshold are excluded entirely — the same rule the charts break their lines on,
 * so a stated share never counts time nothing was measured in.
 */
object BpmZones {

    /**
     * The default bands.
     *
     * Chosen to be legible at a glance rather than clinically exact: resting, moving about, into
     * it, and genuinely going. A settings-driven override is a later concern; the shape of the
     * calculation does not change.
     */
    val DEFAULT = listOf(
        BpmZone("Resting", 0, 100),
        BpmZone("Light", 100, 130),
        BpmZone("Elevated", 130, 160),
        BpmZone("Peak", 160, null)
    )

    /**
     * Adds several already-split sets together.
     *
     * How a person's, a tag's or a whole scope's bands are worked out: every one of them is the
     * sum of the recordings underneath it, so there is one definition of "time in the peak band"
     * rather than one per level.
     */
    fun merge(splits: List<List<ZoneTime>>, zones: List<BpmZone> = DEFAULT): List<ZoneTime> {
        val totals = zones.associateWith { zone ->
            splits.sumOf { split ->
                split.filter { it.zone.name == zone.name }.sumOf { it.durationMs }
            }
        }
        val measured = totals.values.sum()
        return zones.map { zone ->
            val ms = totals[zone] ?: 0L
            ZoneTime(zone, ms, if (measured > 0L) ms.toFloat() / measured else 0f)
        }
    }

    /**
     * @param points Readings on a shared clock, in time order.
     * @param gapThresholdMs Anything longer is a dropout and contributes no time to any band.
     */
    fun split(
        points: List<Pair<Long, Double>>,
        zones: List<BpmZone> = DEFAULT,
        gapThresholdMs: Long = 10_000L
    ): List<ZoneTime> {
        val totals = LongArray(zones.size)

        points.zipWithNext().forEach { (a, b) ->
            val dt = b.first - a.first
            if (dt !in 0..gapThresholdMs) return@forEach
            val index = zones.indexOfFirst { a.second in it }
            if (index >= 0) totals[index] += dt
        }

        val measured = totals.sum()
        return zones.mapIndexed { i, zone ->
            ZoneTime(
                zone = zone,
                durationMs = totals[i],
                share = if (measured > 0L) totals[i].toFloat() / measured else 0f
            )
        }
    }
}
