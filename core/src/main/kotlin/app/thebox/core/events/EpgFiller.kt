// Stub (Event channels commit 0): docs/design/m6-event-channels.md §6.1, §4.1. WP-1 owns the body.
package app.thebox.core.events

/**
 * §6.1: a provider placeholder listing ("No Event Today", "Signing Off", "Next Event: …", "Upcoming …"). The title is
 * the only signal. The guide clips placeholders around an event, search drops them, and the matcher never counts them as
 * guide evidence.
 */
object EpgFiller {
    /** True when [title] (folded inside) is a placeholder. The stub says no, so every listing stays real. */
    @Suppress("UNUSED_PARAMETER")
    fun isFiller(title: String): Boolean = false
}
