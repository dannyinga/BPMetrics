package inga.bpmetrics.export

/**
 * What the graph's vertical scale covers.
 *
 * Gson stores an enum by name, so the order here is presentation only.
 */
enum class BpmScale(val label: String) {
    /**
     * The readings in the window on screen, eased as it moves.
     *
     * The default, because the window shows thirty seconds and a heart rarely moves more than a few
     * beats in thirty seconds: against the whole recording's range, every curve was a flat line
     * and the pills carried all the information (UX Audit O2).
     */
    ON_SCREEN("What's on screen"),

    /** The whole recording, for comparing absolute levels across a set. */
    RECORDING("Whole recording")
}

/**
 * What keeps the curves and text readable over footage.
 *
 * A panel does this on its own. Without one, a thin coloured line over sky or stage lights simply
 * disappears (UX Audit O5).
 */
enum class Contrast(val label: String) {
    /** A soft shadow when the panel is too faint to do the job, nothing when it isn't. */
    AUTO("Auto"),
    OFF("Off"),
    SHADOW("Soft shadow"),
    OUTLINE("Outline");

    /**
     * What to draw for a panel at [backgroundOpacity] percent.
     *
     * Below about a third, the panel stops separating the curve from the footage behind it.
     */
    fun resolvedFor(backgroundOpacity: Int): Contrast = when (this) {
        AUTO -> if (backgroundOpacity < AUTO_PANEL_THRESHOLD) SHADOW else OFF
        else -> this
    }

    companion object {
        /** The panel opacity, in percent, below which [AUTO] adds a shadow. */
        const val AUTO_PANEL_THRESHOLD = 30
    }
}
