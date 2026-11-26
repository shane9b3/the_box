package app.thebox.ui.sports

import android.content.res.Resources
import app.thebox.core.sports.GameMedia
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.GoalItem
import app.thebox.core.sports.hub.Ranked
import app.thebox.core.sports.hub.StatKind
import app.thebox.ui.R
import java.util.Locale

/**
 * (NFL hub commit 0, docs/design/m6-nfl-hub.md §6.6) The NHL's hub: today's NHL branches of `HubPanelContents` (rows,
 * summary, media), `HubSlots.default`, `SportsViewModel.ensureClipInfos` and `GoalsSheet.kt`'s `goalsSheetRows` /
 * `goalQueue`, moved verbatim (docs/design/m6-sports-hub.md §1.3, §1.5, §6.3, §6.4, §6.7). The NHL and its goldens do
 * not change.
 */
internal object NhlHubUi : HubLeagueUi {
    private val NHL_LIVE_KINDS = listOf(StatKind.SOG, StatKind.POWER_PLAY, StatKind.HITS, StatKind.BLOCKED, StatKind.FACEOFF_PCT)

    override val detailed: Boolean get() = true
    override val source: HubSource get() = HubSource.NHL_COM

    // ------------------------------------------------------------------ rows

    override fun rows(res: Resources, m: HubPanelModel, g: GameSnapshot, away: HubName, home: HubName): RowsSpec {
        val live = g.phase == GamePhase.LIVE || g.phase == GamePhase.INTERMISSION
        val final = g.phase == GamePhase.FINAL
        val pre = !live && !final
        var rows: List<PanelRow> = emptyList()
        var error: String? = null
        val header = res.getString(if (pre) R.string.sports_hub_stats_season else R.string.sports_hub_stats_team)
        val d = m.detail
        var loading = d == null
        if (m.detailKind == DetailKind.FAILED && d == null) {
            error = HubPanelContents.failureText(res, m.failure, m.slots, final)
            loading = false
        } else {
            rows = if (pre) nhlSeasonRows(res, d, away, home) else nhlGameRows(res, d, away, home)
        }
        return RowsSpec(header = header, rows = rows, loading = loading, error = error)
    }

    private fun nhlGameRows(res: Resources, d: GameDetail?, away: HubName, home: HubName): List<PanelRow> {
        val none = res.getString(R.string.sports_hub_stat_none)
        val byKind = d?.gameStats?.associateBy { it.kind }.orEmpty()
        return NHL_LIVE_KINDS.map { k ->
            val label = res.getString(HubPanelContents.statLabel(k))
            val row = byKind[k]
            if (row == null) HubPanelContents.blank(label, none, away, home, res) else HubPanelContents.statRow(res, label, row, away, home)
        }
    }

    private fun nhlSeasonRows(res: Resources, d: GameDetail?, away: HubName, home: HubName): List<PanelRow> {
        val none = res.getString(R.string.sports_hub_stat_none)
        val s = d?.season
        fun ranked(r: Ranked?, fmt: (Double) -> String): String =
            if (r == null) none else r.rank?.let { res.getString(R.string.sports_hub_stat_ranked, fmt(r.value), SportsText.ordinal(res, it)) } ?: fmt(r.value)
        fun two(v: Double) = String.format(Locale.US, "%.2f", v)
        fun pct(v: Double) = HubPanelContents.pct(v)
        fun row(labelRes: Int, a: String, h: String, better: app.thebox.core.sports.Side?): PanelRow {
            val label = res.getString(labelRes)
            return PanelRow(label, a, h, better, res.getString(R.string.sports_hub_desc_stat, label, away.nickname, a, home.nickname, h))
        }
        fun side(a: Ranked?, h: Ranked?, higher: Boolean): app.thebox.core.sports.Side? =
            if (a == null || h == null || a.value == h.value) null else if ((a.value > h.value) == higher) app.thebox.core.sports.Side.AWAY else app.thebox.core.sports.Side.HOME
        return listOf(
            row(R.string.sports_hub_stat_record, s?.awayRecord ?: none, s?.homeRecord ?: none, null),
            row(R.string.sports_hub_stat_last10, s?.awayLast10?.record ?: none, s?.homeLast10?.record ?: none, null),
            row(R.string.sports_hub_stat_gf, ranked(s?.awayGf, ::two), ranked(s?.homeGf, ::two), side(s?.awayGf, s?.homeGf, true)),
            row(R.string.sports_hub_stat_ga, ranked(s?.awayGa, ::two), ranked(s?.homeGa, ::two), side(s?.awayGa, s?.homeGa, false)),
            row(R.string.sports_hub_stat_pp_pct, ranked(s?.awayPp, ::pct), ranked(s?.homePp, ::pct), side(s?.awayPp, s?.homePp, true)),
            row(R.string.sports_hub_stat_pk_pct, ranked(s?.awayPk, ::pct), ranked(s?.homePk, ::pct), side(s?.awayPk, s?.homePk, true)),
        )
    }

