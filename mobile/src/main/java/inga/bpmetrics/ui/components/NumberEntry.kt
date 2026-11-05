package inga.bpmetrics.ui.components

/**
 * What a typed number becomes once someone has finished typing it.
 *
 * Number fields used to apply and clamp on every keystroke, and redraw what they applied. Typing
 * 65 over 60 went "6", clamped to 30, shown as "30", and the next digit made it 300, clamped again
 * (Audit #8). A field holds what is typed as text and asks this only when the typing is done, on
 * Done or when focus leaves. When the number had to change, [note] says so under the field rather
 * than the field quietly showing something else.
 */
object NumberEntry {

    data class Result(val value: Int, val note: String?)

    fun commit(text: String, current: Int, range: IntRange): Result {
        val typed = text.trim().toIntOrNull()
            ?: return Result(current, if (text.isBlank()) "Kept $current. It needs a number." else null)
        return when {
            typed < range.first -> Result(range.first, "Set to ${range.first}, the least it can be.")
            typed > range.last -> Result(range.last, "Set to ${range.last}, the most it can be.")
            else -> Result(typed, null)
        }
    }
}
