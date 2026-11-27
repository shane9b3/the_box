package app.thebox.ui.sports

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.LeagueGroup
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.GameDetail
import java.util.Locale

/**
 * (NFL hub commit 0, docs/design/m6-nfl-hub.md §6.6) The one place the hub chooses the league. Each league's panel rows,
 * summary, media, slots, clip lookups and scoring rows live in its own implementer ([NhlHubUi], [NflHubUi],
 * [ChlHubUi]), which replace the `when (group)` branches of `HubPanelContents`, `HubSlots`, `SportsViewModel` and
 * `GoalsSheet`. Commit 0 moved today's branches verbatim (every sports golden hashes equal); P3 owns the file and its
 * implementers from here on and may extend the interface.
 */
internal interface HubLeagueUi {
    /** Header word, mandatory rows, extra rows; or the failure text in their place. */
    fun rows(res: Resources, m: HubPanelModel, g: GameSnapshot, away: HubName, home: HubName): RowsSpec

    /** Lines, fitted with [measure]; plus any extra row a line gave up (the NFL's Timeouts). */
    fun summary(res: Resources, m: HubPanelModel, g: GameSnapshot, away: HubName, home: HubName, measure: Measure, factor: Float): SummarySpec

    fun media(res: Resources, m: HubPanelModel, g: GameSnapshot, clock24: Boolean, locale: Locale, lowEnd: Boolean): MediaSpec

    /** The default slots of the game's phase (§2.3; m6-sports-hub §1.5). */
    fun slots(c: HubSlots.Ctx): List<HubSlot>

    /** One entry per slot: the spoken reason of a disabled slot, else null (§2.3). */
    fun slotReasons(res: Resources, m: HubPanelModel, slots: List<HubSlot>): List<String?>

    /**
     * The clip lookups the panel needs now (the NHL: today's `ensureClipInfos` body; the NFL: none). [goalsOpen]: the
     * game's Goals / Scoring sheet is open (the NHL's condensed game is looked up only then; §16 note 3).
     */
    fun clipRefs(game: GameSnapshot, detail: GameDetail?, infos: Map<Long, ClipInfo>, goalsOpen: Boolean): List<ClipRef>

    /** The Scoring (NHL: Goals) sheet's rows (§3.1). */
    fun scoringRows(
        res: Resources,
        game: GameSnapshot,
        detail: GameDetail?,
        infos: Map<Long, ClipInfo>,
        catchUp: List<ChannelCandidate>,
        offerNowMs: Long,
        videoOn: Boolean,
    ): List<ScoringRow>

    /** Whether this league has a detail source (Retry, the channel line on finals, DetailKind). */
    val detailed: Boolean

    /** NHL.com / ESPN wording (string resources chosen per league, never concatenated). */
    val source: HubSource

    // ---- (NFL hub code review 2026-11-26, UI §"open defect #1") the slot and offer branches, one per league

    /**
     * This league's slot for [action] now, or null for the shared rule in `HubSlots.forAction` (the NHL and the CHL use
     * the shared rule throughout).
     */
    fun slotFor(action: SlotAction, c: HubSlots.Ctx): HubSlot? = null

    /** Whether the recap / highlight plays now (slot 1 on a final keeps it over Retry, `HubSlots.Ctx.detailRetry`). */
    fun playableRecap(c: HubSlots.Ctx): Boolean = c.recapId != null

    /** The recap / highlight that WATCH_RECAP plays now; null plays nothing (then [recapGoneToast], when set, is shown). */
    fun recapRef(game: GameSnapshot, detail: GameDetail?, nowMs: Long): ClipRef? =
        (detail?.recapId ?: game.media?.recapId)?.let { ClipRef(it, ClipKind.RECAP, game.id) }

    /** The toast when WATCH_RECAP finds nothing to play (a frozen slot whose clip just expired); null for none. */
    val recapGoneToast: HubToastKind? get() = null

