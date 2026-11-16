package app.thebox.ui.sports

import android.content.res.Resources
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.core.sports.TeamLine
import app.thebox.ui.R
import java.util.Locale

// (NFL §8; NFL Stacked integration rulings 2026-11-15) The NFL texts both NFL bugs share: the Pill (`NflBug`) and the
// Stacked (`StackedNflBug`) call these, so the announcement wording exists once. Moved verbatim from `NflBug.kt`
// (where they were private), so the Pill's strings and drawing are unchanged.

/** NFL two-line states put "Delayed" / "No update" on the top line; one-line breaks have only a top. */
internal fun nflWaiting(v: ScoreBugView): Boolean =
    v.top is CentreText.Delayed || v.top is CentreText.NoUpdate || v.bottom is CentreText.Delayed || v.bottom is CentreText.NoUpdate

/** A team's short name for the spoken texts (`TeamLine.shortName`, else the name). */
internal fun nflShortOf(t: TeamLine) = t.shortName ?: t.name

/** The score kind's word, lower case, for the spoken texts. */
internal fun nflKindWord(res: Resources, k: NflScoreKind): String = res.getString(
    when (k) {
        NflScoreKind.TOUCHDOWN -> R.string.sports_nfl_td
        NflScoreKind.EXTRA_POINT -> R.string.sports_nfl_xp
        NflScoreKind.FIELD_GOAL -> R.string.sports_nfl_fg
        NflScoreKind.SAFETY -> R.string.sports_nfl_safety
        NflScoreKind.TWO_POINT -> R.string.sports_nfl_2pt
    },
).lowercase(Locale.getDefault())

/** The score announcement of a view under the mark ("%5$s %6$s. %1$s %2$d, %3$s %4$d"); null with no score kind. */
internal fun nflScoreText(res: Resources, v: ScoreBugView): String? {
    val scores = v.nfl?.scores.orEmpty()
    val side = v.goalSide ?: return null
    if (scores.isEmpty()) return null
    val a = v.away
    val h = v.home
    val scorer = (if (side == Side.AWAY) a else h).name
    val first = nflKindWord(res, scores[0].kind)
    return if (scores.size >= 2) {
        res.getString(R.string.sports_live_nfl_pair, nflShortOf(a), a.score ?: 0, nflShortOf(h), h.score ?: 0, scorer, first, nflKindWord(res, scores[1].kind))
    } else {
        res.getString(R.string.sports_live_nfl_score, nflShortOf(a), a.score ?: 0, nflShortOf(h), h.score ?: 0, scorer, first)
    }
}