    // ------------------------------------------------------------------ summary (m6-sports-hub §6.7)

    override fun summary(res: Resources, m: HubPanelModel, g: GameSnapshot, away: HubName, home: HubName, measure: Measure, factor: Float): SummarySpec {
        val sep = res.getString(R.string.sports_hub_summary_sep)
        fun line(label: String, text: String) = SummaryLine(label, label + sep + text)
        val d = m.detail
        val out = ArrayList<SummaryLine>()
        when (g.phase) {
            GamePhase.FINAL -> {
                if (g.finalKind == app.thebox.core.sports.PeriodKind.SO) out += SummaryLine("", res.getString(R.string.sports_hub_won_shootout))
                else if (d != null) {
                    val goal = HubSummary.winningGoal(g, d)
                    if (goal != null) {
                        val text = m.winningGoal?.description?.takeIf { it.isNotBlank() } ?: HubPanelContents.goalSentence(res, goal)
                        out += line(res.getString(R.string.sports_hub_summary_winning_goal), text)
                    }
                }
                if (d != null && d.stars.isNotEmpty()) out += SummaryLine(res.getString(R.string.sports_hub_summary_stars), res.getString(R.string.sports_hub_summary_stars) + " " + d.stars.sortedBy { it.rank }.joinToString(", ") { it.lastName })
            }
            GamePhase.LIVE, GamePhase.INTERMISSION -> if (d != null) {
                d.goals.lastOrNull()?.let { goal ->
                    val text = m.latestGoal?.description?.takeIf { it.isNotBlank() } ?: HubPanelContents.goalSentence(res, goal)
                    out += line(res.getString(R.string.sports_hub_summary_latest_goal), text)
                }
                if (d.periods.isNotEmpty()) {
                    out += line(res.getString(R.string.sports_hub_summary_periods), d.periods.joinToString(sep) { "${SportsText.periodWord(res, it.label)} ${it.away}–${it.home}" })
                }
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
                    if (parts.isNotEmpty()) out += line(res.getString(R.string.sports_hub_summary_points_leaders), parts.joinToString(sep))
                }
            }
        }
        return SummarySpec(out)
    }

    // ------------------------------------------------------------------ media (m6-sports-hub §1.3)

    override fun media(res: Resources, m: HubPanelModel, g: GameSnapshot, clock24: Boolean, locale: Locale, lowEnd: Boolean): MediaSpec {
        // (Review M4) A poster only once its lookup gave a picture and while videos play; otherwise the normal matchup art with
        // its centre disc and a caption that says why there is no video (never a play disc for something that can't play).
        fun pictured(info: ClipInfo?) = !m.videoOff && info != null && (info.posterUrl ?: info.thumbnailUrl) != null
        if (g.phase == GamePhase.FINAL) {
            val id = m.detail?.recapId ?: g.media?.recapId
            val info = m.recap
            return when {
                id != null && m.videoOff -> HubPanelContents.art(res, g, m, res.getString(R.string.sports_hub_caption_videos_off), clock24, locale)
                id != null && pictured(info) -> {
                    val caption = info?.durationMs?.let { res.getString(R.string.sports_hub_caption_recap, SportsText.duration(it)) } ?: res.getString(R.string.sports_hub_row_recap)
                    MediaSpec.Poster(id, info, res.getString(R.string.sports_hub_tag_recap), caption)
                }
                id != null -> HubPanelContents.art(res, g, m, res.getString(R.string.sports_hub_row_recap), clock24, locale)
                else -> HubPanelContents.art(res, g, m, res.getString(R.string.sports_hub_caption_recap_soon), clock24, locale)
            }
        }
        if (g.phase == GamePhase.LIVE || g.phase == GamePhase.INTERMISSION) {
            val clip = m.detail?.goals?.lastOrNull { it.clipId != null }?.clipId
            val info = m.latestGoal
            return when {
                clip != null && m.videoOff -> HubPanelContents.art(res, g, m, res.getString(R.string.sports_hub_caption_videos_off), clock24, locale)
                clip != null && pictured(info) -> {
                    val caption = info?.let { i -> i.durationMs?.let { res.getString(R.string.sports_hub_caption_clip, i.title, SportsText.duration(it)) } ?: i.title }
                    MediaSpec.Poster(clip, info, res.getString(R.string.sports_hub_tag_latest_goal), caption)
                }
                else -> HubPanelContents.art(res, g, m, res.getString(R.string.sports_hub_caption_goals_soon), clock24, locale)
            }
        }
        return HubPanelContents.art(res, g, m, m.detail?.venue, clock24, locale)
    }

    // ------------------------------------------------------------------ slots (m6-sports-hub §1.5)

    override fun slots(c: HubSlots.Ctx): List<HubSlot> {
        if (c.postponed) return List(3) { HubSlot.EMPTY }
        if (c.final) {
            return listOf(
                if (c.detailRetry) HubSlots.forAction(SlotAction.RETRY, c) else HubSlots.forAction(SlotAction.WATCH_RECAP, c),
                HubSlots.forAction(SlotAction.GOALS, c), HubSlots.forAction(SlotAction.BOX_SCORE, c),
            )
        }
        val first = if (c.detailRetry) HubSlots.forAction(SlotAction.RETRY, c) else HubSlots.forAction(SlotAction.FOLLOW, c)
        val third = when {
            c.live -> HubSlots.forAction(SlotAction.GOALS, c)
            else -> HubSlots.forAction(SlotAction.MATCHUP, c)
        }
        return listOf(first, HubSlots.forAction(SlotAction.CHANNEL, c), third)
    }

    /**
     * (NFL hub §2.3, UX M4) The grey Recap soon speaks the reason its media caption shows: "Recap not posted yet". The
     * other NHL slots are grey only while loading (Finding…, a Goals count still 0) or beyond the guide (the panel's line).
     */
    override fun slotReasons(res: Resources, m: HubPanelModel, slots: List<HubSlot>): List<String?> = slots.map { s ->
        if (s.action == SlotAction.WATCH_RECAP && !s.enabled && s.label == SlotLabel.RECAP_SOON) res.getString(R.string.sports_hub_caption_recap_soon) else null
    }

    // ------------------------------------------------------------------ clip lookups (m6-sports-hub §6.3)

    /** The clip lookups of the shown NHL game (§6.3): the recap and the winning goal's clip; the latest goal's clip. */
    override fun clipRefs(game: GameSnapshot, detail: GameDetail?, infos: Map<Long, ClipInfo>, goalsOpen: Boolean): List<ClipRef> {
        val d = detail
        val refs = ArrayList<ClipRef>()
        when (game.phase) {
            GamePhase.FINAL -> {
                (d?.recapId ?: game.media?.recapId)?.let { refs += ClipRef(it, ClipKind.RECAP, game.id) }
                if (d != null) HubSummary.winningGoal(game, d)?.clipId?.let { refs += ClipRef(it, ClipKind.GOAL, game.id) }
                // (Owner bug 2026-11-25) The condensed game, only while its Goals sheet is open (the row that shows it).
                if (goalsOpen) (d?.condensedId ?: game.media?.condensedId)?.let { refs += ClipRef(it, ClipKind.CONDENSED, game.id) }
            }
            GamePhase.LIVE, GamePhase.INTERMISSION ->
                d?.goals?.lastOrNull { it.clipId != null }?.clipId?.let { refs += ClipRef(it, ClipKind.GOAL, game.id) }
            else -> Unit
        }
        return refs
    }

    // ------------------------------------------------------------------ the Goals sheet (m6-sports-hub §6.4)

    /**
     * (NFL hub §6.6) Today's Goals sheet rows ([goalsSheetRows]) as [ScoringRow]s with the same words: each goal with its
     * clip and headshot, the recap and condensed game with today's labels, the catch-up rows. The sheet draws them as it
     * drew the goal rows, so its pixels do not change.
     */
    override fun scoringRows(
        res: Resources,
        game: GameSnapshot,
        detail: GameDetail?,
        infos: Map<Long, ClipInfo>,
        catchUp: List<ChannelCandidate>,
        offerNowMs: Long,
        videoOn: Boolean,
    ): List<ScoringRow> = goalsSheetRows(detail, catchUp, game.phase == GamePhase.FINAL, game.media).map { row ->
        when (row) {
            is GoalsRow.Goal -> ScoringRow.Event(
                row.goal, row.key, goalLine1(res, row.goal), goalLine2(res, game, row.goal), "", RowIcon.Headshot(row.goal.headshot),
                clip = row.goal.clipId?.let { ClipRef(it, ClipKind.GOAL, game.id) },
            )
            is GoalsRow.Video -> {
                val info = infos[row.videoId]
                val duration = clipDuration(info?.durationMs)
                val label = when (row.kind) {
                    ClipKind.RECAP -> res.getString(R.string.sports_hub_row_recap)
                    else -> duration?.let { res.getString(R.string.sports_hub_row_condensed, it) } ?: res.getString(R.string.sports_hub_row_condensed_plain)
                }
                ScoringRow.Video(ClipRef(row.videoId, row.kind, game.id), label, info?.durationMs, info?.thumbnailUrl ?: info?.posterUrl)
            }
            is GoalsRow.CatchUp -> ScoringRow.CatchUp(row.candidate, row.n)
        }
    }
}

