package app.thebox.ui.sports

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.LeagueDetail
import app.thebox.core.sports.LeagueGroup
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.group
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.GoalItem
import app.thebox.core.sports.hub.MatchConfidence
import app.thebox.core.sports.hub.MatchReason
import app.thebox.core.sports.hub.Ranked
import app.thebox.core.sports.hub.StatKind
import app.thebox.core.sports.hub.StatRow
import app.thebox.core.sports.hub.StatValue
import app.thebox.core.sports.Market
import app.thebox.ui.R
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.thebox.core.settings.UiTextScale
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.guide.screen.LocalShellImageLoader
import app.thebox.ui.theme.textSp
import androidx.tv.material3.Text
import coil3.request.allowHardware
import coil3.request.bitmapConfig

// =====================================================================================================================
// The panel's model (docs/design/m6-sports-hub.md §1.3, §1.5, §2.3). Resource-free: the view model builds it, the content
// builders below turn it into words, the composables draw it.
// =====================================================================================================================

/**
 * What a slot does. A slot's identity is its action (§2.3: focus is restored by action identity): [FOLLOW] is Follow and
 * Following ✓ (Stop following); [CHANNEL] is Watch (one confirmed channel: tunes) or Channels (n) (opens the sheet).
 */
enum class SlotAction { FOLLOW, CHANNEL, GOALS, WATCH_RECAP, BOX_SCORE, MATCHUP, WATCH_FROM_START, RETRY }

/** The words of a slot (§1.5): short and fixed; the channel's name is never in a label. */
enum class SlotLabel { FOLLOW, FOLLOWING, WATCH, CHANNELS, CHANNELS_N, FINDING, GOALS, GOALS_N, WATCH_RECAP, RECAP_SOON, BOX_SCORE, MATCHUP, WATCH_FROM_START, RETRY }

/** One of the three fixed slots. [action] null = an empty slot (draws nothing, keeps its place). */
@Immutable
data class HubSlot(val action: SlotAction?, val label: SlotLabel = SlotLabel.FOLLOW, val enabled: Boolean = false, val count: Int = 0) {
    val usable: Boolean get() = action != null && enabled
    companion object { val EMPTY = HubSlot(null) }
}

/** The channel match of the shown game (§5). */
sealed interface MatchState {
    /** Nothing asked yet. */
    data object Idle : MatchState
    data object Finding : MatchState
    data class Done(val candidates: List<ChannelCandidate>) : MatchState
}

/** Where the shown game's detail stands. */
enum class DetailKind { NONE, LOADING, READY, FAILED, BOARD_ONLY }

/**
 * What the panel shows for one game. [detail] is set only when its id equals [game]'s id (U-S2: a late answer for another
 * game is dropped). [slots] always has three entries.
 *
 * (Review L3) [nowMs] is the wall clock of the build and is **not** part of equality: two builds that differ only in it are
 * the same model, so the state flow does not re-emit and the panel does not rebuild its words for every merged emission.
 * What the panel reads from the clock is in the equality through [imminent], [slots] and the local date ([nowDay]).
 */
@Immutable
class HubPanelModel(
    val game: GameSnapshot?,
    val followed: Boolean = false,
    val detail: GameDetail? = null,
    val detailKind: DetailKind = DetailKind.NONE,
    val failure: FailKind? = null,
    val stale: Boolean = false,
    val failing: Boolean = false,
    /** Wall-clock ms of the stats on screen (for "Stats from 8:12 PM"). */
    val statsAtMs: Long? = null,
    /** The headline's scores (§4.3: the newer of the board's and the detail's answer). */
    val awayScore: Int? = null,
    val homeScore: Int? = null,
    val match: MatchState = MatchState.Idle,
    val recap: ClipInfo? = null,
    val latestGoal: ClipInfo? = null,
    val winningGoal: ClipInfo? = null,
    val slots: List<HubSlot> = List(3) { HubSlot.EMPTY },
    val nowMs: Long = 0L,
    /** (Review M4) The video service refused the app: the media area shows no poster, play disc or video tag. */
    val videoOff: Boolean = false,
    /** Live, or starting within `HUB_IMMINENT_MIN` (§5.5): the channel line names the reason instead of the start time. */
    val imminent: Boolean = false,
) {
    /** The index of the first slot OK / Menu on a card enters (§2.2); null = none (the footer shows no OK). */
    val firstEnabled: Int? get() = slots.indexOfFirst { it.usable }.takeIf { it >= 0 }
    val candidates: List<ChannelCandidate> get() = (match as? MatchState.Done)?.candidates.orEmpty()

    fun copy(slots: List<HubSlot> = this.slots): HubPanelModel = HubPanelModel(
        game, followed, detail, detailKind, failure, stale, failing, statsAtMs, awayScore, homeScore, match, recap, latestGoal,
        winningGoal, slots, nowMs, videoOff, imminent,
    )

    /**
     * (Verification review L-d) The one thing the panel's words read from [nowMs] besides [imminent]: the local date, which
     * makes an NFL start "SUN 1:00 PM" or "MON NOV 30 8:15 PM" (the pill and the matchup art's centre). It is in the
     * equality, so the words change at midnight even when nothing else does; a later time on the same day is still equal.
     */
    private val nowDay: Long get() = java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()

    private fun parts(): List<Any?> = listOf(
        game, followed, detail, detailKind, failure, stale, failing, statsAtMs, awayScore, homeScore, match, recap, latestGoal,
        winningGoal, slots, videoOff, imminent, nowDay,
    )

    override fun equals(other: Any?): Boolean = this === other || (other is HubPanelModel && parts() == other.parts())
    override fun hashCode(): Int = parts().hashCode()
    override fun toString(): String = "HubPanelModel(game=${game?.id}, kind=$detailKind, failure=$failure, slots=$slots)"
}

// =====================================================================================================================
// The three fixed slots (§1.5, §2.3, §2.4, §4.5)
// =====================================================================================================================

internal object HubSlots {
    private val RETRYABLE = setOf(FailKind.TIMEOUT, FailKind.IO, FailKind.HTTP_5XX, FailKind.HTTP_OTHER)

    /** The facts a slot's label and enabled state read. */
    class Ctx(
        val game: GameSnapshot,
        val followed: Boolean,
        val detail: GameDetail?,
        val failure: FailKind?,
        val match: MatchState,
        /** The video service refused the app: the video slots are off for a while (§6.8). */
        val videoOff: Boolean,
        val nowMs: Long,
        /** False when no `GameChannelMatcher` is bound. */
        val matcherBound: Boolean = true,
    ) {
        val group = game.id.league.group
        val nhl get() = group == LeagueGroup.NHL
        val pre get() = game.phase == GamePhase.SCHEDULED || game.phase == GamePhase.PRE
        val live get() = game.phase == GamePhase.LIVE || game.phase == GamePhase.INTERMISSION
        val final get() = game.phase == GamePhase.FINAL
        val postponed get() = game.phase == GamePhase.POSTPONED
        val candidates get() = (match as? MatchState.Done)?.candidates.orEmpty()
        val catchUp get() = candidates.filter { it.airing != null }
        val recapId get() = detail?.recapId ?: game.media?.recapId
        val goalClips get() = detail?.goals?.count { it.clipId != null } ?: game.media?.goalClips ?: 0
        val goalCount get() = detail?.goals?.size ?: game.media?.goalClips ?: 0
        /**
         * Slot 1 is Retry while a retryable failure has nothing cached (§4.5), except on a final game whose recap id the
         * board already carries (and videos are not off): Watch recap stays in slot 1 and the panel body says the stats
         * retry on their own.
         */
        val detailRetry get() = nhl && failure != null && failure in RETRYABLE && !(final && recapId != null && !videoOff)
        /** Live, or starting within `HUB_IMMINENT_MIN` (§5.5): Watch tunes and sheet rows tune. */
        val imminent get() = live || game.startUtcMs - nowMs <= SportsConstants.HUB_IMMINENT_MIN * 60_000L
    }

