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
import app.thebox.core.sports.hub.StatRules
import app.thebox.core.sports.hub.StatValue
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
 * [GOALS] opens the Goals sheet (NHL) or the Scoring sheet (NFL hub §3.1); [WATCH_RECAP] is also ▶ Highlights and
 * [WATCH_FROM_START] ▶ From start (NFL hub §2.3).
 */
enum class SlotAction { FOLLOW, CHANNEL, GOALS, WATCH_RECAP, BOX_SCORE, MATCHUP, WATCH_FROM_START, RETRY }

/**
 * The words of a slot (§1.5): short and fixed; the channel's name is never in a label. (NFL hub commit 0, §2.3) Appended:
 * [HIGHLIGHTS] (▶ Highlights, action WATCH_RECAP), [HIGHLIGHTS_SOON], [NO_HIGHLIGHTS], [FROM_START] (▶ From start, action
 * WATCH_FROM_START), [SCORING] and [SCORING_N] (action GOALS, which opens the scoring sheet).
 */
enum class SlotLabel {
    FOLLOW, FOLLOWING, WATCH, CHANNELS, CHANNELS_N, FINDING, GOALS, GOALS_N, WATCH_RECAP, RECAP_SOON, BOX_SCORE, MATCHUP, WATCH_FROM_START, RETRY,
    HIGHLIGHTS, HIGHLIGHTS_SOON, NO_HIGHLIGHTS, FROM_START, SCORING, SCORING_N,
}

/** One of the three fixed slots. [action] null = an empty slot (draws nothing, keeps its place). */
@Immutable
data class HubSlot(val action: SlotAction?, val label: SlotLabel = SlotLabel.FOLLOW, val enabled: Boolean = false, val count: Int = 0) {
    val usable: Boolean get() = action != null && enabled
    companion object { val EMPTY = HubSlot(null) }
}

/** The rows a match offers the Channels sheet: the candidates, or the team channels of a game beyond the guide. */
internal fun candidatesOf(m: MatchState?): List<ChannelCandidate> = when (m) {
    is MatchState.Done -> m.candidates
    is MatchState.BeyondGuide -> m.teams
    else -> emptyList()
}

