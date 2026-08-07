package inga.bpmetrics.library

/**
 * The one place a person's colour is decided.
 *
 * Colour used to be worked out in three places that disagreed: the concurrent chart read a colour
 * stored against the *watch*, the exporter ignored that entirely and read a map the export dialog
 * threw away afterwards, and two hard-coded palettes had drifted apart. The same person could come
 * out one colour on screen and another in the video of the same session.
 *
 * Everything that needs a colour for a recording now asks [colorFor].
 */
object PersonColors {

    /**
     * Starting colours, picked to stay distinguishable side by side on a chart and legible as a
     * thin stripe or a curve against both light and dark backgrounds.
     *
     * Only a starting point — a person can be given any colour they like.
     */
    val PALETTE = listOf(
        0xFF00E5FF.toInt(), // Cyan
        0xFFFF5252.toInt(), // Coral red
        0xFF00E676.toInt(), // Emerald green
        0xFFE040FB.toInt(), // Purple
        0xFFFFD700.toInt(), // Amber gold
        0xFF2979FF.toInt(), // Electric blue
        0xFFFF9100.toInt(), // Orange
        0xFFFF4081.toInt()  // Pink
    )

    /** The colour a new profile starts with, cycling so consecutive additions look different. */
    fun defaultFor(index: Int): Int = PALETTE[index.mod(PALETTE.size)]

    /**
     * The colour for one recording: its person's, else a palette colour by position.
     *
     * [index] is the recording's position within whatever set is being drawn, which is what keeps
     * several unattributed recordings from all coming out the same colour on one chart.
     */
    fun colorFor(personId: Long?, people: Map<Long, PersonEntity>, index: Int): Int =
        personId?.let { people[it]?.colorArgb } ?: defaultFor(index)
}
