package app.thebox.ui.sports

import android.content.res.Resources
import app.thebox.core.settings.UiTextScale
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.GoalItem
import app.thebox.core.sports.hub.Ranked
import app.thebox.core.sports.hub.StatKind
import app.thebox.ui.R
import java.text.NumberFormat
import java.util.Locale

/**
 * The CHL's hub (OHL, WHL, QMJHL).
 *
 * (CHL hub P3, docs/design/m6-chl-hub.md §2, §3, §4.5 #2) With the HockeyTech detail ([enabled], the build's
 * `CHL_HUB_ENABLED`): the pre-game SEASON rows with league ranks, the season series and the season points leaders, the
 * Matchup slot; the final's TEAM STATS rows, the game-winning goal and the three stars, the venue · attendance caption,
 * Watch from start (only with a catch-up airing) · Goals · Box score; the postponed reason line. **Live is today's board
 * rows and slots** (§7), and so is every phase while the switch is off: the board-only branches of `HubPanelContents` and
 * `HubSlots.default` as NFL hub commit 0 moved them (docs/design/m6-sports-hub.md §7.1). No video in any phase.
 *
 * Ranks and goalie stats are whatever the detail carries: the mapper leaves them null in the playoffs and on a partial
 * answer, so the rows show the value without a rank and the names without stats, with no error (§2.1, §4.4 a).
 */
internal object ChlHubUi : HubLeagueUi {
    /**
     * `SportsConstants.CHL_HUB_ENABLED` (§0 D7): false = today's board-only CHL panel. A `var` only so the screen tests can
     * draw the detail screens while the build switch is still off (the integration sets the constant true); production code
     * never writes it.
     */
    @Volatile internal var enabled: Boolean = SportsConstants.CHL_HUB_ENABLED

    override val detailed: Boolean get() = enabled
    override val source: HubSource get() = if (enabled) HubSource.HOCKEYTECH else HubSource.NONE
    /** (§4.3) The detail carries no score: the board gives the headline. */
    override val detailCarriesScore: Boolean get() = false

    /** No highlights in any CHL phase (D3). */
    override fun cardHighlights(game: GameSnapshot, detail: GameDetail?, nowMs: Long): Boolean = false

    /** The final's stat rows, in the panel's order; a kind the detail lacks (FO% with no faceoffs, hits) is left out. */
    private val FINAL_KINDS = listOf(StatKind.SOG, StatKind.POWER_PLAY, StatKind.FACEOFF_PCT, StatKind.PIM, StatKind.HITS)

    private fun live(g: GameSnapshot) = g.phase == GamePhase.LIVE || g.phase == GamePhase.INTERMISSION

    /** Today's board rows (§7.1), unchanged: the board's shots after puck drop, the live power play. */
    private fun boardRows(res: Resources, g: GameSnapshot, away: HubName, home: HubName): List<PanelRow> {
        val live = live(g)
        val final = g.phase == GamePhase.FINAL
        val pre = !live && !final
        val x = g.extras
        val r = ArrayList<PanelRow>()
        if (!pre && x?.awayShots != null && x.homeShots != null) r += HubPanelContents.valueRow(res.getString(R.string.sports_hub_stat_shots), x.awayShots!!, x.homeShots!!, away, home, res)
        val pp = g.powerPlay
        if (live && pp != null) {
            val t = pp.secondsRemaining?.let { SportsText.clock(it) } ?: res.getString(R.string.sports_hub_stat_none)
            val none = res.getString(R.string.sports_hub_stat_none)
            r += PanelRow(
                res.getString(R.string.sports_hub_stat_power_play), if (pp.side == Side.AWAY) t else none, if (pp.side == Side.HOME) t else none, null,
                res.getString(R.string.sports_hub_desc_stat, res.getString(R.string.sports_hub_stat_power_play), away.nickname, if (pp.side == Side.AWAY) t else none, home.nickname, if (pp.side == Side.HOME) t else none),
            )
        }
        return r
    }