/** The channel match of the shown game (§5). */
sealed interface MatchState {
    /** Nothing asked yet. */
    data object Idle : MatchState
    data object Finding : MatchState
    data class Done(val candidates: List<ChannelCandidate>) : MatchState
    /**
     * (Event channels commit 0, docs/design/m6-event-channels.md §4.4, §8.5) The game starts beyond the guide
     * (`ChannelHorizon.beyondGuide`): no channel search runs. [teams]: the game's team channels (stage T), named by the
     * line; [noGuide]: there is no guide at all. Commit 0's branch disables Channels and draws no line; WP-4 draws the
     * line and sets it in the content description.
     */
    data class BeyondGuide(
        val teams: List<ChannelCandidate>,
        val noGuide: Boolean,
        /**
         * (Code review 2026-11-25, UI m3) False for the placeholder published before `teamChannels` answers: a cancelled
         * read leaves it behind, and the next visit must read the team channels again instead of keeping it.
         */
        val complete: Boolean = true,
    ) : MatchState
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
    /**
     * (NFL hub §4.3) The view model's offer tick: bumped at each instant a shown clip's offer changes (an expiry or an
     * embargo), so an otherwise equal model is a new one and every `ClipOffer` check is made again at the new [nowMs].
     */
    val offerTick: Int = 0,
) {
    /** The index of the first slot OK / Menu on a card enters (§2.2); null = none (the footer shows no OK). */
    val firstEnabled: Int? get() = slots.indexOfFirst { it.usable }.takeIf { it >= 0 }
    /** The sheet's rows: the match, or (owner ruling 2026-11-25) beyond the guide the team channels. */
    val candidates: List<ChannelCandidate> get() = candidatesOf(match)

    fun copy(slots: List<HubSlot> = this.slots): HubPanelModel = HubPanelModel(
        game, followed, detail, detailKind, failure, stale, failing, statsAtMs, awayScore, homeScore, match, recap, latestGoal,
        winningGoal, slots, nowMs, videoOff, imminent, offerTick,
    )

    /**
     * (Verification review L-d) The one thing the panel's words read from [nowMs] besides [imminent]: the local date, which
     * makes an NFL start "SUN 1:00 PM" or "MON NOV 30 8:15 PM" (the pill and the matchup art's centre). It is in the
     * equality, so the words change at midnight even when nothing else does; a later time on the same day is still equal.
     */
    private val nowDay: Long get() = java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()

    private fun parts(): List<Any?> = listOf(
        game, followed, detail, detailKind, failure, stale, failing, statsAtMs, awayScore, homeScore, match, recap, latestGoal,
        winningGoal, slots, videoOff, imminent, nowDay, offerTick,
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
        val candidates get() = candidatesOf(match)
        val catchUp get() = candidates.filter { it.airing != null }
        val recapId get() = detail?.recapId ?: game.media?.recapId
        val goalClips get() = detail?.goals?.count { it.clipId != null } ?: game.media?.goalClips ?: 0
        val goalCount get() = detail?.goals?.size ?: game.media?.goalClips ?: 0
        /**
         * Slot 1 is Retry while a retryable failure has nothing cached (§4.5), except on a final game whose recap id the
         * board already carries (and videos are not off): Watch recap stays in slot 1 and the panel body says the stats
         * retry on their own.
         */
        val detailRetry get() = HubLeagues.of(group).detailed && failure != null && failure in RETRYABLE && !(final && playableRecap && !videoOff)
        /** (NFL hub §2.3) The NHL: a recap id; the NFL: a highlight offered now (`ClipOffer`, §4.3). */
        val playableRecap: Boolean get() = if (group == LeagueGroup.NFL) NflHubUi.highlight(game, detail, NflHubUi.offerNow(nowMs, detail)) != null else recapId != null
        /** Live, or starting within `HUB_IMMINENT_MIN` (§5.5): Watch tunes and sheet rows tune. */
        val imminent get() = live || game.startUtcMs - nowMs <= SportsConstants.HUB_IMMINENT_MIN * 60_000L
    }

    /** The default slots of a game's phase (§1.5): (NFL hub commit 0, §6.6) the league's [HubLeagueUi.slots]. */
    fun default(c: Ctx): List<HubSlot> = HubLeagues.of(c.group).slots(c)

    /** The slot for [action] now: its label and enabled state recomputed (used in place while the set is frozen, §2.3). */
    fun forAction(action: SlotAction?, c: Ctx): HubSlot = when (action) {
        null -> HubSlot.EMPTY
        SlotAction.FOLLOW -> HubSlot(SlotAction.FOLLOW, if (c.followed) SlotLabel.FOLLOWING else SlotLabel.FOLLOW, true)
        SlotAction.RETRY -> if (c.detailRetry) HubSlot(SlotAction.RETRY, SlotLabel.RETRY, true) else HubSlot(SlotAction.FOLLOW, if (c.followed) SlotLabel.FOLLOWING else SlotLabel.FOLLOW, true)
        SlotAction.CHANNEL -> channel(c)
        // (NFL hub §2.3) The NFL's Scoring (n), ▶ Highlights / Highlights soon / No highlights and ▶ From start.
        SlotAction.GOALS -> if (c.group == LeagueGroup.NFL) NflHubUi.scoringSlot(c) else if (c.final) {
            HubSlot(SlotAction.GOALS, if (c.goalCount > 0) SlotLabel.GOALS_N else SlotLabel.GOALS, c.goalCount > 0, c.goalCount)
        } else {
            HubSlot(SlotAction.GOALS, if (c.goalClips > 0) SlotLabel.GOALS_N else SlotLabel.GOALS, c.goalClips > 0 && !c.videoOff, c.goalClips)
        }
        SlotAction.WATCH_RECAP ->
            if (c.group == LeagueGroup.NFL) NflHubUi.highlightSlot(c)
            else if (c.recapId != null) HubSlot(SlotAction.WATCH_RECAP, SlotLabel.WATCH_RECAP, !c.videoOff)
            else HubSlot(SlotAction.WATCH_RECAP, SlotLabel.RECAP_SOON, false)
        SlotAction.BOX_SCORE -> HubSlot(SlotAction.BOX_SCORE, SlotLabel.BOX_SCORE, true)
        // (Verification review MED-1) Enabled exactly when the Matchup sheet has rows (a goalie pair with two empty lists
        // draws none).
        SlotAction.MATCHUP -> HubSlot(SlotAction.MATCHUP, SlotLabel.MATCHUP, c.detail != null && hasMatchupRows(c.detail))
        SlotAction.WATCH_FROM_START ->
            if (c.group == LeagueGroup.NFL) NflHubUi.fromStartSlot(c) else HubSlot(SlotAction.WATCH_FROM_START, SlotLabel.WATCH_FROM_START, c.catchUp.isNotEmpty())
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
            // (Event channels §8.5; owner ruling 2026-11-25, §18) Beyond the guide the chip opens the team channels when
            // there are any ("Channels (2)"); it is grey only with none to offer.
            is MatchState.BeyondGuide ->
                if (m.teams.isEmpty()) HubSlot(SlotAction.CHANNEL, SlotLabel.CHANNELS, false)
                else HubSlot(SlotAction.CHANNEL, SlotLabel.CHANNELS_N, true, m.teams.size)
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

/**
 * [oneLine] (NFL hub §2.4, §6.6): drawn `maxLines = 1` (the NFL's fitted lines); false keeps the NHL's wrap rule. [desc]
 * (§3.7): the spoken form when it differs from the words ("Bengals ball, second and 7 at the Commanders 35"); null reads
 * the text.
 */
@Immutable
internal data class SummaryLine(val label: String, val text: String, val oneLine: Boolean = false, val desc: String? = null)

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
    val channelLine: HubPanelContents.ChannelLine?,
    val media: MediaSpec,
    val mediaDesc: String,
    /** NFL / CHL with nothing extra (§7.1): the one line instead of rows. */
    val moreSoon: String?,
    val plan: HubPanelPlan.Plan,
)

internal object HubPanelContents {
    /**
     * The panel's words for [m]. (NFL hub commit 0, §6.6) The league's [HubLeagueUi] gives the rows, summary and media;
     * [measure] answers text widths for the summary's fitted lines; [lowEnd]: a low-RAM device (the media choice).
     */
    fun build(
        res: Resources,
        m: HubPanelModel,
        clock24: Boolean,
        factor: Float,
        measure: Measure,
        locale: Locale = Locale.getDefault(),
        lowEnd: Boolean = false,
    ): PanelContent? {
        val g = m.game ?: return null
        val league = g.id.league
        val away = HubNames.of(league, g.away)
        val home = HubNames.of(league, g.home)
        val pill = SportsText.hubPill(res, g, clock24, m.nowMs, locale)
        val ui = HubLeagues.of(league.group)
        val showScores = g.phase != GamePhase.SCHEDULED && g.phase != GamePhase.PRE && g.phase != GamePhase.POSTPONED

        // ---- rows
        val spec = ui.rows(res, m, g, away, home)
        val rows: List<PanelRow> = spec.rows
        val header = spec.header
        val loading = spec.loading
        val error: String? = spec.error
        val moreSoon: String? = spec.moreSoon

        // ---- the summary and the plan
        val summarySpec = ui.summary(res, m, g, away, home, measure, factor)
        val summary = summarySpec.lines
        val extra: List<PanelRow> = spec.extra + summarySpec.extra
        val channelLine = channelLine(res, m, g, clock24, locale, factor)
        val mediaSpec = ui.media(res, m, g, clock24, locale, lowEnd)
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
            // (NFL hub §2.6, UX m9) An extra row stays only while a playable poster keeps its play disc.
            extraFloor = if (mediaSpec is MediaSpec.Poster) HubPanelPlan.POSTER_DISC_MIN_H else floor,
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

    internal fun statLabel(k: StatKind): Int = when (k) {
        StatKind.SOG -> R.string.sports_hub_stat_sog
        StatKind.POWER_PLAY -> R.string.sports_hub_stat_power_play
        StatKind.HITS -> R.string.sports_hub_stat_hits
        StatKind.BLOCKED -> R.string.sports_hub_stat_blocked
        StatKind.FACEOFF_PCT -> R.string.sports_hub_stat_faceoff_pct
        StatKind.PIM -> R.string.sports_hub_stat_pim
        StatKind.GIVEAWAYS -> R.string.sports_hub_stat_giveaways
        StatKind.TAKEAWAYS -> R.string.sports_hub_stat_takeaways
        // (NFL hub commit 0, §2.2)
        StatKind.TOTAL_YARDS -> R.string.sports_hub_stat_total_yards
        StatKind.PASS_YARDS -> R.string.sports_hub_stat_pass_yards
        StatKind.RUSH_YARDS -> R.string.sports_hub_stat_rush_yards
        StatKind.FIRST_DOWNS -> R.string.sports_hub_stat_first_downs
        StatKind.THIRD_DOWN -> R.string.sports_hub_stat_third_down
        StatKind.TURNOVERS -> R.string.sports_hub_stat_turnovers
        StatKind.POSSESSION -> R.string.sports_hub_stat_possession_time
        StatKind.RECORD -> R.string.sports_hub_stat_record
        StatKind.LAST_FIVE -> R.string.sports_hub_stat_last5
        StatKind.POINTS_PG -> R.string.sports_hub_stat_points_pg
        StatKind.POINTS_ALLOWED_PG -> R.string.sports_hub_stat_points_allowed_pg
        StatKind.YARDS_PG -> R.string.sports_hub_stat_yards_pg
        StatKind.YARDS_ALLOWED_PG -> R.string.sports_hub_stat_yards_allowed_pg
    }

    /**
     * (NFL hub §6.1) The highlighted side is `StatRules.better` for every league: the same comparison the panel made
     * (counts and fractions by made, percentages by value, PIM and giveaways lower), so the NHL rows are unchanged.
     */
    internal fun statRow(res: Resources, label: String, row: StatRow, away: HubName, home: HubName): PanelRow {
        val a = value(row.away)
        val h = value(row.home)
        return PanelRow(label, a, h, StatRules.better(row), res.getString(R.string.sports_hub_desc_stat, label, away.nickname, a, home.nickname, h))
    }

    internal fun value(v: StatValue): String = when (v) {
        is StatValue.Count -> v.n.toString()
        is StatValue.Fraction -> if (v.dash) "${v.made}-${v.of}" else "${v.made}/${v.of}"
        is StatValue.Pct -> pct(v.value)
        // (NFL hub commit 0, §2.2) "22.6", "31:31", "4-5-1".
        is StatValue.Decimal -> String.format(Locale.US, "%.${v.places.coerceIn(0, 3)}f", v.value)
        is StatValue.Clock -> SportsText.clock(v.seconds)
        is StatValue.Text -> v.text
    }

    /** A fraction (0.272727) or a percentage (27.2727) as "27.3%". */
    internal fun pct(v: Double): String = String.format(Locale.US, "%.1f%%", if (v <= 1.0) v * 100.0 else v)

    internal fun blank(label: String, none: String, away: HubName, home: HubName, res: Resources) =
        PanelRow(label, none, none, null, res.getString(R.string.sports_hub_desc_stat, label, away.nickname, none, home.nickname, none))

    internal fun valueRow(label: String, a: Int, h: Int, away: HubName, home: HubName, res: Resources) =
        PanelRow(label, a.toString(), h.toString(), if (a == h) null else if (a > h) Side.AWAY else Side.HOME,
            res.getString(R.string.sports_hub_desc_stat, label, away.nickname, a.toString(), home.nickname, h.toString()))

    internal fun simpleRow(label: String, a: String?, h: String?, away: HubName, home: HubName, res: Resources): PanelRow {
        val none = res.getString(R.string.sports_hub_stat_none)
        return PanelRow(label, a ?: none, h ?: none, null, res.getString(R.string.sports_hub_desc_stat, label, away.nickname, a ?: none, home.nickname, h ?: none))
    }

    // ------------------------------------------------------------------ summary (§6.7): the league's (NFL hub §6.6)

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

    /**
     * The channel line (§1.3, §5.3; event channels §8.5, §9.1): [text] ellipsizes, [more] (" · +n more", U-E-N5) is never
     * cut; [beyond]: the game is beyond the guide (the line explains the grey Channels chip).
     */
    @Immutable
    internal data class ChannelLine(val text: String, val more: String?, val beyond: Boolean = false) {
        fun full(sep: String): String = if (more == null) text else text + sep + more
    }

    /** (E11) The hub line's budget in characters: 55 at Default, 45 at Largest (the 664 px line at 22 design px). */
    internal fun lineBudget(factor: Float): Int = (HUB_LINE_CHARS / factor.coerceAtLeast(0.5f)).toInt()

    internal fun channelLine(res: Resources, m: HubPanelModel, g: GameSnapshot, clock24: Boolean, locale: Locale, factor: Float = 1f): ChannelLine? {
        if (g.phase == GamePhase.POSTPONED) return null
        val final = g.phase == GamePhase.FINAL
        // (NFL hub §2.1) A final shows its catch-up airing's line in every league with a detail source (NHL, NFL).
        if (final && !HubLeagues.of(g.id.league.group).detailed) return null
        val sep = res.getString(R.string.sports_hub_summary_sep)
        val budget = lineBudget(factor)
        return when (val match = m.match) {
            MatchState.Idle, MatchState.Finding -> if (final) null else ChannelLine(res.getString(R.string.sports_hub_finding_channel), null)
            is MatchState.Done -> {
                val list = if (final) match.candidates.filter { it.airing != null } else match.candidates
                if (list.isEmpty()) return if (final) null else ChannelLine(res.getString(R.string.sports_hub_no_channel), null)
                val top = list.first()
                fun moreOf(shown: Int) = (list.size - shown).takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.sports_hub_more, it, it) }
                val time = if (final || m.imminent) null else SportsText.startTime(g.startUtcMs, clock24, locale)
                val team = top.reasons.filterIsInstance<MatchReason.TeamChannel>().firstOrNull()
                if (team != null) {
                    // (U-E-B3) "Oilers team channel", never a bare team name. (Code review 2026-11-25, UI open item 4) Both
                    // team channels when the other side's is listed too, top's team first ("7:00 PM: Bruins and Jets team
                    // channels"); then one nickname; only then (E11) abbreviations.
                    val other = list.drop(1).firstNotNullOfOrNull { c ->
                        c.reasons.filterIsInstance<MatchReason.TeamChannel>().firstOrNull { it.side != team.side }
                    }
                    fun one(name: String) = if (time == null) res.getString(R.string.sports_hub_on_tv_team, name)
                    else res.getString(R.string.sports_hub_on_tv_team_at, time, name)
                    fun two(a: String, b: String) = if (time == null) res.getString(R.string.sports_hub_on_tv_teams, a, b)
                    else res.getString(R.string.sports_hub_on_tv_teams_at, time, a, b)
                    val choices = ArrayList<ChannelLine>(4)
                    if (other != null) choices += ChannelLine(two(team.nickname, other.nickname), moreOf(2))
                    choices += ChannelLine(one(team.nickname), moreOf(1))
                    if (other != null) choices += ChannelLine(two(abbrevOf(g, team.side), abbrevOf(g, other.side)), moreOf(2))
                    choices += ChannelLine(one(abbrevOf(g, team.side)), moreOf(1))
                    return choices.firstOrNull { it.full(sep).length <= budget } ?: choices.last()
                }
                val text = if (time == null) {
                    res.getString(R.string.sports_hub_on_tv, top.channel.displayName, shortReason(res, top))
                } else {
                    res.getString(R.string.sports_hub_on_tv_at, time, top.channel.displayName)
                }
                ChannelLine(text, moreOf(1))
            }
            is MatchState.BeyondGuide -> beyondLine(res, g, match, budget, sep)
        }
    }

    /**
     * (§8.5; owner rulings 2026-11-25, event channels §18) The line of a game beyond the guide. With team channels (the
     * Channels chip is enabled and lists them): the guide does not reach the game yet, and the team channels will carry it,
     * named in the matcher's order (the user's team, else home, first): both nicknames, both abbreviations, one nickname,
     * one abbreviation, the first that fits; never "On TV" (UI m8). "+n more" counts the other rows, as the chip does.
     * Without one (the chip is grey, item 3 speaks this line): the guide, else no guide at all.
     */
    private fun beyondLine(res: Resources, g: GameSnapshot, match: MatchState.BeyondGuide, budget: Int, sep: String): ChannelLine {
        val sides = match.teams.flatMap { c -> c.reasons.filterIsInstance<MatchReason.TeamChannel>() }.distinctBy { it.side }
        if (sides.isEmpty()) {
            return ChannelLine(res.getString(if (match.noGuide) R.string.sports_hub_beyond_guide_none else R.string.sports_hub_beyond_guide), null, beyond = true)
        }
        fun moreOf(shown: Int) = (match.teams.size - shown).takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.sports_hub_more, it, it) }
        val choices = ArrayList<ChannelLine>(4)
        if (sides.size > 1) {
            choices += ChannelLine(res.getString(R.string.sports_hub_beyond_guide_teams, sides[0].nickname, sides[1].nickname), moreOf(2), beyond = true)
            choices += ChannelLine(res.getString(R.string.sports_hub_beyond_guide_teams, abbrevOf(g, sides[0].side), abbrevOf(g, sides[1].side)), moreOf(2), beyond = true)
        }
        choices += ChannelLine(res.getString(R.string.sports_hub_beyond_guide_one, sides[0].nickname), moreOf(1), beyond = true)
        choices += ChannelLine(res.getString(R.string.sports_hub_beyond_guide_one, abbrevOf(g, sides[0].side)), moreOf(1), beyond = true)
        return choices.firstOrNull { it.full(sep).length <= budget } ?: choices.last()
    }

    private fun abbrevOf(g: GameSnapshot, side: Side): String = if (side == Side.AWAY) g.away.abbrev else g.home.abbrev

    /** The line's short reason (§9.1): the guide, the channel name, else (re-review E12) "national broadcaster". */
    private fun shortReason(res: Resources, c: ChannelCandidate): String = res.getString(
        when {
            c.reasons.any { it is MatchReason.Guide || it is MatchReason.SportListing } -> R.string.sports_hub_reason_short_guide
            c.reasons.any { it is MatchReason.EventName } -> R.string.sports_hub_reason_short_event
            else -> R.string.sports_hub_reason_short_national
        },
    )

    // ------------------------------------------------------------------ media (§1.3)

    // (NFL hub commit 0, §6.6) The media choice is the league's ([HubLeagueUi.media]); the art is shared.

    internal fun art(res: Resources, g: GameSnapshot, m: HubPanelModel, caption: String?, clock24: Boolean, locale: Locale): MediaSpec.Art {
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
            // (NFL hub §3.7) "Game highlights, 1 minute 14 seconds".
            else if (spec.tag == res.getString(R.string.sports_hub_tag_highlights) && ms != null) res.getString(R.string.sports_hub_desc_media_highlights, SportsText.spokenDuration(res, ms))
            else spec.caption ?: spec.tag
        }
        else -> res.getString(R.string.sports_hub_desc_media_matchup, away.nickname, home.nickname, SportsText.startTime(g.startUtcMs, clock24, locale))
    }

    /**
     * The reason and the next step for a failed detail (§4.5), from the slots actually on screen (review H3): a usable Retry
     * slot → "Select Retry"; else a final game with a usable Watch recap → "Select Watch recap"; else (the slots were frozen
     * before the failure, so slot 1 is still Follow, or Watch recap is off) → Back, then OK, lands on Retry.
     */
    fun failureText(res: Resources, kind: FailKind?, slots: List<HubSlot>, final: Boolean, source: HubSource = HubSource.NHL_COM): String {
        val espn = source == HubSource.ESPN
        return res.getString(
            when (kind) {
                FailKind.HTTP_429, FailKind.REFUSED -> if (espn) R.string.sports_hub_error_detail_limited_espn else R.string.sports_hub_error_detail_limited
                FailKind.BAD_BODY -> if (espn) R.string.sports_hub_error_detail_changed_espn else R.string.sports_hub_error_detail_changed
                FailKind.OFFLINE -> R.string.sports_hub_error_detail_offline
                else -> when {
                    slots.any { it.action == SlotAction.RETRY && it.usable } -> if (espn) R.string.sports_hub_error_detail_retry_espn else R.string.sports_hub_error_detail_retry
                    final && slots.any { it.action == SlotAction.WATCH_RECAP && it.usable } -> if (espn) R.string.sports_hub_error_detail_auto_espn else R.string.sports_hub_error_detail_auto
                    else -> if (espn) R.string.sports_hub_error_detail_back_espn else R.string.sports_hub_error_detail_back
                }
            },
        )
    }
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
    const val PANEL_H = 864
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
     *
     * [extraFloor] (NFL hub commit 0, §2.6): the floor the extra rows must leave; P3 passes `POSTER_DISC_MIN_H` for a
     * playable poster so the play disc survives. It equals [mediaFloor] for every NHL and art case.
     */
    fun plan(
        factor: Float, mandatoryRows: Int, extraRows: Int, summaryLines: Int, hasChannelLine: Boolean, hasNote: Boolean,
        mediaFloor: Int, hasRows: Boolean, extraFloor: Int = mediaFloor,
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
        while (extras > 0 && media(mandatoryRows + extras, lines) < extraFloor) extras--
        while (lines > 0 && media(mandatoryRows + extras, lines) < mediaFloor) lines--
        return Plan(media(mandatoryRows + extras, lines), lines, extras, mediaFloor)
    }
}


