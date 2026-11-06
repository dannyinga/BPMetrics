package inga.bpmetrics.export

import inga.bpmetrics.core.BpmGson

/**
 * The appearance half of an export, as a preset stores it.
 *
 * Deliberately its own type rather than a serialized [VideoExporter.VideoExportConfig]. A config
 * carries what is being exported as well as how it looks — record ids, a time range, an overlay
 * video, per-record colours, a heading — and every one of those belongs to a single export. A
 * preset holding them would stop working the moment those recordings were deleted, which is the
 * opposite of what saving one is for.
 *
 * Making the split a *type* rather than a rule means the stripping cannot be forgotten: there is
 * nowhere in this class to put a record id.
 *
 * @property version What wrote it. A file from a newer build says so rather than half-applying.
 */
/**
 * A graph rectangle, as fractions of the canvas.
 *
 * Its own type so a preset can hold several of them without four more fields per entry, and
 * fractions rather than pixels so a frame survives a change of canvas size — the same reason the
 * preset's own `graphLeft`..`graphBottom` are fractions.
 *
 * Every field has a default because Gson does not run constructors: it only calls the synthetic
 * no-arg constructor Kotlin emits when *all* parameters have one. Without that, a frame missing a
 * field in the JSON would deserialise with zeroes and draw nothing.
 */
data class GraphFrame(
    val left: Float = 0.25f,
    val top: Float = 0.58f,
    val right: Float = 0.75f,
    val bottom: Float = 0.91f
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun toRectF(): android.graphics.RectF = android.graphics.RectF(left, top, right, bottom)

    /** True where this rectangle has enough area to draw a graph in. */
    val isUsable: Boolean get() = width >= MIN_SIDE && height >= MIN_SIDE

    /** Clamped into the canvas, and refused if it has collapsed. */
    fun sanitised(): GraphFrame? = GraphFrame(
        left = left.coerceIn(0f, 1f),
        top = top.coerceIn(0f, 1f),
        right = right.coerceIn(0f, 1f),
        bottom = bottom.coerceIn(0f, 1f)
    ).takeIf { it.isUsable }

    companion object {
        /** Below this a frame is not a graph, it is a line. Matches `GraphPlacement.MIN_SIZE`. */
        const val MIN_SIDE = 0.1f

        /**
         * [fraction] of the way from [a] to [b], edge by edge.
         *
         * Interpolating the edges rather than the size keeps a frame anchored where it was put: a
         * frame pinned to the bottom of the shot at two people and to the bottom at four stays
         * against the bottom at three, instead of drifting up as it grows.
         */
        fun between(a: GraphFrame, b: GraphFrame, fraction: Float): GraphFrame {
            val f = fraction.coerceIn(0f, 1f)
            fun mix(x: Float, y: Float) = x + (y - x) * f
            return GraphFrame(
                left = mix(a.left, b.left),
                top = mix(a.top, b.top),
                right = mix(a.right, b.right),
                bottom = mix(a.bottom, b.bottom)
            )
        }
    }
}

