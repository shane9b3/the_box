// Contract (CHL commit 0, docs/design/m6-chl-leagues.md §1.5, §6.2): the signature of [ChlTeamColours.of] is fixed for
// WP-4 (the tables) and every caller; changes go through the lead.
package app.thebox.core.sports

/**
 * (CHL §6.2) The CHL teams' goal / power-play colours, keyed by `(League, code)`: CGY, EDM, OTT, SEA, VAN, CHI and VIC
 * exist in the NHL too, VIC in two CHL leagues, and the QMJHL's "NFL" is a team (L2), so a code alone never picks a row.
 *
 * One table per league (20 / 23 / 18 teams, the 2026-27 `teamsbyseason` codes, upper-cased), each (base, accent), resolved
 * with `NhlTeamColours.derive` (ruling 12: base = the team's primary, accent = its secondary, no contrast substitutions;
 * a gold or light primary takes the accent slot). Researched 2026-11-13 from teamcolorcodes.com (TCC), the Wikipedia
 * infobox and a sample of the logo PNG; no brand guide was retrievable, so every row is second-tier. The full sourced
 * table is `docs/research/chl-team-colours.md`. Rows marked LOW are the owner's sign-off list (§7.4).
 */
object ChlTeamColours {
    /** The colours of an unknown team, or of a null code: the NHL table's neutral pair. */
    val FALLBACK: TeamColours = NhlTeamColours.FALLBACK

    /** OHL (20): code → (base, accent), ARGB. The comment on each line is its confidence, sources and access date. */
    internal val OHL_TABLE: Map<String, Pair<Int, Int>> = mapOf(
        "BAR" to (rgb(0x003A8A) to rgb(0xEC1B02)), // medium; TCC barrie-colts-color-codes, Wikipedia; 2026-11-13
        "BRAM" to (rgb(0x002768) to rgb(0xA8A9AC)), // LOW, owner sign-off; Wikipedia, logo, TheSportsDB; 2026-11-13
        "BFD" to (rgb(0x231F20) to rgb(0xB47F3A)), // LOW, owner sign-off; TCC hamilton-bulldogs-color-codes, Wikipedia, logo; 2026-11-13
        "ER" to (rgb(0x0A1E3F) to rgb(0xFEC52F)), // high; TCC erie-otters-color-codes, Wikipedia; 2026-11-13
        "FLNT" to (rgb(0x0C1D3C) to rgb(0xFC5D00)), // medium; TCC flint-firebirds-color-codes, Wikipedia; 2026-11-13
        "GUE" to (rgb(0x900027) to rgb(0xB3B5B8)), // high; TCC guelph-storm-color-codes, Wikipedia; 2026-11-13
        "KGN" to (rgb(0x000000) to rgb(0xFCBA32)), // high; TCC kingston-frontenacs-color-codes, Wikipedia; 2026-11-13
        "KIT" to (rgb(0x0033A0) to rgb(0xCF0A2C)), // medium; TCC kitchener-rangers-color-codes, Wikipedia; 2026-11-13
        "LDN" to (rgb(0x046A38) to rgb(0xFFB81C)), // medium; TCC london-knights-color-codes, Wikipedia; 2026-11-13
        "NIAG" to (rgb(0xBF0C26) to rgb(0x000000)), // medium; TCC niagara-icedogs-color-codes, Wikipedia; 2026-11-13
        "NB" to (rgb(0x7F7911) to rgb(0x000000)), // medium; TCC north-bay-battalion-color-codes, Wikipedia; 2026-11-13 nudged from #807A11 for C1
        "OSH" to (rgb(0xE21836) to rgb(0x06173C)), // LOW, owner sign-off; TCC oshawa-generals-color-codes, Wikipedia, logo; 2026-11-13
        "OTT" to (rgb(0xAC1F2D) to rgb(0x000000)), // high; TCC ottawa-67s-color-codes, Wikipedia; 2026-11-13
        "OS" to (rgb(0xBF2F38) to rgb(0xFFDD00)), // LOW, owner sign-off; TCC owen-sound-attack-color-codes, Wikipedia; 2026-11-13
        "PBO" to (rgb(0x77273E) to rgb(0x000000)), // medium; nudged from 75263D (lead ruling R2, pending owner); TCC peterborough-petes-color-codes, Wikipedia, logo; 2026-11-13
        "SAG" to (rgb(0x003263) to rgb(0xBE2F38)), // high; TCC saginaw-spirit-color-codes, Wikipedia; 2026-11-13
        "SAR" to (rgb(0x231F20) to rgb(0xFFC425)), // high; TCC sarnia-sting-color-codes, Wikipedia; 2026-11-13
        "SOO" to (rgb(0xCF2029) to rgb(0xFFFFFF)), // medium; TCC sault-ste-marie-greyhounds-color-codes, Wikipedia; 2026-11-13
        "SBY" to (rgb(0x000000) to rgb(0xB9BBBD)), // LOW, owner sign-off; TCC sudbury-wolves-color-codes, Wikipedia, logo; 2026-11-13
        "WSR" to (rgb(0x00274D) to rgb(0xE31B23)), // high; TCC windsor-spitfires-color-codes, Wikipedia; 2026-11-13
    )