    /** Whether this game's panel is today's board-only one: the switch off, live (§7), or a board-only answer. */
    private fun boardOnly(m: HubPanelModel, g: GameSnapshot): Boolean = !enabled || live(g) || m.detailKind == DetailKind.BOARD_ONLY

    // ------------------------------------------------------------------ rows (§2.1)

    override fun rows(res: Resources, m: HubPanelModel, g: GameSnapshot, away: HubName, home: HubName): RowsSpec {
        val final = g.phase == GamePhase.FINAL
        if (boardOnly(m, g)) {
            val header = res.getString(R.string.sports_hub_stats_team)
            val rows = boardRows(res, g, away, home)
            // (NFL hub §6.7) The CHL's own words: the NHL-first line is gone with the NFL detail.
            return RowsSpec(header = header, rows = rows, moreSoon = if (rows.isEmpty()) res.getString(R.string.sports_hub_more_soon_chl) else null)
        }
        // (§2.5) Postponed: no detail; the reason line is the summary.
        if (g.phase == GamePhase.POSTPONED) return RowsSpec(header = res.getString(R.string.sports_hub_stats_team), rows = emptyList())
        val d = m.detail
        val failed = m.detailKind == DetailKind.FAILED && d == null
        if (final) {
            val header = res.getString(R.string.sports_hub_stats_team)
            if (d != null && d.gameStats.isNotEmpty()) return RowsSpec(header = header, rows = finalRows(res, d, away, home))
            // (§2.1) While the final loads, or failed with nothing cached, the board's Shots row stays (never less than today).
            val board = boardRows(res, g, away, home)
            if (board.isNotEmpty()) return RowsSpec(header = header, rows = board)
            if (failed) return RowsSpec(header = header, rows = emptyList(), error = failure(res, m, g, final = true))
            val none = res.getString(R.string.sports_hub_stat_none)
            val blank = listOf(StatKind.SOG, StatKind.POWER_PLAY, StatKind.PIM).map { HubPanelContents.blank(res.getString(HubPanelContents.statLabel(it)), none, away, home, res) }
            return RowsSpec(header = header, rows = blank, loading = true)
        }
        val header = res.getString(R.string.sports_hub_stats_season)
        if (failed) return RowsSpec(header = header, rows = emptyList(), error = failure(res, m, g, final = false))
        return RowsSpec(header = header, rows = seasonRows(res, d, away, home), loading = d == null)
    }

    private fun failure(res: Resources, m: HubPanelModel, g: GameSnapshot, final: Boolean): String =
        HubPanelContents.failureText(res, m.failure, m.slots, final, HubSource.HOCKEYTECH, g.id.league)

    private fun finalRows(res: Resources, d: GameDetail, away: HubName, home: HubName): List<PanelRow> {
        val byKind = d.gameStats.associateBy { it.kind }
        return FINAL_KINDS.mapNotNull { k -> byKind[k]?.let { HubPanelContents.statRow(res, res.getString(HubPanelContents.statLabel(k)), it, away, home) } }
    }

