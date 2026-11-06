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

/** The corner of the readout column that stays put, which is also the way it grows. */
enum class ReadoutPin {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

    val onLeft: Boolean get() = this == TOP_LEFT || this == BOTTOM_LEFT
    val onTop: Boolean get() = this == TOP_LEFT || this == TOP_RIGHT

    companion object {
        /**
         * The pin for a column whose middle is at ([centreX], [centreY]) on the canvas: the corner
         * of the canvas it is nearest. A column near the bottom grows up and one near the right
         * grows left, so adding people never pushes it off the frame it was placed against.
         */
        fun nearest(centreX: Float, centreY: Float): ReadoutPin = when {
            centreY < 0.5f && centreX < 0.5f -> TOP_LEFT
            centreY < 0.5f -> TOP_RIGHT
            centreX < 0.5f -> BOTTOM_LEFT
            else -> BOTTOM_RIGHT
        }
    }
}

/**
 * Where the readout column sits once it has been moved off the graph (S14C).
 *
 * A point and a corner rather than a rectangle. The pills are a fixed size, so what the column
 * covers depends on how many people are on the clip; what someone places is where it starts. The
 * [pin] corner of the column sits at ([x], [y]), in fractions of the canvas, and the column grows
 * away from it as people are added.
 *
 * Null wherever it is used means *on the graph*: the column sits inside the plot's top corner on
 * the preset's side, and moves with the graph.
 *
 * Every field has a default because Gson only runs the no-arg constructor Kotlin emits when all of
 * them do.
 */
data class ReadoutSpot(
    val x: Float = 0.94f,
    val y: Float = 0.06f,
    val pin: ReadoutPin = ReadoutPin.TOP_RIGHT
) {
    /** Clamped onto the canvas, with a pin Gson left null put back. */
    @Suppress("USELESS_ELVIS")
    fun sanitised(): ReadoutSpot = ReadoutSpot(
        x = x.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.94f,
        y = y.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.06f,
        pin = pin ?: ReadoutPin.TOP_RIGHT
    )

    companion object {
        /**
         * The spot for a column covering [left]..[right] × [top]..[bottom] of the canvas, pinned to
         * the canvas corner it is nearest. What a drag ends as: the column stays exactly where it
         * was dropped, and only how it would grow is decided.
         */
        fun at(left: Float, top: Float, right: Float, bottom: Float): ReadoutSpot {
            val pin = ReadoutPin.nearest((left + right) / 2f, (top + bottom) / 2f)
            return ReadoutSpot(
                x = if (pin.onLeft) left else right,
                y = if (pin.onTop) top else bottom,
                pin = pin
            ).sanitised()
        }

        /**
         * Part of the way from [a] to [b], for a frame between two pinned casts. The point moves;
         * the pin is the nearer one's, since a corner can't be part of the way to another.
         */
        fun between(a: ReadoutSpot?, b: ReadoutSpot?, fraction: Float): ReadoutSpot? {
            val f = fraction.coerceIn(0f, 1f)
            if (a == null || b == null) return if (f < 0.5f) a else b
            return ReadoutSpot(
                x = a.x + (b.x - a.x) * f,
                y = a.y + (b.y - a.y) * f,
                pin = if (f < 0.5f) a.pin else b.pin
            )
        }
    }
}
