// docs/design/m6-event-channels.md §4.2, §8.2 (WP-1).
package app.thebox.core.sports

import app.thebox.core.sports.hub.BroadcastAliases

/** §8.2: a channel is a team channel when its name equals a team's name **and** its group or tvg id says so. */
object TeamChannelRule {
    /** Group words that make a "… Teams" group something else (radio feeds, replays). */
    private val GROUP_VETO: Set<String> = setOf("radio", "replay", "replays", "highlight", "highlights", "classic", "classics")

    private fun leagueWord(league: League): String? = when (league) {
        League.NHL -> "nhl"
        League.NFL -> "nfl"
        else -> null
    }

    /**
     * True when [group] holds the adjacent words "<league> team(s)" (any prefix: "CA | NHL TEAMS", "NHL Teams (2)") and
     * none of radio, replay(s), highlights or classic(s).
     */
    fun groupIsTeams(league: League, group: String?): Boolean {
        if (group.isNullOrEmpty()) return false
        val word = leagueWord(league) ?: return false
        val tokens = TeamNames.tokens(group)
        if (tokens.any { it in GROUP_VETO }) return false
        for (i in 0 until tokens.size - 1) {
            if (tokens[i] == word && (tokens[i + 1] == "team" || tokens[i + 1] == "teams")) return true
        }
        return false
    }

    /**
     * The team whose channel this is, or null (both the name rule and the context rule must hold). The name rule: the
     * `BroadcastAliases.residue` of [name] (country tag and quality words gone), less a leading league word, equals one
     * of the team's full names or nicknames. The context rule: [groupIsTeams], or [tvgId] is `<stem>.<league>` (an
     * `@…` suffix allowed) with a stem of the same team. Never throws.
     */
    fun teamOf(league: League, name: String, group: String?, tvgId: String?): TeamName? = try {
        teamOfImpl(league, name, group, tvgId)
    } catch (e: Exception) {
        null
    }

    private fun teamOfImpl(league: League, name: String, group: String?, tvgId: String?): TeamName? {
        val word = leagueWord(league) ?: return null
        var tokens = TeamNames.tokens(BroadcastAliases.residue(name).text)
        if (tokens.size > 1 && tokens[0] == word) tokens = tokens.subList(1, tokens.size)
        if (tokens.isEmpty()) return null
        val team = TeamNames.all(league).firstOrNull {
            val s = TeamNames.sideMatch(tokens, it)
            s == TeamNames.Strength.FULL || s == TeamNames.Strength.NICKNAME
        } ?: return null
        return if (groupIsTeams(league, group) || tvgNames(team, word, tvgId)) team else null
    }

    private fun tvgNames(team: TeamName, word: String, tvgId: String?): Boolean {
        if (tvgId.isNullOrBlank()) return false
        var id = tvgId.trim()
        val at = id.indexOf('@')
        if (at >= 0) id = id.substring(0, at)
        val dot = id.lastIndexOf('.')
        if (dot <= 0 || !id.substring(dot + 1).equals(word, ignoreCase = true)) return false
        val stem = alnum(id.substring(0, dot))
        return team.tvgStems.any { alnum(it) == stem }
    }

    private fun alnum(s: String): String = TeamNames.tokens(s).joinToString("")
}
