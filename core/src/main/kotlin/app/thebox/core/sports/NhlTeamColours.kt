package app.thebox.core.sports

import kotlin.math.round

/**
 * (Goal animation, docs/design/m6-goal-animation.md §3.1) One team's takeover colours, ARGB ints.
 * [base]: the panel; [accent]: the lead and streak 1; [glow]: the halo; [pop]: the scorer's digit's start colour on the
 * glass (the painter only lerps it to white). [glow] and [pop] are derived from [base] and [accent], never tabled.
 */
data class TeamColours(
    val base: Int,
    val accent: Int,
    val glow: Int,
    val pop: Int,
    /**
     * (Power play, docs/design/m6-power-play.md §6.1) The power-play tab's colour: [glow] when white text passes C5 on
     * it, else the team's other colour. Commit 0 default: [base] (WP-1 derives it).
     */
    val tab: Int = base,
    /** (Power play §6.3) The pill tint's and the halo's strength, 1 down in 5 % steps until C6 passes. WP-1 derives it. */
    val tintScale: Float = 1f,
)

/**
 * (Goal animation §3; WP-1) The NHL teams' own colours (owner ruling 12: base = the primary, accent = the official
 * secondary, no contrast substitutions; a gold or light primary takes the accent slot: NSH, VGK, PIT, BOS).
 *
 * Sources (verified 2026-11-07, design §3.3). **TCC** = `https://teamcolorcodes.com/<slug>/`, a secondary source,
 * accessed 2026-11-07; no NHL.com hex was retrievable. ANA comes from the Ducks' own brand guide. UTA is unconfirmed (no
 * official hex is published; third-party sources disagree, `#69B3E7` vs `#6CACE3`). The primary order of FLA, MIN and
 * CHI is design open item 2: the owner may flip any of them (a one-line change here).
 *
 * Derived, never tabled:
 * - [TeamColours.glow] = base when `ratio(base, GLASS_DARK) ≥ GOAL_GLOW_BASE_MIN` (2.0), else the accent: a navy or
 *   black panel's halo would vanish on dark video, so EDM glows orange.
 * - [TeamColours.pop]: whichever of accent and base contrasts more with `GLASS_WHITE_ICE`, mixed toward white in 5 %
 *   steps (each channel rounded half to even) until it reaches `GOAL_C4_MIN` (4.5) there. Because the source can be
 *   the base, a black accent (PHI, NJD) still pops in team colour, not grey.
 */
object NhlTeamColours {
    /** Trimmed and upper-cased; an unknown or null abbreviation gives [FALLBACK]. */
    fun of(abbrev: String?): TeamColours {
        val key = abbrev?.trim()?.uppercase() ?: return FALLBACK
        return RESOLVED[key] ?: FALLBACK
    }

    /** `#2A3140` / `#5CA8F2` (`DesignTokens.Accent`, copied: `:core` cannot see `:ui`). */
    val FALLBACK: TeamColours = derive(rgb(0x2A3140), rgb(0x5CA8F2))