/**
 * §6.4 order: goals, then (final) recap, condensed game, catch-up rows. (HIGH-1) Without a [detail] there are no goal rows;
 * the recap and condensed game then come from the board's [media].
 */
internal fun goalsSheetRows(detail: GameDetail?, catchUp: List<ChannelCandidate>, final: Boolean, media: GameMedia? = null): List<GoalsRow> = buildList {
    detail?.goals?.forEachIndexed { i, g -> add(GoalsRow.Goal(g, i)) }
    if (final) {
        (detail?.recapId ?: media?.recapId)?.let { add(GoalsRow.Video(it, ClipKind.RECAP)) }
        (detail?.condensedId ?: media?.condensedId)?.let { add(GoalsRow.Video(it, ClipKind.CONDENSED)) }
        catchUp.filter { it.airing != null }.forEachIndexed { n, c -> add(GoalsRow.CatchUp(c, n)) }
    }
}

/** §6.4: that goal and every later goal with a clip; empty when the goal itself has no clip yet. */
internal fun goalQueue(game: GameSnapshot, goals: List<GoalItem>, from: Int): List<ClipRef> {
    if (goals.getOrNull(from)?.clipId == null) return emptyList()
    return goals.drop(from).mapNotNull { g -> g.clipId?.let { ClipRef(it, ClipKind.GOAL, game.id) } }
}
