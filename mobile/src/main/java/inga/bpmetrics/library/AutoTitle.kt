package inga.bpmetrics.library

/**
 * Tells a title the app made up from one somebody wrote.
 *
 * Auto-naming titles a recording "<prefix> <n>": "Untitled 3" when it arrives, or "Spiderman 5"
 * when it is tagged from the naming category. Re-naming on a later tag is only right while the
 * title is still one of those. Before this check, tagging a recording called "Mom's birthday"
 * with a character turned it into "Hulk 12", and nothing could bring the old title back.
 *
 * Nothing in the database records which titles were generated, so this reads the title itself.
 * It only counts a "<prefix> <n>" title as generated when the prefix is one the app actually names
 * with. A looser rule like "anything ending in a number" would also claim titles such as
 * "Basket Case 1" or "KC to PHX 2", which in a real library were typed by hand.
 */
object AutoTitle {

    /** What a recording is called before anything names it. */
    const val UNTITLED = "Untitled"

    private val numbered = Regex("""^(.+) (\d+)$""")

    /**
     * Whether [title] is blank or was generated from one of [prefixes] ("Untitled" is always one).
     * Prefixes are matched ignoring case, since tag names can be re-cased after naming.
     */
    fun isGenerated(title: String?, prefixes: Collection<String>): Boolean {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isEmpty()) return true
        val prefix = numbered.matchEntire(trimmed)?.groupValues?.get(1) ?: return false
        return prefix.equals(UNTITLED, ignoreCase = true) ||
            prefixes.any { it.trim().equals(prefix, ignoreCase = true) }
    }
}
