package app.thebox.ui.sports

import android.content.res.Resources
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.EspnMapper
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.FollowedGame
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.LeagueDetail
import app.thebox.core.sports.League
import app.thebox.core.sports.NflBreak
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.RemoveReason
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.ScoreEvent
import app.thebox.core.sports.ScoreFacts
import app.thebox.core.sports.Sport
import app.thebox.core.sports.LeagueGroup
import app.thebox.core.sports.group
import app.thebox.core.sports.sport
import app.thebox.ui.R
import app.thebox.ui.full.PlayerText
import app.thebox.ui.guide.screen.TimeFormats
import java.util.Date
import java.util.Locale

/**
 * (Score bug commit 0, docs/design/m6-score-bug.md §4.5, §5, §6, §2.6) The words of the score feature, in one place for
 * the overlay (WP-3), the Sports tab (WP-4a), the quick bar and the picker (WP-4b) and the player VM's toasts. Every
 * string is in `strings_sports.xml`; nothing here allocates per frame (the bug's clock uses its own digit table).
 *
 * (NFL commit 0, docs/design/m6-nfl.md §2 S4) The league-dependent functions take a trailing `league` defaulted to the
 * NHL, so every NHL call is unchanged; (NFL WP-5) their NFL bodies: "4th", "Halftime", "End 3rd", "Sun 1:00 PM", the
 * week label and ESPN's failure and removal texts.
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
    fun period(res: Resources, p: PeriodLabel, league: League = League.NHL): String = when (p.kind) {
        PeriodKind.REG -> when (p.number) {
            1 -> res.getString(R.string.sports_period_1)
            2 -> res.getString(R.string.sports_period_2)
            3 -> res.getString(R.string.sports_period_3)
            // NFL: "4th"; every hockey league keeps "P4" (CHL §1.1: an exhaustive when over the sport, never `== NFL`)
            4 -> when (league.sport) {
                Sport.FOOTBALL -> res.getString(R.string.sports_period_4)
                Sport.HOCKEY -> res.getString(R.string.sports_period_n, p.number)
            }
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
        is CentreText.Period -> period(res, c.period, view.league)
        is CentreText.Intermission -> res.getString(R.string.sports_intermission, period(res, c.period, view.league))
        is CentreText.EndOf -> res.getString(R.string.sports_end_of, period(res, c.period, view.league))
        CentreText.Shootout -> res.getString(R.string.sports_period_so)
        is CentreText.Final -> final(res, c.kind)
        CentreText.NoUpdate -> res.getString(R.string.sports_no_update)
        CentreText.Delayed -> res.getString(R.string.sports_delayed)
        CentreText.Halftime -> res.getString(R.string.sports_halftime) // NFL §2 (S4)
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
     *
     * (NFL §7.2) For the NFL: "Sun 1:00 PM" (weekday and time in the device zone) when the game is not today, else
     * "1:00 PM"; "Halftime"; "End 3rd" in a quarter break; "4th 3:42" live. [nowMs] only decides "today" for a start time.
     */
    fun state(res: Resources, g: GameSnapshot, clock24: Boolean, league: League = League.NHL, nowMs: Long = System.currentTimeMillis()): String {
        val nfl = league == League.NFL || g.id.league == League.NFL
        // (CHL §1.6 #59) The game's own league decides the period words; the shown league only makes a game "NFL".
        val lg = if (nfl) League.NFL else g.id.league
        val p = periodOf(g)
        val clockSecs = g.clock?.secondsRemaining ?: 0
        return when (g.phase) {
            // (CHL B2) A league that lists the time as to be decided shows "Time TBD", never the placeholder midnight.
            GamePhase.SCHEDULED, GamePhase.PRE -> when {
                g.startTbd -> res.getString(R.string.sports_time_tbd)
                nfl -> nflStart(g.startUtcMs, clock24, nowMs)
                else -> startTime(g.startUtcMs, clock24)
            }
            GamePhase.POSTPONED -> res.getString(R.string.sports_postponed)
            GamePhase.FINAL -> final(res, g.finalKind)
            GamePhase.INTERMISSION -> when {
                nfl -> {
                    val kind = (g.detail as? LeagueDetail.Nfl)?.breakKind
                    when {
                        kind == NflBreak.HALFTIME -> res.getString(R.string.sports_halftime)
                        p != null -> res.getString(R.string.sports_end_of, period(res, p, lg))
                        else -> clock(clockSecs)
                    }
                }
                p != null -> res.getString(R.string.sports_intermission, period(res, p))
                else -> clock(clockSecs)
            }
            GamePhase.LIVE -> when {
                p == null -> res.getString(R.string.sports_live)
                p.kind == PeriodKind.SO -> period(res, p, lg)
                g.clock?.inIntermission == true -> res.getString(R.string.sports_intermission, period(res, p, lg))
                else -> res.getString(R.string.sports_picker_live_state, period(res, p, lg), clock(clockSecs))
            }
        }
    }

    /**
     * (NFL §7.2) A scheduled game's start: "1:00 PM" when it is today in the device zone, else "Sun 1:00 PM" (12 h) /
     * "Sun 13:00" (24 h).
     */
    fun nflStart(startUtcMs: Long, clock24: Boolean, nowMs: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val day = java.time.Instant.ofEpochMilli(startUtcMs).atZone(zone).toLocalDate()
        val time = if (clock24) "HH:mm" else "h:mm a"
        return TimeFormats.format(if (day == today) time else "EEE $time", locale, Date(startUtcMs))
    }

    /** True for an NFL week key ("w2026-2-9"; the one grammar and parser are `EspnMapper.weekKey` / `parseWeekKey`). */
    fun isWeekKey(key: String): Boolean = EspnMapper.parseWeekKey(key) != null

    /**
     * (NFL §7.2, S4) The label of a week key: "Week 9", "Wild Card", "Divisional Round", "Conference Championship",
     * "Super Bowl" (postseason weeks 1, 2, 3, 4/5), "Preseason week 2". The board carries no label of its own,
     * so it is derived from the key; a key that cannot be read says "NFL" and is never drawn raw.
     */
    fun weekLabel(res: Resources, key: String): String {
        val (_, type, week) = EspnMapper.parseWeekKey(key) ?: return res.getString(R.string.sports_week_unknown)
        return when (type) {
            2 -> res.getString(R.string.sports_week_n, week)
            1 -> res.getString(R.string.sports_week_preseason, week)
            3 -> when (week) {
                1 -> res.getString(R.string.sports_week_wild_card)
                2 -> res.getString(R.string.sports_week_divisional)
                3 -> res.getString(R.string.sports_week_conference)
                4, 5 -> res.getString(R.string.sports_week_super_bowl) // the calendar numbers it 4; ESPN's playoff weeks also use 5
                else -> res.getString(R.string.sports_week_unknown)
            }
            else -> res.getString(R.string.sports_week_unknown)
        }
    }

    /** A picker game row: "CGY 0–2 EDM · 2nd 10:22" / "CGY – EDM · 7:00 PM" (U-N9). */
    fun pickerGame(res: Resources, g: GameSnapshot, clock24: Boolean, nowMs: Long = System.currentTimeMillis()): String = when (g.phase) {
        GamePhase.SCHEDULED, GamePhase.PRE ->
            res.getString(R.string.sports_picker_game_upcoming, g.away.abbrev, g.home.abbrev, state(res, g, clock24, nowMs = nowMs))
        GamePhase.POSTPONED ->
            res.getString(R.string.sports_picker_game_upcoming, g.away.abbrev, g.home.abbrev, res.getString(R.string.sports_postponed))
        else -> res.getString(R.string.sports_picker_game_live, scoreLine(res, g), state(res, g, clock24, nowMs = nowMs))
    }

    /** A board's date for the picker divider when it is not today's (U-B1): "Wed, Nov 4"; the key itself if unreadable. */
    @Suppress("UNUSED_PARAMETER") // NFL week keys take [weekLabel] (it needs Resources); a date key reads the same in both leagues
    fun dayLabel(dayKey: String, locale: Locale = Locale.getDefault(), league: League = League.NHL): String =
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
        } else if (game.startTbd) {
            // (CHL review S1) Never "Starts 12:00 AM" from the placeholder.
            res.getString(R.string.sports_time_tbd)
        } else {
            res.getString(R.string.sports_quick_starts, startTime(game.startUtcMs, clock24))
        }
    }

    /**
     * §5.4: the day list's failure text: no network; (CHL §2.6, S7) a refused CHL key, which needs an app update; else the
     * league's own "did not answer" line (NHL.com, ESPN, or "OHL scores did not answer").
     */
    fun failure(res: Resources, kind: FailKind, league: League = League.NHL): String = when {
        kind == FailKind.OFFLINE -> res.getString(R.string.sports_no_network)
        else -> when (league.group) {
            LeagueGroup.NHL -> res.getString(R.string.sports_failed)
            LeagueGroup.NFL -> res.getString(R.string.sports_failed_espn)
            LeagueGroup.CHL ->
                if (kind == FailKind.REFUSED) res.getString(R.string.sports_failed_refused, PlayerText.leagueName(res, league))
                else res.getString(R.string.sports_failed_league, PlayerText.leagueName(res, league))
        }
    }

    /** (CHL §6.5) "No OHL games today. Pick another day above." for a CHL day board; the NHL and NFL keep their own strings. */
    fun emptyDay(res: Resources, day: SportsDay, league: League, weekOff: Boolean = false): String = when (league.group) {
        LeagueGroup.NHL -> res.getString(
            when (day) {
                SportsDay.YESTERDAY -> R.string.sports_empty_yesterday
                SportsDay.TODAY -> R.string.sports_empty_today
                SportsDay.TOMORROW -> R.string.sports_empty_tomorrow
            },
        )
        LeagueGroup.NFL -> res.getString(if (weekOff) R.string.sports_week_off else R.string.sports_empty_week)
        LeagueGroup.CHL -> res.getString(
            when (day) {
                SportsDay.YESTERDAY -> R.string.sports_empty_yesterday_league
                SportsDay.TODAY -> R.string.sports_empty_today_league
                SportsDay.TOMORROW -> R.string.sports_empty_tomorrow_league
            },
            PlayerText.leagueName(res, league),
        )
    }

    // ------------------------------------------------------------------ Sports Hub (docs/design/m6-sports-hub.md §1.2, §3, §6.7, §8.2)

    /** A status pill: its upper-case [text] and whether the red dot and the live fill are drawn. */
    data class HubPill(val text: String, val live: Boolean)

    /** "1ST" / "2ND" / "3RD" / "OT" / "2OT" / "SO" (hockey), "Q3" / "OT" (football): the pill's period words (upper case). */
    fun hubPeriod(res: Resources, p: PeriodLabel, league: League): String = when (p.kind) {
        PeriodKind.REG -> when (league.sport) {
            Sport.FOOTBALL -> res.getString(R.string.sports_hub_quarter_n, p.number)
            Sport.HOCKEY -> when (p.number) {
                1 -> res.getString(R.string.sports_hub_period_1)
                2 -> res.getString(R.string.sports_hub_period_2)
                3 -> res.getString(R.string.sports_hub_period_3)
                else -> res.getString(R.string.sports_period_n, p.number)
            }
        }
        PeriodKind.OT -> if (p.number <= 1) res.getString(R.string.sports_hub_period_ot) else res.getString(R.string.sports_hub_period_ot_n, p.number)
        PeriodKind.SO -> res.getString(R.string.sports_hub_period_so)
    }

    /**
     * The card's and the panel's status pill (§1.2): `FINAL`, `FINAL/OT`, `FINAL/SO`, `LIVE · 2ND 10:22`, `1ST INT`, `7:00 PM`,
     * `POSTPONED`, `TIME TBD`; the NFL's upcoming games read `SUN 1:00 PM`, with the date (`MON NOV 30 8:15 PM`) when the start
     * is more than 5 days away (U-S6). The clock is the snapshot's own value (no local countdown).
     */
    fun hubPill(res: Resources, g: GameSnapshot, clock24: Boolean, nowMs: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): HubPill {
        val league = g.id.league
        val p = periodOf(g)
        return when (g.phase) {
            GamePhase.SCHEDULED, GamePhase.PRE -> HubPill(
                when {
                    g.startTbd -> res.getString(R.string.sports_hub_pill_tbd)
                    league.sport == Sport.FOOTBALL -> nflPill(res, g.startUtcMs, clock24, nowMs, locale)
                    else -> startTime(g.startUtcMs, clock24, locale)
                },
                live = false,
            )
            GamePhase.POSTPONED -> HubPill(res.getString(R.string.sports_hub_pill_postponed), false)
            GamePhase.FINAL -> HubPill(
                res.getString(
                    when (g.finalKind) {
                        PeriodKind.OT -> R.string.sports_hub_pill_final_ot
                        PeriodKind.SO -> R.string.sports_hub_pill_final_so
                        PeriodKind.REG, null -> R.string.sports_hub_pill_final
                    },
                ),
                false,
            )
            GamePhase.INTERMISSION -> HubPill(intermissionPill(res, g, p, league), live = false)
            GamePhase.LIVE -> when {
                p == null -> HubPill(res.getString(R.string.sports_live), true)
                p.kind == PeriodKind.SO -> HubPill(res.getString(R.string.sports_hub_pill_live, hubPeriod(res, p, league)), true)
                g.clock?.inIntermission == true -> HubPill(intermissionPill(res, g, p, league), false)
                else -> {
                    val secs = g.clock?.secondsRemaining?.coerceAtLeast(0)
                    val detail = if (secs == null) hubPeriod(res, p, league) else "${hubPeriod(res, p, league)} ${clock(secs)}"
                    // (NFL hub §2.5, UX m12) A weather or other delay: "DELAYED · Q3 5:32", so a still clock is explained.
                    if ((g.detail as? LeagueDetail.Nfl)?.delayed == true) HubPill(res.getString(R.string.sports_hub_pill_delayed, detail), true)
                    else HubPill(res.getString(R.string.sports_hub_pill_live, detail), true)
                }
            }
        }
    }

    private fun intermissionPill(res: Resources, g: GameSnapshot, p: PeriodLabel?, league: League): String {
        val kind = (g.detail as? LeagueDetail.Nfl)?.breakKind
        return when {
            kind == NflBreak.HALFTIME -> res.getString(R.string.sports_hub_halftime)
            // (NFL hub §2.1, UX m4) Football's end of a quarter: "END Q1", "END OT"; hockey keeps "1ST INT".
            p != null && league.sport == Sport.FOOTBALL -> res.getString(R.string.sports_hub_pill_end_quarter, hubPeriod(res, p, league))
            p != null -> res.getString(R.string.sports_hub_pill_intermission, hubPeriod(res, p, league))
            else -> res.getString(R.string.sports_live)
        }
    }

    private fun nflPill(res: Resources, startUtcMs: Long, clock24: Boolean, nowMs: Long, locale: Locale): String {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val day = java.time.Instant.ofEpochMilli(startUtcMs).atZone(zone).toLocalDate()
        val diff = java.time.temporal.ChronoUnit.DAYS.between(today, day)
        val time = startTime(startUtcMs, clock24, locale)
        return if (diff in 0..5) {
            res.getString(R.string.sports_hub_pill_weekday_time, TimeFormats.format("EEE", locale, Date(startUtcMs)).uppercase(locale), time)
        } else {
            res.getString(R.string.sports_hub_pill_date_time, TimeFormats.format("EEE MMM d", locale, Date(startUtcMs)).uppercase(locale), time)
        }
    }

    /** "1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st" (English rules, §8.1 ordinals). */
    fun ordinal(res: Resources, n: Int): String {
        val mod100 = n % 100
        val id = when {
            mod100 in 11..13 -> R.string.sports_hub_ordinal_th
            n % 10 == 1 -> R.string.sports_hub_ordinal_st
            n % 10 == 2 -> R.string.sports_hub_ordinal_nd
            n % 10 == 3 -> R.string.sports_hub_ordinal_rd
            else -> R.string.sports_hub_ordinal_th
        }
        return res.getString(id, n)
    }

    /** A period's row/ordinal word in the goal sentences ("1st", "2nd", "3rd", "OT", "2OT", "SO"). */
    fun periodWord(res: Resources, p: PeriodLabel): String = when (p.kind) {
        PeriodKind.REG -> if (p.number in 1..3) ordinal(res, p.number) else res.getString(R.string.sports_period_n, p.number)
        PeriodKind.OT -> if (p.number <= 1) res.getString(R.string.sports_hub_row_ot) else res.getString(R.string.sports_hub_period_ot_n, p.number)
        PeriodKind.SO -> res.getString(R.string.sports_hub_row_so)
    }

    /** "4:55" from a duration in ms ("m:ss"; the bug's clock rule). */
    fun duration(ms: Long): String = clock((ms / 1000L).toInt())

    /** "4 minutes 55 seconds" (§8.2 media and progress descriptions). */
    fun spokenDuration(res: Resources, ms: Long): String {
        val s = (ms / 1000L).toInt().coerceAtLeast(0)
        val m = s / 60
        val r = s % 60
        val minutes = if (m > 0) res.getQuantityString(R.plurals.sports_hub_desc_minutes, m, m) else null
        val seconds = if (r > 0 || m == 0) res.getQuantityString(R.plurals.sports_hub_desc_seconds, r, r) else null
        return listOfNotNull(minutes, seconds).joinToString(" ")
    }

    /** The day-strip label of a date chip (§3.1): "Yesterday · Nov 23", "Today · Nov 24", "Tomorrow · Nov 25", "Sat · Nov 21". */
    fun hubDayLabel(res: Resources, offset: Int, date: java.time.LocalDate, locale: Locale = Locale.getDefault()): String {
        val short = date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", locale))
        return when (offset) {
            -1 -> res.getString(R.string.sports_hub_day_yesterday, short)
            0 -> res.getString(R.string.sports_hub_day_today, short)
            1 -> res.getString(R.string.sports_hub_day_tomorrow, short)
            else -> res.getString(R.string.sports_hub_day_other, date.format(java.time.format.DateTimeFormatter.ofPattern("EEE", locale)), short)
        }
    }

    /** "Saturday, November 21" for the content description of a day (§8.2). */
    fun spokenDate(date: java.time.LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d", locale))

    /** An NFL week chip: "Last week · Wk 11" (§3.1); the week number comes from the board's key, "Wk" is absent for a playoff round. */
    fun hubWeekChip(res: Resources, offset: Int, key: String?): String {
        val parsed = key?.let { EspnMapper.parseWeekKey(it) }
        val week = if (parsed != null && parsed.second == 2) res.getString(R.string.sports_hub_week_n, parsed.third)
            else if (key != null) weekLabel(res, key) else null
        val base = res.getString(
            when {
                offset < 0 -> R.string.sports_week_last
                offset == 0 -> R.string.sports_week_this
                else -> R.string.sports_week_next
            },
        )
        return if (week == null) base else res.getString(
            when {
                offset < 0 -> R.string.sports_hub_week_last
                offset == 0 -> R.string.sports_hub_week_this
                else -> R.string.sports_hub_week_next
            },
            week,
        )
    }

    /** The toast of a repository event (§2.6, §5.3, U-S12); always a reason and a next step. */
    fun toast(res: Resources, e: ScoreEvent): String = when (e) {
        is ScoreEvent.Followed -> if (e.first) res.getString(R.string.sports_toast_following_first, e.game.away, e.game.home)
            else res.getString(R.string.sports_toast_following, e.game.away, e.game.home)
        is ScoreEvent.FollowedFinal -> res.getString(R.string.sports_toast_followed_final)
        is ScoreEvent.FollowTooOld -> res.getString(
            if (e.final) R.string.sports_toast_too_old_final else R.string.sports_toast_too_old, e.game.away, e.game.home,
        )
        is ScoreEvent.Removed -> removed(res, e.why, e.game, e.game.league)
    }

    private fun removed(res: Resources, why: RemoveReason, g: FollowedGame, league: League = League.NHL): String = when (why) {
        RemoveReason.POSTPONED -> res.getString(R.string.sports_toast_postponed, g.away, g.home)
        RemoveReason.NOT_LISTED -> when (league.group) {
            LeagueGroup.NHL -> res.getString(R.string.sports_toast_not_listed, g.away, g.home)
            LeagueGroup.NFL -> res.getString(R.string.sports_toast_not_listed_espn, g.away, g.home)
            LeagueGroup.CHL -> res.getString(R.string.sports_toast_not_listed_league, g.away, g.home, PlayerText.leagueName(res, league))
        }
        RemoveReason.SPORTS_OFF -> res.getString(R.string.sports_toast_sports_off)
    }
}