    /** The default slots of a game's phase (§1.5). */
    fun default(c: Ctx): List<HubSlot> {
        if (c.postponed) return List(3) { HubSlot.EMPTY }
        if (c.final) {
            return if (c.nhl) {
                listOf(if (c.detailRetry) forAction(SlotAction.RETRY, c) else forAction(SlotAction.WATCH_RECAP, c), forAction(SlotAction.GOALS, c), forAction(SlotAction.BOX_SCORE, c))
            } else {
                listOf(if (c.catchUp.isNotEmpty()) forAction(SlotAction.WATCH_FROM_START, c) else HubSlot.EMPTY, HubSlot.EMPTY, HubSlot.EMPTY)
            }
        }
        val first = if (c.detailRetry) forAction(SlotAction.RETRY, c) else forAction(SlotAction.FOLLOW, c)
        val third = when {
            !c.nhl -> HubSlot.EMPTY
            c.live -> forAction(SlotAction.GOALS, c)
            else -> forAction(SlotAction.MATCHUP, c)
        }
        return listOf(first, forAction(SlotAction.CHANNEL, c), third)
    }

    /** The slot for [action] now: its label and enabled state recomputed (used in place while the set is frozen, §2.3). */
    fun forAction(action: SlotAction?, c: Ctx): HubSlot = when (action) {
        null -> HubSlot.EMPTY
        SlotAction.FOLLOW -> HubSlot(SlotAction.FOLLOW, if (c.followed) SlotLabel.FOLLOWING else SlotLabel.FOLLOW, true)
        SlotAction.RETRY -> if (c.detailRetry) HubSlot(SlotAction.RETRY, SlotLabel.RETRY, true) else HubSlot(SlotAction.FOLLOW, if (c.followed) SlotLabel.FOLLOWING else SlotLabel.FOLLOW, true)
        SlotAction.CHANNEL -> channel(c)
        SlotAction.GOALS -> if (c.final) {
            HubSlot(SlotAction.GOALS, if (c.goalCount > 0) SlotLabel.GOALS_N else SlotLabel.GOALS, c.goalCount > 0, c.goalCount)
        } else {
            HubSlot(SlotAction.GOALS, if (c.goalClips > 0) SlotLabel.GOALS_N else SlotLabel.GOALS, c.goalClips > 0 && !c.videoOff, c.goalClips)
        }
        SlotAction.WATCH_RECAP ->
            if (c.recapId != null) HubSlot(SlotAction.WATCH_RECAP, SlotLabel.WATCH_RECAP, !c.videoOff)
            else HubSlot(SlotAction.WATCH_RECAP, SlotLabel.RECAP_SOON, false)
        SlotAction.BOX_SCORE -> HubSlot(SlotAction.BOX_SCORE, SlotLabel.BOX_SCORE, true)
        // (Verification review MED-1) Enabled exactly when the Matchup sheet has rows (a goalie pair with two empty lists
        // draws none).
        SlotAction.MATCHUP -> HubSlot(SlotAction.MATCHUP, SlotLabel.MATCHUP, c.detail != null && hasMatchupRows(c.detail))
        SlotAction.WATCH_FROM_START -> HubSlot(SlotAction.WATCH_FROM_START, SlotLabel.WATCH_FROM_START, c.catchUp.isNotEmpty())
    }

    private fun channel(c: Ctx): HubSlot {
        if (!c.matcherBound) return HubSlot(SlotAction.CHANNEL, SlotLabel.CHANNELS, false)
        return when (val m = c.match) {
            MatchState.Idle, MatchState.Finding -> HubSlot(SlotAction.CHANNEL, SlotLabel.FINDING, false)
            is MatchState.Done -> when {
                m.candidates.isEmpty() -> HubSlot(SlotAction.CHANNEL, SlotLabel.CHANNELS, false)
                watchable(c) != null -> HubSlot(SlotAction.CHANNEL, SlotLabel.WATCH, true, 1)
                else -> HubSlot(SlotAction.CHANNEL, SlotLabel.CHANNELS_N, true, m.candidates.size)
            }
        }
    }

    /** The one channel Watch tunes (§1.5): the only candidate, CONFIRMED, while the game is live or starts within 30 minutes. */
    fun watchable(c: Ctx): ChannelCandidate? =
        c.candidates.singleOrNull()?.takeIf { it.confidence == MatchConfidence.CONFIRMED && c.imminent }
}

// =====================================================================================================================
// The panel's words (§1.3, §4.5, §6.7, §7.1)
// =====================================================================================================================

@Immutable
internal data class PanelRow(val label: String, val away: String, val home: String, val better: Side?, val desc: String)

@Immutable
internal data class SummaryLine(val label: String, val text: String)

internal sealed interface MediaSpec {
    /** A clip's poster over the matchup art: [info] is null until its lookup answered. */
    @Immutable
    data class Poster(val videoId: Long, val info: ClipInfo?, val tag: String, val caption: String?) : MediaSpec
    @Immutable
    data class Art(val caption: String?, val centreTop: String, val centreBottom: String?) : MediaSpec
    /** An empty board: a `White05` plate. */
    data object Empty : MediaSpec
}

@Immutable
internal data class PanelContent(
    val pill: SportsText.HubPill,
    val away: HubName,
    val home: HubName,
    val awayScore: Int?,
    val homeScore: Int?,
    val headerAway: String,
    val headerCentre: String,
    val headerHome: String,
    /** The phase's rows; "–" values while the detail loads (`loading`). */
    val rows: List<PanelRow>,
    val loading: Boolean,
    /** Under the header: "Stats from 8:12 PM" (22 `Warn`) or, instead of rows, a failure text. */
    val note: String?,
    val noteWarn: Boolean,
    /** The reason + next step in place of the rows (§4.5). */
    val error: String?,
    val summary: List<SummaryLine>,
    val channelLine: String?,
    val media: MediaSpec,
    val mediaDesc: String,
    /** NFL / CHL with nothing extra (§7.1): the one line instead of rows. */
    val moreSoon: String?,
    val plan: HubPanelPlan.Plan,
)

internal object HubPanelContents {
    private val NHL_LIVE_KINDS = listOf(StatKind.SOG, StatKind.POWER_PLAY, StatKind.HITS, StatKind.BLOCKED, StatKind.FACEOFF_PCT)

