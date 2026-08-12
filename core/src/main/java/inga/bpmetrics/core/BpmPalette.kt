package inga.bpmetrics.core

/**
 * Every colour the app assigns by *meaning*, in one place, as plain ARGB.
 *
 * ## Why here
 *
 * This lived in `mobile`'s theme package, which put it out of reach of the watch — and the watch
 * had quietly kept the palette from before the ramp was fixed, with a green middle the phone
 * deliberately moved away from. So the same heart rate was drawn one colour on the wrist and
 * another on the phone it synced to, which is precisely the failure §5 of the taxonomy document is
 * about. A shared module is the only fix that cannot drift back: there is nowhere for a second copy
 * to live.
 *
 * Plain `Int`s rather than Compose colours for the same reason it works at all — `:core` has no
 * Compose and no `android.graphics`. Each module derives its own representation from these.
 *
 * ## The three schemes, and only three
 *
 * - **Identity** — a person — takes that person's own colour. Not here; see `PersonColors`.
 * - **Quantity** — a rate — takes [LOW]–[AVG]–[HIGH] and the ramp between them.
 * - **Zones** are the deliberate third scheme, [zone].
 *
 * Nothing else introduces a palette. Anything that needs to tell N things apart without saying
 * anything about who or how fast takes [neutral].
 */
object BpmPalette {

    // --- Quantity: what a heart rate looks like, everywhere it is drawn ---

    /**
     * Cool to warm, so the ramp reads as effort without a legend.
     *
     * The middle used to be green, which fought the teal and read as "good" rather than "middling";
     * amber sits between the ends instead of arguing with them.
     */
    const val LOW = 0xFF6FC3FF.toInt()
    const val AVG = 0xFFFFC46B.toInt()
    const val HIGH = 0xFFFF6B6B.toInt()

    // --- Surfaces the renderers draw on, matching the app's own ---

    const val SURFACE = 0xFF0E1513.toInt()
    const val ON_SURFACE = 0xFFDDE4E1.toInt()

    /** Gridlines: present enough to read a value against, quiet enough to ignore. */
    const val GRID = 0x1FBEC9C4

    /** The brand teal. The mark, the splash, and the seed the whole scheme is built from. */
    const val TEAL = 0xFF00DCC2.toInt()

    // --- Zones: the deliberate third scheme ---

    /**
     * The orange between the amber and the red. The only colour the zone scheme adds.
     *
     * Named because the bands want a fourth step and there is nothing at that point on the ramp:
     * the ramp is defined by three colours, and a band scale of four needs one more.
     */
    const val ZONE_HARD = 0xFFFF9A52.toInt()

    /**
     * The band scheme: blue, amber, orange, red.
     *
     * **Written down rather than derived, and that is the point.** The first attempt sampled the
     * continuous ramp, on the reasoning that a fourth set of hexes would be a fourth thing to keep
     * in step. It produced blue, violet, pink, red — because the ramp interpolates *hue*, and the
     * wheel between blue and red passes through neither amber nor orange. Going by way of the
     * middle only moved the problem: blue to amber, taken the short way, runs through green, which
     * is the one colour this palette has explicitly rejected.
     *
     * The lesson is the one §5 already stated and this took the long way round to: zones are the
     * deliberate third scheme, and a deliberate scheme is a list of colours somebody chose. Three
     * of the four are the ramp's own, so the bands still agree with every rate in the app.
     */
    val ZONES = listOf(LOW, AVG, ZONE_HARD, HIGH)

    /**
     * The colour of the band at [index] of [count], resting first.
     *
     * Keyed on position rather than name. What it replaces was a `when` on the band's *name* as a
     * string literal, with `BpmHigh` at 70% alpha standing in for the third band and an `else`
     * catching anything unrecognised — so a renamed band silently lost its colour and a fifth band
     * was invisible. A position works for any number of bands: four land exactly on [ZONES], and
     * anything else spreads across the same stops.
     */
    fun zone(index: Int, count: Int): Int {
        if (count <= 1) return LOW
        val fraction = index.coerceIn(0, count - 1).toFloat() / (count - 1)
        val position = fraction * (ZONES.size - 1)
        val stop = position.toInt().coerceIn(0, ZONES.size - 2)
        // Mixed, not hue-walked: these are chosen colours sitting close together, so the straight
        // line between two of them is the right one. See [BpmRamp.mix].
        return BpmRamp.mix(ZONES[stop], ZONES[stop + 1], position - stop)
    }

    // --- Neutral: telling things apart, without claiming anything about them ---

    /**
     * A series for lanes that are neither a person nor a rate — split by tag, by venue, by type.
     *
     * Deliberately muted, and deliberately not the person palette: those colours are saturated
     * because they stand for *someone*, and reusing them for "the Concert lane" would say a tag
     * was a person. Neither can it be the ramp, which would say the lane with the warmest colour
     * was the fastest — it is not, it is just third in the list.
     *
     * These are the theme's own neutrals and secondaries, spread far enough apart in hue to be
     * told apart at the size of a 10dp dot, and desaturated enough to sit behind the figure they
     * label rather than in front of it.
     *
     * Before this, every non-person lane fell back to a single colour — the metric's — so a
     * comparison split six ways drew six identical dots, and the colour column said nothing at all.
     */
    val NEUTRAL = listOf(
        0xFFB0CCC5.toInt(), // Muted teal — the theme's secondary
        0xFFADCAE6.toInt(), // Slate blue — the tertiary
        0xFFC9BFA6.toInt(), // Sand
        0xFFC4AEC8.toInt(), // Mauve
        0xFFA8C3A5.toInt(), // Sage
        0xFFD3B6A8.toInt()  // Clay
    )

    /** The [NEUTRAL] entry for a lane at [index], cycling so a long list keeps going. */
    fun neutral(index: Int): Int = NEUTRAL[index.mod(NEUTRAL.size)]

    /**
     * The residual lane — "no tag", "no venue", the ones that did not fall into any group.
     *
     * Dimmer than any real lane on purpose. It is the absence of a category rather than one more of
     * them, and it was previously the only lane left to the fallback colour, which made the row for
     * "everything uncategorised" the brightest thing in the comparison.
     */
    const val RESIDUAL = 0xFF89938F.toInt()
}
