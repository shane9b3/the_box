package app.thebox.ui.sports

import android.content.res.Resources
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.FollowedGame
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.RemoveReason
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.ScoreEvent
import app.thebox.core.sports.ScoreFacts
import app.thebox.core.sports.Side
import app.thebox.ui.R
import app.thebox.ui.guide.screen.TimeFormats
import java.util.Date
import java.util.Locale

/**
 * (Score bug commit 0, docs/design/m6-score-bug.md §4.5, §5, §6, §2.6) The words of the score feature, in one place for
 * the overlay (WP-3), the Sports tab (WP-4a), the quick bar and the picker (WP-4b) and the player VM's toasts. Every
 * string is in `strings_sports.xml`; nothing here allocates per frame (the bug's clock uses its own digit table).
 */
object SportsText {
    /** `m:ss` below 10:00, `mm:ss` from 10:00 (§4.5); negative values read 0:00. */
    fun clock(secs: Int): String {
        val s = secs.coerceAtLeast(0)
        val m = s / 60
        val r = s % 60
        return if (r < 10) "$m:0$r" else "$m:$r"
    }

    /** "1st" / "2nd" / "3rd", "OT" / "2OT", "SO". */
    fun period(res: Resources, p: PeriodLabel): String = when (p.kind) {
        PeriodKind.REG -> when (p.number) {
            1 -> res.getString(R.string.sports_period_1)
            2 -> res.getString(R.string.sports_period_2)
            3 -> res.getString(R.string.sports_period_3)
            else -> res.getString(R.string.sports_period_n, p.number)
        }
        PeriodKind.OT -> if (p.number <= 1) res.getString(R.string.sports_period_ot) else res.getString(R.string.sports_period_ot_n, p.number)
        PeriodKind.SO -> res.getString(R.string.sports_period_so)
    }

    /** A snapshot's period label (REG period n, OT number, SO); null before the game. */
    fun periodOf(g: GameSnapshot): PeriodLabel? {
        val kind = g.periodKind ?: return null
        return when (kind) {
            PeriodKind.REG -> PeriodLabel(kind, g.period ?: return null)
            PeriodKind.OT -> PeriodLabel(kind, g.otNumber ?: 1)
            PeriodKind.SO -> PeriodLabel(kind, 1)
        }
    }

    /** "Final", "Final/OT", "Final/SO". */
    fun final(res: Resources, kind: PeriodKind?): String = when (kind) {
        PeriodKind.OT -> res.getString(R.string.sports_final_ot)
        PeriodKind.SO -> res.getString(R.string.sports_final_so)
        PeriodKind.REG, null -> res.getString(R.string.sports_final)
    }

    /**
     * One centre line of [view]; [secs] is the clock's displayed value (only [CentreText.Clock] reads it). The bug draws
     * its clock from a digit table instead; this is for measuring, semantics and tests.
     */
    fun centre(res: Resources, c: CentreText, view: ScoreBugView, secs: Int): String = when (c) {
        CentreText.Clock -> clock(secs)
        is CentreText.Period -> period(res, c.period)
        is CentreText.Intermission -> res.getString(R.string.sports_intermission, period(res, c.period))
        is CentreText.EndOf -> res.getString(R.string.sports_end_of, period(res, c.period))
        CentreText.Shootout -> res.getString(R.string.sports_period_so)
        is CentreText.Goal -> res.getString(R.string.sports_goal, (if (c.side == Side.AWAY) view.away else view.home).abbrev)
        is CentreText.Final -> final(res, c.kind)
        CentreText.NoUpdate -> res.getString(R.string.sports_no_update)
        CentreText.Delayed -> res.getString(R.string.sports_delayed)
    }

    fun corner(res: Resources, c: ScoreCorner): String = res.getString(
        when (c) {
            ScoreCorner.TOP_RIGHT -> R.string.sports_corner_top_right
            ScoreCorner.TOP_LEFT -> R.string.sports_corner_top_left
            ScoreCorner.BOTTOM_RIGHT -> R.string.sports_corner_bottom_right
            ScoreCorner.BOTTOM_LEFT -> R.string.sports_corner_bottom_left
        },
    )

    /** "Off" for 0, else "30 s". */
    fun delay(res: Resources, sec: Int): String =
        if (sec <= 0) res.getString(R.string.sports_delay_off) else res.getString(R.string.sports_delay_seconds, sec)

    fun size(res: Resources, s: ScoreBugSize): String = res.getString(
        when (s) {
            ScoreBugSize.STANDARD -> R.string.sports_size_standard
            ScoreBugSize.LARGE -> R.string.sports_size_large
        },
    )

    /** A start time in the device zone: "7:00 PM" (12 h) or "19:00" (24 h). */
    fun startTime(startUtcMs: Long, clock24: Boolean, locale: Locale = Locale.getDefault()): String =
        TimeFormats.format(if (clock24) "HH:mm" else "h:mm a", locale, Date(startUtcMs))

