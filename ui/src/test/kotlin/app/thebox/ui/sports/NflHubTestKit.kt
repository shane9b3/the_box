package app.thebox.ui.sports

import app.thebox.core.sports.BoardExtras
import app.thebox.core.sports.FieldPos
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GameMedia
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.LeagueDetail
import app.thebox.core.sports.Market
import app.thebox.core.sports.NflBreak
import app.thebox.core.sports.NflPlay
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.TvBroadcast
import app.thebox.core.sports.hub.BoxCategory
import app.thebox.core.sports.hub.BoxLine
import app.thebox.core.sports.hub.BoxScore
import app.thebox.core.sports.hub.BoxTable
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.GameClip
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.GameResult
import app.thebox.core.sports.hub.InjuryLine
import app.thebox.core.sports.hub.InjuryStatus
import app.thebox.core.sports.hub.Leader
import app.thebox.core.sports.hub.LeaderPair
import app.thebox.core.sports.hub.PeriodScore
import app.thebox.core.sports.hub.PlayScore
import app.thebox.core.sports.hub.RecentGame
import app.thebox.core.sports.hub.ScoringPlay
import app.thebox.core.sports.hub.StatKind
import app.thebox.core.sports.hub.StatRow
import app.thebox.core.sports.hub.StatValue
import app.thebox.core.sports.hub.TeamBox
import java.time.Instant

/**
 * (NFL hub P3) NFL details built by hand to the frozen contracts (`HubContracts.kt` §6.1), with the real values of the
 * design's examples: MNF CIN @ WSH (`summary-final-espn-401873078.json`: 20–22, ten scoring plays, the 99-character recap,
 * the highlight and three single-play clips) and the GB @ LAR pre-game (`summary-pre-401873079.json`). P1's mapper is a
 * stub in this package, so every UI test drives these objects.
 */
internal object NflHubTestKit {
    /** MNF kick-off, 2026-11-24 01:15 UTC (8:15 PM ET on the 23rd). */
    val MNF_START: Long = Instant.parse("2026-11-24T01:15:00Z").toEpochMilli()
    /** 04:32:11Z, the highlight's publish instant. */
    val HIGHLIGHT_PUBLISHED: Long = Instant.parse("2026-11-24T04:32:11Z").toEpochMilli()
    /** Ten minutes after the highlight was posted: everything offered. */
    val AFTER_FINAL: Long = HIGHLIGHT_PUBLISHED + 10 * 60_000L

    val CIN = SportsFixtures.nflTeam("CIN", "Cincinnati Bengals", "Bengals")
    val WSH = SportsFixtures.nflTeam("WSH", "Washington Commanders", "Commanders")
    val GB = SportsFixtures.nflTeam("GB", "Green Bay Packers", "Packers")
    val LAR = SportsFixtures.nflTeam("LAR", "Los Angeles Rams", "Rams")
    val TB = SportsFixtures.nflTeam("TB", "Tampa Bay Buccaneers", "Buccaneers")

    const val RECAP_HEADLINE = "Jayden Daniels rallies Commanders in final seconds to set up winning kick in 22-20 win over Bengals"

    private fun q(n: Int) = PeriodLabel(PeriodKind.REG, n)