    /** The NHL's six SEASON rows (Record W-L-OTL-SOL, Last 10, GF/GA per game, PP%, PK%) with nullable ranks. */
    private fun seasonRows(res: Resources, d: GameDetail?, away: HubName, home: HubName): List<PanelRow> {
        val none = res.getString(R.string.sports_hub_stat_none)
        val s = d?.season
        fun ranked(r: Ranked?, fmt: (Double) -> String): String =
            if (r == null) none else r.rank?.let { res.getString(R.string.sports_hub_stat_ranked, fmt(r.value), SportsText.ordinal(res, it)) } ?: fmt(r.value)
        // (§2.1) Java's HALF_UP on the exact quotient: 87 / 24 = 3.625 → "3.63".
        fun two(v: Double) = java.math.BigDecimal(v.toString()).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
        fun pct(v: Double) = HubPanelContents.pct(v)
        fun row(labelRes: Int, a: String, h: String, better: Side?): PanelRow {
            val label = res.getString(labelRes)
            return PanelRow(label, a, h, better, res.getString(R.string.sports_hub_desc_stat, label, away.nickname, a, home.nickname, h))
        }
        fun side(a: Ranked?, h: Ranked?, higher: Boolean): Side? =
            if (a == null || h == null || a.value == h.value) null else if ((a.value > h.value) == higher) Side.AWAY else Side.HOME
        fun text(v: String?) = v?.takeIf { it.isNotBlank() } ?: none
        return listOf(
            row(R.string.sports_hub_stat_record, text(s?.awayRecord), text(s?.homeRecord), null),
            row(R.string.sports_hub_stat_last10, text(s?.awayLast10?.record), text(s?.homeLast10?.record), null),
            row(R.string.sports_hub_stat_gf, ranked(s?.awayGf, ::two), ranked(s?.homeGf, ::two), side(s?.awayGf, s?.homeGf, true)),
            row(R.string.sports_hub_stat_ga, ranked(s?.awayGa, ::two), ranked(s?.homeGa, ::two), side(s?.awayGa, s?.homeGa, false)),
            row(R.string.sports_hub_stat_pp_pct, ranked(s?.awayPp, ::pct), ranked(s?.homePp, ::pct), side(s?.awayPp, s?.homePp, true)),
            row(R.string.sports_hub_stat_pk_pct, ranked(s?.awayPk, ::pct), ranked(s?.homePk, ::pct), side(s?.awayPk, s?.homePk, true)),
        )
    }

    // ------------------------------------------------------------------ summary (§2.2, §2.5)

    override fun summary(res: Resources, m: HubPanelModel, g: GameSnapshot, away: HubName, home: HubName, measure: Measure, factor: Float): SummarySpec {
        if (boardOnly(m, g)) return SummarySpec(emptyList())
        val sep = res.getString(R.string.sports_hub_summary_sep)
        fun line(label: String, text: String) = SummaryLine(label, label + sep + text)
        val d = m.detail
        val out = ArrayList<SummaryLine>()
        when (g.phase) {
            GamePhase.POSTPONED -> return wrapping(res.getString(R.string.sports_hub_postponed_chl, shortName(res, g.id.league)))
            GamePhase.FINAL -> {
                // The board's Shots row stands in for the rows: the failure's reason and next step are said here instead.
                if (d == null && m.detailKind == DetailKind.FAILED && boardRows(res, g, away, home).isNotEmpty()) return wrapping(failure(res, m, g, final = true))
                if (g.finalKind == PeriodKind.SO) out += SummaryLine("", res.getString(R.string.sports_hub_won_shootout))
                else if (d != null) {
                    // (R3) The feed's game-winning flag first, else the NHL's rule.
                    val goal = d.goals.firstOrNull { it.gameWinning } ?: HubSummary.winningGoal(g, d)
                    if (goal != null) out += line(res.getString(R.string.sports_hub_summary_winning_goal), HubPanelContents.goalSentence(res, goal))
                }
                val stars = d?.stars.orEmpty().sortedBy { it.rank }.map { it.lastName }.filter { it.isNotBlank() }
                if (stars.isNotEmpty()) out += SummaryLine(res.getString(R.string.sports_hub_summary_stars), res.getString(R.string.sports_hub_summary_stars) + " " + stars.joinToString(", "))
            }
            else -> if (d != null) {
                d.series?.let { s ->
                    val text = when {
                        s.meetingsPlayed <= 0 -> res.getString(R.string.sports_hub_series_first)
                        s.awayWins == s.homeWins -> res.getString(R.string.sports_hub_series_tied, s.awayWins, s.homeWins)
                        s.awayWins > s.homeWins -> res.getString(R.string.sports_hub_series_leads, g.away.abbrev, s.awayWins, s.homeWins)
                        else -> res.getString(R.string.sports_hub_series_leads, g.home.abbrev, s.homeWins, s.awayWins)
                    }
                    out += if (s.meetingsPlayed <= 0) SummaryLine("", text) else line(res.getString(R.string.sports_hub_summary_series), text)
                }
                d.leaders.firstOrNull { it.category.equals("points", true) }?.let { p ->
                    val parts = listOfNotNull(p.away, p.home).map { "${it.name.substringAfterLast(' ')} ${it.value}" }
                    val label = res.getString(if (d.leadersSeason) R.string.sports_hub_summary_points_leaders_season else R.string.sports_hub_summary_points_leaders)
                    if (parts.isNotEmpty()) out += line(label, parts.joinToString(sep))
                }
            }
        }
        return SummarySpec(out)
    }