    /** "CGY 0–2 EDM" (away first). */
    fun scoreLine(res: Resources, g: GameSnapshot): String =
        res.getString(R.string.sports_quick_score, g.away.abbrev, g.away.score ?: 0, g.home.score ?: 0, g.home.abbrev)

    /**
     * A game's state words for the picker and the cards: "2nd 10:22", "1st INT", "SO", "Final/OT", "7:00 PM". The clock is
     * the snapshot's own value (owner ruling 6: nothing counts down between updates).
     */
    fun state(res: Resources, g: GameSnapshot, clock24: Boolean): String {
        val p = periodOf(g)
        val clockSecs = g.clock?.secondsRemaining ?: 0
        return when (g.phase) {
            GamePhase.SCHEDULED, GamePhase.PRE -> startTime(g.startUtcMs, clock24)
            GamePhase.POSTPONED -> res.getString(R.string.sports_postponed)
            GamePhase.FINAL -> final(res, g.finalKind)
            GamePhase.INTERMISSION -> if (p != null) res.getString(R.string.sports_intermission, period(res, p)) else clock(clockSecs)
            GamePhase.LIVE -> when {
                p == null -> res.getString(R.string.sports_live)
                p.kind == PeriodKind.SO -> period(res, p)
                g.clock?.inIntermission == true -> res.getString(R.string.sports_intermission, period(res, p))
                else -> res.getString(R.string.sports_picker_live_state, period(res, p), clock(clockSecs))
            }
        }
    }

    /** A picker game row: "CGY 0–2 EDM · 2nd 10:22" / "CGY – EDM · 7:00 PM" (U-N9). */
    fun pickerGame(res: Resources, g: GameSnapshot, clock24: Boolean): String = when (g.phase) {
        GamePhase.SCHEDULED, GamePhase.PRE ->
            res.getString(R.string.sports_picker_game_upcoming, g.away.abbrev, g.home.abbrev, startTime(g.startUtcMs, clock24))
        GamePhase.POSTPONED ->
            res.getString(R.string.sports_picker_game_upcoming, g.away.abbrev, g.home.abbrev, res.getString(R.string.sports_postponed))
        else -> res.getString(R.string.sports_picker_game_live, scoreLine(res, g), state(res, g, clock24))
    }

    /** A board's date for the picker divider when it is not today's (U-B1): "Wed, Nov 4"; the key itself if unreadable. */
    fun dayLabel(dayKey: String, locale: Locale = Locale.getDefault()): String =
        runCatching { java.time.LocalDate.parse(dayKey).format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", locale)) }
            .getOrDefault(dayKey)

    /**
     * The quick-bar value line (§6): "Follow a game" / "CGY 0–2 EDM" / "Starts 7:00 PM". U-S4: a game whose bug is hidden
     * for want of updates (after puck drop) keeps its last known score ([ScoreFacts.lastScore]); "Starts …" is only for a
     * game that has not gone live.
     */
    fun quickValue(res: Resources, facts: ScoreFacts, clock24: Boolean): String {
        val game = facts.game ?: return res.getString(R.string.sports_quick_follow)
        val view = facts.followed ?: facts.lastScore
        return if (view != null) {
            res.getString(R.string.sports_quick_score, view.away.abbrev, view.away.score ?: 0, view.home.score ?: 0, view.home.abbrev)
        } else {
            res.getString(R.string.sports_quick_starts, startTime(game.startUtcMs, clock24))
        }
    }

    /** §5.4: the day list's failure text (no network vs NHL.com did not answer). */
    fun failure(res: Resources, kind: FailKind): String =
        res.getString(if (kind == FailKind.OFFLINE) R.string.sports_no_network else R.string.sports_failed)

    /** The toast of a repository event (§2.6, §5.3, U-S12); always a reason and a next step. */
    fun toast(res: Resources, e: ScoreEvent): String = when (e) {
        is ScoreEvent.Followed -> if (e.first) res.getString(R.string.sports_toast_following_first, e.game.away, e.game.home)
            else res.getString(R.string.sports_toast_following, e.game.away, e.game.home)
        is ScoreEvent.FollowedFinal -> res.getString(R.string.sports_toast_followed_final)
        is ScoreEvent.Removed -> removed(res, e.why, e.game)
    }

    private fun removed(res: Resources, why: RemoveReason, g: FollowedGame): String = when (why) {
        RemoveReason.POSTPONED -> res.getString(R.string.sports_toast_postponed, g.away, g.home)
        RemoveReason.NOT_LISTED -> res.getString(R.string.sports_toast_not_listed, g.away, g.home)
        RemoveReason.SPORTS_OFF -> res.getString(R.string.sports_toast_sports_off)
    }
}
