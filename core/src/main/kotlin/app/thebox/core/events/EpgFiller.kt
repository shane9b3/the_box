// docs/design/m6-event-channels.md §6.1, §4.1 (WP-1).
package app.thebox.core.events

/**
 * §6.1: a provider placeholder listing ("No Event Today", "Signing Off", "Next Event: …", "Upcoming …"). The title is
 * the only signal. The guide clips placeholders around an event, search drops them, and the matcher never counts them as
 * guide evidence.
 */
object EpgFiller {
    /** Whole folded titles that are placeholders. */
    private val EXACT: Set<String> = setOf(
        "no event today", "no event", "no events", "no events today", "offline", "signing off", "sign off", "off air",
    )

    /**
     * True when [title] (folded inside: lower case, accents removed, whitespace runs collapsed, trailing "." / "!"
     * dropped) is a placeholder: one of the exact titles, or a title starting with "next event:" or with the word
     * "upcoming". Never throws.
     */
    fun isFiller(title: String): Boolean {
        if (title.isEmpty() || title.length > 400) return false
        val f = normalise(title)
        if (f in EXACT) return true
        if (f.startsWith("next event:")) return true
        if (f.startsWith("upcoming")) return f.length == 8 || !f[8].isLetterOrDigit()
        return false
    }

    private fun normalise(title: String): String {
        val folded = try { EventText.fold(title) } catch (e: Exception) { title.lowercase() }
        val sb = StringBuilder(folded.length)
        var space = false
        for (c in folded) {
            if (c == ' ' || c == '\t' || c == ' ' || c == '\n' || c == '\r') {
                space = sb.isNotEmpty()
            } else {
                if (space) sb.append(' ')
                space = false
                sb.append(c)
            }
        }
        while (sb.isNotEmpty() && (sb[sb.length - 1] == '.' || sb[sb.length - 1] == '!')) sb.setLength(sb.length - 1)
        return sb.toString()
    }
}