    /**
     * One sentence that may take both summary lines: the panel lets line 1 wrap to two lines only when two are planned
     * (`Summary`, U-S8), so an empty second line reserves the room; it is never drawn once line 1 wrapped.
     */
    private fun wrapping(text: String) = SummarySpec(listOf(SummaryLine("", text), SummaryLine("", "")))

    // ------------------------------------------------------------------ media (§2.3)

    override fun media(res: Resources, m: HubPanelModel, g: GameSnapshot, clock24: Boolean, locale: Locale, lowEnd: Boolean): MediaSpec {
        val d = m.detail
        if (!boardOnly(m, g) && g.phase == GamePhase.FINAL && d != null) {
            return HubPanelContents.art(res, g, m, finalCaption(res, d, locale, UiTextScale.factor), clock24, locale)
        }
        return HubPanelContents.art(res, g, m, d?.venue, clock24, locale)
    }

    /**
     * (§2.3) "Venue · Attendance 2,801"; no attendance → the venue. The caption is one 22 px line on the 632 px art: when it
     * is longer than the hub's line budget at [factor] (E11: 55 characters at Default, 45 at Largest), the venue's
     * " - City, AB" tail goes first ("Scotiabank Saddledome · Attendance 2,226"), then the attendance.
     */
    internal fun finalCaption(res: Resources, d: GameDetail, locale: Locale, factor: Float): String? {
        val venue = d.venue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val n = d.attendance?.takeIf { it > 0 } ?: return venue
        val count = NumberFormat.getIntegerInstance(locale).format(n)
        val short = venue.substringBefore(" - ").trim().ifEmpty { venue }
        val choices = listOf(
            res.getString(R.string.sports_hub_caption_venue_attendance, venue, count),
            res.getString(R.string.sports_hub_caption_venue_attendance, short, count),
            short,
        )
        val budget = HubPanelContents.lineBudget(factor)
        return choices.firstOrNull { it.length <= budget } ?: choices.last()
    }

    // ------------------------------------------------------------------ slots (§2.4)

    override fun slots(c: HubSlots.Ctx): List<HubSlot> {
        if (c.postponed) return List(3) { HubSlot.EMPTY }
        if (!enabled) {
            if (c.final) return listOf(if (c.catchUp.isNotEmpty()) HubSlots.forAction(SlotAction.WATCH_FROM_START, c) else HubSlot.EMPTY, HubSlot.EMPTY, HubSlot.EMPTY)
            return listOf(first(c), HubSlots.forAction(SlotAction.CHANNEL, c), HubSlot.EMPTY)
        }
        if (c.final) {
            // Watch from start only with a catch-up airing; Retry while the detail failed; then Goals and Box score.
            val set = buildList {
                if (c.catchUp.isNotEmpty()) add(HubSlots.forAction(SlotAction.WATCH_FROM_START, c))
                if (c.detailRetry) add(HubSlots.forAction(SlotAction.RETRY, c))
                add(HubSlots.forAction(SlotAction.GOALS, c))
                add(HubSlots.forAction(SlotAction.BOX_SCORE, c))
            }
            return (set + List(3) { HubSlot.EMPTY }).take(3)
        }
        if (c.live) return listOf(first(c), HubSlots.forAction(SlotAction.CHANNEL, c), HubSlot.EMPTY)
        return listOf(first(c), HubSlots.forAction(SlotAction.CHANNEL, c), HubSlots.forAction(SlotAction.MATCHUP, c))
    }

