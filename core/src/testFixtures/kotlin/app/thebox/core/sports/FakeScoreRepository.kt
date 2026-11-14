package app.thebox.core.sports

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * (Score bug commit 0, docs/design/m6-score-bug.md §9; CHL commit 0: any of the five leagues, see
 * [SportsFixtures.chlGame]) A [ScoreRepository] for `:ui`, `:data` and `:app` tests. Every
 * output is a public mutable flow the test drives; every input is recorded. [follow] sets [followed] (and, with
 * [raiseFollowEvents], emits `Followed` / `FollowedFinal` as the real repository does), so follow toggles round-trip.
 * It makes no requests and has no clock.
 */
class FakeScoreRepository(
    prefs: SportsPrefs = SportsPrefs(),
    private val raiseFollowEvents: Boolean = false,
) : ScoreRepository {
    override val bug = MutableStateFlow<ScoreBugView?>(null)
    override val lastScore = MutableStateFlow<ScoreBugView?>(null)
    override val prefs = MutableStateFlow(prefs)
    override val day = MutableStateFlow<DayState>(DayState.Loading)
    override val followed = MutableStateFlow<FollowedGame?>(null)
    /** (CHL integration, L10) The test sets it; [follow] clears it, as the real repository does on a follow change. */
    override val followFailure = MutableStateFlow<FailKind?>(null)
    val eventFlow = MutableSharedFlow<ScoreEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<ScoreEvent> get() = eventFlow

    /** `reportDemand` calls in order, and the owners currently on. */
    val demandCalls = mutableListOf<Pair<DemandOwner, Boolean>>()
    val demandOn: MutableSet<DemandOwner> = linkedSetOf()
    val followCalls = mutableListOf<GameSnapshot?>()
    val showDayCalls = mutableListOf<String?>()
    /** `reportScreenOff` calls in order (C-S5). */
    val screenOffCalls = mutableListOf<Boolean>()
    var retries = 0
        private set
    /**
     * (CHL emulator pass, defect 2) While true, [follow] of a game refuses it as the real repository does when the hard
     * expiry would drop it at once: the call is recorded, [followed] is unchanged, and `FollowTooOld` is emitted.
     */
    var refuseFollowsAsTooOld = false

    /** The demand the real repository would derive from the reports alone (no follow, awake and lifecycle rules). */
    val seen: Boolean get() = demandOn.isNotEmpty()

    override fun reportDemand(owner: DemandOwner, on: Boolean) {
        demandCalls += owner to on
        if (on) demandOn += owner else demandOn -= owner
    }

    override fun reportScreenOff(on: Boolean) {
        screenOffCalls += on
    }

    override fun follow(game: GameSnapshot?) {
        followCalls += game
        val f = game?.let { SportsFixtures.followedOf(it) }
        if (refuseFollowsAsTooOld && game != null && f != null && f.id != followed.value?.id) {
            eventFlow.tryEmit(ScoreEvent.FollowTooOld(f, final = game.phase == GamePhase.FINAL))
            return
        }
        val first = followed.value == null && followCalls.count { it != null } == 1
        if (f?.id != followed.value?.id) followFailure.value = null
        followed.value = f
        if (f == null) { bug.value = null; lastScore.value = null }
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
        FollowedGame(s.id.league, s.id.id, s.startUtcMs, s.away.abbrev, s.home.abbrev, startTbd = s.startTbd)

    fun team(abbrev: String, name: String, score: Int? = null, shortName: String? = null): TeamLine =
        TeamLine(abbrev, name, score, "https://assets.nhle.com/logos/nhl/svg/${abbrev}_dark.svg", shortName)

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
        powerPlay: PowerPlay? = null,
        lastGoal: GoalFact? = null,
        detail: LeagueDetail? = null,
        league: League = League.NHL,
        /** (CHL B3) The source could not decide the power play. */
        powerPlayUnknown: Boolean = false,
        /** (CHL fix pass B2) The start time is "Time TBD". */
        startTbd: Boolean = false,
    ): GameSnapshot = GameSnapshot(
        GameId(league, id), dayKey, startUtcMs, phase, away, home, period, periodKind, otNumber, clock, finalKind,
        powerPlay, lastGoal, detail, powerPlayUnknown, startTbd,
    )

    // ------------------------------------------------------------------ NFL (docs/design/m6-nfl.md commit 0)

    /** An NFL team line: the full [name] ("Kansas City Chiefs"), the [shortName] ("Chiefs"), the ESPN combiner logo. */
    fun nflTeam(abbrev: String, name: String, shortName: String, score: Int? = null): TeamLine = TeamLine(
        abbrev, name, score,
        "https://a.espncdn.com/combiner/i?img=/i/teamlogos/nfl/500-dark/scoreboard/${abbrev.lowercase()}.png&h=200&w=200",
        shortName,
    )

    /** The default NFL detail: KC ball, 1st & 10 in its own half at the 35. */
    fun nflDetail(
        play: NflPlay = NflPlay.Down(1, 10, goalToGo = false, field = FieldPos(Side.AWAY, 35)),
        possession: Side? = Side.AWAY,
        redZone: Boolean = false,
        breakKind: NflBreak? = null,
        lastPlay: NflLastPlay? = null,
    ): LeagueDetail.Nfl = LeagueDetail.Nfl(play, possession, redZone, breakKind, lastPlay)

    /** An NFL game; defaults to KC @ BUF (the fake panel's featured game), LIVE, 4th quarter, 3:42, 24–17. */
    fun nflGame(
        id: String = "401899001",
        phase: GamePhase = GamePhase.LIVE,
        away: TeamLine = nflTeam("KC", "Kansas City Chiefs", "Chiefs", 24),
        home: TeamLine = nflTeam("BUF", "Buffalo Bills", "Bills", 17),
        period: Int? = 4,
        periodKind: PeriodKind? = PeriodKind.REG,
        otNumber: Int? = null,
        clock: GameClock? = GameClock(222, running = false, inIntermission = false),
        finalKind: PeriodKind? = null,
        dayKey: String = "2026-11-08",
        startUtcMs: Long = 1_794_157_200_000L, // 2026-11-08T17:00:00Z
        detail: LeagueDetail? = nflDetail(),
    ): GameSnapshot = game(
        id, phase, away, home, period, periodKind, otNumber, clock, finalKind, dayKey, startUtcMs,
        detail = detail, league = League.NFL,
    )

    /** The NFL bug's view of [game] in play: the quarter over the clock (NFL §3.1), with [nfl] (default 1st & 10, KC ball, own 35). */
    fun nflView(
        game: GameSnapshot = nflGame(),
        nfl: NflView? = NflView(
            down = DownText.Down(1, 10, goal = false),
            tab = NflTab(Side.AWAY, TabLead.BALL, TabTail.Field(FieldZone.OWN, 35)),
        ),
        goalSeq: Int = 0,
        goalSide: Side? = null,
    ): ScoreBugView = ScoreBugView(
        away = game.away,
        home = game.home,
        top = CentreText.Period(PeriodLabel(game.periodKind ?: PeriodKind.REG, game.period ?: 1)),
        bottom = CentreText.Clock,
        dim = false,
        goalSeq = goalSeq,
        goalSide = goalSide,
        clockSecs = game.clock?.secondsRemaining?.coerceAtLeast(0),
        gameId = game.id,
        league = League.NFL,
        nfl = nfl,
    )

    /**
     * (Power play commit 0, S7) The default live game ([game]) with [side] on a [kind] power play, [secs] left on it
     * (null = the feed gave none). Any other [game] parameter can be passed through [base].
     */
    fun ppGame(
        side: Side = Side.HOME,
        kind: PowerPlayKind = PowerPlayKind.ONE_MAN,
        secs: Int? = 79,
        base: GameSnapshot = game(),
    ): GameSnapshot = base.copy(powerPlay = PowerPlay(side, kind, secs))

    fun board(vararg games: GameSnapshot, dayKey: String = "2026-11-05", league: League? = null): DayBoard =
        DayBoard(dayKey, prevDay = "2026-11-04", nextDay = "2026-11-06", games = games.toList(), league = league)

    /** The bug's view of [game] in play: the snapshot's clock (owner ruling 6) over the period. */
    fun liveView(game: GameSnapshot = game()): ScoreBugView = ScoreBugView(
        away = game.away,
        home = game.home,
        top = CentreText.Clock,
        bottom = CentreText.Period(PeriodLabel(game.periodKind ?: PeriodKind.REG, game.period ?: 1)),
        dim = false,
        goalSeq = 0,
        goalSide = null,
        clockSecs = game.clock?.secondsRemaining?.coerceAtLeast(0),
        gameId = game.id, // goal animation §5.1: the machine sets it on every view
        powerPlay = game.powerPlay, // power play §1.4: a fresh LIVE view carries the snapshot's power play
        league = game.id.league,
        // (Stacked §2.1, B1) the machine's hockey views carry the period label; the NFL's never do
        period = if (game.id.league.isHockey) PeriodLabel(game.periodKind ?: PeriodKind.REG, game.period ?: 1) else null,
    )

    // ------------------------------------------------------------------ CHL (docs/design/m6-chl-leagues.md commit 0)

    /**
     * A CHL team line: the upper-cased [abbrev], "City Nickname" as [name] (a followed snapshot's form, P10), no short name,
     * and a validated-looking HockeyTech logo URL under [client]'s logo folder (null [teamId] = no logo, the fallback).
     */
    fun chlTeam(abbrev: String, name: String, score: Int? = null, client: String = "ohl", teamId: Int? = 14, sog: Int? = null): TeamLine =
        TeamLine(abbrev, name, score, teamId?.let { "https://assets.leaguestat.com/$client/logos/$it.png" }, shortName = null, sog = sog)

    /**
     * A CHL game; defaults to OHL LDN @ KIT, LIVE, 2nd period, 10:22 running, 1–2 (HockeyTech game ids are numeric and
     * unique only within a league). Any [League] may be passed; the teams default to OHL ones.
     */
    fun chlGame(
        league: League = League.OHL,
        id: String = "29191",
        phase: GamePhase = GamePhase.LIVE,
        away: TeamLine = chlTeam("LDN", "London Knights", 1, teamId = 14),
        home: TeamLine = chlTeam("KIT", "Kitchener Rangers", 2, teamId = 10),
        period: Int? = 2,
        periodKind: PeriodKind? = PeriodKind.REG,
        otNumber: Int? = null,
        clock: GameClock? = GameClock(622, running = true, inIntermission = false),
        finalKind: PeriodKind? = null,
        dayKey: String = "2026-11-13",
        startUtcMs: Long = 1_794_612_600_000L, // 2026-11-13T23:30:00Z (18:30 Eastern)
        powerPlay: PowerPlay? = null,
        lastGoal: GoalFact? = null,
        powerPlayUnknown: Boolean = false,
        startTbd: Boolean = false,
    ): GameSnapshot = game(
        id, phase, away, home, period, periodKind, otNumber, clock, finalKind, dayKey, startUtcMs, powerPlay, lastGoal,
        detail = null, league = league, powerPlayUnknown = powerPlayUnknown, startTbd = startTbd,
    )

    /** The QMJHL's Newfoundland Regiment, whose code is "NFL" (L2: a team code, never a league), at Charlottetown. */
    fun qmjhlNflAtCha(): GameSnapshot = chlGame(
        league = League.QMJHL, id = "32755",
        away = chlTeam("NFL", "Newfoundland Regiment", 2, client = "lhjmq", teamId = 2),
        home = chlTeam("CHA", "Charlottetown Islanders", 3, client = "lhjmq", teamId = 7),
    )
}
