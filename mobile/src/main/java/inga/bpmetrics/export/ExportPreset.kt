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
    val labelsColor: Int = 0xFFFFFFFF.toInt(),
    val showGrid: Boolean = true,
    val gridColor: Int = 0x33CCCCCC,
    val lowBpmColor: Int = 0xFF42A5F5.toInt(),
    val highBpmColor: Int = 0xFFF44336.toInt(),
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
    val timeZoneId: String = java.time.ZoneId.systemDefault().id
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
            graphBottom = if (degenerate) shipped.graphBottom else graphBottom.coerceIn(0f, 1f)
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
         * 2 covers graph framing, the sync offset and match-source frame rate, and the removal of
         * the axes toggle. Files written at 1 still load: the check is one-directional, so raising
         * this only stops *older* builds reading what they cannot render.
         */
        const val CURRENT_VERSION = 2

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
         * Three shapes rather than three looks: landscape, story and square. What a preset is
         * mostly for is the canvas, and having to type 1080×1920 to post something is the friction
         * this removes.
         */
        val BUILT_IN: List<ExportPreset> = listOf(
            ExportPreset(name = "Landscape 1080p", width = 1920, height = 1080),
            ExportPreset(name = "Story 9:16", width = 1080, height = 1920),
            ExportPreset(name = "Square 1:1", width = 1080, height = 1080)
        )
    }
}