// =====================================================================================================================
// The panel (§1.3): 720 × 864 (owner change 2026-11-25: was 812), inner 664
// =====================================================================================================================

const val HUB_PANEL_TAG = "hub_panel"
const val HUB_PILL_TAG = "hub_panel_pill"
const val HUB_HEADLINE_TAG = "hub_panel_headline"
internal const val HUB_HEADLINE_AWAY_TAG = "hub_panel_headline_away"
internal const val HUB_HEADLINE_HOME_TAG = "hub_panel_headline_home"
const val HUB_CHANNEL_LINE_TAG = "hub_panel_channel"
/** The channel line's never-cut " · +n more" (U-E-N5). */
const val HUB_CHANNEL_MORE_TAG = "hub_panel_channel_more"
/** (E11) Characters of the hub's one-line slots at Default (22 design px on 664 px); 45 at Largest. */
internal const val HUB_LINE_CHARS = 55
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
    // (NFL hub commit 0, §2.3)
    SlotLabel.HIGHLIGHTS -> res.getString(R.string.sports_hub_slot_highlights)
    SlotLabel.HIGHLIGHTS_SOON -> res.getString(R.string.sports_hub_slot_highlights_soon)
    SlotLabel.NO_HIGHLIGHTS -> res.getString(R.string.sports_hub_slot_no_highlights)
    SlotLabel.FROM_START -> res.getString(R.string.sports_hub_slot_from_start)
    SlotLabel.SCORING -> res.getString(R.string.sports_hub_slot_scoring)
    SlotLabel.SCORING_N -> res.getString(R.string.sports_hub_slot_scoring_n, slot.count)
}

