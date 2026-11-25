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

    /**
     * The live recording of 2026020385 (§10.5 lead task 1) is **not in the repo yet**. When the lead adds it, the files go
     * here as `score-now-385-{p1,int,p3,final}.json`, `gc-2026020385-{right-rail,landing,boxscore}-{p1,int,p3,final}.json`,
     * `clip-goal-live.json`, `clip-recap-385.json` and `master-goal.m3u8`; WP-1's live-phase mapper tests are gated on them
     * (A-S9). Until then this list is empty and those tests are skipped.
     */
    val LIVE: List<String> = emptyList()
}
