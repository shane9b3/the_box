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

    /**
     * abbrev → (base, accent), ARGB; 32 entries (§6). **TCC** = `https://teamcolorcodes.com/<slug>/`, read 2026-11-08
     * from each page's "HEX codes are …" sentence; the comment on each line is its slug. ESPN's own `color` /
     * `alternateColor` disagree with the teams' palettes in places, so they are not used.
     */
    internal val TABLE: Map<String, Pair<Int, Int>> = mapOf(
        "ARI" to (rgb(0x97233F) to rgb(0x000000)), // TCC arizona-cardinals-color-codes
        "ATL" to (rgb(0xA71930) to rgb(0x000000)), // TCC atlanta-falcons-color-codes
        "BAL" to (rgb(0x241773) to rgb(0x9E7C0C)), // TCC baltimore-ravens-color-codes (gold, not black: ruling 27)
        "BUF" to (rgb(0x00338D) to rgb(0xC60C30)), // TCC buffalo-bills-color-codes
        "CAR" to (rgb(0x0085CA) to rgb(0x101820)), // TCC carolina-panthers-color-codes (C1 exception)
        "CHI" to (rgb(0x0B162A) to rgb(0xC83803)), // TCC chicago-bears-color-codes
        "CIN" to (rgb(0xFB4F14) to rgb(0x000000)), // TCC cincinnati-bengals-color-codes (C1 exception)
        "CLE" to (rgb(0x311D00) to rgb(0xFF3C00)), // TCC cleveland-browns-color-codes
        "DAL" to (rgb(0x041E42) to rgb(0x869397)), // TCC dallas-cowboys-color-codes (navy + silver: ruling 27)
        "DEN" to (rgb(0xFB4F14) to rgb(0x002244)), // TCC denver-broncos-color-codes (C1 exception)
        "DET" to (rgb(0x0076B6) to rgb(0xB0B7BC)), // TCC detroit-lions-color-codes
        "GB" to (rgb(0x203731) to rgb(0xFFB612)), // TCC green-bay-packers-color-codes
        "HOU" to (rgb(0x03202F) to rgb(0xA71930)), // TCC houston-texans-color-codes
        "IND" to (rgb(0x002C5F) to rgb(0xA2AAAD)), // TCC indianapolis-colts-color-codes
        "JAX" to (rgb(0x006778) to rgb(0xD7A22A)), // TCC jacksonville-jaguars-color-codes (teal primary: ruling 27)
        "KC" to (rgb(0xE31837) to rgb(0xFFB81C)), // TCC kansas-city-chiefs-color-codes
        "LAC" to (rgb(0x0080C6) to rgb(0xFFC20E)), // TCC los-angeles-chargers-color-codes (C1 exception)
        "LAR" to (rgb(0x003594) to rgb(0xFFA300)), // TCC los-angeles-rams-team-colors
        "LV" to (rgb(0x000000) to rgb(0xA5ACAF)), // TCC oakland-raiders-color-codes (titled Las Vegas)
        "MIA" to (rgb(0x008E97) to rgb(0xFC4C02)), // TCC miami-dolphins-color-codes (C1 exception)
        "MIN" to (rgb(0x4F2683) to rgb(0xFFC62F)), // TCC minnesota-vikings-color-codes
        "NE" to (rgb(0x002244) to rgb(0xC60C30)), // TCC new-england-patriots-color-codes
        "NO" to (rgb(0x101820) to rgb(0xD3BC8D)), // TCC new-orleans-saints-color-codes (old-gold primary → accent)
        "NYG" to (rgb(0x0B2265) to rgb(0xA71930)), // TCC new-york-giants-color-codes
        "NYJ" to (rgb(0x125740) to rgb(0x000000)), // TCC new-york-jets-color-codes
        "PHI" to (rgb(0x004C54) to rgb(0xA5ACAF)), // TCC philadelphia-eagles-color-codes (midnight green + silver)
        "PIT" to (rgb(0x101820) to rgb(0xFFB612)), // TCC pittsburgh-steelers-color-codes (gold primary → accent)
        "SEA" to (rgb(0x002244) to rgb(0x69BE28)), // TCC seattle-seahawks-color-codes
        "SF" to (rgb(0xAA0000) to rgb(0xB3995D)), // TCC san-francisco-49ers-team-colors
        "TB" to (rgb(0xD50A0A) to rgb(0x34302B)), // TCC tampa-bay-buccaneers-team-colors (pewter: ruling 27)
        "TEN" to (rgb(0x0C2340) to rgb(0x4B92DB)), // TCC tennessee-titans-color-codes
        "WSH" to (rgb(0x5A1414) to rgb(0xFFB612)), // TCC washington-redskins-color-codes (titled Commanders)
    )

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
