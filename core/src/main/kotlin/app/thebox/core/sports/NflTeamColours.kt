package app.thebox.core.sports

/**
 * (NFL, docs/design/m6-nfl.md §6; commit 0 stub, WP-1 fills [TABLE]) The NFL teams' own colours (rulings 12 and 27).
 * base = the primary, accent = the official secondary; `glow`, `pop` and C1–C3 from the shared [NhlTeamColours.derive],
 * untouched; then `tab` = base when C5 passes on it, else the accent (S1).
 */
object NflTeamColours {
    /** Trimmed and upper-cased; an unknown or null abbreviation gives [FALLBACK]. */
    fun of(abbrev: String?): TeamColours {
        val key = abbrev?.trim()?.uppercase() ?: return FALLBACK
        return RESOLVED[key] ?: FALLBACK
    }

    /** `#2A3140` / `#5CA8F2`, as the NHL. */
    val FALLBACK: TeamColours = resolve(rgb(0x2A3140), rgb(0x5CA8F2))

    /** abbrev → (base, accent), ARGB; 32 entries (§6). Commit 0: empty (WP-1). */
    internal val TABLE: Map<String, Pair<Int, Int>> = emptyMap()

    /** White word on base below 4.5 : 1, kept under ruling 12's "no substitute colours" (§6). */
    val C1_EXCEPTIONS: Set<String> = setOf("CIN", "DEN", "MIA", "CAR", "LAC")

    /** White tab text failing C5 on the tab colour: none (§6). */
    val C5_EXCEPTIONS: Set<String> = emptySet()

    private val RESOLVED: Map<String, TeamColours> = TABLE.mapValues { (_, c) -> resolve(c.first, c.second) }

    /** (S1) The shared derive, then the tab overridden to the base (or the accent when C5 fails on the base). */
    internal fun resolve(base: Int, accent: Int): TeamColours =
        NhlTeamColours.derive(base, accent).copy(tab = if (NhlTeamColours.c5(base) >= SportsConstants.PP_C5_MIN) base else accent)

    private fun rgb(rgb: Int): Int = (0xFF shl 24) or rgb
}
