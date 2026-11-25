// docs/design/m6-event-channels.md §4.2, §7, §8.2, §8.4 (WP-1). The NHL and NFL tables, keyed by the boards'
// abbreviations (`NhlTeamPlaces` / `NhlTeamColours`, `NflTeamColours`: ESPN's "WSH", "LV", "JAX", "LAR").
package app.thebox.core.sports

import app.thebox.core.events.EventText

/** One club's names, for team channels (§8.2), event sides (§8.4) and search's nickname expansion (§7). */
data class TeamName(
    val league: League,
    /** The board's ("EDM"; NFL as ESPN gives it, "WSH"). */
    val abbrev: String,
    /** Current first, then former names of the same club: ["Utah Mammoth", "Utah Hockey Club"]. */
    val fullNames: List<String>,
    /** ["Oilers"], ["Golden Knights"]. */
    val nicknames: List<String>,
    /** ["Edmonton"], ["Vegas", "Las Vegas"], ["St. Louis"], ["Montréal"], ["Los Angeles", "LA"]. */
    val places: List<String>,
    /** tvg-id stems: ["EdmontonOilers"], ["UtahMammoth", "UtahHockeyClub"]. */
    val tvgStems: List<String>,
)

object TeamNames {
    /** Which of a team's names a side equals. */
    enum class Strength { FULL, NICKNAME, PLACE }

    /** The team [abbrev] of [league], or null. */
    fun of(league: League, abbrev: String): TeamName? = BY_ABBREV[league]?.get(abbrev.trim().uppercase())

    /** Every team of [league]; empty for a league without a table (CHL). */
    fun all(league: League): List<TeamName> = when (league) {
        League.NHL -> NHL
        League.NFL -> NFL
        else -> emptyList()
    }

    /** Folded tokens: "saint"/"st"/"st." → "st", "&" → "and", "l.a." → "la"; letters and digits only. */
    fun tokens(s: String): List<String> = try {
        EventText.tokens(s)
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * How [side] (cleaned tokens, §3.7) **equals** one of [team]'s names, or null (arch S2: equality, never containment).
     * FULL: a full name (current or former), or any place followed by any nickname ("LA Kings", "Montreal Canadiens").
     */
    fun sideMatch(side: List<String>, team: TeamName): Strength? {
        if (side.isEmpty()) return null
        val f = folded(team)
        val key = side.joinToString(" ")
        return when (key) {
            in f.full -> Strength.FULL
            in f.nick -> Strength.NICKNAME
            in f.place -> Strength.PLACE
            else -> null
        }
    }

    /** True when [place] belongs to two clubs of [league] ("New York", "Los Angeles"). */
    fun placeShared(league: League, place: String): Boolean {
        val key = tokens(place).joinToString(" ")
        if (key.isEmpty()) return false
        return all(league).count { key in folded(it).place } >= 2
    }

    /**
     * (§8.4) Nicknames that another league's or college's club also uses ("Jets", "Panthers", "Kings", "Rangers",
     * "Giants", "Cardinals", …): a side that equals one only is not CONFIRMED without a positive hint. Folded.
     */
    internal fun crossLeagueNickname(league: League, side: List<String>): Boolean =
        side.joinToString(" ") in (CROSS[league] ?: emptySet<String>())

    /** The folded places of [team] (the hub's board-clash check). */
    internal fun foldedPlaces(team: TeamName): Set<String> = folded(team).place

    // ------------------------------------------------------------------ folding cache

    private class Folded(val full: Set<String>, val nick: Set<String>, val place: Set<String>)

    private fun key(s: String) = EventText.tokens(s).joinToString(" ")

    private fun folded(team: TeamName): Folded = FOLDED[team] ?: build(team)

    private fun build(team: TeamName): Folded {
        val nick = team.nicknames.mapTo(HashSet()) { key(it) }
        val place = team.places.mapTo(HashSet()) { key(it) }
        val full = team.fullNames.mapTo(HashSet()) { key(it) }
        for (p in place) for (n in nick) full += "$p $n"
        return Folded(full, nick, place)
    }

    // ------------------------------------------------------------------ tables

    private fun t(
        league: League,
        abbrev: String,
        full: List<String>,
        nick: List<String>,
        places: List<String>,
        stems: List<String> = full.map { stem(it) },
    ) = TeamName(league, abbrev, full, nick, places, stems)

    /** "St. Louis Blues" → "StLouisBlues"; "Montréal" folds to "Montreal". */
    private fun stem(full: String): String =
        EventText.tokens(full).joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }

    private val N = League.NHL
    private val F = League.NFL

