package app.thebox.core.sports

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * (Score bug commit 0, docs/design/m6-score-bug.md §9) A [ScoreRepository] for `:ui`, `:data` and `:app` tests. Every
 * output is a public mutable flow the test drives; every input is recorded. [follow] sets [followed] (and, with
 * [raiseFollowEvents], emits `Followed` / `FollowedFinal` as the real repository does), so follow toggles round-trip.
 * It makes no requests and has no clock.
 */
class FakeScoreRepository(
    prefs: SportsPrefs = SportsPrefs(),
    private val raiseFollowEvents: Boolean = false,
) : ScoreRepository {
    override val bug = MutableStateFlow<ScoreBugView?>(null)
    override val clock = MutableStateFlow<ClockAnchor?>(null)
    override val prefs = MutableStateFlow(prefs)
    override val day = MutableStateFlow<DayState>(DayState.Loading)
    override val followed = MutableStateFlow<FollowedGame?>(null)
    val eventFlow = MutableSharedFlow<ScoreEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<ScoreEvent> get() = eventFlow

    /** `reportDemand` calls in order, and the owners currently on. */
    val demandCalls = mutableListOf<Pair<DemandOwner, Boolean>>()
    val demandOn: MutableSet<DemandOwner> = linkedSetOf()
    val followCalls = mutableListOf<GameSnapshot?>()
    val showDayCalls = mutableListOf<String?>()
    var retries = 0
        private set

    /** The demand the real repository would derive from the reports alone (no follow, awake and lifecycle rules). */
    val seen: Boolean get() = demandOn.isNotEmpty()

    override fun reportDemand(owner: DemandOwner, on: Boolean) {
        demandCalls += owner to on
        if (on) demandOn += owner else demandOn -= owner
    }

    override fun follow(game: GameSnapshot?) {
        followCalls += game
        val f = game?.let { SportsFixtures.followedOf(it) }
        val first = followed.value == null && followCalls.count { it != null } == 1
        followed.value = f
        if (f == null) { bug.value = null; clock.value = null }
        if (raiseFollowEvents && game != null && f != null) {
            eventFlow.tryEmit(if (game.phase == GamePhase.FINAL) ScoreEvent.FollowedFinal(f) else ScoreEvent.Followed(f, first))
        }
    }

    override fun showDay(dayKey: String?) {
        showDayCalls += dayKey
    }

    override fun retry() {
        retries++
    }

    /** Emits [e] as the repository's actor would. */
    fun emit(e: ScoreEvent) {
        eventFlow.tryEmit(e)
    }
}

/** Builders and recorded bodies for sports tests (fixtures under `resources/sports/nhl/`). */
object SportsFixtures {
    /** Recorded 2026-11-05 ~19:52 ET: 9 LIVE, 1 PRE, 1 FUT (`currentDate` 2026-11-05). */
    const val SCORE_2026_11_05 = "sports/nhl/score-2026-11-05.json"
    /** Recorded the same evening for the previous day: 4 OFF games, one ended in OT. */
    const val SCORE_2026_11_04 = "sports/nhl/score-2026-11-04.json"
    /** `/v1/gamecenter/2026020251/landing`, UTA @ MTL in the 1st intermission (15:58, not running). */
    const val LANDING_LIVE_INT = "sports/nhl/landing-live-int.json"
    /** The raw response headers of the captures above (HTTP/2 status line, then `name: value`). */
    const val SCORE_2026_11_05_HEADERS = "sports/nhl/score-2026-11-05.headers.txt"
    const val LANDING_LIVE_INT_HEADERS = "sports/nhl/landing-live-int.headers.txt"
    const val SCORE_NOW_307_HEADERS = "sports/nhl/score-now-307.headers.txt"

    /** A fixture's text from the test classpath. */
    fun text(path: String): String =
        (SportsFixtures::class.java.classLoader.getResourceAsStream(path) ?: error("missing fixture $path")).use { it.readBytes().decodeToString() }

    fun followedOf(s: GameSnapshot): FollowedGame =
        FollowedGame(s.id.league, s.id.id, s.startUtcMs, s.away.abbrev, s.home.abbrev)

    fun team(abbrev: String, name: String, score: Int? = null): TeamLine =
        TeamLine(abbrev, name, score, "https://assets.nhle.com/logos/nhl/svg/${abbrev}_dark.svg")

    /** A game; defaults to CGY @ EDM, LIVE, 2nd period, 10:22 running, 0–2. */
    fun game(
        id: String = "2026020999",
        phase: GamePhase = GamePhase.LIVE,
        away: TeamLine = team("CGY", "Flames", 0),
        home: TeamLine = team("EDM", "Oilers", 2),
        period: Int? = 2,
        periodKind: PeriodKind? = PeriodKind.REG,
        otNumber: Int? = null,
        clock: GameClock? = GameClock(622, running = true, inIntermission = false),
        finalKind: PeriodKind? = null,
        dayKey: String = "2026-11-05",
        startUtcMs: Long = 1_793_923_200_000L, // 2026-11-06T00:00:00Z
    ): GameSnapshot = GameSnapshot(GameId(League.NHL, id), dayKey, startUtcMs, phase, away, home, period, periodKind, otNumber, clock, finalKind)

    fun board(vararg games: GameSnapshot, dayKey: String = "2026-11-05"): DayBoard =
        DayBoard(dayKey, prevDay = "2026-11-04", nextDay = "2026-11-06", games = games.toList())

    /** The bug's view of [game] in play: clock over the period. */
    fun liveView(game: GameSnapshot = game()): ScoreBugView = ScoreBugView(
        away = game.away,
        home = game.home,
        top = CentreText.Clock,
        bottom = CentreText.Period(PeriodLabel(game.periodKind ?: PeriodKind.REG, game.period ?: 1)),
        dim = false,
        goalSeq = 0,
        goalSide = null,
    )
}
