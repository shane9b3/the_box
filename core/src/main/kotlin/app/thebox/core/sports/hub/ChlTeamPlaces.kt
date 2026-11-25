package app.thebox.core.sports.hub

import app.thebox.core.sports.League

/**
 * (Sports Hub WP-4, docs/design/m6-sports-hub.md §4.6, U-S15) The place and nickname of each CHL team, keyed by
 * `(league, code)`: a code alone never picks a row (CGY, EDM, OTT, SEA, VAN, CHI and VIC exist in more than one league, and the
 * QMJHL's "NFL" is a team). `HubNames` (`:ui`) shows the place over the nickname ("Windsor" / "Spitfires") on the cards, so a
 * French-order name such as "Saguenéens de Chicoutimi" splits the same way as an English one. The table is the single source.
 *
 * [Entry.place] is the full place (the panel's headline: "Blainville-Boisbriand Armada"); [Entry.cardPlace] is the form the
 * 220 px name column uses, a short form only where the full one does not fit at the shrink floor at every text size
 * (`HubCardFitTest`): never a truncated abbreviation.
 */
object ChlTeamPlaces {
    data class Entry(val place: String, val nickname: String, val cardPlace: String = place)

    fun of(league: League, abbrev: String?): Entry? = TABLE[league]?.get(abbrev?.trim()?.uppercase() ?: return null)

    private fun e(place: String, nickname: String, card: String = place) = Entry(place, nickname, card)

    /** OHL (20). */
    private val OHL_PLACES: Map<String, Entry> = mapOf(
        "BAR" to e("Barrie", "Colts"), "BRAM" to e("Brampton", "Steelheads"), "BFD" to e("Brantford", "Bulldogs"),
        "ER" to e("Erie", "Otters"), "FLNT" to e("Flint", "Firebirds"), "GUE" to e("Guelph", "Storm"),
        "KGN" to e("Kingston", "Frontenacs"), "KIT" to e("Kitchener", "Rangers"), "LDN" to e("London", "Knights"),
        "NIAG" to e("Niagara", "IceDogs"), "NB" to e("North Bay", "Battalion"), "OSH" to e("Oshawa", "Generals"),
        "OTT" to e("Ottawa", "67's"), "OS" to e("Owen Sound", "Attack"), "PBO" to e("Peterborough", "Petes"),
        "SAG" to e("Saginaw", "Spirit"), "SAR" to e("Sarnia", "Sting"), "SOO" to e("Sault Ste. Marie", "Greyhounds", "Sault Ste. Marie"),
        "SBY" to e("Sudbury", "Wolves"), "WSR" to e("Windsor", "Spitfires"),
    )

    /** WHL (23). */
    private val WHL_PLACES: Map<String, Entry> = mapOf(
        "BDN" to e("Brandon", "Wheat Kings"), "CGY" to e("Calgary", "Hitmen"), "EDM" to e("Edmonton", "Oil Kings"),
        "EVT" to e("Everett", "Silvertips"), "KAM" to e("Kamloops", "Blazers"), "KEL" to e("Kelowna", "Rockets"),
        "LET" to e("Lethbridge", "Hurricanes"), "MH" to e("Medicine Hat", "Tigers"), "MJ" to e("Moose Jaw", "Warriors"),
        "PEN" to e("Penticton", "Vees"), "POR" to e("Portland", "Winterhawks"), "PA" to e("Prince Albert", "Raiders"),
        "PG" to e("Prince George", "Cougars"), "RD" to e("Red Deer", "Rebels"), "REG" to e("Regina", "Pats"),
        "SAS" to e("Saskatoon", "Blades"), "SEA" to e("Seattle", "Thunderbirds"), "SPO" to e("Spokane", "Chiefs"),
        "SC" to e("Swift Current", "Broncos"), "TC" to e("Tri-City", "Americans"), "VAN" to e("Vancouver", "Giants"),
        "VIC" to e("Victoria", "Royals"), "WEN" to e("Wenatchee", "Wild"),
    )

    /** QMJHL (18; the feed's mixed-case codes upper-cased). */
    private val QMJHL_PLACES: Map<String, Entry> = mapOf(
        "BAC" to e("Baie-Comeau", "Drakkar"), "BLB" to e("Blainville-Boisbriand", "Armada", "Boisbriand"),
        "CAP" to e("Cape Breton", "Eagles"), "CHA" to e("Charlottetown", "Islanders"), "CHI" to e("Chicoutimi", "Saguenéens"),
        "DRU" to e("Drummondville", "Voltigeurs"), "GAT" to e("Gatineau", "Olympiques"), "HAL" to e("Halifax", "Mooseheads"),
        "MON" to e("Moncton", "Wildcats"), "NFL" to e("Newfoundland", "Regiment"), "QUE" to e("Québec", "Remparts"),
        "RIM" to e("Rimouski", "Océanic"), "ROU" to e("Rouyn-Noranda", "Huskies"), "SNB" to e("Saint John", "Sea Dogs"),
        "SHA" to e("Shawinigan", "Cataractes"), "SHE" to e("Sherbrooke", "Phœnix"), "VDO" to e("Val-d'Or", "Foreurs"),
        "VIC" to e("Victoriaville", "Tigres"),
    )

    /** league → code → entry; 20 + 23 + 18 = 61 entries. */
    val TABLE: Map<League, Map<String, Entry>> = mapOf(League.OHL to OHL_PLACES, League.WHL to WHL_PLACES, League.QMJHL to QMJHL_PLACES)
}