    /** The MNF scoring plays (team 4 = CIN away, 28 = WSH home), ESPN's text as recorded. */
    val MNF_PLAYS: List<ScoringPlay> = listOf(
        ScoringPlay("4018730781", q(2), "6:17", Side.AWAY, PlayScore.FG, "Evan McPherson 38 Yd Field Goal", 3, 0),
        ScoringPlay("4018730782", q(2), "1:55", Side.AWAY, PlayScore.TD, "Tee Higgins 31 Yd pass from Joe Burrow (Evan McPherson Kick)", 10, 0),
        ScoringPlay("4018730783", q(2), "0:12", Side.HOME, PlayScore.TD, "Jacory Croskey-Merritt 1 Yd Rush (Drew Stevens Kick)", 10, 7),
        ScoringPlay("4018730784", q(3), "11:47", Side.HOME, PlayScore.FG, "Drew Stevens 36 Yd Field Goal", 10, 10),
        ScoringPlay("4018730785", q(3), "7:04", Side.AWAY, PlayScore.TD, "Colbie Young 4 Yd pass from Joe Burrow (Evan McPherson Kick)", 17, 10),
        ScoringPlay("4018730786", q(3), "2:36", Side.HOME, PlayScore.FG, "Drew Stevens 40 Yd Field Goal", 17, 13),
        ScoringPlay("4018730787", q(4), "10:32", Side.HOME, PlayScore.FG, "Drew Stevens 25 Yd Field Goal", 17, 16),
        ScoringPlay("4018730788", q(4), "2:26", Side.HOME, PlayScore.FG, "Drew Stevens 41 Yd Field Goal", 17, 19),
        ScoringPlay("4018730789", q(4), "0:46", Side.AWAY, PlayScore.FG, "Evan McPherson 56 Yd Field Goal", 20, 19),
        ScoringPlay("4018730790", q(4), "0:00", Side.HOME, PlayScore.FG, "Drew Stevens 26 Yd Field Goal", 20, 22),
    )

    /** A long ESPN text (over 100 characters) for the sheet's fit test. */
    val LONG_PLAY = ScoringPlay(
        "4018730799", PeriodLabel(PeriodKind.OT, 1), "4:12", Side.AWAY, PlayScore.DEF_TD,
        "Jamarr Chase-Montgomery-Williams 101 Yd Interception Return after a tipped pass at the goal line by Jacory Croskey-Merritt (Evan McPherson Kick)", 26, 20,
    )

    fun highlight(expiresMs: Long = HIGHLIGHT_PUBLISHED + 96 * 3_600_000L, embargoMs: Long? = null) = GameClip(
        50579688L, ClipKind.RECAP, "Commanders storm back, stun Bengals 22-20 on MNF", 74_000L, HIGHLIGHT_PUBLISHED, embargoMs, expiresMs,
        "https://img.invalid/50579688.jpg",
    )

    val PLAY_CLIPS: List<GameClip> = listOf(
        // Out of order on purpose: More videos sorts by publish time.
        GameClip(50579083L, ClipKind.PLAY, "Stadium lights go out in third quarter of Bengals-Commanders", 47_000L, Instant.parse("2026-11-24T03:21:20Z").toEpochMilli(), null, HIGHLIGHT_PUBLISHED + 48 * 3_600_000L, "https://img.invalid/50579083.jpg"),
        GameClip(50578630L, ClipKind.PLAY, "Joe Burrow heaves a beauty to Tee Higgins for a Bengals TD", 23_000L, Instant.parse("2026-11-24T02:30:58Z").toEpochMilli(), null, HIGHLIGHT_PUBLISHED + 48 * 3_600_000L, "https://img.invalid/50578630.jpg"),
        GameClip(50578740L, ClipKind.PLAY, "Jacory Croskey-Merritt finds pay dirt for a Commanders TD", 18_000L, Instant.parse("2026-11-24T02:46:13Z").toEpochMilli(), null, HIGHLIGHT_PUBLISHED + 48 * 3_600_000L, "https://img.invalid/50578740.jpg"),
    )

    private fun row(k: StatKind, a: StatValue, h: StatValue) = StatRow(k, a, h)

    val MNF_FINAL_STATS = listOf(
        row(StatKind.TOTAL_YARDS, StatValue.Count(312), StatValue.Count(428)),
        row(StatKind.PASS_YARDS, StatValue.Count(281), StatValue.Count(278)),
        row(StatKind.RUSH_YARDS, StatValue.Count(31), StatValue.Count(150)),
        row(StatKind.FIRST_DOWNS, StatValue.Count(17), StatValue.Count(23)),
        row(StatKind.THIRD_DOWN, StatValue.Fraction(4, 12, dash = true), StatValue.Fraction(2, 10, dash = true)),
        row(StatKind.TURNOVERS, StatValue.Count(0), StatValue.Count(0)),
        row(StatKind.POSSESSION, StatValue.Clock(28 * 60 + 29), StatValue.Clock(31 * 60 + 31)),
    )

