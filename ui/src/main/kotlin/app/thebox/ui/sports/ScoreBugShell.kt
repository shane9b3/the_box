package app.thebox.ui.sports

import android.content.res.Resources
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.GameId
import app.thebox.core.sports.PowerPlay
import app.thebox.core.sports.PowerPlayKind
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.ui.R

// (Stacked commit 0, docs/design/m6-score-bug-stacked-theme.md §2.1) The NHL bugs' shared shell pieces. `LiveText` moved
// here from ScoreBug.kt unchanged but for its visibility (private → internal), so the Pill (`ScoreBugWith`) and the Stacked
// bug (`StackedBug`) share it.

/**
 * (Power play §7) The live region's text for the power play: the first of the goal string (while the mark is set), a start or
 * kind change, a real end ([ScoreBugView.ppEnded]), else "". A stale or waiting view leaves it unchanged; a new follow
 * resets it. Idempotent per view instance, so recompositions never repeat or lose a phrase.
 */
internal class LiveText {
    private var game: GameId? = null
    private var lastView: ScoreBugView? = null
    private var prev: PowerPlay? = null
    var text = ""
        private set

    fun next(v: ScoreBugView, goalDesc: String, res: Resources): String {
        if (v === lastView) return text
        lastView = v
        val first = v.gameId != game
        if (first) { game = v.gameId; prev = null; text = "" }
        if (v.bottom is CentreText.Delayed || v.bottom is CentreText.NoUpdate) return text
        val pp = v.powerPlay
        val p = prev
        val began = !first && pp != null && (p == null || p.side != pp.side || p.kind != pp.kind)
        prev = pp
        text = when {
            v.goalSide != null -> goalDesc
            began && pp != null -> res.getString(
                if (pp.kind == PowerPlayKind.TWO_MAN) R.string.sports_live_pp_two_man else R.string.sports_live_pp,
                (if (pp.side == Side.AWAY) v.away else v.home).name,
            )
            v.ppEnded -> res.getString(R.string.sports_live_pp_over)
            else -> ""
        }
        return text
    }
}
