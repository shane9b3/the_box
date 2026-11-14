package app.thebox.ui.sports

import app.thebox.core.sports.League

/**
 * (CHL WP-5, docs/design/m6-chl-leagues.md §6.1) The 61 teams of 2026-27 as the fit tests draw them: the league, the
 * upper-cased code, the city and the nickname. A followed snapshot carries "City Nickname" ([full]); a day board and the
 * cards carry the nickname (P10).
 */
internal data class ChlTestTeam(val league: League, val code: String, val city: String, val nickname: String) {
    val full: String get() = "$city $nickname"
}

internal object ChlTestTeams {
    private fun t(l: League, code: String, city: String, nick: String) = ChlTestTeam(l, code, city, nick)

    val OHL: List<ChlTestTeam> = listOf(
        t(League.OHL, "BAR", "Barrie", "Colts"), t(League.OHL, "BRAM", "Brampton", "Steelheads"),
        t(League.OHL, "BFD", "Brantford", "Bulldogs"), t(League.OHL, "ER", "Erie", "Otters"),
        t(League.OHL, "FLNT", "Flint", "Firebirds"), t(League.OHL, "GUE", "Guelph", "Storm"),
        t(League.OHL, "KGN", "Kingston", "Frontenacs"), t(League.OHL, "KIT", "Kitchener", "Rangers"),
        t(League.OHL, "LDN", "London", "Knights"), t(League.OHL, "NIAG", "Niagara", "IceDogs"),
        t(League.OHL, "NB", "North Bay", "Battalion"), t(League.OHL, "OSH", "Oshawa", "Generals"),
        t(League.OHL, "OTT", "Ottawa", "67's"), t(League.OHL, "OS", "Owen Sound", "Attack"),
        t(League.OHL, "PBO", "Peterborough", "Petes"), t(League.OHL, "SAG", "Saginaw", "Spirit"),
        t(League.OHL, "SAR", "Sarnia", "Sting"), t(League.OHL, "SOO", "Sault Ste. Marie", "Greyhounds"),
        t(League.OHL, "SBY", "Sudbury", "Wolves"), t(League.OHL, "WSR", "Windsor", "Spitfires"),
    )

    val WHL: List<ChlTestTeam> = listOf(
        t(League.WHL, "BDN", "Brandon", "Wheat Kings"), t(League.WHL, "CGY", "Calgary", "Hitmen"),
        t(League.WHL, "EDM", "Edmonton", "Oil Kings"), t(League.WHL, "EVT", "Everett", "Silvertips"),
        t(League.WHL, "KAM", "Kamloops", "Blazers"), t(League.WHL, "KEL", "Kelowna", "Rockets"),
        t(League.WHL, "LET", "Lethbridge", "Hurricanes"), t(League.WHL, "MH", "Medicine Hat", "Tigers"),
        t(League.WHL, "MJ", "Moose Jaw", "Warriors"), t(League.WHL, "PEN", "Penticton", "Vees"),
        t(League.WHL, "POR", "Portland", "Winterhawks"), t(League.WHL, "PA", "Prince Albert", "Raiders"),
        t(League.WHL, "PG", "Prince George", "Cougars"), t(League.WHL, "RD", "Red Deer", "Rebels"),
        t(League.WHL, "REG", "Regina", "Pats"), t(League.WHL, "SAS", "Saskatoon", "Blades"),
        t(League.WHL, "SEA", "Seattle", "Thunderbirds"), t(League.WHL, "SPO", "Spokane", "Chiefs"),
        t(League.WHL, "SC", "Swift Current", "Broncos"), t(League.WHL, "TC", "Tri-City", "Americans"),
        t(League.WHL, "VAN", "Vancouver", "Giants"), t(League.WHL, "VIC", "Victoria", "Royals"),
        t(League.WHL, "WEN", "Wenatchee", "Wild"),
    )

    val QMJHL: List<ChlTestTeam> = listOf(
        t(League.QMJHL, "BAC", "Baie-Comeau", "Drakkar"), t(League.QMJHL, "BLB", "Blainville-Boisbriand", "Armada"),
        t(League.QMJHL, "CAP", "Cape Breton", "Eagles"), t(League.QMJHL, "CHA", "Charlottetown", "Islanders"),
        t(League.QMJHL, "CHI", "Chicoutimi", "Saguenéens"), t(League.QMJHL, "DRU", "Drummondville", "Voltigeurs"),
        t(League.QMJHL, "GAT", "Gatineau", "Olympiques"), t(League.QMJHL, "HAL", "Halifax", "Mooseheads"),
        t(League.QMJHL, "MON", "Moncton", "Wildcats"), t(League.QMJHL, "NFL", "Newfoundland", "Regiment"),
        t(League.QMJHL, "QUE", "Québec", "Remparts"), t(League.QMJHL, "RIM", "Rimouski", "Océanic"),
        t(League.QMJHL, "ROU", "Rouyn-Noranda", "Huskies"), t(League.QMJHL, "SNB", "Saint John", "Sea Dogs"),
        t(League.QMJHL, "SHA", "Shawinigan", "Cataractes"), t(League.QMJHL, "SHE", "Sherbrooke", "Phœnix"),
        t(League.QMJHL, "VDO", "Val-d'Or", "Foreurs"), t(League.QMJHL, "VIC", "Victoriaville", "Tigres"),
    )

    val ALL: List<ChlTestTeam> = OHL + WHL + QMJHL

    fun of(league: League): List<ChlTestTeam> = when (league) {
        League.OHL -> OHL
        League.WHL -> WHL
        League.QMJHL -> QMJHL
        League.NHL, League.NFL -> emptyList()
    }

    /** Every code of every league, once (the codes of the three leagues overlap on CGY-style names only across leagues). */
    val CODES: List<String> = ALL.map { it.code }.distinct()
}