    fun build(res: Resources, m: HubPanelModel, clock24: Boolean, factor: Float, locale: Locale = Locale.getDefault()): PanelContent? {
        val g = m.game ?: return null
        val league = g.id.league
        val away = HubNames.of(league, g.away)
        val home = HubNames.of(league, g.home)
        val pill = SportsText.hubPill(res, g, clock24, m.nowMs, locale)
        val group = league.group
        val showScores = g.phase != GamePhase.SCHEDULED && g.phase != GamePhase.PRE && g.phase != GamePhase.POSTPONED
        val live = g.phase == GamePhase.LIVE || g.phase == GamePhase.INTERMISSION
        val final = g.phase == GamePhase.FINAL
        val pre = !live && !final

        // ---- rows
        var rows: List<PanelRow> = emptyList()
        var extra: List<PanelRow> = emptyList()
        var header = ""
        var loading = false
        var error: String? = null
        var moreSoon: String? = null
        when (group) {
            LeagueGroup.NHL -> {
                header = res.getString(if (pre) R.string.sports_hub_stats_season else R.string.sports_hub_stats_team)
                val d = m.detail
                loading = d == null
                if (m.detailKind == DetailKind.FAILED && d == null) {
                    error = failureText(res, m.failure, m.slots, final)
                    loading = false
                } else {
                    rows = if (pre) nhlSeasonRows(res, d, away, home) else nhlGameRows(res, d, away, home)
                }
            }
            LeagueGroup.NFL -> {
                val x = g.extras
                val nfl = g.detail as? LeagueDetail.Nfl
                if (pre) {
                    header = res.getString(R.string.sports_hub_stats_season)
                    if (x?.awayRecord != null || x?.homeRecord != null) rows = listOf(simpleRow(res.getString(R.string.sports_hub_stat_record), x.awayRecord, x.homeRecord, away, home, res))
                } else if (x != null && x.periods.isNotEmpty()) {
                    header = res.getString(R.string.sports_hub_stats_by_quarter)
                    rows = x.periods.map { p ->
                        val label = if (p.label.kind == app.thebox.core.sports.PeriodKind.REG) res.getString(R.string.sports_hub_row_quarter_n, p.label.number) else SportsText.periodWord(res, p.label)
                        valueRow(label, p.away, p.home, away, home, res)
                    } + valueRow(res.getString(R.string.sports_hub_row_total), g.away.score ?: x.periods.sumOf { it.away }, g.home.score ?: x.periods.sumOf { it.home }, away, home, res)
                    if (live && nfl != null) extra = nflLiveRows(res, g, nfl, away, home)
                }
                if (rows.isEmpty()) moreSoon = res.getString(R.string.sports_hub_more_soon)
            }
            LeagueGroup.CHL -> {
                val x = g.extras
                header = res.getString(R.string.sports_hub_stats_team)
                val r = ArrayList<PanelRow>()
                if (!pre && x?.awayShots != null && x.homeShots != null) r += valueRow(res.getString(R.string.sports_hub_stat_shots), x.awayShots!!, x.homeShots!!, away, home, res)
                val pp = g.powerPlay
                if (live && pp != null) {
                    val t = pp.secondsRemaining?.let { SportsText.clock(it) } ?: res.getString(R.string.sports_hub_stat_none)
                    val none = res.getString(R.string.sports_hub_stat_none)
                    r += PanelRow(
                        res.getString(R.string.sports_hub_stat_power_play), if (pp.side == Side.AWAY) t else none, if (pp.side == Side.HOME) t else none, null,
                        res.getString(R.string.sports_hub_desc_stat, res.getString(R.string.sports_hub_stat_power_play), away.nickname, if (pp.side == Side.AWAY) t else none, home.nickname, if (pp.side == Side.HOME) t else none),
                    )
                }
                rows = r
                if (rows.isEmpty()) moreSoon = res.getString(R.string.sports_hub_more_soon)
            }
        }

        // ---- the summary and the plan
        val summary = summary(res, m, g, away, home)
        val channelLine = channelLine(res, m, g, clock24, locale)
        val mediaSpec = media(res, m, g, clock24, locale)
        val note = when {
            m.detail != null && (m.stale || m.failing) && m.statsAtMs != null ->
                res.getString(R.string.sports_hub_stats_from, SportsText.startTime(m.statsAtMs, clock24, locale))
            else -> null
        }
        val floor = if (mediaSpec is MediaSpec.Poster) HubPanelPlan.POSTER_FLOOR else HubPanelPlan.ART_FLOOR
        val plan = HubPanelPlan.plan(
            factor, mandatoryRows = if (error != null) HubPanelPlan.ERROR_ROWS else rows.size, extraRows = extra.size, summaryLines = summary.size,
            hasChannelLine = channelLine != null, hasNote = note != null || moreSoon != null, mediaFloor = floor,
            hasRows = rows.isNotEmpty() || error != null,
        )
        val kept = rows + extra.take(plan.extraRows)
        val headerAway = away.nickname
        val headerHome = home.nickname
        return PanelContent(
            pill = pill, away = away, home = home,
            awayScore = if (showScores) m.awayScore ?: g.away.score else null, homeScore = if (showScores) m.homeScore ?: g.home.score else null,
            headerAway = headerAway, headerCentre = header, headerHome = headerHome,
            rows = kept, loading = loading, note = note, noteWarn = note != null, error = error,
            summary = summary.take(plan.summaryLines), channelLine = channelLine, media = mediaSpec,
            mediaDesc = mediaDesc(res, m, g, mediaSpec, away, home, clock24, locale), moreSoon = moreSoon, plan = plan,
        )
    }

    // ------------------------------------------------------------------ rows

    private fun nhlGameRows(res: Resources, d: GameDetail?, away: HubName, home: HubName): List<PanelRow> {
        val none = res.getString(R.string.sports_hub_stat_none)
        val byKind = d?.gameStats?.associateBy { it.kind }.orEmpty()
        return NHL_LIVE_KINDS.map { k ->
            val label = res.getString(statLabel(k))
            val row = byKind[k]
            if (row == null) blank(label, none, away, home, res) else statRow(res, label, row, away, home)
        }
    }

    private fun statLabel(k: StatKind): Int = when (k) {
        StatKind.SOG -> R.string.sports_hub_stat_sog
        StatKind.POWER_PLAY -> R.string.sports_hub_stat_power_play
        StatKind.HITS -> R.string.sports_hub_stat_hits
        StatKind.BLOCKED -> R.string.sports_hub_stat_blocked
        StatKind.FACEOFF_PCT -> R.string.sports_hub_stat_faceoff_pct
        StatKind.PIM -> R.string.sports_hub_stat_pim
        StatKind.GIVEAWAYS -> R.string.sports_hub_stat_giveaways
        StatKind.TAKEAWAYS -> R.string.sports_hub_stat_takeaways
    }

    private fun statRow(res: Resources, label: String, row: StatRow, away: HubName, home: HubName): PanelRow {
        val a = value(row.away)
        val h = value(row.home)
        val better = compare(row.away, row.home, higherIsBetter = row.kind != StatKind.PIM && row.kind != StatKind.GIVEAWAYS)
        return PanelRow(label, a, h, better, res.getString(R.string.sports_hub_desc_stat, label, away.nickname, a, home.nickname, h))
    }

    private fun value(v: StatValue): String = when (v) {
        is StatValue.Count -> v.n.toString()
        is StatValue.Fraction -> "${v.made}/${v.of}"
        is StatValue.Pct -> pct(v.value)
    }

    private fun num(v: StatValue): Double = when (v) {
        is StatValue.Count -> v.n.toDouble()
        is StatValue.Fraction -> v.made.toDouble()
        is StatValue.Pct -> v.value
    }

    private fun compare(a: StatValue, h: StatValue, higherIsBetter: Boolean): Side? {
        val x = num(a)
        val y = num(h)
        if (x == y) return null
        return if ((x > y) == higherIsBetter) Side.AWAY else Side.HOME
    }

    /** A fraction (0.272727) or a percentage (27.2727) as "27.3%". */
    internal fun pct(v: Double): String = String.format(Locale.US, "%.1f%%", if (v <= 1.0) v * 100.0 else v)