data class ExportPreset(
    val version: Int = CURRENT_VERSION,
    val name: String = "",

    // Canvas
    val width: Int = 1920,
    val height: Int = 1080,
    val lockAspectRatio: Boolean = true,

    // Graph
    val showLabels: Boolean = true,
    val labelsColor: Int = inga.bpmetrics.core.BpmPalette.ON_SURFACE,
    val showGrid: Boolean = true,
    val gridColor: Int = inga.bpmetrics.core.BpmPalette.GRID,
    /**
     * The ends of the ramp a curve is drawn along.
     *
     * Kept on the preset because they are part of a saved look and someone may want a different
     * one — but the *defaults* are the app's own metric colours, so a new preset matches the charts
     * it was made from. A colour meaning "peak" has to mean peak in the picture as well as on the
     * screen. See `ui/theme/Color.kt`.
     */
    val lowBpmColor: Int = inga.bpmetrics.core.BpmPalette.LOW,
    val highBpmColor: Int = inga.bpmetrics.core.BpmPalette.HIGH,
    val showTitle: Boolean = true,
    val showCurrentStats: Boolean = true,
    val futureOpacity: Float = 0.65f,

    // Background and overlay
    val backgroundOpacity: Int = 100,
    /**
     * Where the graph sits on the canvas, proportionally, so it survives an aspect change.
     *
     * A centred band across the lower part of the frame rather than the whole of it. A graph
     * covering the entire video hides the thing it is annotating — the footage is the subject and
     * the curve is the caption. Inset from all four edges on purpose: a default flush to the sides
     * leaves its resize handles on the boundary of the preview, where they cannot be grabbed.
     */
    val graphLeft: Float = 0.25f,
    val graphTop: Float = 0.58f,
    val graphRight: Float = 0.75f,
    val graphBottom: Float = 0.91f,

    /**
     * The frame to use when a clip has a particular number of people on it.
     *
     * A look does not change with the cast; a *frame* does. The renderer already divides the
     * readout column by the number of wearers — see `drawMultiStatsHUD` — so four people in a
     * frame drawn for two squeeze to the minimum pill height and crowd the curves, and one person
     * in that same frame leaves half of it empty. The four fractions above are the only part of a
     * preset that has ever needed to know how many people are in shot.
     *
     * Which is why this is a map on one preset rather than a preset per count. A 1-, 2- and
     * 4-person preset would be identical in colour, canvas, bitrate, window and title, differing
     * in four floats — four copies of one look, and changing the ramp means changing it four times
     * or letting them drift. Bulk export is exactly where that drift is invisible: six videos
     * render and two of them carry a stale look.
     *
     * Empty by default, and an empty map means the behaviour this had before: the frame above is
     * used for everything. See [frameFor] for how a count nobody pinned is answered.
     *
     * Deliberately **without** a version bump. The guard exists so a preset from a newer build
     * refuses to half-apply, and this field cannot half-apply: an older build's Gson drops a field
     * it does not know, leaving a preset that renders exactly as it did before. Bumping would make
     * an older build reject a preset it can read perfectly well, which is a worse answer than
     * ignoring one map.
     */
    val framesByLaneCount: Map<Int, GraphFrame> = emptyMap(),

    // Video
    val windowSizeMs: Long = 30_000L,
    val frameRate: Int = 30,
    /** Take the frame rate from each clip instead of [frameRate]. */
    val matchSourceFrameRate: Boolean = false,
    /**
     * How far to nudge the curves against the footage, in milliseconds.
     *
     * The escape hatch for what automatic correction cannot settle: a phone that stamps its videos
     * in local time, a watch whose clock had drifted. Both show up as a constant shift, and a
     * constant shift is what this cancels. Part of a preset because it is usually a property of the
     * phone that filmed, not of one clip.
     */
    val syncOffsetMs: Long = 0L,

    // "timeZoneId" lived here until S7. A time zone is where something happened, which is a
    // location's business, not a look's: each preset froze the phone's zone on the day it was made,
    // and a set in Kansas came out in Pacific time. Exports read their clock from
    // [inga.bpmetrics.library.exportZoneOf]. Older preset JSON still carries the key; Gson skips it.

    // Live readouts
    /**
     * What each wearer's pill carries, and how large it is.
     *
     * A preset rather than a fixed design because the right answer genuinely changes with the
     * export. A story clip of two people wants faces and names and room to read them; a landscape
     * shot of six wants the numbers and nothing else, because six named pills is a third of the
     * frame. Both of those were the *same* argument being had every time the pill was touched, and
     * a setting is what ends it.
     */
    val pillShowPhoto: Boolean = true,
    val pillShowName: Boolean = true,
    /**
     * Whether the reading takes the wearer's colour.
     *
     * On, because the number is the largest thing on the pill and colouring it is the strongest
     * available tie back to the curve it belongs to. Off leaves it in the label colour, which reads
     * more calmly when several pills sit close together.
     */
    val pillBpmInPersonColor: Boolean = true,
    /**
     * Overall pill size, as a multiplier on what the renderer works out for the graph.
     *
     * The renderer already sizes pills to the space and the number of wearers; this scales that
     * result rather than replacing it, so a preset cannot produce pills that do not fit.
     */
    val pillScale: Float = 1f,
    /** The face's size within the pill, as a multiplier. Independent of the text beside it. */
    val pillPhotoScale: Float = 1f,
    /** The reading's size within the pill, as a multiplier. */
    val pillBpmScale: Float = 1f,
    /** The name's size within the pill, as a multiplier. */
    val pillNameScale: Float = 1f,
    /**
     * Where the readouts sit. The clock takes the opposite side of the same edge.
     *
     * Right by default, over the faded half of the graph.
     *
     * The playhead is centred and everything past it is drawn at `futureOpacity`, so the right of
     * the plot is already deliberately quiet — which makes it the cheapest place on the frame to
     * put something opaque. The solid, full-strength past stays clear.
     */
    val pillCorner: PillCorner = PillCorner.TOP_RIGHT,

    /**
     * Whether the graph says when it is, and how.
     *
     * A setting, and one that belongs with the other time settings. It used to appear only on
     * multi-wearer exports — not by decision, but because it was written inside the multi-wearer
     * HUD — so a solo export, the commonest kind there is, carried no time at all.
     */
    val clockMode: ClockMode = ClockMode.CLOCK,

    // Identity
    /**
     * Whether to sign the export.
     *
     * Off, and staying off through development: an unfinished app putting its name on someone's
     * footage is asking them to publish a version of the mark that is about to change. It exists
     * now so the renderers, the preset editor and the preview all learn about it together, rather
     * than being retrofitted later against three surfaces that have moved on.
     */
    val showWordmark: Boolean = false,
    val wordmarkCorner: WordmarkCorner = WordmarkCorner.BOTTOM_RIGHT,
    /**
     * How present the mark is, 0..100.
     *
     * Defaulted well below full. A credit, not a watermark — the footage and the curve are the
     * subject, and a mark competing with them is one someone will crop out.
     */
    val wordmarkOpacity: Int = 55
) {
    fun toJson(): String = BpmGson.instance.toJson(this)

    /** The framing this preset carries, replaced. Framing is part of a look, not of a clip. */
    fun withFraming(left: Float, top: Float, right: Float, bottom: Float): ExportPreset =
        copy(graphLeft = left, graphTop = top, graphRight = right, graphBottom = bottom)

    /**
     * Whether this preset's framing is one that shipped as a default and has since been replaced.
     *
     * A preset is a stored copy, so changing a Kotlin default does nothing for a row already
     * written — an install carrying presets seeded under an older default keeps drawing the graph
     * where that build put it, and no amount of dragging fixes the *next* export because the preset
     * reapplies itself. This identifies exactly those rows: framing nobody chose, only inherited.
     * Anything dragged even slightly off one of these values is a deliberate choice and is left be.
     */
    fun hasSupersededFraming(): Boolean = SUPERSEDED_FRAMINGS.any { (l, t, r, b) ->
        val close = { a: Float, x: Float -> kotlin.math.abs(a - x) < 0.005f }
        close(graphLeft, l) && close(graphTop, t) && close(graphRight, r) && close(graphBottom, b)
    }

    /** This preset with the framing the current build ships. */
    fun withDefaultFraming(): ExportPreset = ExportPreset().let {
        withFraming(it.graphLeft, it.graphTop, it.graphRight, it.graphBottom)
    }

    /** The frame this preset was drawn with before any of this existed. */
    val baseFrame: GraphFrame
        get() = GraphFrame(graphLeft, graphTop, graphRight, graphBottom)

    /**
     * The frame to draw a clip with [laneCount] people on it.
     *
     * The rule, in order:
     *
     * 1. **A frame pinned for exactly this many people** wins. That is the whole point — you looked
     *    at four people in a frame and said "this one".
     * 2. **Between two pins, interpolate.** Three people, with two and four pinned, lands halfway.
     *    A frame that grew between the counts you cared about is far likelier to be right than the
     *    two-person one applied whole.
     * 3. **Outside the pins, hold the nearest.** Deliberately *not* extrapolated. Growing a frame
     *    past the last pin means inventing a rate of growth, and a made-up rate applied to seven
     *    people produces a rectangle nobody has ever looked at. Holding the nearest pin gives the
     *    frame you approved for the closest cast you have actually framed.
     * 4. **No pins at all** gives [baseFrame] — exactly what every preset did before this existed,
     *    which is what keeps existing presets rendering identically.
     *
     * [laneCount] is how many curves go on the clip, which is what the readout column is divided
     * by. **Zero means "no clip in hand"** and answers [baseFrame] — that is the preset editor in
     * Settings, which is framing the look itself rather than any particular shot. Without that, the
     * editor would display the frame pinned nearest to one person while dragging wrote to the base,
     * which is two different rectangles wearing one control.
     */
    fun frameFor(laneCount: Int): GraphFrame {
        if (laneCount <= 0) return baseFrame

        val pins = framesByLaneCount.mapNotNull { (count, frame) ->
            frame.sanitised()?.let { count to it }
        }.toMap()
        if (pins.isEmpty()) return baseFrame

        val n = laneCount
        pins[n]?.let { return it }

        val below = pins.keys.filter { it < n }.maxOrNull()
        val above = pins.keys.filter { it > n }.minOrNull()

        return when {
            below != null && above != null -> GraphFrame.between(
                pins.getValue(below),
                pins.getValue(above),
                (n - below).toFloat() / (above - below)
            )
            below != null -> pins.getValue(below)
            above != null -> pins.getValue(above)
            else -> baseFrame
        }
    }

    /**
     * Repairs anything deserialization could have left impossible.
     *
     * Gson builds objects without running the constructor, so a field absent from the payload is
     * left at the JVM's zero rather than at the Kotlin default. A preset saved before a field
     * existed therefore comes back with `0`, `false`, or — worse — a `null` sitting in a `String`
     * the type system swears cannot be null. Every caller downstream is entitled to trust the type,
     * so the repair belongs here, at the one door values enter through.
     *
     * Only states nothing could have chosen are corrected. Fields where "absent" and "deliberately
     * zero" look identical (the opacities) are left as they arrive; they have
     * existed since the first version, so absent is not a case that occurs.
     */
    @Suppress("USELESS_ELVIS")
    fun sanitised(): ExportPreset {
        val shipped = ExportPreset()
        // A rectangle with no area draws nothing, which is how an older preset with no framing
        // stored would silently render an empty graph.
        val degenerate = (graphRight - graphLeft) < 0.01f || (graphBottom - graphTop) < 0.01f

        return copy(
            name = name ?: "",
            width = width.takeIf { it > 0 } ?: shipped.width,
            height = height.takeIf { it > 0 } ?: shipped.height,
            frameRate = frameRate.takeIf { it in 1..240 } ?: shipped.frameRate,
            windowSizeMs = windowSizeMs.takeIf { it > 0L } ?: shipped.windowSizeMs,
            backgroundOpacity = backgroundOpacity.coerceIn(0, 100),
            futureOpacity = futureOpacity.coerceIn(0f, 1f),
            graphLeft = if (degenerate) shipped.graphLeft else graphLeft.coerceIn(0f, 1f),
            graphTop = if (degenerate) shipped.graphTop else graphTop.coerceIn(0f, 1f),
            graphRight = if (degenerate) shipped.graphRight else graphRight.coerceIn(0f, 1f),
            graphBottom = if (degenerate) shipped.graphBottom else graphBottom.coerceIn(0f, 1f),
            // A collapsed pin is dropped rather than repaired. There is no sensible repair — the
            // frame is whatever somebody dragged — and a pin that draws nothing is worse than an
            // absent one, which at least falls back to a frame that works.
            framesByLaneCount = framesByLaneCount
                .filterKeys { it >= 1 }
                .mapNotNull { (count, frame) -> frame.sanitised()?.let { count to it } }
                .toMap(),
            // Gson leaves an absent enum as null, and the type says it cannot be — every preset
            // written before the wordmark existed arrives that way.
            wordmarkCorner = wordmarkCorner ?: shipped.wordmarkCorner,
            wordmarkOpacity = wordmarkOpacity.coerceIn(0, 100),
            pillCorner = pillCorner ?: shipped.pillCorner,
            clockMode = clockMode ?: shipped.clockMode,
            // A scale of zero is not a small pill, it is an absent one — and zero is exactly what a
            // preset written before these existed would arrive with if Gson ever stops finding the
            // no-arg constructor. Bounded rather than defaulted, because a deliberate 0.6 and an
            // absent 0 both need to end up somewhere sane.
            pillScale = pillScale.takeIf { it > 0.05f }?.coerceIn(0.5f, 2f) ?: shipped.pillScale,
            pillPhotoScale = pillPhotoScale.takeIf { it > 0.05f }?.coerceIn(0.5f, 2f)
                ?: shipped.pillPhotoScale,
            pillBpmScale = pillBpmScale.takeIf { it > 0.05f }?.coerceIn(0.5f, 2f)
                ?: shipped.pillBpmScale,
            pillNameScale = pillNameScale.takeIf { it > 0.05f }?.coerceIn(0.5f, 2f)
                ?: shipped.pillNameScale
        )
    }

    companion object {
        /**
         * Bumped when a field is added or its meaning changes.
         *
         * A preset file from a future build is rejected with a message rather than half-applied —
         * silently ignoring fields it does not understand would produce an export that looks
         * nothing like the one it was shared from.
         *
         * 4 adds the live readout settings: what a pill carries and how large it is.
         * 3 adds the wordmark.
         * 2 covers graph framing, the sync offset, match-source frame rate, and the removal of the
         * axes toggle.
         *
         * Files written at 1 still load: the check is one-directional, so raising this only stops
         * *older* builds reading what they cannot render.
         */
        const val CURRENT_VERSION = 4

        /**
         * Graph framings that shipped as the default in some earlier build.
         *
         * Kept as data rather than fixed by a migration because presets are stored as JSON blobs:
         * SQL cannot reach inside one, and rewriting the blob in a migration would duplicate what
         * the Kotlin defaults already say. Append to this whenever the default framing changes;
         * dropping an entry only means those users keep a framing they never picked.
         *
         * `0, 0, 1, 1` is here as the full-frame sentinel a config starts life with.
         */
        val SUPERSEDED_FRAMINGS: List<List<Float>> = listOf(
            listOf(0f, 0f, 1f, 1f),
            listOf(0.04f, 0.62f, 0.96f, 0.97f),
            listOf(0.14f, 0.58f, 0.86f, 0.90f)
        )

        /**
         * Reads a preset back.
         *
         * @return the preset, or null when the payload is malformed or written by a newer build.
         *   Never a partly-applied one: an export that looks nothing like the one it was shared
         *   from is worse than a refusal that says why.
         */
        fun fromJson(json: String): ExportPreset? = runCatching {
            BpmGson.instance.fromJson(json, ExportPreset::class.java)
        }.getOrNull()
            ?.takeIf { it.version <= CURRENT_VERSION }
            // Every preset the app reads comes through here, so this is the one place a payload
            // missing fields can be brought back to something the rest of the code can trust.
            ?.sanitised()

        /**
         * The presets the app ships with.
         *
         * The first three are shapes rather than looks: landscape, story and square. What a preset
         * is mostly for is the canvas, and having to type 1080×1920 to post something is the
         * friction this removes.
         *
         * The last two are looks, and are the first presets here that differ in anything but size.
         * They exist because the shape presets all draw the same full panel in the same place, and
         * someone posting a clip has no starting point that is *quiet* — the whole apparatus of
         * grid, panel and header on top of footage that is the actual subject. Both are still
         * built from the app's own colours; what changes is how much of the frame they claim.
         */
        /**
         * Bumped whenever a preset is added to [BUILT_IN].
         *
         * An install that already has presets never runs the seed, so without this the two looks
         * added here would only ever reach a fresh install. Compared against what the install has
         * been offered, and offered exactly once — see `LibraryRepository.offerNewBuiltInPresets`.
         */
        const val BUILT_IN_REVISION = 2

        val BUILT_IN: List<ExportPreset> = listOf(
            ExportPreset(name = "Landscape 1080p", width = 1920, height = 1080),
            ExportPreset(name = "Story 9:16", width = 1080, height = 1920),
            ExportPreset(name = "Square 1:1", width = 1080, height = 1080),

            // A story with the graph as a caption rather than a panel. No background behind the
            // curve at all, no grid, and a band across the lower third — above where a platform
            // puts its own caption and buttons, which is why the framing is not simply centred.
            ExportPreset(
                name = "Story · minimal",
                width = 1080,
                height = 1920,
                backgroundOpacity = 0,
                showGrid = false,
                showTitle = false,
                graphLeft = 0.08f,
                graphTop = 0.60f,
                graphRight = 0.92f,
                graphBottom = 0.78f,
                // A shorter window than the 30s default. On a nine-second clip a half-minute
                // window is nearly flat; ten seconds gives the curve something to do.
                windowSizeMs = 10_000L
            ),

            // The landscape counterpart: a wide, low band along the bottom, panel kept but dimmed
            // so the curve reads over bright footage without boxing off a quarter of the frame.
            ExportPreset(
                name = "Landscape · lower third",
                width = 1920,
                height = 1080,
                backgroundOpacity = 35,
                showGrid = false,
                graphLeft = 0.06f,
                graphTop = 0.68f,
                graphRight = 0.94f,
                graphBottom = 0.93f,
                windowSizeMs = 20_000L
            )
        )
    }
}