    /** WHL (23). */
    internal val WHL_TABLE: Map<String, Pair<Int, Int>> = mapOf(
        "BDN" to (rgb(0x000000) to rgb(0xE6BF2E)), // medium; TCC brandon-wheat-kings-color-codes, Wikipedia; 2026-11-13
        "CGY" to (rgb(0x000000) to rgb(0xE13A3E)), // medium; TCC calgary-hitmen-color-codes, Wikipedia; 2026-11-13
        "EDM" to (rgb(0x00539C) to rgb(0xFFC526)), // medium; TCC edmonton-oil-kings-color-codes, Wikipedia, logo; 2026-11-13
        "EVT" to (rgb(0x045B41) to rgb(0xBC8448)), // medium; TCC everett-silvertips-color-codes, Wikipedia; 2026-11-13
        "KAM" to (rgb(0x032C62) to rgb(0xD0471D)), // high; TCC kamloops-blazers-color-codes, Wikipedia; 2026-11-13
        "KEL" to (rgb(0x008293) to rgb(0x000000)), // medium; TCC kelowna-rockets-color-codes, Wikipedia; 2026-11-13 nudged from #008394 for C1
        "LET" to (rgb(0xCC1D30) to rgb(0x121942)), // high; TCC lethbridge-hurricanes-color-codes, Wikipedia; 2026-11-13
        "MH" to (rgb(0xE56B1F) to rgb(0x231F20)), // high; TCC medicine-hat-tigers-color-codes, Wikipedia; 2026-11-13
        "MJ" to (rgb(0xCC0000) to rgb(0x000000)), // medium; TCC moose-jaw-warriors-color-codes, Wikipedia; 2026-11-13
        "PEN" to (rgb(0x25366C) to rgb(0x7AB2E0)), // medium; Wikipedia, logo; 2026-11-13
        "POR" to (rgb(0x000000) to rgb(0xCC202E)), // medium; TCC portland-winterhawks-color-codes, Wikipedia; 2026-11-13
        "PA" to (rgb(0x0D843E) to rgb(0x231501)), // medium; TCC prince-albert-raiders-color-codes, Wikipedia; 2026-11-13
        "PG" to (rgb(0xD0202E) to rgb(0xD0A176)), // medium; TCC prince-george-cougars-color-codes, Wikipedia; 2026-11-13
        "RD" to (rgb(0x900028) to rgb(0x000000)), // high; TCC red-deer-rebels-color-codes, Wikipedia; 2026-11-13
        "REG" to (rgb(0x1A3668) to rgb(0xEF3342)), // high; TCC regina-pats-color-codes, Wikipedia; 2026-11-13
        "SAS" to (rgb(0x262266) to rgb(0xFFCD36)), // medium; TCC saskatoon-blades-color-codes, Wikipedia; 2026-11-13
        "SEA" to (rgb(0x003876) to rgb(0x008357)), // high; TCC seattle-thunderbirds-color-codes, Wikipedia; 2026-11-13
        "SPO" to (rgb(0xC41230) to rgb(0x013A81)), // medium; TCC spokane-chiefs-color-codes, Wikipedia; 2026-11-13
        "SC" to (rgb(0x026937) to rgb(0x001F5B)), // medium; TCC swift-current-broncos-color-codes, Wikipedia; 2026-11-13
        "TC" to (rgb(0x0D1D41) to rgb(0xAC1A2D)), // medium; TCC tri-city-americans-color-codes, Wikipedia; 2026-11-13
        "VAN" to (rgb(0x231F20) to rgb(0x97002E)), // medium; TCC vancouver-giants-color-codes, Wikipedia; 2026-11-13
        "VIC" to (rgb(0x013974) to rgb(0xA0A9AD)), // medium; TCC victoria-royals-color-codes, Wikipedia; 2026-11-13
        "WEN" to (rgb(0x002F87) to rgb(0x026FA8)), // medium; accent is the sourced blue, not 231F20 (lead ruling R2, pending owner); TCC wenatchee-wild-color-codes, Wikipedia; 2026-11-13
    )

