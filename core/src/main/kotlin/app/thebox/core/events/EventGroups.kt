// docs/design/m6-event-channels.md §18 (code review 2026-11-25, data m1).
package app.thebox.core.events

/**
 * Whether a provider group holds event slots (§18): its name has a sports, PPV, event or league word ("Sports | NHL",
 * "US| ESPN+ PPV", "LIVE EVENTS", "NHL Game Pass", "Sports | OHL/WHL/QMJHL"). Only the bare empty forms
 * ([EventNameParser.isBareSlot]) depend on it: "BBC 1 | Offline" in "UK | Entertainment" stays an ordinary channel with
 * its full name. A null or blank group is not event-like. Pure, never throws.
 */
object EventGroups {
    /** Substrings of the lower-cased group name. */
    private val PARTS: Array<String> = arrayOf("sport", "ppv", "event", "live", "game pass", "match")

    /** Whole words (letters and digits runs, lower case). */
    private val WORDS: Set<String> = setOf(
        "nhl", "nfl", "nba", "wnba", "mlb", "mls", "ncaa", "ncaab", "ncaaf", "ncaaw", "ufc", "wwe", "aew", "mma", "boxing",
        "soccer", "football", "futbol", "hockey", "basketball", "baseball", "ahl", "ohl", "whl", "qmjhl", "chl", "echl",
        "espn", "dazn", "f1", "nascar", "golf", "pga", "tennis", "rugby", "cricket", "epl", "uefa", "fifa", "cfl", "lnh",
    )

    fun eventLike(group: String?): Boolean {
        if (group.isNullOrBlank()) return false
        return try {
            val g = group.lowercase()
            if (PARTS.any { it in g }) return true
            var i = 0
            while (i < g.length) {
                while (i < g.length && !g[i].isLetterOrDigit()) i++
                val st = i
                while (i < g.length && g[i].isLetterOrDigit()) i++
                if (i > st && g.substring(st, i) in WORDS) return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}