    private fun nhlSeasonRows(res: Resources, d: GameDetail?, away: HubName, home: HubName): List<PanelRow> {
        val none = res.getString(R.string.sports_hub_stat_none)
        val s = d?.season
        fun ranked(r: Ranked?, fmt: (Double) -> String): String =
            if (r == null) none else r.rank?.let { res.getString(R.string.sports_hub_stat_ranked, fmt(r.value), SportsText.ordinal(res, it)) } ?: fmt(r.value)
        fun two(v: Double) = String.format(Locale.US, "%.2f", v)
        fun row(labelRes: Int, a: String, h: String, better: Side?): PanelRow {
            val label = res.getString(labelRes)
            return PanelRow(label, a, h, better, res.getString(R.string.sports_hub_desc_stat, label, away.nickname, a, home.nickname, h))
        }
        fun side(a: Ranked?, h: Ranked?, higher: Boolean): Side? =
            if (a == null || h == null || a.value == h.value) null else if ((a.value > h.value) == higher) Side.AWAY else Side.HOME
        return listOf(
            row(R.string.sports_hub_stat_record, s?.awayRecord ?: none, s?.homeRecord ?: none, null),
            row(R.string.sports_hub_stat_last10, s?.awayLast10?.record ?: none, s?.homeLast10?.record ?: none, null),
            row(R.string.sports_hub_stat_gf, ranked(s?.awayGf, ::two), ranked(s?.homeGf, ::two), side(s?.awayGf, s?.homeGf, true)),
            row(R.string.sports_hub_stat_ga, ranked(s?.awayGa, ::two), ranked(s?.homeGa, ::two), side(s?.awayGa, s?.homeGa, false)),
            row(R.string.sports_hub_stat_pp_pct, ranked(s?.awayPp, ::pct), ranked(s?.homePp, ::pct), side(s?.awayPp, s?.homePp, true)),
            row(R.string.sports_hub_stat_pk_pct, ranked(s?.awayPk, ::pct), ranked(s?.homePk, ::pct), side(s?.awayPk, s?.homePk, true)),
        )
    }

    private fun blank(label: String, none: String, away: HubName, home: HubName, res: Resources) =
        PanelRow(label, none, none, null, res.getString(R.string.sports_hub_desc_stat, label, away.nickname, none, home.nickname, none))

    private fun valueRow(label: String, a: Int, h: Int, away: HubName, home: HubName, res: Resources) =
        PanelRow(label, a.toString(), h.toString(), if (a == h) null else if (a > h) Side.AWAY else Side.HOME,
            res.getString(R.string.sports_hub_desc_stat, label, away.nickname, a.toString(), home.nickname, h.toString()))

