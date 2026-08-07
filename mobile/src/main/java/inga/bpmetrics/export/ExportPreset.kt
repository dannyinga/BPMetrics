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
    val showAxes: Boolean = true,
    val axesColor: Int = 0xFFCCCCCC.toInt(),
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
    /** Where the graph sits on the canvas, proportionally, so it survives an aspect change. */
    val graphLeft: Float = 0f,
    val graphTop: Float = 0f,
    val graphRight: Float = 1f,
    val graphBottom: Float = 1f,

    // Video
    val windowSizeMs: Long = 30_000L,
    val frameRate: Int = 30,
    val overlayBitRate: Int = 8_000_000,
    val regularBitRate: Int = 2_500_000,
    val alignByElapsedTime: Boolean = true,
    val timeZoneId: String = java.time.ZoneId.systemDefault().id
) {
    fun toJson(): String = BpmGson.instance.toJson(this)

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
                showAxes = showAxes,
                axesColor = axesColor,
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
                alignByElapsedTime = alignByElapsedTime
                // startTimeMs, endTimeMs, customRecordColors and graphTitle are untouched on
                // purpose. They describe this export, not how exports look.
            ),
            windowSizeMs = windowSizeMs,
            frameRate = frameRate,
            overlayBitRate = overlayBitRate,
            regularBitRate = regularBitRate,
            lockAspectRatio = lockAspectRatio,
            graphRect = android.graphics.RectF(graphLeft, graphTop, graphRight, graphBottom)
            // overlayVideoUri, syncOffsetMs and records likewise.
        )

    companion object {
        /**
         * Bumped when a field is added or its meaning changes.
         *
         * A preset file from a future build is rejected with a message rather than half-applied —
         * silently ignoring fields it does not understand would produce an export that looks
         * nothing like the one it was shared from.
         */
        const val CURRENT_VERSION = 1

        /** Captures the appearance of a config, and nothing else. */
        fun from(config: VideoExporter.VideoExportConfig, name: String = ""): ExportPreset {
            val image = config.imageConfig
            return ExportPreset(
                name = name,
                width = image.width,
                height = image.height,
                lockAspectRatio = config.lockAspectRatio,
                showAxes = image.showAxes,
                axesColor = image.axesColor,
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
                overlayBitRate = config.overlayBitRate,
                regularBitRate = config.regularBitRate,
                alignByElapsedTime = image.alignByElapsedTime,
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
        }.getOrNull()?.takeIf { it.version <= CURRENT_VERSION }

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