    private val NHL: List<TeamName> = listOf(
        t(N, "ANA", listOf("Anaheim Ducks"), listOf("Ducks"), listOf("Anaheim")),
        t(N, "BOS", listOf("Boston Bruins"), listOf("Bruins"), listOf("Boston")),
        t(N, "BUF", listOf("Buffalo Sabres"), listOf("Sabres"), listOf("Buffalo")),
        t(N, "CGY", listOf("Calgary Flames"), listOf("Flames"), listOf("Calgary")),
        t(N, "CAR", listOf("Carolina Hurricanes"), listOf("Hurricanes", "Canes"), listOf("Carolina")),
        t(N, "CHI", listOf("Chicago Blackhawks"), listOf("Blackhawks"), listOf("Chicago")),
        t(N, "COL", listOf("Colorado Avalanche"), listOf("Avalanche"), listOf("Colorado")),
        t(N, "CBJ", listOf("Columbus Blue Jackets"), listOf("Blue Jackets"), listOf("Columbus")),
        t(N, "DAL", listOf("Dallas Stars"), listOf("Stars"), listOf("Dallas")),
        t(N, "DET", listOf("Detroit Red Wings"), listOf("Red Wings"), listOf("Detroit")),
        t(N, "EDM", listOf("Edmonton Oilers"), listOf("Oilers"), listOf("Edmonton")),
        t(N, "FLA", listOf("Florida Panthers"), listOf("Panthers"), listOf("Florida")),
        t(N, "LAK", listOf("Los Angeles Kings"), listOf("Kings"), listOf("Los Angeles", "LA")),
        t(N, "MIN", listOf("Minnesota Wild"), listOf("Wild"), listOf("Minnesota")),
        t(N, "MTL", listOf("Montréal Canadiens"), listOf("Canadiens", "Habs"), listOf("Montréal")),
        t(N, "NSH", listOf("Nashville Predators"), listOf("Predators", "Preds"), listOf("Nashville")),
        t(N, "NJD", listOf("New Jersey Devils"), listOf("Devils"), listOf("New Jersey")),
        t(N, "NYI", listOf("New York Islanders"), listOf("Islanders"), listOf("New York", "NY")),
        t(N, "NYR", listOf("New York Rangers"), listOf("Rangers"), listOf("New York", "NY")),
        t(N, "OTT", listOf("Ottawa Senators"), listOf("Senators", "Sens"), listOf("Ottawa")),
        t(N, "PHI", listOf("Philadelphia Flyers"), listOf("Flyers"), listOf("Philadelphia")),
        t(N, "PIT", listOf("Pittsburgh Penguins"), listOf("Penguins"), listOf("Pittsburgh")),
        t(N, "SJS", listOf("San Jose Sharks"), listOf("Sharks"), listOf("San Jose")),
        t(N, "SEA", listOf("Seattle Kraken"), listOf("Kraken"), listOf("Seattle")),
        t(N, "STL", listOf("St. Louis Blues"), listOf("Blues"), listOf("St. Louis")),
        t(N, "TBL", listOf("Tampa Bay Lightning"), listOf("Lightning"), listOf("Tampa Bay", "Tampa")),
        t(N, "TOR", listOf("Toronto Maple Leafs"), listOf("Maple Leafs", "Leafs"), listOf("Toronto")),
        // The club played its first season as the Utah Hockey Club (the owner's tvg id is still UtahHockeyClub.nhl)
        // and took the Arizona Coyotes' hockey assets; older feeds may still name either.
        t(
            N, "UTA", listOf("Utah Mammoth", "Utah Hockey Club", "Utah HC", "Arizona Coyotes"), listOf("Mammoth"), listOf("Utah"),
            stems = listOf("UtahMammoth", "UtahHockeyClub", "UtahHC", "ArizonaCoyotes"),
        ),
        t(N, "VAN", listOf("Vancouver Canucks"), listOf("Canucks"), listOf("Vancouver")),
        t(N, "VGK", listOf("Vegas Golden Knights"), listOf("Golden Knights"), listOf("Vegas", "Las Vegas")),
        t(N, "WSH", listOf("Washington Capitals"), listOf("Capitals", "Caps"), listOf("Washington")),
        t(N, "WPG", listOf("Winnipeg Jets"), listOf("Jets"), listOf("Winnipeg")),
    )