    private fun leader(name: String, value: Int, line: String) = Leader(name, value, "https://img.invalid/h/$name.png", line)

    /** The MNF game leaders (ESPN's `shortName`, home and away mapped by team id). */
    val MNF_LEADERS = listOf(
        LeaderPair("passingYards", leader("J. Burrow", 300, "28/40, 300 YDS, 2 TD"), leader("J. Daniels", 289, "23/35, 289 YDS")),
        LeaderPair("rushingYards", leader("S. Perine", 26, "6 CAR, 26 YDS"), leader("J. Daniels", 55, "6 CAR, 55 YDS")),
        LeaderPair("receivingYards", leader("M. Gesicki", 77, "5 REC, 77 YDS"), leader("A. Williams", 122, "9 REC, 122 YDS")),
        LeaderPair("sacks", leader("B. Mafe", 3, "3"), leader("T. Settle", 1, "1.5")),
        LeaderPair("totalTackles", leader("B. Carter", 10, "10"), leader("F. Luvu", 12, "12")),
    )

    /** The board's quarters (CIN 0-10-7-3, WSH 0-7-6-9). */
    val MNF_PERIODS = listOf(PeriodScore(q(1), 0, 0), PeriodScore(q(2), 10, 7), PeriodScore(q(3), 7, 6), PeriodScore(q(4), 3, 9))

    fun mnfFinal(media: GameMedia? = null, broadcasts: List<TvBroadcast> = listOf(TvBroadcast("ESPN", Market.NATIONAL, "US"))): GameSnapshot =
        SportsFixtures.nflGame(
            id = "401873078", phase = GamePhase.FINAL, away = CIN.copy(score = 20), home = WSH.copy(score = 22),
            period = 4, clock = null, finalKind = PeriodKind.REG, dayKey = "w2026-2-12", startUtcMs = MNF_START, detail = null,
        ).copy(media = media, broadcasts = broadcasts, extras = BoardExtras("7-3", "4-5-1", MNF_PERIODS))

    fun nflDetail(
        g: GameSnapshot,
        stats: List<StatRow> = emptyList(),
        season: List<StatRow> = emptyList(),
        plays: List<ScoringPlay> = emptyList(),
        clips: List<GameClip> = emptyList(),
        leaders: List<LeaderPair> = emptyList(),
        recap: String? = null,
        recent: List<RecentGame> = emptyList(),
        injuries: List<InjuryLine> = emptyList(),
        venue: String? = "Northwest Stadium",
        skew: Long = 0L,
    ) = GameDetail(
        id = g.id, fetchedPhase = g.phase, serverDateMs = null, awayScore = null, homeScore = null, venue = venue, gameStats = stats,
        season = null, periods = emptyList(), goals = emptyList(), stars = emptyList(), leaders = leaders, goalies = null, series = null,
        recapId = null, condensedId = null, broadcasts = emptyList(), plays = plays, seasonStats = season, clips = clips, recapHeadline = recap,
        recent = recent, injuries = injuries, clockSkewMs = skew,
    )

    fun mnfFinalDetail(g: GameSnapshot = mnfFinal(), clips: List<GameClip> = listOf(highlight()) + PLAY_CLIPS, plays: List<ScoringPlay> = MNF_PLAYS) =
        nflDetail(g, stats = MNF_FINAL_STATS, plays = plays, clips = clips, leaders = MNF_LEADERS.take(3), recap = RECAP_HEADLINE)

    // ------------------------------------------------------------------ live

