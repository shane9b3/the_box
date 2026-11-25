package app.thebox.ui.sports

import android.content.res.Resources
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.LeagueDetail
import app.thebox.core.sports.Side
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.GameDetail
import app.thebox.ui.R
import java.util.Locale

/**
 * (NFL hub commit 0, docs/design/m6-nfl-hub.md §6.6) The NFL's hub. Commit 0 holds today's board-only NFL branches of
 * `HubPanelContents` (the record row, the BY QUARTER rows, the live down / possession / timeouts extras, the passing and
 * rushing leader lines) and `HubSlots.default`, moved verbatim (docs/design/m6-sports-hub.md §7.1). **P3 replaces it with
 * the NFL hub design** (§2, §3): every `// P3` below is an entry point.
 */
internal object NflHubUi : HubLeagueUi {
    /** P3: true once the NFL detail is wired (§6.6); today the NFL panel is board-only. */
    override val detailed: Boolean get() = false
    override val source: HubSource get() = HubSource.ESPN

    // ------------------------------------------------------------------ rows (P3: §2.2)

    override fun rows(res: Resources, m: HubPanelModel, g: GameSnapshot, away: HubName, home: HubName): RowsSpec {
        val live = g.phase == GamePhase.LIVE || g.phase == GamePhase.INTERMISSION
        val final = g.phase == GamePhase.FINAL
        val pre = !live && !final
        var rows: List<PanelRow> = emptyList()
        var extra: List<PanelRow> = emptyList()
        var header = ""
        var moreSoon: String? = null
        val x = g.extras
        val nfl = g.detail as? LeagueDetail.Nfl
        if (pre) {
            header = res.getString(R.string.sports_hub_stats_season)
            if (x?.awayRecord != null || x?.homeRecord != null) rows = listOf(HubPanelContents.simpleRow(res.getString(R.string.sports_hub_stat_record), x.awayRecord, x.homeRecord, away, home, res))
        } else if (x != null && x.periods.isNotEmpty()) {
            header = res.getString(R.string.sports_hub_stats_by_quarter)
            rows = x.periods.map { p ->
                val label = if (p.label.kind == app.thebox.core.sports.PeriodKind.REG) res.getString(R.string.sports_hub_row_quarter_n, p.label.number) else SportsText.periodWord(res, p.label)
                HubPanelContents.valueRow(label, p.away, p.home, away, home, res)
            } + HubPanelContents.valueRow(res.getString(R.string.sports_hub_row_total), g.away.score ?: x.periods.sumOf { it.away }, g.home.score ?: x.periods.sumOf { it.home }, away, home, res)
            if (live && nfl != null) extra = nflLiveRows(res, g, nfl, away, home)
        }
        if (rows.isEmpty()) moreSoon = res.getString(R.string.sports_hub_more_soon)
        return RowsSpec(header = header, rows = rows, extra = extra, moreSoon = moreSoon)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun nflLiveRows(res: Resources, g: GameSnapshot, nfl: LeagueDetail.Nfl, away: HubName, home: HubName): List<PanelRow> {
        val rows = ArrayList<PanelRow>()
        val none = res.getString(R.string.sports_hub_stat_none)
        val down = nfl.play as? app.thebox.core.sports.NflPlay.Down
        if (down != null) {
            val ord = SportsText.ordinal(res, down.down)
            val text = when {
                down.goalToGo -> res.getString(R.string.sports_hub_nfl_down_goal, ord)
                down.distance != null -> res.getString(R.string.sports_hub_nfl_down_distance, ord, down.distance.toString())
                else -> ord
            }
            rows += PanelRow(res.getString(R.string.sports_hub_stat_down), text, none, null, res.getString(R.string.sports_hub_stat_down) + ": " + text)
        }
        nfl.possession?.let { side ->
            val team = if (side == Side.AWAY) away.nickname else home.nickname
            rows += PanelRow(res.getString(R.string.sports_hub_stat_possession), if (side == Side.AWAY) team else none, if (side == Side.HOME) team else none, null,
                res.getString(R.string.sports_hub_stat_possession) + ": " + team)
        }
        if (nfl.awayTimeouts != null && nfl.homeTimeouts != null) rows += HubPanelContents.valueRow(res.getString(R.string.sports_hub_stat_timeouts), nfl.awayTimeouts!!, nfl.homeTimeouts!!, away, home, res)
        return rows
    }

    // ------------------------------------------------------------------ summary (P3: §2.4, fitted with measure)

    override fun summary(res: Resources, m: HubPanelModel, g: GameSnapshot, away: HubName, home: HubName, measure: Measure, factor: Float): SummarySpec {
        val sep = res.getString(R.string.sports_hub_summary_sep)
        fun line(label: String, text: String) = SummaryLine(label, label + sep + text)
        val out = ArrayList<SummaryLine>()
        if (g.phase == GamePhase.FINAL || g.phase == GamePhase.LIVE || g.phase == GamePhase.INTERMISSION) {
            val leaders = g.extras?.leaders.orEmpty()
            for ((cat, labelRes) in listOf("passing" to R.string.sports_hub_summary_passing, "rushing" to R.string.sports_hub_summary_rushing)) {
                // One leader per category for the whole game, from either team: a single line "Name line (TEAM)", never a comparison.
                val l = leaders.firstOrNull { it.category.equals(cat, true) } ?: continue
                val team = if (l.side == Side.AWAY) g.away.abbrev else g.home.abbrev
                out += line(res.getString(labelRes), "${l.name} ${l.line} ($team)".replace("  ", " "))
            }
        }
        return SummarySpec(out)
    }

    // ------------------------------------------------------------------ media (P3: §2.1, §2.4)

    override fun media(res: Resources, m: HubPanelModel, g: GameSnapshot, clock24: Boolean, locale: Locale, lowEnd: Boolean): MediaSpec =
        HubPanelContents.art(res, g, m, m.detail?.venue, clock24, locale)

    // ------------------------------------------------------------------ slots (P3: §2.3)

    override fun slots(c: HubSlots.Ctx): List<HubSlot> {
        if (c.postponed) return List(3) { HubSlot.EMPTY }
        if (c.final) {
            return listOf(if (c.catchUp.isNotEmpty()) HubSlots.forAction(SlotAction.WATCH_FROM_START, c) else HubSlot.EMPTY, HubSlot.EMPTY, HubSlot.EMPTY)
        }
        val first = if (c.detailRetry) HubSlots.forAction(SlotAction.RETRY, c) else HubSlots.forAction(SlotAction.FOLLOW, c)
        return listOf(first, HubSlots.forAction(SlotAction.CHANNEL, c), HubSlot.EMPTY)
    }

    /** P3: the §2.3 spoken reasons (Highlights soon, No highlights, Scoring). */
    override fun slotReasons(res: Resources, m: HubPanelModel, slots: List<HubSlot>): List<String?> = slots.map { null } // P3

    // ------------------------------------------------------------------ clips and the Scoring sheet

    /** None, by design (§4.2): the panel and the sheet build their pictures from `GameClip`, never `info()`. */
    override fun clipRefs(game: GameSnapshot, detail: GameDetail?, infos: Map<Long, ClipInfo>, goalsOpen: Boolean): List<ClipRef> = emptyList()

    /** P3: scoring plays, Game highlights, More videos, catch-up rows (§3.1). */
    override fun scoringRows(
        res: Resources,
        game: GameSnapshot,
        detail: GameDetail?,
        infos: Map<Long, ClipInfo>,
        catchUp: List<ChannelCandidate>,
        offerNowMs: Long,
        videoOn: Boolean,
    ): List<ScoringRow> = emptyList() // P3
}
