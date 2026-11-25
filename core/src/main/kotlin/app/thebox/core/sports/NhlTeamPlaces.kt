package app.thebox.core.sports

/**
 * (Sports Hub commit 0, docs/design/m6-sports-hub.md §4.6) The place of each NHL team, keyed by abbreviation. The NHL
 * board's `name` is the nickname ("Jets"); `HubNames` (`:ui`, WP-4) shows the place over it ("Winnipeg" / "Jets").
 * Places are the city or region the team is named for, as NHL.com writes them ("NY" teams spelled out).
 */
object NhlTeamPlaces {
    /** Trimmed and upper-cased; null for an unknown or null abbreviation (the card then shows one line). */
    fun of(abbrev: String?): String? = TABLE[abbrev?.trim()?.uppercase() ?: return null]

    /** abbrev → place; 32 entries (the same keys as `NhlTeamColours.TABLE`). */
    val TABLE: Map<String, String> = mapOf(
        "ANA" to "Anaheim",
        "BOS" to "Boston",
        "BUF" to "Buffalo",
        "CGY" to "Calgary",
        "CAR" to "Carolina",
        "CHI" to "Chicago",
        "COL" to "Colorado",
        "CBJ" to "Columbus",
        "DAL" to "Dallas",
        "DET" to "Detroit",
        "EDM" to "Edmonton",
        "FLA" to "Florida",
        "LAK" to "Los Angeles",
        "MIN" to "Minnesota",
        "MTL" to "Montréal",
        "NSH" to "Nashville",
        "NJD" to "New Jersey",
        "NYI" to "New York",
        "NYR" to "New York",
        "OTT" to "Ottawa",
        "PHI" to "Philadelphia",
        "PIT" to "Pittsburgh",
        "SJS" to "San Jose",
        "SEA" to "Seattle",
        "STL" to "St. Louis",
        "TBL" to "Tampa Bay",
        "TOR" to "Toronto",
        "UTA" to "Utah",
        "VAN" to "Vancouver",
        "VGK" to "Vegas",
        "WSH" to "Washington",
        "WPG" to "Winnipeg",
    )
}