    fun mnfLive(
        play: NflPlay = NflPlay.Down(2, 7, goalToGo = false, field = FieldPos(Side.HOME, 35)),
        possession: Side? = Side.AWAY,
        redZone: Boolean = true,
        awayTimeouts: Int? = 2,
        homeTimeouts: Int? = 3,
        phase: GamePhase = GamePhase.LIVE,
        breakKind: NflBreak? = null,
        delayed: Boolean = false,
        period: Int = 3,
        clockSecs: Int = 332,
    ): GameSnapshot = SportsFixtures.nflGame(
        id = "401873078", phase = phase, away = CIN.copy(score = 17), home = WSH.copy(score = 13), period = period,
        clock = GameClock(clockSecs, running = phase == GamePhase.LIVE, inIntermission = phase == GamePhase.INTERMISSION),
        dayKey = "w2026-2-12", startUtcMs = MNF_START,
        detail = LeagueDetail.Nfl(play, possession, redZone, breakKind, null, awayTimeouts, homeTimeouts, delayed = delayed),
    ).copy(broadcasts = listOf(TvBroadcast("ESPN", Market.NATIONAL, "US")), extras = BoardExtras("7-3", "4-5-1", MNF_PERIODS.take(3)))

    val MNF_LIVE_STATS = listOf(
        row(StatKind.TOTAL_YARDS, StatValue.Count(240), StatValue.Count(301)),
        row(StatKind.PASS_YARDS, StatValue.Count(221), StatValue.Count(190)),
        row(StatKind.RUSH_YARDS, StatValue.Count(19), StatValue.Count(111)),
        row(StatKind.THIRD_DOWN, StatValue.Fraction(3, 8, dash = true), StatValue.Fraction(2, 7, dash = true)),
        row(StatKind.TURNOVERS, StatValue.Count(0), StatValue.Count(1)),
    )

    fun mnfLiveDetail(g: GameSnapshot = mnfLive(), plays: List<ScoringPlay> = MNF_PLAYS.take(6), clips: List<GameClip> = PLAY_CLIPS.take(2)) =
        nflDetail(g, stats = MNF_LIVE_STATS, plays = plays, clips = clips)

    // ------------------------------------------------------------------ pre-game (GB @ LAR)

    val PRE_START: Long = Instant.parse("2026-11-30T21:25:00Z").toEpochMilli()

    fun pre(phase: GamePhase = GamePhase.PRE, broadcasts: List<TvBroadcast> = listOf(TvBroadcast("Netflix", Market.NATIONAL, "US"))): GameSnapshot = SportsFixtures.nflGame(
        id = "401873079", phase = phase, away = GB, home = LAR, period = null, periodKind = null, clock = null, dayKey = "w2026-2-13",
        startUtcMs = PRE_START, detail = null,
    ).copy(broadcasts = broadcasts, extras = BoardExtras("6-4", "6-4"))

    val PRE_SEASON = listOf(
        row(StatKind.RECORD, StatValue.Text("6-4"), StatValue.Text("6-4")),
        row(StatKind.LAST_FIVE, StatValue.Text("3-2"), StatValue.Text("4-1")),
        row(StatKind.POINTS_PG, StatValue.Decimal(22.6), StatValue.Decimal(24.8)),
        row(StatKind.POINTS_ALLOWED_PG, StatValue.Decimal(22.1), StatValue.Decimal(18.7)),
        row(StatKind.YARDS_PG, StatValue.Decimal(351.1), StatValue.Decimal(396.6)),
        row(StatKind.YARDS_ALLOWED_PG, StatValue.Decimal(340.4), StatValue.Decimal(295.8)),
    )

    val PRE_LEADERS = listOf(
        LeaderPair("passingYards", leader("J. Love", 2838, "232/368, 2,838 YDS, 20 TD, 7 INT"), leader("M. Stafford", 2623, "248/372, 2,623 YDS, 19 TD, 10 INT")),
        LeaderPair("rushingYards", leader("K. Johnson", 214, "52 CAR, 214 YDS, 1 TD"), leader("K. Williams", 616, "139 CAR, 616 YDS, 6 TD")),
        LeaderPair("receivingYards", leader("C. Watson", 916, "52 REC, 916 YDS, 7 TD"), leader("P. Nacua", 715, "70 REC, 715 YDS, 4 TD")),
        LeaderPair("sacks", leader("L. Van Ness", 6, "6"), leader("B. Young", 5, "5.5")),
        LeaderPair("totalTackles", leader("E. Williams", 84, "84"), leader("N. Landman", 67, "67")),
    )