/**
 * The glyph of slot 1 only (U-B3): a plus for Follow, a triangle for a video, a loop for Retry. (NFL hub §2.3, UX B1) A
 * disabled slot draws none ("Highlights soon", the NHL's "Recap soon"): a ▶ on something that cannot play misleads.
 */
internal fun slotGlyph(slot: HubSlot, index: Int): String? = if (index != 0 || !slot.enabled) null else when (slot.action) {
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
    // (NFL hub commit 0, §6.6) The measure first: the build fits the NFL's summary lines with it.
    val measure = rememberHubMeasure()
    // (NFL hub P3) The fitted summary lines compare design px with the 664 px line: the measure answers device px, so it
    // is divided by the density here (the headline keeps the device-px measure and width).
    val density = LocalDensity.current.density
    val designMeasure: Measure = remember(measure, density) { { t, px, w -> measure(t, px, w) / density } }
    val low = lowEnd()
    val content = remember(model, clock24, factor, designMeasure, low) { HubPanelContents.build(res, model, clock24, factor, designMeasure, lowEnd = low) }
    // (NFL hub §2.3, UX M4) The spoken reason of every grey slot: the league's, and the beyond-guide line for Channels.
    val reasons = remember(model, content) { slotReasonsOf(res, model, content) }
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
            .semantics {
                paneTitle = title
                // (U-E-B4) The grey Channels chip is never focused: the panel itself says why.
                greyReason(model, content)?.let { contentDescription = it }
            }
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
        content.channelLine?.let { line ->
            Spacer(Modifier.height(16.dp))
            ChannelLineRow(line, factor)
        }
        Spacer(Modifier.height(12.dp))
        Buttons(model, slotRequesters, focusedSlot, entered, onSlot, reasons, greyReason(model, content) != null) { i ->
            focusedSlot = i ?: -1
            onSlotFocus(i)
        }
    }
}