    private val NFL: List<TeamName> = listOf(
        t(F, "ARI", listOf("Arizona Cardinals"), listOf("Cardinals"), listOf("Arizona")),
        t(F, "ATL", listOf("Atlanta Falcons"), listOf("Falcons"), listOf("Atlanta")),
        t(F, "BAL", listOf("Baltimore Ravens"), listOf("Ravens"), listOf("Baltimore")),
        t(F, "BUF", listOf("Buffalo Bills"), listOf("Bills"), listOf("Buffalo")),
        t(F, "CAR", listOf("Carolina Panthers"), listOf("Panthers"), listOf("Carolina")),
        t(F, "CHI", listOf("Chicago Bears"), listOf("Bears"), listOf("Chicago")),
        t(F, "CIN", listOf("Cincinnati Bengals"), listOf("Bengals"), listOf("Cincinnati")),
        t(F, "CLE", listOf("Cleveland Browns"), listOf("Browns"), listOf("Cleveland")),
        t(F, "DAL", listOf("Dallas Cowboys"), listOf("Cowboys"), listOf("Dallas")),
        t(F, "DEN", listOf("Denver Broncos"), listOf("Broncos"), listOf("Denver")),
        t(F, "DET", listOf("Detroit Lions"), listOf("Lions"), listOf("Detroit")),
        t(F, "GB", listOf("Green Bay Packers"), listOf("Packers"), listOf("Green Bay")),
        t(F, "HOU", listOf("Houston Texans"), listOf("Texans"), listOf("Houston")),
        t(F, "IND", listOf("Indianapolis Colts"), listOf("Colts"), listOf("Indianapolis")),
        t(F, "JAX", listOf("Jacksonville Jaguars"), listOf("Jaguars"), listOf("Jacksonville")),
        t(F, "KC", listOf("Kansas City Chiefs"), listOf("Chiefs"), listOf("Kansas City")),
        t(
            F, "LAC", listOf("Los Angeles Chargers", "San Diego Chargers"), listOf("Chargers"), listOf("Los Angeles", "LA"),
        ),
        t(F, "LAR", listOf("Los Angeles Rams", "St. Louis Rams"), listOf("Rams"), listOf("Los Angeles", "LA")),
        t(F, "LV", listOf("Las Vegas Raiders", "Oakland Raiders"), listOf("Raiders"), listOf("Las Vegas")),
        t(F, "MIA", listOf("Miami Dolphins"), listOf("Dolphins"), listOf("Miami")),
        t(F, "MIN", listOf("Minnesota Vikings"), listOf("Vikings"), listOf("Minnesota")),
        t(F, "NE", listOf("New England Patriots"), listOf("Patriots"), listOf("New England")),
        t(F, "NO", listOf("New Orleans Saints"), listOf("Saints"), listOf("New Orleans")),
        t(F, "NYG", listOf("New York Giants"), listOf("Giants"), listOf("New York", "NY")),
        t(F, "NYJ", listOf("New York Jets"), listOf("Jets"), listOf("New York", "NY")),
        t(F, "PHI", listOf("Philadelphia Eagles"), listOf("Eagles"), listOf("Philadelphia")),
        t(F, "PIT", listOf("Pittsburgh Steelers"), listOf("Steelers"), listOf("Pittsburgh")),
        t(F, "SEA", listOf("Seattle Seahawks"), listOf("Seahawks"), listOf("Seattle")),
        t(F, "SF", listOf("San Francisco 49ers"), listOf("49ers", "Niners"), listOf("San Francisco")),
        t(F, "TB", listOf("Tampa Bay Buccaneers"), listOf("Buccaneers", "Bucs"), listOf("Tampa Bay", "Tampa")),
        t(F, "TEN", listOf("Tennessee Titans"), listOf("Titans"), listOf("Tennessee")),
        t(
            F, "WSH", listOf("Washington Commanders", "Washington Football Team", "Washington Redskins"), listOf("Commanders"),
            listOf("Washington"),
        ),
    )

    /**
     * Nicknames shared with a club of another league or a major college programme. The NHL list: Panthers (NFL),
     * Kings (NBA), Rangers (MLB, SPFL), Jets (NFL), Senators, Hurricanes (Miami), Ducks (Oregon), Islanders (TAMU-CC),
     * Bruins (UCLA), Flyers (Dayton), Penguins (Youngstown State), Flames (UIC), Sharks (LIU), Mammoth (NLL). The NFL
     * list: the same idea (MLB Cardinals and Giants, college Rams, Eagles, Lions, …).
     */
    private val CROSS: Map<League, Set<String>> = mapOf(
        League.NHL to setOf(
            "panthers", "kings", "rangers", "jets", "senators", "sens", "hurricanes", "canes", "ducks", "islanders", "bruins",
            "flyers", "penguins", "flames", "sharks", "mammoth",
        ),
        League.NFL to setOf(
            "cardinals", "giants", "jets", "panthers", "rams", "eagles", "lions", "bears", "broncos", "cowboys", "falcons",
            "jaguars", "chargers", "titans", "vikings", "texans", "raiders", "seahawks", "saints", "dolphins", "buccaneers",
            "bucs", "49ers", "niners", "bengals", "patriots", "colts", "chiefs",
        ),
    )

    private val BY_ABBREV: Map<League, Map<String, TeamName>> = mapOf(
        League.NHL to NHL.associateBy { it.abbrev },
        League.NFL to NFL.associateBy { it.abbrev },
    )

    private val FOLDED: Map<TeamName, Folded> = (NHL + NFL).associateWith { build(it) }
}