    private fun recent(side: Side, at: Boolean, opp: String, r: GameResult, score: String) = RecentGame(side, at, opp, r, score, null)

    val PRE_RECENT = listOf(
        recent(Side.AWAY, false, "DAL", GameResult.W, "23-13"), recent(Side.AWAY, true, "DET", GameResult.L, "20-17"),
        recent(Side.AWAY, false, "CAR", GameResult.W, "27-19"), recent(Side.AWAY, true, "NE", GameResult.W, "45-27"),
        recent(Side.AWAY, false, "MIN", GameResult.L, "30-10"),
        recent(Side.HOME, false, "ARI", GameResult.L, "18-16"), recent(Side.HOME, true, "LV", GameResult.W, "35-21"),
        recent(Side.HOME, false, "LAC", GameResult.W, "24-9"), recent(Side.HOME, true, "WSH", GameResult.W, "20-13"),
        recent(Side.HOME, true, "ARI", GameResult.W, "34-6"),
    )

    val PRE_INJURIES = listOf(
        InjuryLine(Side.AWAY, "Kevin Zeitler", "G", InjuryStatus.QUESTIONABLE, "Triceps"),
        InjuryLine(Side.AWAY, "Sean Rhyan", "G", InjuryStatus.QUESTIONABLE, "Ankle"),
        InjuryLine(Side.AWAY, "Travis Glover", "OT", InjuryStatus.QUESTIONABLE, "Shoulder"),
        InjuryLine(Side.HOME, "Coleman Shelton", "C", InjuryStatus.OUT, "Hamstring"),
    )

    fun preDetail(g: GameSnapshot = pre(), injuries: List<InjuryLine> = PRE_INJURIES) =
        nflDetail(g, season = PRE_SEASON, leaders = PRE_LEADERS, recent = PRE_RECENT, injuries = injuries, venue = "SoFi Stadium")

    // ------------------------------------------------------------------ box score

    fun mnfBox(): BoxScore {
        fun t(cat: BoxCategory, cols: List<String>, vararg lines: Pair<String, List<String>>) = BoxTable(cat, cols, lines.map { BoxLine(it.first, it.second) })
        val pass = listOf("completions/passingAttempts", "passingYards", "passingTouchdowns", "interceptions", "QBRating")
        val rush = listOf("rushingAttempts", "rushingYards", "yardsPerRushAttempt", "rushingTouchdowns", "longRushing")
        val rec = listOf("receptions", "receivingYards", "yardsPerReception", "receivingTouchdowns", "longReception")
        val away = TeamBox(
            emptyList(), emptyList(),
            listOf(
                t(BoxCategory.PASSING, pass, "Joe Burrow" to listOf("28/40", "300", "2", "0", "108.3")),
                t(BoxCategory.RUSHING, rush, "Samaje Perine" to listOf("6", "26", "4.3", "0", "9"), "Chase Brown" to listOf("8", "5", "0.6", "0", "4")),
                t(BoxCategory.RECEIVING, rec, "Mike Gesicki" to listOf("5", "77", "15.4", "0", "29"), "Tee Higgins" to listOf("4", "70", "17.5", "1", "31"), "Ja'Marr Chase" to listOf("7", "64", "9.1", "0", "18")),
            ),
        )
        val home = TeamBox(
            emptyList(), emptyList(),
            listOf(
                t(BoxCategory.PASSING, pass, "Jayden Daniels" to listOf("23/35", "289", "0", "0", "93.6")),
                t(BoxCategory.RUSHING, rush, "Jacory Croskey-Merritt" to listOf("19", "88", "4.6", "1", "17"), "Jayden Daniels" to listOf("6", "55", "9.2", "0", "21")),
                t(BoxCategory.RECEIVING, rec, "Austin Williams" to listOf("9", "122", "13.6", "0", "34")),
            ),
        )
        return BoxScore(away, home)
    }

    /** The highlight's expiry minus the offer margin: the instant `ClipOffer` turns false. */
    fun offEnd(c: GameClip): Long = c.expiresMs - SportsConstants.HUB_CLIP_EXPIRY_MARGIN_MS
}
