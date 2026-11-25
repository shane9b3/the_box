package app.thebox.ui.sports

import android.content.res.Resources
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.Side
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.GameDetail
import app.thebox.ui.R
import java.util.Locale

/**
 * (NFL hub commit 0, docs/design/m6-nfl-hub.md §6.6) The CHL's hub (OHL, WHL, QMJHL): today's board-only CHL branches of
 * `HubPanelContents` (shots, the live power play) and `HubSlots.default`, moved verbatim (docs/design/m6-sports-hub.md
 * §7.1). No detail source, no video.
 */
internal object ChlHubUi : HubLeagueUi {
    override val detailed: Boolean get() = false
    override val source: HubSource get() = HubSource.NONE

    override fun rows(res: Resources, m: HubPanelModel, g: GameSnapshot, away: HubName, home: HubName): RowsSpec {
        val live = g.phase == GamePhase.LIVE || g.phase == GamePhase.INTERMISSION
        val final = g.phase == GamePhase.FINAL
        val pre = !live && !final
        var moreSoon: String? = null
        val x = g.extras
        val header = res.getString(R.string.sports_hub_stats_team)
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
        val rows: List<PanelRow> = r
        // P3: sports_hub_more_soon_chl ("Game stats for this league aren't available yet.", §6.7).
        if (rows.isEmpty()) moreSoon = res.getString(R.string.sports_hub_more_soon)
        return RowsSpec(header = header, rows = rows, moreSoon = moreSoon)
    }

    override fun summary(res: Resources, m: HubPanelModel, g: GameSnapshot, away: HubName, home: HubName, measure: Measure, factor: Float): SummarySpec =
        SummarySpec(emptyList())

    override fun media(res: Resources, m: HubPanelModel, g: GameSnapshot, clock24: Boolean, locale: Locale, lowEnd: Boolean): MediaSpec =
        HubPanelContents.art(res, g, m, m.detail?.venue, clock24, locale)

    override fun slots(c: HubSlots.Ctx): List<HubSlot> {
        if (c.postponed) return List(3) { HubSlot.EMPTY }
        if (c.final) {
            return listOf(if (c.catchUp.isNotEmpty()) HubSlots.forAction(SlotAction.WATCH_FROM_START, c) else HubSlot.EMPTY, HubSlot.EMPTY, HubSlot.EMPTY)
        }
        val first = if (c.detailRetry) HubSlots.forAction(SlotAction.RETRY, c) else HubSlots.forAction(SlotAction.FOLLOW, c)
        return listOf(first, HubSlots.forAction(SlotAction.CHANNEL, c), HubSlot.EMPTY)
    }

    override fun slotReasons(res: Resources, m: HubPanelModel, slots: List<HubSlot>): List<String?> = slots.map { null }

    override fun clipRefs(game: GameSnapshot, detail: GameDetail?, infos: Map<Long, ClipInfo>, goalsOpen: Boolean): List<ClipRef> = emptyList()

    override fun scoringRows(
        res: Resources,
        game: GameSnapshot,
        detail: GameDetail?,
        infos: Map<Long, ClipInfo>,
        catchUp: List<ChannelCandidate>,
        offerNowMs: Long,
        videoOn: Boolean,
    ): List<ScoringRow> = emptyList()
}