/** The beyond-guide line while the Channels chip is grey (no team channel to offer), else null. */
private fun greyReason(model: HubPanelModel, content: PanelContent?): String? =
    content?.channelLine?.takeIf { it.beyond && model.slots.any { s -> s.action == SlotAction.CHANNEL && !s.enabled } }?.text

/**
 * (NFL hub §2.3, UX M4; generalises event channels §18.3) One entry per slot: the spoken reason of a grey slot, else
 * null. A grey Channels slot speaks the beyond-guide line; every other grey slot the league's reason
 * ([HubLeagueUi.slotReasons]: Highlights soon, No highlights, Scoring, the NHL's Recap soon).
 */
internal fun slotReasonsOf(res: Resources, model: HubPanelModel, content: PanelContent?): List<String?> {
    val g = model.game ?: return model.slots.map { null }
    val league = HubLeagues.of(g.id.league.group).slotReasons(res, model, model.slots)
    val beyond = greyReason(model, content)
    return model.slots.mapIndexed { i, s ->
        if (s.action == SlotAction.CHANNEL && !s.enabled) beyond ?: league.getOrNull(i) else league.getOrNull(i)
    }
}

/**
 * The slot descriptions (§2.3): a grey slot with a reason is "<label>, unavailable. <reason>"; the first usable slot (the
 * one that takes focus on entering the panel, where accessibility focus follows the D-pad) adds the grey slots' reasons:
 * "<label>. Channels unavailable: <line>" when only Channels is grey beyond the guide (event channels §18.3, unchanged),
 * else "<label>. <grey labels> unavailable: <reasons>", joined with "; " for two.
 */
