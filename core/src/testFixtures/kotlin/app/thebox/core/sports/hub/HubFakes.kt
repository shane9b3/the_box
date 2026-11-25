package app.thebox.core.sports.hub

import app.thebox.core.parental.ParentalAccess
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GameSnapshot
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * (Sports Hub commit 0, docs/design/m6-sports-hub.md §11) A [GameDetailRepository] for `:ui`, `:data` and `:app` tests.
 * [detail] is a public mutable flow the test drives; every input is recorded. No requests, no clock.
 */
class FakeGameDetailRepository : GameDetailRepository {
    override val detail = MutableStateFlow<DetailState>(DetailState.None)
    val showCalls = mutableListOf<GameSnapshot?>()
    val pauseCalls = mutableListOf<Boolean>()
    val boxScoreCalls = mutableListOf<GameId>()
    var retries = 0
        private set
    var trims = 0
        private set
    /** What [boxScore] answers; the default is a failure (no box score). */
    var boxScoreAnswer: (GameId) -> Fetch<BoxScore> = { Fetch.Failed(FailKind.HTTP_OTHER, null, 0L) }

    override fun show(game: GameSnapshot?) { showCalls += game }
    override fun pause(paused: Boolean) { pauseCalls += paused }
    override fun retry() { retries++ }
    override suspend fun boxScore(id: GameId): Fetch<BoxScore> { boxScoreCalls += id; return boxScoreAnswer(id) }
    override fun trim() { trims++ }
}

/**
 * (Sports Hub commit 0) A [ClipResolver] whose answers the test sets per video id ([infos], [streams]); an id without an
 * answer is [ClipResult.NotFound]. Every call is recorded in order.
 */
class FakeClipResolver : ClipResolver {
    val infos = mutableMapOf<Long, ClipResult<ClipInfo>>()
    val streams = mutableMapOf<Long, ClipResult<Pair<ClipInfo, ClipStream>>>()
    val infoCalls = mutableListOf<ClipRef>()
    val streamCalls = mutableListOf<ClipRef>()

    override suspend fun info(ref: ClipRef): ClipResult<ClipInfo> {
        infoCalls += ref
        return infos[ref.videoId] ?: ClipResult.NotFound
    }

    override suspend fun stream(ref: ClipRef): ClipResult<Pair<ClipInfo, ClipStream>> {
        streamCalls += ref
        return streams[ref.videoId] ?: ClipResult.NotFound
    }

    companion object {
        /** A playable answer for [ref]: a fake HLS URL on a test host (never a real signed URL). */
        fun okStream(ref: ClipRef, title: String = "Clip ${ref.videoId}", durationMs: Long? = 46_000L): ClipResult<Pair<ClipInfo, ClipStream>> =
            ClipResult.Ok(
                ClipInfo(ref, title, null, durationMs, null, null) to
                    ClipStream("https://clips.test/${ref.videoId}/master.m3u8?redacted=1", ClipFormat.HLS, null),
            )
    }
}

/** (Sports Hub commit 0) A [GameChannelMatcher] answering [answers] by game id (empty by default); calls recorded. */
class FakeGameChannelMatcher : GameChannelMatcher {
    val answers = mutableMapOf<GameId, List<ChannelCandidate>>()
    val matchCalls = mutableListOf<Triple<GameSnapshot, Long, ParentalAccess>>()
    var clears = 0
        private set

    override suspend fun match(game: GameSnapshot, nowMs: Long, access: ParentalAccess): List<ChannelCandidate> {
        matchCalls += Triple(game, nowMs, access)
        return answers[game.id].orEmpty()
    }

    override fun clear() { clears++ }
}

/**
 * (Sports Hub commit 0, §10.1) The recorded NHL bodies under `resources/sports/nhl/hub/` (read with `SportsFixtures.text`).
 * Clip bodies have every query string replaced by `?redacted=1`; nothing in them is a key or a token.
 */
object HubFixtures {
    private const val DIR = "sports/nhl/hub/"
    /** 2026-11-23: finals, incl. UTA @ WPG 2026020382 (recap, condensed game and goal clips). */
    const val SCORE_2026_11_23 = DIR + "score-2026-11-23.json"
    /** 2026-11-24 before puck drop: VGK @ EDM 2026020385 and the other future games (tvBroadcasts, gameWeek). */
    const val SCORE_NOW_2026_11_24 = DIR + "score-now-2026-11-24.json"
    const val GC_382_RIGHT_RAIL = DIR + "gc-2026020382-right-rail.json"
    const val GC_382_LANDING = DIR + "gc-2026020382-landing.json"
    const val GC_382_BOXSCORE = DIR + "gc-2026020382-boxscore.json"
    const val GC_385_RIGHT_RAIL = DIR + "gc-2026020385-right-rail.json"
    const val GC_385_LANDING = DIR + "gc-2026020385-landing.json"
    const val GC_385_BOXSCORE = DIR + "gc-2026020385-boxscore.json"
    /** Video lookups: 6408795671112 (recap), 6408795191112 (condensed game), 6408791393112 (goal, "Vilardi strikes first"). */
    const val CLIP_RECAP = DIR + "clip-recap.json"
    const val CLIP_CONDENSED = DIR + "clip-condensed.json"
    const val CLIP_GOAL = DIR + "clip-goal.json"

    /** The recorded phases of 2026020385 in the repo (see [LIVE]). */
    val LIVE_PHASES: List<String> = listOf("p1", "int", "pp", "p3", "final")

    /**
     * The live recording of 2026020385 (§10.5 lead task 1): per phase `score-now-385-{phase}.json` and
     * `gc-2026020385-{right-rail,landing,boxscore}-{phase}.json`. WP-1's live-phase mapper tests run on them (A-S9). The
     * clip lookups and the HLS master were recorded by the lead but are not fixtures (their signed addresses).
     */
    val LIVE: List<String> = LIVE_PHASES.flatMap { phase ->
        // (WP-1) Recorded 2026-11-25 by the lead: p1 = 01:31:31 UTC, 1st period 8:37, VGK 2-0, VGK power play (1541), the
        // first goal's clip id posted, the second's not yet; int = 01:49:55 UTC, 1st intermission (LIVE + inIntermission).
        // (Integration) pp = 02:37:06 UTC, 2nd period 3:09, VGK 2-1, EDM power play (1451), three goal clips posted;
        // p3 = 03:15:06 UTC, 3rd period 12:10, VGK 4-3, seven goals, the last three without a clip id yet;
        // final = 03:42:50 UTC, FINAL in regulation, VGK 5-3, eight goals (the empty-netter's clip not posted yet), three
        // stars, no recap yet (no `threeMinRecap` on the board, no `gameVideo` in the right-rail). No overtime.
        // Query strings scrubbed to `?redacted=1`.
        listOf("score-now-385-$phase.json") +
            listOf("right-rail", "landing", "boxscore").map { "gc-2026020385-$it-$phase.json" }
    }.map { DIR + it }
}
