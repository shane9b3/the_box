package app.thebox.ui.sports

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.LeagueGroup
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.ClipInfo
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
}

/** (§6.6) Whose words a league's texts use. */
internal enum class HubSource {
    /** NHL.com (the NHL's detail and video). */
    NHL_COM,
    /** ESPN (the NFL's board, summary and video). */
    ESPN,
    /** No detail source (the CHL: the board only). */
    NONE,
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