    /** abbrev → (base, accent), ARGB; 32 entries (§3.3). The comment on each line is its source. */
    internal val TABLE: Map<String, Pair<Int, Int>> = mapOf(
        "ANA" to (rgb(0x171C19) to rgb(0xFE4111)), // brand.anaheimducks.com/brand-guides/intro-copy%201 (2024 rebrand; digital orange)
        "BOS" to (rgb(0x000000) to rgb(0xFFB81C)), // TCC boston-bruins-color-codes (gold primary → accent)
        "BUF" to (rgb(0x003087) to rgb(0xFFB81C)), // TCC buffalo-sabres-color-codes
        "CGY" to (rgb(0xD2001C) to rgb(0xFAAF19)), // TCC calgary-flames-color-codes (2020 palette)
        "CAR" to (rgb(0xCE1126) to rgb(0xA4A9AD)), // TCC carolina-hurricanes-color-codes
        "CHI" to (rgb(0xCF0A2C) to rgb(0xFFD100)), // TCC chicago-blackhawks-color-codes (primary order: open item 2)
        "COL" to (rgb(0x6F263D) to rgb(0x236192)), // TCC colorado-avalanche-color-codes
        "CBJ" to (rgb(0x002654) to rgb(0xCE1126)), // TCC columbus-blue-jackets-color-codes
        "DAL" to (rgb(0x006847) to rgb(0x8F8F8C)), // TCC dallas-stars-color-codes
        "DET" to (rgb(0xCE1126) to rgb(0xFFFFFF)), // TCC detroit-red-wings-color-codes
        "EDM" to (rgb(0x041E42) to rgb(0xFF4C00)), // TCC edmonton-oilers-color-codes
        "FLA" to (rgb(0xC8102E) to rgb(0x041E42)), // TCC florida-panthers-color-codes (primary order: open item 2)
        "LAK" to (rgb(0x111111) to rgb(0xA2AAAD)), // TCC los-angeles-kings-color-codes
        "MIN" to (rgb(0x154734) to rgb(0xA6192E)), // TCC minnesota-wild-color-codes (primary order: open item 2)
        "MTL" to (rgb(0xAF1E2D) to rgb(0x192168)), // TCC montreal-canadiens-color-codes
        "NSH" to (rgb(0x041E42) to rgb(0xFFB81C)), // TCC nashville-predators-color-codes (gold primary → accent)
        "NJD" to (rgb(0xCE1126) to rgb(0x000000)), // TCC new-jersey-devils-color-codes
        "NYI" to (rgb(0x00539B) to rgb(0xF47D30)), // TCC new-york-islanders-color-codes
        "NYR" to (rgb(0x0038A8) to rgb(0xCE1126)), // TCC new-york-rangers-color-codes
        "OTT" to (rgb(0xDA1A32) to rgb(0xB79257)), // TCC ottawa-senators-color-codes (2020 palette)
        "PHI" to (rgb(0xF74902) to rgb(0x000000)), // TCC philadelphia-flyers-color-codes (C1 3.6: owner exception, ruling 12)
        "PIT" to (rgb(0x000000) to rgb(0xFCB514)), // TCC pittsburgh-penguins-color-codes (gold primary → accent)
        "SJS" to (rgb(0x006D75) to rgb(0xEA7200)), // TCC san-jose-sharks-color-codes
        "SEA" to (rgb(0x001628) to rgb(0x99D9D9)), // TCC seattle-kraken-colors
        "STL" to (rgb(0x002F87) to rgb(0xFCB514)), // TCC st-louis-blues-color-codes
        "TBL" to (rgb(0x002868) to rgb(0xFFFFFF)), // TCC tampa-bay-lightning-color-codes
        "TOR" to (rgb(0x00205B) to rgb(0xFFFFFF)), // TCC toronto-maple-leafs-color-codes
        "UTA" to (rgb(0x010101) to rgb(0x69B3E7)), // UNCONFIRMED (no official hex): sportsteamcolorcodes.com/utah-mammoth-color-codes/
        "VAN" to (rgb(0x00205B) to rgb(0x00843D)), // TCC vancouver-canucks-color-codes
        "VGK" to (rgb(0x333F42) to rgb(0xB4975A)), // TCC vegas-golden-knights-colors (gold primary → accent)
        "WSH" to (rgb(0x041E42) to rgb(0xC8102E)), // TCC washington-capitals-color-codes
        "WPG" to (rgb(0x041E42) to rgb(0x004C97)), // TCC winnipeg-jets-color-codes
    )

    /** C1 (white on base ≥ 4.5) exceptions: owner ruling 12. */
    internal val C1_EXCEPTIONS: Set<String> = setOf("PHI")

    /** (Power play §6.2) C5 (white tab text ≥ `PP_C5_MIN`) exceptions: none (owner ruling 17). */
    internal val C5_EXCEPTIONS: Set<String> = emptySet()

    /** Resolved once: [of] allocates nothing per call. */
    private val RESOLVED: Map<String, TeamColours> = TABLE.mapValues { (_, c) -> derive(c.first, c.second) }

    /** [TeamColours] from a (base, accent) pair, with the §3.1 glow and pop rules. */
    internal fun derive(base: Int, accent: Int): TeamColours {
        val glow = if (Contrast.ratio(base, SportsConstants.GLASS_DARK) >= SportsConstants.GOAL_GLOW_BASE_MIN) base else accent
        val ice = SportsConstants.GLASS_WHITE_ICE
        val src = if (Contrast.ratio(accent, ice) >= Contrast.ratio(base, ice)) accent else base
        var pop = src
        var step = 0
        while (Contrast.ratio(pop, ice) < SportsConstants.GOAL_C4_MIN && step < POP_STEPS) {
            step++
            pop = towardWhite(src, step / POP_STEPS.toDouble())
        }
        return TeamColours(base, accent, glow, pop)
    }

    /** 20 steps of 5 %: the last one is white, which always passes. */
    private const val POP_STEPS = 20

    private fun towardWhite(argb: Int, t: Double): Int {
        fun mix(shift: Int): Int {
            val v = (argb shr shift) and 0xFF
            return round(v + (255 - v) * t).toInt() shl shift // ties to even (kotlin.math.round)
        }
        return (0xFF shl 24) or mix(16) or mix(8) or mix(0)
    }

    private fun rgb(rgb: Int): Int = (0xFF shl 24) or rgb
}