    private fun simpleRow(label: String, a: String?, h: String?, away: HubName, home: HubName, res: Resources): PanelRow {
        val none = res.getString(R.string.sports_hub_stat_none)
        return PanelRow(label, a ?: none, h ?: none, null, res.getString(R.string.sports_hub_desc_stat, label, away.nickname, a ?: none, home.nickname, h ?: none))
    }

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
        if (nfl.awayTimeouts != null && nfl.homeTimeouts != null) rows += valueRow(res.getString(R.string.sports_hub_stat_timeouts), nfl.awayTimeouts!!, nfl.homeTimeouts!!, away, home, res)
        return rows
    }

    // ------------------------------------------------------------------ summary (§6.7)

    private fun summary(res: Resources, m: HubPanelModel, g: GameSnapshot, away: HubName, home: HubName): List<SummaryLine> {
        val sep = res.getString(R.string.sports_hub_summary_sep)
        fun line(label: String, text: String) = SummaryLine(label, label + sep + text)
        val d = m.detail
        val out = ArrayList<SummaryLine>()
        when (g.id.league.group) {
            LeagueGroup.NHL -> when (g.phase) {
                GamePhase.FINAL -> {
                    if (g.finalKind == app.thebox.core.sports.PeriodKind.SO) out += SummaryLine("", res.getString(R.string.sports_hub_won_shootout))
                    else if (d != null) {
                        val goal = HubSummary.winningGoal(g, d)
                        if (goal != null) {
                            val text = m.winningGoal?.description?.takeIf { it.isNotBlank() } ?: goalSentence(res, goal)
                            out += line(res.getString(R.string.sports_hub_summary_winning_goal), text)
                        }
                    }
                    if (d != null && d.stars.isNotEmpty()) out += SummaryLine(res.getString(R.string.sports_hub_summary_stars), res.getString(R.string.sports_hub_summary_stars) + " " + d.stars.sortedBy { it.rank }.joinToString(", ") { it.lastName })
                }
                GamePhase.LIVE, GamePhase.INTERMISSION -> if (d != null) {
                    d.goals.lastOrNull()?.let { goal ->
                        val text = m.latestGoal?.description?.takeIf { it.isNotBlank() } ?: goalSentence(res, goal)
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
            LeagueGroup.NFL -> if (g.phase == GamePhase.FINAL || g.phase == GamePhase.LIVE || g.phase == GamePhase.INTERMISSION) {
                val leaders = g.extras?.leaders.orEmpty()
                for ((cat, labelRes) in listOf("passing" to R.string.sports_hub_summary_passing, "rushing" to R.string.sports_hub_summary_rushing)) {
                    // One leader per category for the whole game, from either team: a single line "Name line (TEAM)", never a comparison.
                    val l = leaders.firstOrNull { it.category.equals(cat, true) } ?: continue
                    val team = if (l.side == Side.AWAY) g.away.abbrev else g.home.abbrev
                    out += line(res.getString(labelRes), "${l.name} ${l.line} ($team)".replace("  ", " "))
                }
            }
            LeagueGroup.CHL -> Unit
        }
        return out
    }

    /** "Vilardi (4th) from Connor and Morrissey, 1st period 5:11." (§6.7). */
    internal fun goalSentence(res: Resources, goal: GoalItem): String {
        val period = SportsText.periodWord(res, goal.period)
        val total = goal.scorerTotal ?: return res.getString(R.string.sports_hub_goal_when, goal.scorerLast, "$period ${goal.timeInPeriod}")
        val ord = SportsText.ordinal(res, total)
        val assists = when (goal.assistsLast.size) {
            0 -> null
            1 -> goal.assistsLast[0]
            else -> res.getString(R.string.sports_hub_and, goal.assistsLast[0], goal.assistsLast[1])
        }
        return if (assists == null) res.getString(R.string.sports_hub_goal_sentence_solo, goal.scorerLast, ord, period, goal.timeInPeriod)
        else res.getString(R.string.sports_hub_goal_sentence, goal.scorerLast, ord, assists, period, goal.timeInPeriod)
    }

    // ------------------------------------------------------------------ channel line (§1.3, §5.3)

    private fun channelLine(res: Resources, m: HubPanelModel, g: GameSnapshot, clock24: Boolean, locale: Locale): String? {
        if (g.phase == GamePhase.POSTPONED) return null
        val final = g.phase == GamePhase.FINAL
        val nhlFinal = final && g.id.league.group == LeagueGroup.NHL
        if (final && !nhlFinal) return null
        return when (val match = m.match) {
            MatchState.Idle, MatchState.Finding -> if (final) null else res.getString(R.string.sports_hub_finding_channel)
            is MatchState.Done -> {
                val list = if (final) match.candidates.filter { it.airing != null } else match.candidates
                if (list.isEmpty()) return if (final) null else res.getString(R.string.sports_hub_no_channel)
                val top = list.first()
                val name = top.channel.displayName
                val base = if (final || m.imminent) res.getString(R.string.sports_hub_on_tv, name, shortReason(res, top))
                else res.getString(R.string.sports_hub_on_tv_at, SportsText.startTime(g.startUtcMs, clock24, locale), name)
                if (list.size > 1) res.getQuantityString(R.plurals.sports_hub_on_tv_more, list.size - 1, base, list.size - 1) else base
            }
        }
    }

    private fun shortReason(res: Resources, c: ChannelCandidate): String = res.getString(
        when {
            c.reasons.any { it is MatchReason.Guide || it is MatchReason.SportListing } -> R.string.sports_hub_reason_short_guide
            c.confidence == MatchConfidence.LIKELY && c.reasons.any { it is MatchReason.Broadcaster && it.market == Market.NATIONAL } -> R.string.sports_hub_reason_short_national
            else -> R.string.sports_hub_reason_short_local
        },
    )

    // ------------------------------------------------------------------ media (§1.3)

    private fun media(res: Resources, m: HubPanelModel, g: GameSnapshot, clock24: Boolean, locale: Locale): MediaSpec {
        val nhl = g.id.league.group == LeagueGroup.NHL
        // (Review M4) A poster only once its lookup gave a picture and while videos play; otherwise the normal matchup art with
        // its centre disc and a caption that says why there is no video (never a play disc for something that can't play).
        fun pictured(info: ClipInfo?) = !m.videoOff && info != null && (info.posterUrl ?: info.thumbnailUrl) != null
        if (nhl && g.phase == GamePhase.FINAL) {
            val id = m.detail?.recapId ?: g.media?.recapId
            val info = m.recap
            return when {
                id != null && m.videoOff -> art(res, g, m, res.getString(R.string.sports_hub_caption_videos_off), clock24, locale)
                id != null && pictured(info) -> {
                    val caption = info?.durationMs?.let { res.getString(R.string.sports_hub_caption_recap, SportsText.duration(it)) } ?: res.getString(R.string.sports_hub_row_recap)
                    MediaSpec.Poster(id, info, res.getString(R.string.sports_hub_tag_recap), caption)
                }
                id != null -> art(res, g, m, res.getString(R.string.sports_hub_row_recap), clock24, locale)
                else -> art(res, g, m, res.getString(R.string.sports_hub_caption_recap_soon), clock24, locale)
            }
        }
        if (nhl && (g.phase == GamePhase.LIVE || g.phase == GamePhase.INTERMISSION)) {
            val clip = m.detail?.goals?.lastOrNull { it.clipId != null }?.clipId
            val info = m.latestGoal
            return when {
                clip != null && m.videoOff -> art(res, g, m, res.getString(R.string.sports_hub_caption_videos_off), clock24, locale)
                clip != null && pictured(info) -> {
                    val caption = info?.let { i -> i.durationMs?.let { res.getString(R.string.sports_hub_caption_clip, i.title, SportsText.duration(it)) } ?: i.title }
                    MediaSpec.Poster(clip, info, res.getString(R.string.sports_hub_tag_latest_goal), caption)
                }
                else -> art(res, g, m, res.getString(R.string.sports_hub_caption_goals_soon), clock24, locale)
            }
        }
        return art(res, g, m, m.detail?.venue, clock24, locale)
    }

    private fun art(res: Resources, g: GameSnapshot, m: HubPanelModel, caption: String?, clock24: Boolean, locale: Locale): MediaSpec.Art {
        val date = app.thebox.ui.guide.screen.TimeFormats.format("EEE", locale, java.util.Date(g.startUtcMs))
        return when (g.phase) {
            GamePhase.POSTPONED -> MediaSpec.Art(caption, res.getString(R.string.sports_hub_disc_postponed), null)
            GamePhase.SCHEDULED, GamePhase.PRE ->
                MediaSpec.Art(caption, if (g.startTbd) res.getString(R.string.sports_hub_pill_tbd) else SportsText.startTime(g.startUtcMs, clock24, locale), date)
            // A live game needs no weekday under its clock: it is on now.
            GamePhase.LIVE, GamePhase.INTERMISSION -> MediaSpec.Art(caption, SportsText.hubPill(res, g, clock24, m.nowMs, locale).text, null)
            else -> MediaSpec.Art(caption, SportsText.hubPill(res, g, clock24, m.nowMs, locale).text, date)
        }
    }

    private fun mediaDesc(res: Resources, m: HubPanelModel, g: GameSnapshot, spec: MediaSpec, away: HubName, home: HubName, clock24: Boolean, locale: Locale): String = when (spec) {
        is MediaSpec.Poster -> {
            val ms = spec.info?.durationMs
            if (spec.tag == res.getString(R.string.sports_hub_tag_recap)) res.getString(R.string.sports_hub_desc_media_recap, ms?.let { SportsText.spokenDuration(res, it) } ?: spec.caption.orEmpty())
            else spec.caption ?: spec.tag
        }
        else -> res.getString(R.string.sports_hub_desc_media_matchup, away.nickname, home.nickname, SportsText.startTime(g.startUtcMs, clock24, locale))
    }

    /**
     * The reason and the next step for a failed detail (§4.5), from the slots actually on screen (review H3): a usable Retry
     * slot → "Select Retry"; else a final game with a usable Watch recap → "Select Watch recap"; else (the slots were frozen
     * before the failure, so slot 1 is still Follow, or Watch recap is off) → Back, then OK, lands on Retry.
     */
    fun failureText(res: Resources, kind: FailKind?, slots: List<HubSlot>, final: Boolean): String = res.getString(
        when (kind) {
            FailKind.HTTP_429, FailKind.REFUSED -> R.string.sports_hub_error_detail_limited
            FailKind.BAD_BODY -> R.string.sports_hub_error_detail_changed
            FailKind.OFFLINE -> R.string.sports_hub_error_detail_offline
            else -> when {
                slots.any { it.action == SlotAction.RETRY && it.usable } -> R.string.sports_hub_error_detail_retry
                final && slots.any { it.action == SlotAction.WATCH_RECAP && it.usable } -> R.string.sports_hub_error_detail_auto
                else -> R.string.sports_hub_error_detail_back
            }
        },
    )
}

/** (§6.7) Pure helpers over a detail. */
internal object HubSummary {
    /** The winner's goal number (the loser's final score + 1) in game order; null for a tie, a shootout or a short list. */
    fun winningGoal(g: GameSnapshot, d: GameDetail): GoalItem? {
        val a = g.away.score ?: return null
        val h = g.home.score ?: return null
        if (a == h) return null
        val winner = if (a > h) Side.AWAY else Side.HOME
        val target = minOf(a, h) + 1
        return d.goals.filter { it.side == winner }.getOrNull(target - 1)
    }
}

// =====================================================================================================================
// The panel's height budget (§1.3, §1.4): the media area absorbs what the rows leave.
// =====================================================================================================================

internal object HubPanelPlan {
    const val PANEL_H = 812
    const val INNER_W = 664
    const val POSTER_FLOOR = 160
    /** (Review M4) The poster's play disc is drawn only in a media area at least this tall (it would meet the caption below). */
    const val POSTER_DISC_MIN_H = 200
    const val ART_FLOOR = 120
    /** An error text in place of the rows takes the room of this many rows (a reason and a next step, up to three lines). */
    const val ERROR_ROWS = 3

    /** [mediaH] design px; [summaryLines] 0..2 and [extraRows] are what the floors left. */
    data class Plan(val mediaH: Int, val summaryLines: Int, val extraRows: Int, val floor: Int) { val ok: Boolean get() = mediaH >= floor }

    /** A default height plus its growth at the Largest size (§1.4); the factor interpolates (never below the default). */
    private fun h(default: Int, growth: Int, factor: Float): Int = default + Math.round(growth * ((factor - 1f) / 0.2f).coerceIn(0f, 1f))

    fun pill(f: Float) = h(36, 5, f)
    fun headline(f: Float) = h(44, 9, f)
    fun header(f: Float) = h(32, 6, f)
    fun row(f: Float) = h(38, 8, f)
    fun summaryLine(f: Float) = h(32, 7, f)
    fun channel(f: Float) = h(30, 5, f)
    fun buttons(f: Float) = h(60, 6, f)

    /**
     * The media height with [rows] rows, [summaryLines] summary lines and a channel line, and the summary dropped from line 2
     * (§1.4: media to its floor, then summary line 2, then line 1) and the optional [extraRows] kept only while the floor holds.
     */
    fun plan(
        factor: Float, mandatoryRows: Int, extraRows: Int, summaryLines: Int, hasChannelLine: Boolean, hasNote: Boolean,
        mediaFloor: Int, hasRows: Boolean,
    ): Plan {
        fun media(rows: Int, lines: Int): Int {
            var used = 28 + pill(factor) + 12 + headline(factor) + 16 // top padding, pill, gap, headline, gap to the media
            used += 16 // media → header
            if (hasRows) used += header(factor) + rows * row(factor)
            if (hasNote) used += summaryLine(factor)
            if (lines > 0) used += 16 + lines * summaryLine(factor)
            if (hasChannelLine) used += 16 + channel(factor)
            used += 12 + buttons(factor) + 28
            return PANEL_H - used
        }
        // The optional rows first give way: keep as many as the floor allows with the summary at full height.
        var extras = extraRows
        var lines = minOf(summaryLines, 2)
        while (extras > 0 && media(mandatoryRows + extras, lines) < mediaFloor) extras--
        while (lines > 0 && media(mandatoryRows + extras, lines) < mediaFloor) lines--
        return Plan(media(mandatoryRows + extras, lines), lines, extras, mediaFloor)
    }
}


// =====================================================================================================================
// The panel (§1.3): 720 × 812, inner 664
// =====================================================================================================================

const val HUB_PANEL_TAG = "hub_panel"
const val HUB_PILL_TAG = "hub_panel_pill"
const val HUB_HEADLINE_TAG = "hub_panel_headline"
internal const val HUB_HEADLINE_AWAY_TAG = "hub_panel_headline_away"
internal const val HUB_HEADLINE_HOME_TAG = "hub_panel_headline_home"
const val HUB_CHANNEL_LINE_TAG = "hub_panel_channel"
const val HUB_ERROR_TAG = "hub_panel_error"
const val HUB_NOTE_TAG = "hub_panel_note"
const val HUB_MEDIA_TAG = "hub_panel_media"
internal const val HUB_PLAY_DISC_TAG = "hub_panel_play_disc"
const val HUB_SUMMARY_TAG = "hub_panel_summary:"
internal fun hubSlotTag(i: Int) = "hub_slot:$i"
internal fun hubStatRowTag(i: Int) = "hub_stat_row:$i"

private val PanelFill = Color.White.copy(alpha = 0.05f)
private val Black60 = Color.Black.copy(alpha = 0.6f)
private val Black50 = Color.Black.copy(alpha = 0.5f)
private val ButtonFocusFill = Color(0xFFEEF1F6)
private val ButtonInk = Color(0xFF0B0E14)

/** The slot's words (§1.5). */
internal fun slotText(res: Resources, slot: HubSlot): String = when (slot.label) {
    SlotLabel.FOLLOW -> res.getString(R.string.sports_hub_slot_follow)
    SlotLabel.FOLLOWING -> res.getString(R.string.sports_hub_slot_following)
    SlotLabel.WATCH -> res.getString(R.string.sports_hub_slot_watch)
    SlotLabel.CHANNELS -> res.getString(R.string.sports_hub_slot_channels)
    SlotLabel.CHANNELS_N -> res.getString(R.string.sports_hub_slot_channels_n, slot.count)
    SlotLabel.FINDING -> res.getString(R.string.sports_hub_slot_finding)
    SlotLabel.GOALS -> res.getString(R.string.sports_hub_slot_goals)
    SlotLabel.GOALS_N -> res.getString(R.string.sports_hub_slot_goals_n, slot.count)
    SlotLabel.WATCH_RECAP -> res.getString(R.string.sports_hub_slot_watch_recap)
    SlotLabel.RECAP_SOON -> res.getString(R.string.sports_hub_slot_recap_soon)
    SlotLabel.BOX_SCORE -> res.getString(R.string.sports_hub_slot_box_score)
    SlotLabel.MATCHUP -> res.getString(R.string.sports_hub_slot_matchup)
    SlotLabel.WATCH_FROM_START -> res.getString(R.string.sports_hub_slot_watch_from_start)
    SlotLabel.RETRY -> res.getString(R.string.sports_hub_slot_retry)
}

/** The glyph of slot 1 only (U-B3): a plus for Follow, a triangle for a video, a loop for Retry. */
private fun slotGlyph(slot: HubSlot, index: Int): String? = if (index != 0) null else when (slot.action) {
    SlotAction.FOLLOW -> if (slot.label == SlotLabel.FOLLOWING) null else "+"
    SlotAction.WATCH_RECAP, SlotAction.WATCH_FROM_START -> "▶"
    SlotAction.RETRY -> "↻"
    else -> null
}

/**
 * The detail panel for [model]. [slotRequesters] are the three buttons' requesters (the screen moves focus in with the first
 * enabled one). Left / Right move between enabled slots, Right from the last (and Back) call [onExit], Up / Down are
 * consumed (§2.3). [onSlotFocus] reports the focused slot's index or null.
 */
@Composable
internal fun HubPanel(
    model: HubPanelModel,
    clock24: Boolean,
    slotRequesters: List<FocusRequester>,
    onSlot: (Int) -> Unit,
    onSlotFocus: (Int?) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * (Integration) The buttons take focus only while the page has entered the panel (OK, Menu or Left on a card). Without
     * this, a D-pad search from the title row's first chip or the strip's first chip landed on slot 1 with the page not
     * knowing (wrong footer, Back left the page, Right went nowhere).
     */
    entered: Boolean = true,
) {
    val res = LocalResources.current
    val factor = UiTextScale.factor
    val content = remember(model, clock24, factor) { HubPanelContents.build(res, model, clock24, factor) }
    val measure = rememberHubMeasure()
    var focusedSlot by remember { mutableIntStateOf(-1) }
    val shape = RoundedCornerShape(24.dp)
    fun move(delta: Int): Boolean {
        val usable = model.slots.indices.filter { model.slots[it].usable }
        val next = usable.filter { if (delta > 0) it > focusedSlot else it < focusedSlot }.let { if (delta > 0) it.firstOrNull() else it.lastOrNull() }
        if (next != null) { runCatching { slotRequesters[next].requestFocus() }; return true }
        if (delta > 0) onExit()
        return true
    }
    val g = model.game
    val title = if (g != null && content != null) res.getString(R.string.sports_hub_desc_panel, content.away.nickname, content.home.nickname) else ""
    Column(
        modifier
            .width(720.dp).height(HubPanelPlan.PANEL_H.dp)
            .testTag(HUB_PANEL_TAG)
            .background(PanelFill, shape).border(1.dp, GuideUi.White08, shape)
            .padding(28.dp)
            .semantics { paneTitle = title }
            .onPreviewKeyEvent { e ->
                if (focusedSlot < 0) false
                else when (e.key) {
                    Key.DirectionLeft -> { if (e.type == KeyEventType.KeyDown) move(-1) else true }
                    Key.DirectionRight -> { if (e.type == KeyEventType.KeyDown) move(1) else true }
                    Key.DirectionUp, Key.DirectionDown -> true
                    Key.Back, Key.Escape, Key.ButtonB -> { if (e.type == KeyEventType.KeyUp) onExit(); true }
                    else -> false
                }
            },
    ) {
        if (content == null || g == null) {
            // An empty board: the media area's place filled with White05 and no buttons.
            Spacer(Modifier.height((HubPanelPlan.pill(factor) + 12 + HubPanelPlan.headline(factor) + 16).dp))
            // (Integration) White05 as the design says (`White08.copy(alpha = 0.5f)` was white at 50 %, a bright grey slab).
            Box(Modifier.fillMaxWidth().weight(1f).background(PanelFill, RoundedCornerShape(16.dp)))
            Spacer(Modifier.height(28.dp))
            return@Column
        }
        Box(Modifier.fillMaxWidth().height(HubPanelPlan.pill(factor).dp), contentAlignment = Alignment.Center) {
            StatusPill(content.pill, Modifier.testTag(HUB_PILL_TAG), height = HubPanelPlan.pill(factor))
        }
        Spacer(Modifier.height(12.dp))
        Headline(content, Modifier.fillMaxWidth().height(HubPanelPlan.headline(factor).dp), measure)
        Spacer(Modifier.height(16.dp))
        HubMedia(content, g, Modifier.fillMaxWidth().weight(1f).testTag(HUB_MEDIA_TAG))
        Spacer(Modifier.height(16.dp))
        Stats(content)
        Summary(content, factor)
        content.channelLine?.let {
            Spacer(Modifier.height(16.dp))
            Line(it, GuideUi.sans(22, FontWeight.Medium, GuideUi.Fg70), Modifier.fillMaxWidth().height(HubPanelPlan.channel(factor).dp).testTag(HUB_CHANNEL_LINE_TAG))
        }
        Spacer(Modifier.height(12.dp))
        Buttons(model, slotRequesters, focusedSlot, entered, onSlot) { i ->
            focusedSlot = i ?: -1
            onSlotFocus(i)
        }
    }
}

/** `[away name][ (n)] vs. [home name][ (n)]` (§1.3, U-B4): only the names give way; the scores never shrink or ellipsize. */
@Composable
private fun Headline(c: PanelContent, modifier: Modifier, measure: Measure) {
    val res = LocalResources.current
    val density = LocalDensity.current
    val factor = UiTextScale.factor
    val words = remember(res) { HubFit.HeadlineWords.of(res) }
    val plan = remember(c.away, c.home, c.awayScore, c.homeScore, factor, density.density, words) {
        HubFit.headline(c.away, c.home, c.awayScore, c.homeScore, with(density) { HubPanelPlan.INNER_W.dp.toPx() }, measure, words)
    }
    val style = GuideUi.sans(plan.designPx, FontWeight.SemiBold)
    val awayName = if (plan.nicknames) c.away.nickname else c.away.full
    val homeName = if (plan.nicknames) c.home.nickname else c.home.full
    val scored = c.awayScore != null && c.homeScore != null
    val desc = if (scored) res.getString(R.string.sports_hub_desc_headline_score, awayName, c.awayScore!!, homeName, c.homeScore!!)
    else res.getString(R.string.sports_hub_desc_headline, awayName, homeName)
    Row(
        modifier.testTag(HUB_HEADLINE_TAG).semantics(mergeDescendants = true) { contentDescription = desc },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        // (Integration, review M5) The plan measured the four parts as laid out (each rounded up). When they fit, the home
        // name takes its own width and the away name the rest (weighted children are measured last, so the scores are never
        // cut). When the names must ellipsize, each name's weight is its measured width, so a short home nickname does not
        // keep half of the free width while a long away one is cut (the equal split §15.2 removed).
        val awayMod = Modifier.weight(if (plan.ellipsis) plan.awayWeight else 1f, fill = false)
        val homeMod = if (plan.ellipsis) Modifier.weight(plan.homeWeight, fill = false) else Modifier
        Text(awayName, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = awayMod.testTag(HUB_HEADLINE_AWAY_TAG))
        Text(words.middle(c.awayScore, c.homeScore), style = style, maxLines = 1, softWrap = false)
        Text(homeName, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = homeMod.testTag(HUB_HEADLINE_HOME_TAG))
        if (scored) Text(words.tail(c.homeScore), style = style, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Stats(c: PanelContent) {
    val factor = UiTextScale.factor
    if (c.moreSoon != null) {
        Line(c.moreSoon, GuideUi.sans(22, FontWeight.Medium, GuideUi.Fg55), Modifier.fillMaxWidth().height(HubPanelPlan.summaryLine(factor).dp))
        return
    }
    if (c.rows.isEmpty() && c.error == null) return
    Row(Modifier.fillMaxWidth().height(HubPanelPlan.header(factor).dp), verticalAlignment = Alignment.CenterVertically) {
        val st = GuideUi.sans(22, FontWeight.SemiBold, GuideUi.Fg55)
        Line(c.headerAway, st, Modifier.weight(1f))
        Line(c.headerCentre, st.copy(textAlign = TextAlign.Center), Modifier.weight(1f))
        Line(c.headerHome, st.copy(textAlign = TextAlign.End), Modifier.weight(1f))
    }
    c.note?.let { Line(it, GuideUi.sans(22, FontWeight.Medium, GuideUi.Warn), Modifier.fillMaxWidth().height(HubPanelPlan.summaryLine(factor).dp).testTag(HUB_NOTE_TAG)) }
    if (c.error != null) {
        Text(
            c.error, style = GuideUi.sans(24, FontWeight.Medium, GuideUi.Fg70), maxLines = HubPanelPlan.ERROR_ROWS,
            modifier = Modifier.fillMaxWidth().height((HubPanelPlan.ERROR_ROWS * HubPanelPlan.row(factor)).dp).padding(top = 8.dp).testTag(HUB_ERROR_TAG),
        )
        return
    }
    c.rows.forEachIndexed { i, r ->
        val awayInk = if (c.loading) GuideUi.Fg.copy(alpha = 0.4f) else if (r.better == Side.HOME) GuideUi.Fg70 else GuideUi.Fg
        val homeInk = if (c.loading) GuideUi.Fg.copy(alpha = 0.4f) else if (r.better == Side.AWAY) GuideUi.Fg70 else GuideUi.Fg
        Row(
            Modifier.fillMaxWidth().height(HubPanelPlan.row(factor).dp).testTag(hubStatRowTag(i))
                .drawBehind { drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) }
                .semantics(mergeDescendants = true) { contentDescription = r.desc },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Line(r.away, GuideUi.sans(24, FontWeight.Medium, awayInk), Modifier.weight(1f))
            Line(r.label, GuideUi.sans(24, FontWeight.Medium, GuideUi.Fg55).copy(textAlign = TextAlign.Center), Modifier.weight(2f))
            Line(r.home, GuideUi.sans(24, FontWeight.Medium, homeInk).copy(textAlign = TextAlign.End), Modifier.weight(1f))
        }
    }
}

/** The summary (§6.7): line 1 may wrap to two lines, then line 2 is not drawn (U-S8). */
@Composable
private fun Summary(c: PanelContent, factor: Float) {
    val lines = c.plan.summaryLines.coerceAtMost(c.summary.size)
    if (lines == 0) return
    Spacer(Modifier.height(16.dp))
    var wrapped by remember(c.summary) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().height((lines * HubPanelPlan.summaryLine(factor)).dp)) {
        c.summary.take(lines).forEachIndexed { i, s ->
            if (i == 1 && wrapped) return@forEachIndexed
            val label = s.label
            val rest = if (label.isNotEmpty() && s.text.startsWith(label)) s.text.removePrefix(label) else s.text
            val text = androidx.compose.ui.text.buildAnnotatedString {
                if (label.isNotEmpty() && s.text.startsWith(label)) {
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = GuideUi.Fg, fontWeight = FontWeight.SemiBold))
                    append(label)
                    pop()
                }
                append(rest)
            }
            Text(
                text, style = GuideUi.sans(24, FontWeight.Normal, GuideUi.Fg70), maxLines = if (i == 0 && lines == 2) 2 else 1, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (i == 0) wrapped = it.lineCount > 1 },
                modifier = Modifier.fillMaxWidth().testTag(HUB_SUMMARY_TAG + i),
            )
        }
    }
}