    /** Whether [ref] may be played now (`ClipOffer`; the NHL's and the CHL's clips do not expire). */
    fun offeredRef(ref: ClipRef, game: GameSnapshot?, detail: GameDetail?, nowMs: Long): Boolean = true

    /** The next wall instant anything [game]'s panel or card shows changes with time alone (the offer timer); null for none. */
    fun nextOfferChange(game: GameSnapshot, detail: GameDetail?, nowMs: Long, skewMs: Long): Long? = null

    /**
     * Whether the card's "● Highlights" chip lights (§1.2). [detail]: the shown game's detail, for its card only (the NFL
     * asks the panel's own highlight question, NFL hub §20); null for every other card.
     */
    fun cardHighlights(game: GameSnapshot, detail: GameDetail?, nowMs: Long): Boolean {
        val m = game.media ?: return false
        return m.recapId != null || m.condensedId != null || m.goalClips > 0
    }

    /** The league's video is switched off in this build (the NFL's `ESPN_VIDEO_ENABLED` kill switch). */
    val videoKilled: Boolean get() = false

    /** Whether the detail's live score may replace the board's in the headline (the NFL detail carries none). */
    val detailCarriesScore: Boolean get() = true

    /**
     * (UI M1) While focus is in the panel the slot set is frozen (§2.3). True: a phase change (pre-game → live → final)
     * re-freezes it to the new phase's slots. The NHL and the CHL keep the set for the whole visit, as before.
     */
    val refreezeOnPhaseChange: Boolean get() = false

    /**
     * (NFL hub §20) While focus is in the panel the slot set is frozen (§2.3); a change of this value re-freezes it to the
     * default set (the NFL: whether a final's highlight is offered, so slot 1 turns into ▶ Highlights when it appears).
     * Null (the NHL and the CHL): never.
     */
    fun slotSetKey(c: HubSlots.Ctx): Any? = null

    /** (UI M1) Whether an open Matchup sheet of [game] stays open; false closes it (focus to the first usable slot). */
    fun matchupOpen(game: GameSnapshot): Boolean = true

    /**
     * (CHL hub commit 0, docs/design/m6-chl-hub.md §3.3, §4.5 #1) The Box score sheet's error text for this league, or
     * null for the sheet's own rule (today's text; the NHL and the NFL). The CHL (P3): `sports_hub_error_box_score_chl`
     * with the league's short name.
     */
    fun boxScoreError(res: Resources, game: GameSnapshot): String? = null
}

/** (§6.6) Whose words a league's texts use. */
internal enum class HubSource {
    /** NHL.com (the NHL's detail and video). */
    NHL_COM,
    /** ESPN (the NFL's board, summary and video). */
    ESPN,
    /** No detail source (the CHL: the board only). */
    NONE,
    /**
     * (CHL hub commit 0, §0 D4) HockeyTech `lscluster` (the CHL's pre-game and final detail): the `*_chl` texts, worded
     * with the league's short name. `ChlHubUi` switches from [NONE] to this with `CHL_HUB_ENABLED` (P3).
     */
    HOCKEYTECH,
}

/**
 * What [HubLeagueUi.rows] decided: the stats header [header], the mandatory [rows], the optional [extra] rows (dropped
 * first, §2.6), [loading] ("–" in `Fg40`), the failure [error] in place of the rows, or the one [moreSoon] line.
 */
@Immutable
internal data class RowsSpec(
    val header: String,
    val rows: List<PanelRow>,
    val extra: List<PanelRow> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val moreSoon: String? = null,
)

/** What [HubLeagueUi.summary] decided: the [lines] (at most two are drawn) and any [extra] row a line gave up. */
@Immutable
internal data class SummarySpec(val lines: List<SummaryLine>, val extra: List<PanelRow> = emptyList())

/** (§6.6) The league's [HubLeagueUi]. */
internal object HubLeagues {
    fun of(group: LeagueGroup): HubLeagueUi = when (group) {
        LeagueGroup.NHL -> NhlHubUi
        LeagueGroup.NFL -> NflHubUi
        LeagueGroup.CHL -> ChlHubUi
    }
}
