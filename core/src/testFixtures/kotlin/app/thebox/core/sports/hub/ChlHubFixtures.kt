package app.thebox.core.sports.hub

import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * (CHL hub commit 0, docs/design/m6-chl-hub.md §6, §8) The CHL hub fixtures (`resources/sports/chl/hub/`, see the README
 * there: keys removed, lineups trimmed to 5 skaters per side) and **fake details** built from them.
 *
 * - P1 (`ChlDetailMapperTest`) reads the bodies with [obj] / [table] and should produce exactly the fakes below (they
 *   restate §2–§3's worked examples under the KDoc rules of [ChlDetailMapper]; the headshot builder is [HEADSHOT]).
 * - P2 serves the bodies from MockWebServer ([text] keeps the JSONP wrapper) and stands in for the mapper with the fakes.
 * - P3 draws the fakes (panel rows, summary, sheets, goldens) without the real mapper.
 *
 * The fakes were generated once from the fixtures by a throwaway reading of the design (not a second mapper). Where a
 * fake and P1's mapper disagree, the design's worked examples decide and the lead fixes the fake (this file is the lead's).
 */
object ChlHubFixtures {
    private const val DIR = "sports/chl/hub/"

    const val PREVIEW_OHL_29246 = DIR + "gc-preview-pre-ohl-29246.json"
    const val PREVIEW_WHL_1023348 = DIR + "gc-preview-pre-whl-1023348.json"
    const val PREVIEW_QMJHL_32953 = DIR + "gc-preview-pre-lhjmq-32953.json"
    /** Read after the final: still as of the game's start (BAR 5-17). */
    const val PREVIEW_AFTER_FINAL_OHL_29239 = DIR + "gc-preview-after-final-ohl-29239.json"
    const val SUMMARY_OHL_29239 = DIR + "gc-gamesummary-final-ohl-29239.json"
    const val SUMMARY_WHL_1023337 = DIR + "gc-gamesummary-final-whl-1023337.json"
    const val SUMMARY_OT_QMJHL_32952 = DIR + "gc-gamesummary-final-ot-lhjmq-32952.json"
    /** Status 1 (before the game): `penalties` null, `mvps` `[null, null, null]`. */
    const val SUMMARY_PRE_OHL_29246 = DIR + "gc-gamesummary-pre-ohl-29246.json"
    /** London (t14): traded Medvedev (`latest_team_id` 6), inactive Nadalin. */
    const val GOALIES_OHL_T14 = DIR + "mk-topgoalies-ohl-t14.json"
    const val GOALIES_WHL_T206 = DIR + "mk-topgoalies-whl-t206.json"
    const val GOALIES_QMJHL_T2 = DIR + "mk-topgoalies-lhjmq-t2.json"
    /** JSONP `([ … ])` league tables. */
    const val TABLE_OHL = DIR + "svf-teams-ohl.json"
    const val TABLE_SPECIAL_OHL = DIR + "svf-teams-special-ohl.json"
    const val TABLE_SPECIAL_WHL = DIR + "svf-teams-special-whl.json"
    const val TABLE_QMJHL = DIR + "svf-teams-lhjmq.json"

    /** The headshot builder the fakes assume (a test host; `:data` builds the real one). */
    val HEADSHOT: (League) -> (String) -> String? = { league ->
        { id -> if (id.isNotEmpty() && id.all { it.isDigit() }) "https://headshots.test/${client(league)}/$id.jpg" else null }
    }

    private fun client(league: League): String = when (league) {
        League.OHL -> "ohl"
        League.WHL -> "whl"
        League.QMJHL -> "lhjmq"
        League.NHL, League.NFL -> error("not a CHL league: $league")
    }

    /** The raw body text (the JSONP wrapper kept), for a MockWebServer answer. */
    fun text(path: String): String = SportsFixtures.text(path)

    /** A `gc` or `modulekit` body as the transport decodes it (the whole object, `{"GC": …}` / `{"SiteKit": …}`). */
    fun obj(path: String): JsonObject = Json.parseToJsonElement(text(path)).jsonObject

    /** A `statviewfeed` table with its `( … )` stripped, as `HockeyTechHttp.element` gives it. */
    fun table(path: String): JsonArray = Json.parseToJsonElement(text(path).trim().removePrefix("(").removeSuffix(")")).jsonArray

    // ------------------------------------------------------------------ fake details (§2–§3 worked examples)

    /** OHL 29246 NIAG @ LDN, pre-game: every rank, first meeting, NIAG Haffner starting (no NIAG `topgoalies`: names only). */
    fun preOhl29246(): GameDetail = GameDetail(
        id = GameId(League.OHL, "29246"),
        fetchedPhase = GamePhase.SCHEDULED,
        serverDateMs = null,
        awayScore = null,
        homeScore = null,
        venue = null,
        gameStats = emptyList(),
        season = SeasonComparison(
            awayRecord = "10-10-3-0", homeRecord = "13-8-1-2",
            awayLast10 = Last10("3-5-2-0", null, null), homeLast10 = Last10("4-5-0-1", null, null),
            awayGf = Ranked(3.0869565217391304, 15), homeGf = Ranked(3.75, 8),
            awayGa = Ranked(4.043478260869565, 18), homeGa = Ranked(3.625, 12),
            awayPp = Ranked(0.17045454545454544, 19), homePp = Ranked(0.21839080459770116, 9),
            awayPk = Ranked(0.7088607594936709, 18), homePk = Ranked(0.6831683168316831, 20),
        ),
        periods = emptyList(),
        goals = emptyList(),
        stars = emptyList(),
        leaders = listOf(LeaderPair("points", Leader("R. Edgar", 22, "https://headshots.test/ohl/9373.jpg"), Leader("T. Zocco", 27, "https://headshots.test/ohl/9884.jpg"))),
        goalies = GoaliePair(
            away = listOf(Goalie("C. Haffner", null, null, null, "https://headshots.test/ohl/9741.jpg", starting = true), Goalie("V. Yermolenko", null, null, null, "https://headshots.test/ohl/9376.jpg")),
            home = listOf(Goalie("B. Cooke", "8-4-0-0", 3.6, 0.879, "https://headshots.test/ohl/9924.jpg"), Goalie("A. Jaswal", "1-1-0-1", 2.89, 0.893, "https://headshots.test/ohl/9189.jpg")),
        ),
        series = SeriesFacts(0, 0, 0),
        recapId = null,
        condensedId = null,
        broadcasts = emptyList(),
        recent = listOf(
            RecentGame(Side.AWAY, atOpponent = true, "ER", GameResult.W, "5-4", 1794697200000L),
            RecentGame(Side.AWAY, atOpponent = false, "ER", GameResult.L, "7-3", 1794769200000L),
            RecentGame(Side.AWAY, atOpponent = false, "SAG", GameResult.W, "4-3", 1795132800000L),
            RecentGame(Side.AWAY, atOpponent = false, "OS", GameResult.W, "7-2", 1795219200000L),
            RecentGame(Side.AWAY, atOpponent = true, "NB", GameResult.L, "9-1", 1795374000000L),
            RecentGame(Side.HOME, atOpponent = true, "SAG", GameResult.L, "6-5", 1794701100000L),
            RecentGame(Side.HOME, atOpponent = false, "SOO", GameResult.W, "4-3", 1795219200000L),
            RecentGame(Side.HOME, atOpponent = true, "GUE", GameResult.L, "4-2", 1795295220000L),
            RecentGame(Side.HOME, atOpponent = false, "PBO", GameResult.L, "5-0", 1795388400000L),
            RecentGame(Side.HOME, atOpponent = true, "ER", GameResult.W, "2-0", 1795651200000L),
        ),
        leadersSeason = true,
    )

    /** WHL 1023348 CGY @ MH, pre-game: no GF/GA ranks (WHL standard table not captured), CGY leads 2–1, lineup null (MH rule 2; CGY none). */
    fun preWhl1023348(): GameDetail = GameDetail(
        id = GameId(League.WHL, "1023348"),
        fetchedPhase = GamePhase.SCHEDULED,
        serverDateMs = null,
        awayScore = null,
        homeScore = null,
        venue = null,
        gameStats = emptyList(),
        season = SeasonComparison(
            awayRecord = "13-9-1-1", homeRecord = "14-6-1-2",
            awayLast10 = Last10("5-3-1-1", null, null), homeLast10 = Last10("7-1-1-1", null, null),
            awayGf = Ranked(4.083333333333333, null), homeGf = Ranked(4.086956521739131, null),
            awayGa = Ranked(3.5833333333333335, null), homeGa = Ranked(3.130434782608696, null),
            awayPp = Ranked(0.25287356321839083, 5), homePp = Ranked(0.2463768115942029, 8),
            awayPk = Ranked(0.8222222222222222, 6), homePk = Ranked(0.8252427184466019, 4),
        ),
        periods = emptyList(),
        goals = emptyList(),
        stars = emptyList(),
        leaders = listOf(LeaderPair("points", Leader("C. Sanderson", 31, "https://headshots.test/whl/30765.jpg"), Leader("M. Ruck", 33, "https://headshots.test/whl/29384.jpg"))),
        goalies = GoaliePair(
            away = listOf(),
            home = listOf(Goalie("C. Casey", "12-5-0-1", 2.77, 0.898, "https://headshots.test/whl/30291.jpg"), Goalie("C. Christie", "2-1-1-1", 3.65, 0.872, "https://headshots.test/whl/29629.jpg")),
        ),
        series = SeriesFacts(2, 1, 3),
        recapId = null,
        condensedId = null,
        broadcasts = emptyList(),
        recent = listOf(
            RecentGame(Side.AWAY, atOpponent = true, "MH", GameResult.W, "3-2", 1794618000000L),
            RecentGame(Side.AWAY, atOpponent = true, "LET", GameResult.W, "4-3", 1794700800000L),
            RecentGame(Side.AWAY, atOpponent = false, "EDM", GameResult.L, "6-5", 1795222800000L),
            RecentGame(Side.AWAY, atOpponent = false, "SC", GameResult.W, "8-3", 1795377600000L),
            RecentGame(Side.AWAY, atOpponent = false, "RD", GameResult.L, "4-3", 1795654800000L),
            RecentGame(Side.HOME, atOpponent = false, "RD", GameResult.W, "5-1", 1794704400000L),
            RecentGame(Side.HOME, atOpponent = false, "EDM", GameResult.L, "4-3", 1794963600000L),
            RecentGame(Side.HOME, atOpponent = true, "PA", GameResult.W, "5-1", 1795309200000L),
            RecentGame(Side.HOME, atOpponent = true, "SAS", GameResult.L, "6-5", 1795384800000L),
            RecentGame(Side.HOME, atOpponent = false, "BDN", GameResult.W, "5-2", 1795568400000L),
        ),
        leadersSeason = true,
    )

    /** QMJHL 32953 Chi @ NFL, pre-game: both starters marked, no PP/PK ranks (special table not captured), Chi leads 1–0. */
    fun preQmjhl32953(): GameDetail = GameDetail(
        id = GameId(League.QMJHL, "32953"),
        fetchedPhase = GamePhase.SCHEDULED,
        serverDateMs = null,
        awayScore = null,
        homeScore = null,
        venue = null,
        gameStats = emptyList(),
        season = SeasonComparison(
            awayRecord = "12-9-0-1", homeRecord = "14-8-2-2",
            awayLast10 = Last10("6-3-0-1", null, null), homeLast10 = Last10("5-3-1-1", null, null),
            awayGf = Ranked(3.6363636363636362, 7), homeGf = Ranked(3.3846153846153846, 11),
            awayGa = Ranked(3.3636363636363638, 9), homeGa = Ranked(3.1538461538461537, 7),
            awayPp = Ranked(0.2125, null), homePp = Ranked(0.14423076923076922, null),
            awayPk = Ranked(0.8488372093023255, null), homePk = Ranked(0.7745098039215687, null),
        ),
        periods = emptyList(),
        goals = emptyList(),
        stars = emptyList(),
        leaders = listOf(LeaderPair("points", Leader("N. Lecompte", 29, "https://headshots.test/lhjmq/20167.jpg"), Leader("T. Goguen", 27, "https://headshots.test/lhjmq/20177.jpg"))),
        goalies = GoaliePair(
            away = listOf(Goalie("J. Lee", null, null, null, "https://headshots.test/lhjmq/23420.jpg", starting = true), Goalie("P. Plumins", null, null, null, "https://headshots.test/lhjmq/24591.jpg")),
            home = listOf(Goalie("L. Denault", "6-6-1-1", 2.84, 0.907, "https://headshots.test/lhjmq/20480.jpg", starting = true), Goalie("A. Proulx", "8-2-1-1", 3.11, 0.895, "https://headshots.test/lhjmq/21301.jpg")),
        ),
        series = SeriesFacts(1, 0, 1),
        recapId = null,
        condensedId = null,
        broadcasts = emptyList(),
        recent = listOf(
            RecentGame(Side.AWAY, atOpponent = true, "GAT", GameResult.W, "3-2", 1794690000000L),
            RecentGame(Side.AWAY, atOpponent = true, "DRU", GameResult.W, "3-2", 1794776400000L),
            RecentGame(Side.AWAY, atOpponent = false, "CAP", GameResult.L, "5-4", 1795294800000L),
            RecentGame(Side.AWAY, atOpponent = false, "ROU", GameResult.L, "5-3", 1795381200000L),
            RecentGame(Side.AWAY, atOpponent = true, "NFL", GameResult.W, "5-4", 1795732200000L),
            RecentGame(Side.HOME, atOpponent = true, "HAL", GameResult.L, "3-2", 1794769200000L),
            RecentGame(Side.HOME, atOpponent = true, "CHA", GameResult.W, "6-4", 1795129200000L),
            RecentGame(Side.HOME, atOpponent = true, "CHA", GameResult.L, "3-2", 1795215600000L),
            RecentGame(Side.HOME, atOpponent = true, "MON", GameResult.W, "7-3", 1795377600000L),
            RecentGame(Side.HOME, atOpponent = false, "CHI", GameResult.L, "5-4", 1795732200000L),
        ),
        leadersSeason = true,
    )

    /** OHL 29239 OTT 5 @ BAR 3, final: Dietsch the game winner, Nikonovich on one box line. */
    fun finalOhl29239(): GameDetail = GameDetail(
        id = GameId(League.OHL, "29239"),
        fetchedPhase = GamePhase.FINAL,
        serverDateMs = null,
        awayScore = null,
        homeScore = null,
        venue = "Sadlon Arena",
        gameStats = listOf(
            StatRow(StatKind.SOG, StatValue.Count(38), StatValue.Count(21)),
            StatRow(StatKind.POWER_PLAY, StatValue.Fraction(2, 4), StatValue.Fraction(0, 2)),
            StatRow(StatKind.FACEOFF_PCT, StatValue.Pct(0.532258064516129), StatValue.Pct(0.46774193548387094)),
            StatRow(StatKind.PIM, StatValue.Count(6), StatValue.Count(10)),
        ),
        season = null,
        periods = listOf(PeriodScore(PeriodLabel(PeriodKind.REG, 1), 1, 2), PeriodScore(PeriodLabel(PeriodKind.REG, 2), 1, 1), PeriodScore(PeriodLabel(PeriodKind.REG, 3), 3, 0)),
        goals = listOf(
            GoalItem(PeriodLabel(PeriodKind.REG, 1), "0:47", Side.HOME, "E. Edgar", "Edgar", 8, listOf("Schneid", "Lowe"), Strength.EV, emptyNet = false, away = 0, home = 1, clipId = null, headshot = "https://headshots.test/ohl/9534.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.REG, 1), "15:36", Side.AWAY, "M. Manza", "Manza", 6, listOf("Bowes", "Ruml"), Strength.PP, emptyNet = false, away = 1, home = 1, clipId = null, headshot = "https://headshots.test/ohl/9330.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.REG, 1), "19:41", Side.HOME, "M. Tang", "Tang", 5, listOf("Lombardi", "Matheson"), Strength.EV, emptyNet = false, away = 1, home = 2, clipId = null, headshot = "https://headshots.test/ohl/10029.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.REG, 2), "8:03", Side.AWAY, "S. Bowes", "Bowes", 18, listOf("Manza", "Handsor"), Strength.EV, emptyNet = false, away = 2, home = 2, clipId = null, headshot = "https://headshots.test/ohl/9321.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.REG, 2), "14:36", Side.HOME, "S. Mogull", "Mogull", 8, listOf("Matheson"), Strength.EV, emptyNet = false, away = 2, home = 3, clipId = null, headshot = "https://headshots.test/ohl/9844.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.REG, 3), "8:42", Side.AWAY, "N. Whitehead", "Whitehead", 7, listOf("Manza", "Dietsch"), Strength.PP, emptyNet = false, away = 3, home = 3, clipId = null, headshot = "https://headshots.test/ohl/8976.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.REG, 3), "10:53", Side.AWAY, "K. Dietsch", "Dietsch", 2, listOf("Houben"), Strength.EV, emptyNet = false, away = 4, home = 3, clipId = null, headshot = "https://headshots.test/ohl/8975.jpg", gameWinning = true),
            GoalItem(PeriodLabel(PeriodKind.REG, 3), "17:40", Side.AWAY, "M. Manza", "Manza", 7, listOf("Bowes", "Yanni"), Strength.EV, emptyNet = false, away = 5, home = 3, clipId = null, headshot = "https://headshots.test/ohl/9330.jpg", gameWinning = false),
        ),
        stars = listOf(
            Star(1, "M. Manza", "Manza", "OTT", "https://headshots.test/ohl/9330.jpg"),
            Star(2, "S. Bowes", "Bowes", "OTT", "https://headshots.test/ohl/9321.jpg"),
            Star(3, "K. Dietsch", "Dietsch", "OTT", "https://headshots.test/ohl/8975.jpg"),
        ),
        leaders = emptyList(),
        goalies = null,
        series = null,
        recapId = null,
        condensedId = null,
        broadcasts = emptyList(),
        attendance = 2801,
    )

    /** The box score of [finalOhl29239] (the same parse; 5 skaters per side as trimmed in the fixture; goalies with `seconds > 0`). */
    fun boxOhl29239(): BoxScore =
        BoxScore(
            away = TeamBox(
                skaters = listOf(
                    SkaterLine("J. Handsor", "LD", 0, 1, 1, -1, 0, null),
                    SkaterLine("K. Dietsch", "RD", 1, 1, 2, 1, 3, null),
                    SkaterLine("J. Newton", "C", 0, 0, 0, -1, 0, null),
                    SkaterLine("A. Laurin", "RW", 0, 0, 0, -1, 0, null),
                    SkaterLine("N. Whitehead", "LW", 1, 0, 1, -1, 4, null),
                ),
                goalies = listOf(
                    GoalieLine("J. Nelson", 21, 18, 0.8571428571428571, "60:00"),
                ),
            ),
            home = TeamBox(
                skaters = listOf(
                    SkaterLine("N. Desiderio", "C", 0, 0, 0, -1, 0, null),
                    SkaterLine("S. Mogull", "LW", 1, 0, 1, 0, 2, null),
                    SkaterLine("L. Howard", "LD", 0, 0, 0, 1, 1, null),
                    SkaterLine("W. Schneid", "C", 0, 1, 1, 0, 1, null),
                    SkaterLine("P. Babin", "RD", 0, 0, 0, -1, 1, null),
                ),
                goalies = listOf(
                    GoalieLine("M. Nikonovich", 38, 33, 0.868421052631579, "58:21"),
                ),
            ),
            periods = listOf(PeriodScore(PeriodLabel(PeriodKind.REG, 1), 1, 2), PeriodScore(PeriodLabel(PeriodKind.REG, 2), 1, 1), PeriodScore(PeriodLabel(PeriodKind.REG, 3), 3, 0)),
            periodShots = listOf(PeriodScore(PeriodLabel(PeriodKind.REG, 1), 15, 11), PeriodScore(PeriodLabel(PeriodKind.REG, 2), 17, 5), PeriodScore(PeriodLabel(PeriodKind.REG, 3), 6, 5)),
        )

    /** WHL 1023337 RD 4 @ CGY 3, final: venue with the city. */
    fun finalWhl1023337(): GameDetail = GameDetail(
        id = GameId(League.WHL, "1023337"),
        fetchedPhase = GamePhase.FINAL,
        serverDateMs = null,
        awayScore = null,
        homeScore = null,
        venue = "Scotiabank Saddledome - Calgary, AB",
        gameStats = listOf(
            StatRow(StatKind.SOG, StatValue.Count(36), StatValue.Count(28)),
            StatRow(StatKind.POWER_PLAY, StatValue.Fraction(1, 2), StatValue.Fraction(0, 3)),
            StatRow(StatKind.FACEOFF_PCT, StatValue.Pct(0.4426229508196721), StatValue.Pct(0.5573770491803278)),
            StatRow(StatKind.PIM, StatValue.Count(10), StatValue.Count(8)),
        ),
        season = null,
        periods = listOf(PeriodScore(PeriodLabel(PeriodKind.REG, 1), 1, 2), PeriodScore(PeriodLabel(PeriodKind.REG, 2), 2, 0), PeriodScore(PeriodLabel(PeriodKind.REG, 3), 1, 1)),
        goals = listOf(
            GoalItem(PeriodLabel(PeriodKind.REG, 1), "0:51", Side.AWAY, "P. Sopiarz", "Sopiarz", 7, listOf("Banicevic", "Kuzma"), Strength.EV, emptyNet = false, away = 1, home = 0, clipId = null, headshot = "https://headshots.test/whl/29478.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.REG, 1), "1:46", Side.HOME, "K. Jundt", "Jundt", 4, listOf("Simard"), Strength.EV, emptyNet = false, away = 1, home = 1, clipId = null, headshot = "https://headshots.test/whl/30190.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.REG, 1), "18:08", Side.HOME, "A. Molgachev", "Molgachev", 8, listOf("Amrhein", "Anaka"), Strength.EV, emptyNet = false, away = 1, home = 2, clipId = null, headshot = "https://headshots.test/whl/30181.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.REG, 2), "1:05", Side.AWAY, "B. Hamilton", "Hamilton", 16, listOf("Fawcett", "Lindgren"), Strength.EV, emptyNet = false, away = 2, home = 2, clipId = null, headshot = "https://headshots.test/whl/29516.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.REG, 2), "19:03", Side.AWAY, "P. Andersen", "Andersen", 23, listOf("Kuzma", "Hamilton"), Strength.EV, emptyNet = false, away = 3, home = 2, clipId = null, headshot = "https://headshots.test/whl/30198.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.REG, 3), "4:25", Side.HOME, "G. Anaka", "Anaka", 8, listOf("Aura", "Molgachev"), Strength.EV, emptyNet = false, away = 3, home = 3, clipId = null, headshot = "https://headshots.test/whl/30754.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.REG, 3), "19:25", Side.AWAY, "B. Hamilton", "Hamilton", 17, listOf("Andersen", "Kuzma"), Strength.PP, emptyNet = false, away = 4, home = 3, clipId = null, headshot = "https://headshots.test/whl/29516.jpg", gameWinning = true),
        ),
        stars = listOf(
            Star(1, "B. Hamilton", "Hamilton", "RD", "https://headshots.test/whl/29516.jpg"),
            Star(2, "C. Kuzma", "Kuzma", "RD", "https://headshots.test/whl/29457.jpg"),
            Star(3, "G. Anaka", "Anaka", "CGY", "https://headshots.test/whl/30754.jpg"),
        ),
        leaders = emptyList(),
        goalies = null,
        series = null,
        recapId = null,
        condensedId = null,
        broadcasts = emptyList(),
        attendance = 2226,
    )

    /** The box score of [finalWhl1023337] (the same parse; 5 skaters per side as trimmed in the fixture; goalies with `seconds > 0`). */
    fun boxWhl1023337(): BoxScore =
        BoxScore(
            away = TeamBox(
                skaters = listOf(
                    SkaterLine("J. Kamas", "RD", 0, 0, 0, 0, 1, null),
                    SkaterLine("J. Missura", "RD", 0, 0, 0, 0, 2, null),
                    SkaterLine("N. Chadi", "LD", 0, 0, 0, 0, 3, null),
                    SkaterLine("Z. Lindgren", "LD", 0, 1, 1, 0, 1, null),
                    SkaterLine("C. Temple", "LW", 0, 0, 0, -1, 0, null),
                ),
                goalies = listOf(
                    GoalieLine("M. Kondro", 28, 25, 0.8928571428571429, "60:00"),
                ),
            ),
            home = TeamBox(
                skaters = listOf(
                    SkaterLine("M. Simard", "LD", 0, 1, 1, 0, 1, null),
                    SkaterLine("C. Hamilton", "LD", 0, 0, 0, 0, 1, null),
                    SkaterLine("B. Laylin", "RD", 0, 0, 0, 0, 3, null),
                    SkaterLine("J. Leander", "RD", 0, 0, 0, 0, 0, null),
                    SkaterLine("H. Aura", "LD", 0, 1, 1, 0, 2, null),
                ),
                goalies = listOf(
                    GoalieLine("E. Tu", 36, 32, 0.8888888888888888, "59:25"),
                ),
            ),
            periods = listOf(PeriodScore(PeriodLabel(PeriodKind.REG, 1), 1, 2), PeriodScore(PeriodLabel(PeriodKind.REG, 2), 2, 0), PeriodScore(PeriodLabel(PeriodKind.REG, 3), 1, 1)),
            periodShots = listOf(PeriodScore(PeriodLabel(PeriodKind.REG, 1), 13, 9), PeriodScore(PeriodLabel(PeriodKind.REG, 2), 11, 9), PeriodScore(PeriodLabel(PeriodKind.REG, 3), 12, 10)),
        )

    /** QMJHL 32952 Chi 5 @ NFL 4, final in OT: hits, an OT column, unassisted goals. */
    fun finalOtQmjhl32952(): GameDetail = GameDetail(
        id = GameId(League.QMJHL, "32952"),
        fetchedPhase = GamePhase.FINAL,
        serverDateMs = null,
        awayScore = null,
        homeScore = null,
        venue = "Mary Brown's Centre",
        gameStats = listOf(
            StatRow(StatKind.SOG, StatValue.Count(28), StatValue.Count(27)),
            StatRow(StatKind.POWER_PLAY, StatValue.Fraction(1, 4), StatValue.Fraction(0, 3)),
            StatRow(StatKind.FACEOFF_PCT, StatValue.Pct(0.4603174603174603), StatValue.Pct(0.5396825396825397)),
            StatRow(StatKind.PIM, StatValue.Count(14), StatValue.Count(16)),
            StatRow(StatKind.HITS, StatValue.Count(2), StatValue.Count(10)),
        ),
        season = null,
        periods = listOf(PeriodScore(PeriodLabel(PeriodKind.REG, 1), 0, 1), PeriodScore(PeriodLabel(PeriodKind.REG, 2), 3, 1), PeriodScore(PeriodLabel(PeriodKind.REG, 3), 1, 2), PeriodScore(PeriodLabel(PeriodKind.OT, 1), 1, 0)),
        goals = listOf(
            GoalItem(PeriodLabel(PeriodKind.REG, 1), "6:36", Side.HOME, "L. Arsenault", "Arsenault", 13, listOf(), Strength.SH, emptyNet = false, away = 0, home = 1, clipId = null, headshot = "https://headshots.test/lhjmq/21296.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.REG, 2), "11:44", Side.AWAY, "N. Lecompte", "Lecompte", 11, listOf("Dubé", "Ricard"), Strength.PP, emptyNet = false, away = 1, home = 1, clipId = null, headshot = "https://headshots.test/lhjmq/20167.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.REG, 2), "17:10", Side.AWAY, "N. Lecompte", "Lecompte", 12, listOf("Walsh"), Strength.EV, emptyNet = false, away = 2, home = 1, clipId = null, headshot = "https://headshots.test/lhjmq/20167.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.REG, 2), "18:22", Side.HOME, "L. Bélanger", "Bélanger", 9, listOf("Michaud"), Strength.EV, emptyNet = false, away = 2, home = 2, clipId = null, headshot = "https://headshots.test/lhjmq/20184.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.REG, 2), "18:51", Side.AWAY, "L. Poirier", "Poirier", 6, listOf("Lecompte", "Dufour"), Strength.EV, emptyNet = false, away = 3, home = 2, clipId = null, headshot = "https://headshots.test/lhjmq/21465.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.REG, 3), "2:58", Side.HOME, "B. Veitch", "Veitch", 9, listOf("Goguen", "Michaud"), Strength.EV, emptyNet = false, away = 3, home = 3, clipId = null, headshot = "https://headshots.test/lhjmq/23333.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.REG, 3), "5:36", Side.AWAY, "M. Dufour", "Dufour", 12, listOf(), Strength.EV, emptyNet = false, away = 4, home = 3, clipId = null, headshot = "https://headshots.test/lhjmq/24496.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.REG, 3), "11:06", Side.HOME, "T. Goguen", "Goguen", 12, listOf("Norman", "Austin"), Strength.EV, emptyNet = false, away = 4, home = 4, clipId = null, headshot = "https://headshots.test/lhjmq/20177.jpg", gameWinning = false),
            GoalItem(PeriodLabel(PeriodKind.OT, 1), "4:16", Side.AWAY, "W. Dubé", "Dubé", 4, listOf("Dufour", "Hartl"), Strength.EV, emptyNet = false, away = 5, home = 4, clipId = null, headshot = "https://headshots.test/lhjmq/23364.jpg", gameWinning = true),
        ),
        stars = listOf(
            Star(1, "W. Dubé", "Dubé", "CHI", "https://headshots.test/lhjmq/23364.jpg"),
            Star(2, "N. Lecompte", "Lecompte", "CHI", "https://headshots.test/lhjmq/20167.jpg"),
            Star(3, "Q. Norman", "Norman", "NFL", "https://headshots.test/lhjmq/23334.jpg"),
        ),
        leaders = emptyList(),
        goalies = null,
        series = null,
        recapId = null,
        condensedId = null,
        broadcasts = emptyList(),
        attendance = 4852,
    )

    /** The box score of [finalOtQmjhl32952] (the same parse; 5 skaters per side as trimmed in the fixture; goalies with `seconds > 0`). */
    fun boxOtQmjhl32952(): BoxScore =
        BoxScore(
            away = TeamBox(
                skaters = listOf(
                    SkaterLine("B. Kaldenbach", "D", 0, 0, 0, 0, 2, null),
                    SkaterLine("C. Berthelot", "RW", 0, 0, 0, -1, 0, null),
                    SkaterLine("É. Ricard", "LW", 0, 1, 1, -3, 2, null),
                    SkaterLine("J. Phillips", "C", 0, 0, 0, 0, 0, null),
                    SkaterLine("M. Dufour", "RW", 1, 2, 3, 3, 4, null),
                ),
                goalies = listOf(
                    GoalieLine("P. Plumins", 27, 23, 0.8518518518518519, "64:16"),
                ),
            ),
            home = TeamBox(
                skaters = listOf(
                    SkaterLine("K. Austin", "LD", 0, 1, 1, 0, 1, null),
                    SkaterLine("L. Arsenault", "LW", 1, 0, 1, 0, 2, null),
                    SkaterLine("M. Frost", "RW", 0, 0, 0, 0, 0, null),
                    SkaterLine("T. Hymovitz", "RW", 0, 0, 0, 1, 1, null),
                    SkaterLine("L. Sinclair", "RW", 0, 0, 0, -1, 1, null),
                ),
                goalies = listOf(
                    GoalieLine("A. Proulx", 28, 23, 0.8214285714285714, "64:16"),
                ),
            ),
            periods = listOf(PeriodScore(PeriodLabel(PeriodKind.REG, 1), 0, 1), PeriodScore(PeriodLabel(PeriodKind.REG, 2), 3, 1), PeriodScore(PeriodLabel(PeriodKind.REG, 3), 1, 2), PeriodScore(PeriodLabel(PeriodKind.OT, 1), 1, 0)),
            periodShots = listOf(PeriodScore(PeriodLabel(PeriodKind.REG, 1), 5, 17), PeriodScore(PeriodLabel(PeriodKind.REG, 2), 11, 4), PeriodScore(PeriodLabel(PeriodKind.REG, 3), 9, 5), PeriodScore(PeriodLabel(PeriodKind.OT, 1), 3, 1)),
        )
}