    private fun first(c: HubSlots.Ctx): HubSlot = if (c.detailRetry) HubSlots.forAction(SlotAction.RETRY, c) else HubSlots.forAction(SlotAction.FOLLOW, c)

    override fun slotReasons(res: Resources, m: HubPanelModel, slots: List<HubSlot>): List<String?> = slots.map { null }

    override fun clipRefs(game: GameSnapshot, detail: GameDetail?, infos: Map<Long, ClipInfo>, goalsOpen: Boolean): List<ClipRef> = emptyList()

    // ------------------------------------------------------------------ the Goals sheet (§3.2)

    /**
     * The goals in game order (scorer and season goal number, strength, the feed's game-winning mark; when, the running
     * score, the assists), then the final's catch-up rows. No video: the rows are never dimmed and OK on one does nothing.
     */
    override fun scoringRows(
        res: Resources,
        game: GameSnapshot,
        detail: GameDetail?,
        infos: Map<Long, ClipInfo>,
        catchUp: List<ChannelCandidate>,
        offerNowMs: Long,
        videoOn: Boolean,
    ): List<ScoringRow> {
        if (!enabled) return emptyList()
        val out = ArrayList<ScoringRow>()
        detail?.goals?.forEachIndexed { i, goal ->
            out += ScoringRow.Event(goal, "goal:$i", chlGoalLine1(res, goal), chlGoalLine2(res, game, goal), "", RowIcon.Headshot(goal.headshot), clip = null, noVideo = true)
        }
        if (game.phase == GamePhase.FINAL) catchUp.filter { it.airing != null }.forEachIndexed { n, c -> out += ScoringRow.CatchUp(c, n) }
        return out
    }

    /** "K. Dietsch (2) · Game-winning goal", "B. Hamilton (17) · PP · Game-winning goal". */
    internal fun chlGoalLine1(res: Resources, g: GoalItem): String {
        val base = goalLine1(res, g)
        return if (g.gameWinning) base + res.getString(R.string.sports_hub_summary_sep) + res.getString(R.string.sports_hub_summary_winning_goal) else base
    }

    /** "3rd 10:53 · OTT leads 4–3 · Houben": the NHL's line 2 without "Video soon" (there is never a CHL video). */
    internal fun chlGoalLine2(res: Resources, game: GameSnapshot, g: GoalItem): String {
        val sep = res.getString(R.string.sports_hub_summary_sep)
        val score = when {
            g.away == g.home -> res.getString(R.string.sports_hub_goal_tied, g.away, g.home)
            g.away > g.home -> res.getString(R.string.sports_hub_goal_leads, game.away.abbrev, g.away, g.home)
            else -> res.getString(R.string.sports_hub_goal_leads, game.home.abbrev, g.home, g.away)
        }
        return listOfNotNull(goalWhen(res, g), score, g.assistsLast.takeIf { it.isNotEmpty() }?.joinToString(", ")).joinToString(sep)
    }

    override fun boxScoreError(res: Resources, game: GameSnapshot): String? =
        if (enabled) res.getString(R.string.sports_hub_error_box_score_chl, shortName(res, game.id.league)) else null

    /** "OHL", "WHL", "QMJHL" (the existing league names). */
    internal fun shortName(res: Resources, league: League?): String = res.getString(
        when (league) {
            League.WHL -> R.string.sports_league_whl
            League.QMJHL -> R.string.sports_league_qmjhl
            League.OHL -> R.string.sports_league_ohl
            League.NHL, League.NFL, null -> R.string.sports_league_chl
        },
    )
}
