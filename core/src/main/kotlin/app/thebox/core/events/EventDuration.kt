// docs/design/m6-event-channels.md §3.5, §4.1 (WP-1).
package app.thebox.core.events

/** The estimated length of an event from its title's tokens (§3.5; the guide shows the end as "about"). */
object EventDuration {
    /** One-word and two-word football markers (§3.5). */
    private val FOOTBALL: List<List<String>> = listOf(listOf("nfl"), listOf("ncaaf"), listOf("cfl"), listOf("college", "football"))

    /** Soccer markers (§3.5); the " v " separator also counts. */
    private val SOCCER: List<List<String>> = listOf(
        listOf("uefa"), listOf("champions", "league"), listOf("premier", "league"), listOf("epl"), listOf("efl"),
        listOf("la", "liga"), listOf("ligue", "1"), listOf("bundesliga"), listOf("serie", "a"), listOf("mls"), listOf("fc"),
    )

    /**
     * Minutes: [EventConstants.EVENT_MIN_FOOTBALL], [EventConstants.EVENT_MIN_SOCCER], [EventConstants.EVENT_MIN_MATCHUP]
     * or [EventConstants.EVENT_MIN_SHOW]. A title "has a matchup" when one of its segments carries a §3.7 separator, even
     * when it lists several games ("CLEM vs. WASH • SAM vs. GT" is a matchup block, not a show). Never throws.
     */
    fun minutes(title: String): Int = try {
        minutesImpl(title)
    } catch (e: Exception) {
        EventConstants.EVENT_MIN_SHOW
    }

    private fun minutesImpl(title: String): Int {
        if (!EventMatchup.hasMatchup(title)) return EventConstants.EVENT_MIN_SHOW
        val tokens = EventText.tokens(title)
        if (FOOTBALL.any { tokens.containsRun(it) }) return EventConstants.EVENT_MIN_FOOTBALL
        if (SOCCER.any { tokens.containsRun(it) } || hasVSeparator(title)) return EventConstants.EVENT_MIN_SOCCER
        return EventConstants.EVENT_MIN_MATCHUP
    }

    private fun hasVSeparator(title: String): Boolean =
        title.indexOf(" v ", ignoreCase = true) >= 0 || title.indexOf(" v. ", ignoreCase = true) >= 0

    private fun List<String>.containsRun(run: List<String>): Boolean {
        if (run.size > size) return false
        outer@ for (i in 0..size - run.size) {
            for (j in run.indices) if (this[i + j] != run[j]) continue@outer
            return true
        }
        return false
    }
}