internal fun slotDescriptions(res: Resources, slots: List<HubSlot>, reasons: List<String?>, beyondChannels: Boolean): List<String> {
    val texts = slots.map { if (it.action == null) "" else slotText(res, it) }
    val grey = slots.indices.filter { slots[it].action != null && !slots[it].enabled && reasons.getOrNull(it) != null }
    val firstUsable = slots.indexOfFirst { it.usable }
    fun noun(s: HubSlot, text: String): String = when (s.label) {
        SlotLabel.HIGHLIGHTS_SOON, SlotLabel.NO_HIGHLIGHTS -> res.getString(R.string.sports_hub_slot_highlights)
        SlotLabel.RECAP_SOON -> res.getString(R.string.sports_hub_summary_recap)
        else -> text
    }
    return slots.mapIndexed { i, s ->
        val text = texts[i]
        when {
            s.action == null -> text
            i in grey -> res.getString(R.string.sports_hub_desc_slot_unavailable, text, reasons[i]!!)
            i == firstUsable && grey.isNotEmpty() -> {
                val onlyChannels = grey.size == 1 && slots[grey[0]].action == SlotAction.CHANNEL && beyondChannels
                if (onlyChannels) res.getString(R.string.sports_hub_desc_slot_channels_note, text, reasons[grey[0]]!!)
                else res.getString(
                    R.string.sports_hub_desc_slot_note, text,
                    grey.map { noun(slots[it], texts[it]) }.reduce { a, b -> res.getString(R.string.sports_hub_desc_join, a, b) },
                    grey.map { reasons[it]!! }.reduce { a, b -> res.getString(R.string.sports_hub_desc_join, a, b) },
                )
            }
            else -> text
        }
    }
}

