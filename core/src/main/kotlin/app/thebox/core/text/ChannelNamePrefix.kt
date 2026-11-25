// Contract (M3 commit 0): docs/design/m3-guide.md §2.9.2. Verbatim; changes go through the lead.
package app.thebox.core.text

sealed interface NamePrefixRule {
    data object Auto : NamePrefixRule
    data object Off : NamePrefixRule
    /** Literal prefixes (case-insensitive, longest first) or one regex (applied with lookingAt). Exactly one is set. */
    data class Custom(val literals: List<String> = emptyList(), val regex: String? = null) : NamePrefixRule
}
// (reviewed 2026-10-14, B7) No `parse` body in this contract file. WP-B adds, in ChannelNamePrefixImpl.kt,
// `object NamePrefixRules { fun parse(mode: String, customJson: String?): NamePrefixRule }`, specified in §3.12.2.

interface ChannelNamePrefix {
    /** The display name for [name] in group [groupName], or null when the rule leaves it unchanged. */
    fun strip(name: String, groupName: String?, rule: NamePrefixRule): String?

    companion object {
        /**
         * Bump when the Auto algorithm changes; the startup backfill recomputes every playlist below it.
         *
         * (Event channels commit 0, docs/design/m6-event-channels.md §5.3, re-review E8) Linked to the event parser: a
         * slot short name ("Sportsnet+ 09") comes first in `strip`, so a parser bump re-runs the backfill with no manual
         * step. Bump the `1` for an Auto change, [app.thebox.core.events.EventNameParser.VERSION] for a parser change.
         */
        const val RULE_VERSION = 1 + app.thebox.core.events.EventNameParser.VERSION
    }
}
