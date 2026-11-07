package app.thebox.core.sports

/**
 * (Goal animation, docs/design/m6-goal-animation.md §3.1) One team's takeover colours, ARGB ints.
 * [base]: the panel; [accent]: the lead and streak 1; [glow]: the halo; [pop]: the scorer's digit's start colour on the
 * glass (the painter only lerps it to white). [glow] and [pop] are derived from [base] and [accent], never tabled.
 */
data class TeamColours(val base: Int, val accent: Int, val glow: Int, val pop: Int)

/**
 * (Goal animation §3; WP-1) The NHL teams' own colours (owner ruling 12: base = the primary, accent = the official
 * secondary, no contrast substitutions). Commit 0 stub: WP-1 fills [TABLE] from §3.3 with its sources and implements
 * [of] and [FALLBACK].
 */
object NhlTeamColours {
    /** Trimmed and upper-cased; an unknown or null abbreviation gives [FALLBACK]. */
    @Suppress("UNUSED_PARAMETER")
    fun of(abbrev: String?): TeamColours = TODO("WP-1")

    /** `#2A3140` / `#5CA8F2` (`DesignTokens.Accent`, copied). */
    val FALLBACK: TeamColours get() = TODO("WP-1")

    /** abbrev → (base, accent), ARGB; 32 entries (§3.3). */
    internal val TABLE: Map<String, Pair<Int, Int>> get() = TODO("WP-1")

    /** C1 (white on base ≥ 4.5) exceptions: owner ruling 12. */
    internal val C1_EXCEPTIONS: Set<String> = setOf("PHI")
}