// ------------------------------------------------------------------ media

@Composable
private fun HubMedia(c: PanelContent, g: GameSnapshot, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val density = LocalDensity.current
    val context = LocalContext.current
    Box(modifier.clip(shape).semantics(mergeDescendants = true) { contentDescription = c.mediaDesc }) {
        val spec = c.media
        val heightDp = c.plan.mediaH
        val art = spec as? MediaSpec.Art
        if (spec is MediaSpec.Empty) {
            Box(Modifier.fillMaxSize().background(PanelFill))
            return@Box
        }
        // (Integration) Under a poster the art is only the backdrop until the picture decodes (or when it never does): no
        // centre disc and no caption of its own, so nothing sits under the play disc or the poster's caption.
        // (Emulator E1) Under a poster the logos keep clear of its tag (top) and caption (bottom); their heights follow the
        // text size, so they are measured rather than assumed.
        val poster = spec as? MediaSpec.Poster
        val tagH = if (poster != null) POSTER_TAG_INSET + lineHeightDp(GuideUi.sans(22, FontWeight.Bold)) + 8 else 0
        val captionH = if (poster?.caption != null) lineHeightDp(GuideUi.sans(26, FontWeight.SemiBold)) + 26 else 0
        MatchupArt(
            g.away, g.home, g.id.league, art?.centreTop, art?.centreBottom, art?.caption, heightDp, Modifier.fillMaxSize(),
            topReserveDp = tagH, bottomReserveDp = captionH,
        )
        if (spec is MediaSpec.Poster) {
            val loader = LocalShellImageLoader.current
            val low = lowEnd()
            val url = remember(spec.info, low) { if (low) spec.info?.thumbnailUrl ?: spec.info?.posterUrl else spec.info?.posterUrl ?: spec.info?.thumbnailUrl }
            if (loader != null && url != null) {
                val w = with(density) { HubPanelPlan.INNER_W.dp.roundToPx() }
                val h = with(density) { heightDp.dp.roundToPx() }
                val key = "nhl-clip:${spec.videoId}:${w}x$h"
                val request = remember(url, key, low) {
                    coil3.request.ImageRequest.Builder(context).data(url).size(w, h)
                        .memoryCacheKey(key).diskCacheKey(key)
                        .let { if (low) it.allowHardware(false).bitmapConfig(android.graphics.Bitmap.Config.RGB_565) else it }
                        .build()
                }
                coil3.compose.AsyncImage(
                    model = request, contentDescription = null, imageLoader = loader, contentScale = ContentScale.Crop,
                    alignment = BiasAlignment(0f, -0.4f), modifier = Modifier.fillMaxSize(),
                )
            }
            // The tag (top-left), the play disc (decoration, never focusable) and the caption over a static gradient.
            Box(Modifier.align(Alignment.TopStart).padding(POSTER_TAG_INSET.dp).testTag(HUB_POSTER_TAG_TAG).background(Black60, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Line(spec.tag, GuideUi.sans(22, FontWeight.Bold))
            }
            // (Review M4) Decoration only (U-N11): below POSTER_DISC_MIN_H (Larger, Largest) the 88 px disc would run into the
            // caption, so it is not drawn.
            if (heightDp >= HubPanelPlan.POSTER_DISC_MIN_H) Box(Modifier.align(Alignment.Center).size(88.dp).background(Black50, CircleShape).testTag(HUB_PLAY_DISC_TAG), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(28.dp).drawBehind {
                        val p = Path().apply { moveTo(size.width * 0.15f, 0f); lineTo(size.width, size.height / 2f); lineTo(size.width * 0.15f, size.height); close() }
                        drawPath(p, Color.White)
                    },
                )
            }
            spec.caption?.let {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().testTag(HUB_POSTER_CAPTION_TAG)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
                ) { Line(it, GuideUi.sans(26, FontWeight.SemiBold)) }
            }
        }
    }
}