    /** QMJHL (18; the feed's mixed-case codes upper-cased; "NFL" is the Newfoundland Regiment, a team). */
    internal val QMJHL_TABLE: Map<String, Pair<Int, Int>> = mapOf(
        "BAC" to (rgb(0xBF2F38) to rgb(0xFFCC02)), // medium; TCC baie-comeau-drakkar-color-codes, Wikipedia; 2026-11-13
        "BLB" to (rgb(0x000000) to rgb(0xFFFFFF)), // high; TCC blainville-boisbriand-armada-color-codes, Wikipedia; 2026-11-13
        "CAP" to (rgb(0x23282B) to rgb(0xFAB406)), // medium; TCC cape-breton-eagles-color-codes, Wikipedia; 2026-11-13
        "CHA" to (rgb(0x000000) to rgb(0xA8996E)), // medium; TCC charlottetown-islanders-color-codes, Wikipedia; 2026-11-13
        "CHI" to (rgb(0x003263) to rgb(0x3AB4E7)), // LOW, owner sign-off; TCC chicoutimi-sagueneens-color-codes, Wikipedia; 2026-11-13
        "DRU" to (rgb(0x000000) to rgb(0xEE3224)), // medium; TCC drummondville-voltigeurs-color-codes, Wikipedia; 2026-11-13
        "GAT" to (rgb(0x000000) to rgb(0x717073)), // medium; TCC gatineau-olympiques-color-codes, Wikipedia; 2026-11-13
        "HAL" to (rgb(0x174734) to rgb(0xDEB408)), // LOW, owner sign-off; TCC halifax-mooseheads-color-codes, Wikipedia, logo; 2026-11-13
        "MON" to (rgb(0xC60C30) to rgb(0xFDC13D)), // medium; TCC moncton-wildcats-color-codes, Wikipedia; 2026-11-13
        "NFL" to (rgb(0x0E2342) to rgb(0x8B2535)), // medium; Wikipedia, logo; 2026-11-13
        "QUE" to (rgb(0xE31836) to rgb(0x000000)), // medium; TCC quebec-remparts-color-codes, Wikipedia; 2026-11-13
        "RIM" to (rgb(0x002243) to rgb(0xACB2B5)), // LOW, owner sign-off; TCC rimouski-oceanic-color-codes, Wikipedia, logo; 2026-11-13
        "ROU" to (rgb(0x000000) to rgb(0xED1C2E)), // medium; TCC rouyn-noranda-huskies-color-codes, Wikipedia; 2026-11-13
        "SNB" to (rgb(0x004C97) to rgb(0x000000)), // medium; TCC saint-john-sea-dogs-color-codes, Wikipedia; 2026-11-13
        "SHA" to (rgb(0x002D62) to rgb(0xFFC425)), // medium; TCC shawinigan-cataractes-color-codes, Wikipedia; 2026-11-13
        "SHE" to (rgb(0x051B54) to rgb(0x99BADD)), // medium; TCC sherbrooke-phoenix-color-codes, Wikipedia; 2026-11-13
        "VDO" to (rgb(0x005837) to rgb(0xDDB208)), // medium; TCC val-dor-foreurs-color-codes, Wikipedia; 2026-11-13
        "VIC" to (rgb(0x000000) to rgb(0xFCB827)), // high; TCC victoriaville-tigres-color-codes, Wikipedia; 2026-11-13
    )

    /** The table of a CHL [league]; empty for any other league. */
    internal fun table(league: League): Map<String, Pair<Int, Int>> = when (league) {
        League.OHL -> OHL_TABLE
        League.WHL -> WHL_TABLE
        League.QMJHL -> QMJHL_TABLE
        League.NHL, League.NFL -> emptyMap()
    }

    /** The team codes of a CHL [league] (empty for any other league), for pickers and contact sheets. */
    fun codes(league: League): Set<String> = table(league).keys

    /**
     * C1 (white on base ≥ 4.5) exceptions, per league: MH's orange primary is 3.25 : 1 (the brand colour, kept as with the
     * NHL's PHI, ruling 12). NB (olive) and KEL (teal) were 4.46 and 4.49 at the sourced hex and are nudged 1 % darker
     * instead, so they pass.
     */
    internal val C1_EXCEPTIONS: Map<League, Set<String>> = mapOf(
        League.OHL to emptySet(),
        League.WHL to setOf("MH"),
        League.QMJHL to emptySet(),
    )

    /** Resolved once: [of] allocates nothing per call. */
    private val RESOLVED: Map<League, Map<String, TeamColours>> = CHL_LEAGUES.associateWith { l ->
        table(l).mapValues { (_, c) -> NhlTeamColours.derive(c.first, c.second) }
    }

    /**
     * [league] must be OHL, WHL or QMJHL (any other league gives [FALLBACK]); [abbrev] is trimmed and upper-cased; an
     * unknown or null code gives [FALLBACK].
     */
    fun of(league: League, abbrev: String?): TeamColours {
        val key = abbrev?.trim()?.uppercase() ?: return FALLBACK
        return RESOLVED[league]?.get(key) ?: FALLBACK
    }

    private fun rgb(rgb: Int): Int = (0xFF shl 24) or rgb
}
