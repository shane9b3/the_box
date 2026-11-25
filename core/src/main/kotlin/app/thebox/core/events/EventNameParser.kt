// Stub (Event channels commit 0): docs/design/m6-event-channels.md §3, §4.1. The signatures are frozen; WP-1 owns the
// bodies. Until then every name is `ParsedName.None`, so the app behaves exactly as before.
package app.thebox.core.events

/**
 * Reads an event from a provider channel name (§3). Pure and table-driven: plain string scanning, no `java.util.regex`
 * (Android's ICU regex differs from the JVM's). Never throws: any exception inside is [ParsedName.None]. Right-anchored:
 * it reads the tail first. The input is always the provider name (`channel.name`), never the custom or display name.
 */
object EventNameParser {
    /**
     * Keys the event index (§5.2) and feeds `ChannelNamePrefix.RULE_VERSION` (§5.3, re-review E8): bumping it rebuilds
     * both with no manual step.
     */
    const val VERSION: Int = 1

    /** The full result for [name] read at [ctx] (§3.2–§3.5). */
    @Suppress("UNUSED_PARAMETER")
    fun parse(name: String, ctx: EventParseContext): ParsedName = ParsedName.None

    /** The slot of an event or empty-slot name; null otherwise. No clock, no zone (§5.3). */
    @Suppress("UNUSED_PARAMETER")
    fun slotOf(name: String): String? = null

    /** True for an empty slot (§3.2 `empty`). No clock, no zone (§5.5). */
    @Suppress("UNUSED_PARAMETER")
    fun isEmptySlot(name: String): Boolean = false
}