/** The poster tag's inset from the media area's top-left corner (design px). */
private const val POSTER_TAG_INSET = 12
internal const val HUB_POSTER_TAG_TAG = "hub_poster_tag"
internal const val HUB_POSTER_CAPTION_TAG = "hub_poster_caption"

/** (A-N3) Low-memory devices take the thumbnail at 16 bits; the app has no per-screen device class, so the system's flag. */
@Composable
private fun lowEnd(): Boolean {
    val context = LocalContext.current
    return remember(context) { (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.isLowRamDevice == true }
}

// ------------------------------------------------------------------ buttons

@Composable
private fun Buttons(model: HubPanelModel, requesters: List<FocusRequester>, focusedSlot: Int, entered: Boolean, onSlot: (Int) -> Unit, onFocus: (Int?) -> Unit) {
    val res = LocalResources.current
    val factor = UiTextScale.factor
    val focusable = LocalHubFocusable.current && !LocalHubSlotsRefuseFocus.current
    val large = HubCardMetrics.large(factor)
    val pad = if (large) 20 else 28
    val gap = if (large) 12 else 16
    Row(Modifier.fillMaxWidth().height(HubPanelPlan.buttons(factor).dp), horizontalArrangement = Arrangement.spacedBy(gap.dp), verticalAlignment = Alignment.CenterVertically) {
        model.slots.forEachIndexed { i, slot ->
            if (slot.action == null) return@forEachIndexed
            val focused = focusedSlot == i
            val text = slotText(res, slot)
            val glyph = slotGlyph(slot, i)
            Box(
                Modifier
                    .testTag(hubSlotTag(i))
                    .height(HubPanelPlan.buttons(factor).dp)
                    .focusRequester(requesters[i])
                    .focusProperties { canFocus = slot.enabled && entered && focusable }
                    .onFocusChanged { if (it.isFocused) onFocus(i) else if (focusedSlot == i) onFocus(null) }
                    .background(if (focused) ButtonFocusFill else GuideUi.White10, RoundedCornerShape(30.dp))
                    .let { if (focused) it.border(3.dp, GuideUi.Accent, RoundedCornerShape(30.dp)) else it.border(1.dp, GuideUi.White16, RoundedCornerShape(30.dp)) }
                    .clickable(enabled = slot.enabled, interactionSource = remember { MutableInteractionSource() }, indication = null) { onSlot(i) }
                    .semantics { contentDescription = text }
                    .padding(horizontal = pad.dp),
                contentAlignment = Alignment.Center,
            ) {
                val ink = (if (focused) ButtonInk else GuideUi.Fg).let { if (slot.enabled) it else it.copy(alpha = 0.4f) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (glyph != null) Line(glyph, GuideUi.sans(24, FontWeight.SemiBold, ink))
                    Line(text, GuideUi.sans(24, FontWeight.SemiBold, ink))
                }
            }
        }
    }
}
