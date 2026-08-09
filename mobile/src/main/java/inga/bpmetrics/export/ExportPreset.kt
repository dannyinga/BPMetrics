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
data class ExportPreset(
    val version: Int = CURRENT_VERSION,
    val name: String = "",

    // Canvas
    val width: Int = 1920,
    val height: Int = 1080,
    val lockAspectRatio: Boolean = true,

    // Graph
    val showLabels: Boolean = true,
    val labelsColor: Int = inga.bpmetrics.ui.theme.BpmPalette.ON_SURFACE,
    val showGrid: Boolean = true,
    val gridColor: Int = inga.bpmetrics.ui.theme.BpmPalette.GRID,
    /**
     * The ends of the ramp a curve is drawn along.
     *
     * Kept on the preset because they are part of a saved look and someone may want a different
     * one — but the *defaults* are the app's own metric colours, so a new preset matches the charts
     * it was made from. A colour meaning "peak" has to mean peak in the picture as well as on the
     * screen. See `ui/theme/Color.kt`.
     */
    val lowBpmColor: Int = inga.bpmetrics.ui.theme.BpmPalette.LOW,
    val highBpmColor: Int = inga.bpmetrics.ui.theme.BpmPalette.HIGH,
    val showTitle: Boolean = true,
    val showCurrentStats: Boolean = true,
    val headerXPercent: Float = 0.85f,
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

    // Video
    val windowSizeMs: Long = 30_000L,
    val frameRate: Int = 30,
    /** Take the frame rate from each clip instead of [frameRate]. */
    val matchSourceFrameRate: Boolean = false,
    val overlayBitRate: Int = 8_000_000,
    val regularBitRate: Int = 2_500_000,
    /**
     * How far to nudge the curves against the footage, in milliseconds.
     *
     * The escape hatch for what automatic correction cannot settle: a phone that stamps its videos
     * in local time, a watch whose clock had drifted. Both show up as a constant shift, and a
     * constant shift is what this cancels. Part of a preset because it is usually a property of the
     * phone that filmed, not of one clip.
     */
    val syncOffsetMs: Long = 0L,
    val timeZoneId: String = java.time.ZoneId.systemDefault().id,

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
     * zero" look identical — the opacities, `headerXPercent` — are left as they arrive; they have
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
            timeZoneId = timeZoneId?.takeIf { it.isNotBlank() } ?: shipped.timeZoneId,
            width = width.takeIf { it > 0 } ?: shipped.width,
            height = height.takeIf { it > 0 } ?: shipped.height,
            frameRate = frameRate.takeIf { it in 1..240 } ?: shipped.frameRate,
            windowSizeMs = windowSizeMs.takeIf { it > 0L } ?: shipped.windowSizeMs,
            overlayBitRate = overlayBitRate.takeIf { it > 0 } ?: shipped.overlayBitRate,
            regularBitRate = regularBitRate.takeIf { it > 0 } ?: shipped.regularBitRate,
            backgroundOpacity = backgroundOpacity.coerceIn(0, 100),
            futureOpacity = futureOpacity.coerceIn(0f, 1f),
            headerXPercent = headerXPercent.coerceIn(0f, 1f),
            graphLeft = if (degenerate) shipped.graphLeft else graphLeft.coerceIn(0f, 1f),
            graphTop = if (degenerate) shipped.graphTop else graphTop.coerceIn(0f, 1f),
            graphRight = if (degenerate) shipped.graphRight else graphRight.coerceIn(0f, 1f),
            graphBottom = if (degenerate) shipped.graphBottom else graphBottom.coerceIn(0f, 1f),
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

    /**
     * Applies this preset over a config, leaving everything content-specific alone.
     *
     * The config passed in supplies the recordings, the time range, the overlay video and the
     * colours; the preset supplies only how it looks. Written as an overlay rather than a
     * construction so a caller cannot accidentally lose the content half.
     */
    fun applyTo(config: VideoExporter.VideoExportConfig): VideoExporter.VideoExportConfig =
        config.copy(
            imageConfig = config.imageConfig.copy(
                width = width,
                height = height,
                backgroundOpacity = backgroundOpacity,
                showLabels = showLabels,
                labelsColor = labelsColor,
                showGrid = showGrid,
                gridColor = gridColor,
                lowBpmColor = lowBpmColor,
                highBpmColor = highBpmColor,
                showTitle = showTitle,
                showCurrentStats = showCurrentStats,
                headerXPercent = headerXPercent,
                futureOpacity = futureOpacity,
                timeZoneId = timeZoneId,
                pillShowPhoto = pillShowPhoto,
                pillShowName = pillShowName,
                pillBpmInPersonColor = pillBpmInPersonColor,
                pillScale = pillScale,
                pillPhotoScale = pillPhotoScale,
                pillBpmScale = pillBpmScale,
                pillNameScale = pillNameScale,
                pillCorner = pillCorner,
                clockMode = clockMode,
                showWordmark = showWordmark,
                wordmarkCorner = wordmarkCorner,
                wordmarkOpacity = wordmarkOpacity,
                // Always clock-aligned. A recording happened at a particular time, and placing it
                // at an arbitrary point in a video was never right — it only ever looked right when
                // the recording happened to start when filming did.
                alignByElapsedTime = false
                // startTimeMs, endTimeMs, customRecordColors and graphTitle are untouched on
                // purpose. They describe this export, not how exports look.
            ),
            windowSizeMs = windowSizeMs,
            frameRate = frameRate,
            matchSourceFrameRate = matchSourceFrameRate,
            overlayBitRate = overlayBitRate,
            regularBitRate = regularBitRate,
            lockAspectRatio = lockAspectRatio,
            syncOffsetMs = syncOffsetMs,
            graphRect = android.graphics.RectF(graphLeft, graphTop, graphRight, graphBottom)
            // overlayVideoUri and records are content, and stay as the caller set them.
        )

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

        /** Captures the appearance of a config, and nothing else. */
        fun from(config: VideoExporter.VideoExportConfig, name: String = ""): ExportPreset {
            val image = config.imageConfig
            return ExportPreset(
                name = name,
                width = image.width,
                height = image.height,
                lockAspectRatio = config.lockAspectRatio,
                showLabels = image.showLabels,
                labelsColor = image.labelsColor,
                showGrid = image.showGrid,
                gridColor = image.gridColor,
                lowBpmColor = image.lowBpmColor,
                highBpmColor = image.highBpmColor,
                showTitle = image.showTitle,
                showCurrentStats = image.showCurrentStats,
                headerXPercent = image.headerXPercent,
                futureOpacity = image.futureOpacity,
                backgroundOpacity = image.backgroundOpacity,
                graphLeft = config.graphRect.left,
                graphTop = config.graphRect.top,
                graphRight = config.graphRect.right,
                graphBottom = config.graphRect.bottom,
                windowSizeMs = config.windowSizeMs,
                frameRate = config.frameRate,
                matchSourceFrameRate = config.matchSourceFrameRate,
                overlayBitRate = config.overlayBitRate,
                regularBitRate = config.regularBitRate,
                syncOffsetMs = config.syncOffsetMs,
                timeZoneId = image.timeZoneId
            )
        }

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