/**
 * (Event channels §9.1, U-E-N5) The channel line as two texts in a row: the words ellipsize, " · +n more" is never cut.
 * The row reads as one sentence to TalkBack.
 */
@Composable
private fun ChannelLineRow(line: HubPanelContents.ChannelLine, factor: Float) {
    val res = LocalResources.current
    val sep = res.getString(R.string.sports_hub_summary_sep)
    val style = GuideUi.sans(22, FontWeight.Medium, GuideUi.Fg70)
    Row(
        Modifier.fillMaxWidth().height(HubPanelPlan.channel(factor).dp).testTag(HUB_CHANNEL_LINE_TAG)
            .semantics(mergeDescendants = true) { contentDescription = line.full(sep) },
        // Top, as the one-text line was drawn in its fixed-height slot.
        verticalAlignment = Alignment.Top,
    ) {
        Line(line.text, style, Modifier.weight(1f, fill = false))
        line.more?.let { Line(sep + it, style, Modifier.testTag(HUB_CHANNEL_MORE_TAG)) }
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
                text, style = GuideUi.sans(24, FontWeight.Normal, GuideUi.Fg70), maxLines = if (s.oneLine) 1 else if (i == 0 && lines == 2) 2 else 1, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (i == 0) wrapped = it.lineCount > 1 },
                modifier = Modifier.fillMaxWidth().testTag(HUB_SUMMARY_TAG + i).let { m -> s.desc?.let { d -> m.semantics { contentDescription = d } } ?: m },
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
                val key = "${clipKeyPrefix(g)}:${spec.videoId}:${w}x$h"
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

/** (NFL hub §4.5) The image cache key's prefix: "espn-clip" for the NFL's ESPN pictures, "nhl-clip" otherwise. */
internal fun clipKeyPrefix(g: GameSnapshot): String = if (g.id.league.group == LeagueGroup.NFL) "espn-clip" else "nhl-clip"

/** The poster tag's inset from the media area's top-left corner (design px). */
private const val POSTER_TAG_INSET = 12
internal const val HUB_POSTER_TAG_TAG = "hub_poster_tag"
internal const val HUB_POSTER_CAPTION_TAG = "hub_poster_caption"

/** (A-N3) Low-memory devices take the thumbnail at 16 bits; the app has no per-screen device class, so the system's flag. */
@Composable
internal fun lowEnd(): Boolean {
    val context = LocalContext.current
    return remember(context) { (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.isLowRamDevice == true }
}

// ------------------------------------------------------------------ buttons

@Composable
private fun Buttons(
    model: HubPanelModel,
    requesters: List<FocusRequester>,
    focusedSlot: Int,
    entered: Boolean,
    onSlot: (Int) -> Unit,
    /** (NFL hub §2.3, UX M4) Each slot's spoken reason while grey ([slotReasonsOf]). */
    reasons: List<String?>,
    /** (Code review 2026-11-25, UI M3) Channels is grey beyond the guide: its reason is the line (§18.3's words). */
    beyondChannels: Boolean,
    onFocus: (Int?) -> Unit,
) {
    val res = LocalResources.current
    val factor = UiTextScale.factor
    val focusable = LocalHubFocusable.current && !LocalHubSlotsRefuseFocus.current
    val large = HubCardMetrics.large(factor)
    val pad = if (large) 20 else 28
    val gap = if (large) 12 else 16
    Row(Modifier.fillMaxWidth().height(HubPanelPlan.buttons(factor).dp), horizontalArrangement = Arrangement.spacedBy(gap.dp), verticalAlignment = Alignment.CenterVertically) {
        // (Code review 2026-11-25, UI M3; NFL hub UX M4) A grey slot never takes focus, and on a TV accessibility focus
        // follows the D-pad: the slot that takes focus on entering the panel also says why the grey ones are grey, and each
        // grey slot says it for touch.
        val descs = remember(model.slots, reasons, beyondChannels) { slotDescriptions(res, model.slots, reasons, beyondChannels) }
        model.slots.forEachIndexed { i, slot ->
            if (slot.action == null) return@forEachIndexed
            val focused = focusedSlot == i
            val text = slotText(res, slot)
            val desc = descs[i]
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
                    .semantics { contentDescription = desc }
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
