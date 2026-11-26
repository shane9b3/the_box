package app.thebox.core.sports.hub

import app.thebox.core.sports.EspnMapper
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.LeagueDetail
import app.thebox.core.sports.NflBreak
import app.thebox.core.sports.NflPlay
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.SportsFixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.Instant

/**
 * (NFL hub integration, docs/design/m6-nfl-hub.md §18) The real pipeline's cases, shared by the `:data` and `:ui`
 * end-to-end tests so the two halves are the same pipeline:
 *
 * - `:data` (`NflHubPipelineDataTest`) serves each [Case]'s body from a MockWebServer through `EspnDetailSource` (the real
 *   `EspnDetailMapper`, no stand-in) into `GameDetailRepositoryImpl`, and asserts that the repository publishes exactly
 *   [expected] (and the box through its `Input.Box`).
 * - `:ui` (`NflHubPipelineUiTest`) cannot see `:data`'s internal classes, so it publishes [expected] (proved above to be
 *   what the repository publishes) through a fake repository into `SportsViewModel` → `HubLeagues` / `NflHubUi` → the
 *   panel contents and the three sheets.
 *
 * [expected] is what the source does, written out: the mapper at `wall + skew` with the debug-override http rule, then
 * the source's own `clockSkewMs` and `serverDateMs` (the mapper leaves both at their defaults).
 */
object NflHubPipeline {
    /** One pipeline case: [body] the summary text served, [wallMs] the test's wall clock, [serverDateMs] the `Date` sent. */
    data class Case(
        val name: String,
        val id: GameId,
        val body: String,
        val wallMs: Long,
        val serverDateMs: Long,
        /** The board's own event for this moment (a `{events: [ … ]}` capture), mapped by `EspnMapper.game`; null: see [game]. */
        val boardEvent: String? = null,
    ) {
        val skewMs: Long get() = (serverDateMs - wallMs).coerceIn(-3_600_000L, 3_600_000L)
        override fun toString(): String = name
    }

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()
    private fun nfl(id: String) = GameId(League.NFL, id)

    /** GB @ LAR pre-game, a day before kick-off (2026-11-26T01:00Z), its Analysis clip never offered. */
    val PRE = Case("pre-game", nfl("401873079"), SportsFixtures.text(NflHubFixtures.PRE_GB_LAR), at("2026-11-25T01:00:00Z"), at("2026-11-25T01:00:07Z"))

    /**
     * GB @ LAR live, **real**: the 02:04Z capture (Q2 11:29, LAR 13–3) of the summary and of the board's own event
     * (`scoreboard/{id}`, the situation right after a kick-off touchback: no `possession`), reduced (integration §18).
     */
    val LIVE = Case(
        "live", nfl("401873079"), SportsFixtures.text(NflHubFixtures.LIVE_GB_LAR_1), at("2026-11-26T02:04:30Z"), at("2026-11-26T02:04:31Z"),
        boardEvent = SportsFixtures.text(NflHubFixtures.BOARD_EVENT_LIVE),
    )

    /** GB @ LAR at halftime, **real** (02:44Z capture, 13–13): summary and board event (integration §18). */
    val LIVE_HALFTIME = Case(
        "halftime", nfl("401873079"), SportsFixtures.text(NflHubFixtures.LIVE_GB_LAR_HALF), at("2026-11-26T02:44:30Z"), at("2026-11-26T02:44:31Z"),
        boardEvent = SportsFixtures.text(NflHubFixtures.BOARD_EVENT_HALF),
    )

    /** GB @ LAR at halftime with one in-game clip: P1's **provisional** hand-built body (§17.1 P1 note 13). */
    val LIVE_HALFTIME_PROVISIONAL = Case(
        "halftime (provisional)", nfl("401873079"), SportsFixtures.text(NflHubFixtures.LIVE_GB_LAR_2), at("2026-11-26T01:50:00Z"), at("2026-11-26T01:49:58Z"),
    )

    /** MNF CIN @ WSH final, 10 min after the highlight was posted: the highlight and three single plays offered. */
    val FINAL_HIGHLIGHT = Case("final with highlight", nfl("401873078"), SportsFixtures.text(NflHubFixtures.FINAL_MNF), at("2026-11-24T04:42:11Z"), at("2026-11-24T04:42:13Z"))

    /** PIT @ PHI final after its highlight expired (2026-11-26T21:29:49Z): listed, no longer offered. */
    val FINAL_EXPIRED = Case("final, highlight expired", nfl("401873076"), SportsFixtures.text(NflHubFixtures.FINAL_PIT_PHI), at("2026-11-27T09:00:00Z"), at("2026-11-27T09:00:01Z"))

    /** MIA @ BUF final: ESPN posted no video ("J. Cook III" leads the rushers). */
    val NO_CLIP = Case("final, no clip", nfl("401873070"), SportsFixtures.text(NflHubFixtures.FINAL_MIA_BUF), at("2026-11-22T22:30:00Z"), at("2026-11-22T22:30:00Z"))

    /**
     * (§20) GB @ LAR final, 50 min after ESPN posted the highlight: the **real** final summary (five OnePlay clips, no
     * highlight) and the board event with its highlight (**synthesized**, see [NflHubFixtures.BOARD_EVENT_FINAL_SYNTH]).
     * The highlight exists only on the board; the one clip list is the board's highlight then the five plays.
     */
    val FINAL_BOARD_HIGHLIGHT = Case(
        "final, highlight on the board only", nfl("401873079"), SportsFixtures.text(NflHubFixtures.FINAL_GB_LAR), at("2026-11-26T05:48:00Z"), at("2026-11-26T05:48:01Z"),
        boardEvent = SportsFixtures.text(NflHubFixtures.BOARD_EVENT_FINAL_SYNTH),
    )

    val CASES = listOf(PRE, LIVE, LIVE_HALFTIME, LIVE_HALFTIME_PROVISIONAL, FINAL_HIGHLIGHT, FINAL_EXPIRED, NO_CLIP, FINAL_BOARD_HIGHLIGHT)

    /**
     * The GB @ LAR pre-game with GB's (away) receiving leader renamed "A. St. Brown" and 14 more Out lines in GB's injury
     * report (17 qualifying in all; the sheet draws 6, "+11 more"), so the surname rule and the injury count run through
     * the real mapper.
     */
    val NAMES_AND_INJURIES: Case = PRE.copy(name = "pre-game, St. Brown and 14 injuries", body = edited(PRE.body) { root ->
        val leaders = root["leaders"]!!.jsonArray.map { team ->
            team as JsonObject
            if (team.teamId() != GB_ID) team else team.with("leaders", JsonArray(team["leaders"]!!.jsonArray.map { cat ->
                cat as JsonObject
                if ((cat["name"] as? JsonPrimitive)?.content != "receivingYards") cat else cat.with("leaders", JsonArray(cat["leaders"]!!.jsonArray.mapIndexed { i, l ->
                    if (i > 0) l else (l as JsonObject).with("athlete", (l["athlete"] as JsonObject).with("shortName", JsonPrimitive("A. St. Brown")))
                }))
            }))
        }
        val injuries = root["injuries"]!!.jsonArray.map { team ->
            team as JsonObject
            if (team.teamId() != GB_ID) team else team.with("injuries", JsonArray((1..14).map { n ->
                JsonObject(mapOf(
                    "status" to JsonPrimitive("Out"),
                    "athlete" to JsonObject(mapOf("displayName" to JsonPrimitive("Packer Number$n"), "position" to JsonObject(mapOf("abbreviation" to JsonPrimitive("WR"))))),
                    "details" to JsonObject(mapOf("type" to JsonPrimitive("Knee"))),
                ))
            } + team["injuries"]!!.jsonArray))
        }
        root.with("leaders", JsonArray(leaders)).with("injuries", JsonArray(injuries))
    })

    private const val GB_ID = "9"

    private fun JsonObject.teamId(): String? = ((this["team"] as? JsonObject)?.get("id") as? JsonPrimitive)?.content
    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(LinkedHashMap(this).apply { put(key, value) })

    private fun edited(body: String, edit: (JsonObject) -> JsonObject): String =
        edit(NflHubFixtures.JSON.parseToJsonElement(body).jsonObject).toString()

    /**
     * What the repository publishes for [c] shown with [game] (see the class KDoc): `EspnDetailSource`'s answer, then
     * (§20) its clips ∪ the board's highlight (`NflHighlight.clips`), as `GameDetailRepositoryImpl` publishes them.
     */
    fun expected(c: Case, httpOk: Boolean = true): GameDetail? {
        val dto = NflHubFixtures.JSON.decodeFromString(EspnSummaryDto.serializer(), c.body)
        val d = EspnDetailMapper.detail(dto, c.id, c.wallMs + c.skewMs, httpOk)?.copy(clockSkewMs = c.skewMs, serverDateMs = c.serverDateMs) ?: return null
        return d.copy(clips = NflHighlight.clips(d.clips, game(c).media?.highlight?.clip))
    }

    /** What `EspnDetailSource` alone answers for [c] (the summary's own clips, before the repository's merge). */
    fun sourceDetail(c: Case, httpOk: Boolean = true): GameDetail? {
        val dto = NflHubFixtures.JSON.decodeFromString(EspnSummaryDto.serializer(), c.body)
        return EspnDetailMapper.detail(dto, c.id, c.wallMs + c.skewMs, httpOk)?.copy(clockSkewMs = c.skewMs, serverDateMs = c.serverDateMs)
    }

    private val NAMES = mapOf(
        "GB" to ("Green Bay Packers" to "Packers"), "LAR" to ("Los Angeles Rams" to "Rams"),
        "CIN" to ("Cincinnati Bengals" to "Bengals"), "WSH" to ("Washington Commanders" to "Commanders"),
        "PIT" to ("Pittsburgh Steelers" to "Steelers"), "PHI" to ("Philadelphia Eagles" to "Eagles"),
        "MIA" to ("Miami Dolphins" to "Dolphins"), "BUF" to ("Buffalo Bills" to "Bills"),
    )

    /**
     * The board snapshot the panel shows for [c]: the real board (`scoreboard-highlights-wk11.json` through
     * `EspnMapper.board`, its highlight evaluated at [c]'s server date) for the two week-11 games it lists; otherwise a
     * snapshot built from the summary's header (teams, scores, phase, kick-off), live with a board situation.
     */
    fun game(c: Case): GameSnapshot {
        c.boardEvent?.let { ev ->
            val dto = NflHubFixtures.JSON.decodeFromString(app.thebox.core.sports.EspnGameDto.serializer(), ev)
            return EspnMapper.game(dto, c.id, c.serverDateMs, EspnVideoMapper.PRODUCTION_LOGO_BASE) ?: error("${c.name}: the board event does not map")
        }
        EspnMapper.board(NflHubFixtures.board(), c.serverDateMs, EspnVideoMapper.PRODUCTION_LOGO_BASE)?.games
            ?.firstOrNull { it.id == c.id }?.let { return it }
        val comp = NflHubFixtures.JSON.parseToJsonElement(c.body).jsonObject["header"]!!.jsonObject["competitions"]!!.jsonArray[0].jsonObject
        fun str(o: JsonElement?, vararg path: String): String? = path.fold(o) { e, k -> (e as? JsonObject)?.get(k) }?.let { (it as? JsonPrimitive)?.content }
        val competitors = comp["competitors"]!!.jsonArray.map { it.jsonObject }
        fun team(side: String) = competitors.first { str(it, "homeAway") == side }.let { t ->
            val abbr = str(t, "team", "abbreviation")!!
            val (name, nick) = NAMES[abbr] ?: (abbr to abbr)
            SportsFixtures.nflTeam(abbr, name, nick, str(t, "score")?.toIntOrNull())
        }
        val status = str(comp, "status", "type", "name").orEmpty()
        val phase = when (str(comp, "status", "type", "state")) {
            "pre" -> GamePhase.SCHEDULED
            "in" -> if (status == "STATUS_HALFTIME") GamePhase.INTERMISSION else GamePhase.LIVE
            else -> GamePhase.FINAL
        }
        val live = phase == GamePhase.LIVE || phase == GamePhase.INTERMISSION
        val start = Instant.parse(str(comp, "date")!!.let { if (it.length == 17) it.dropLast(1) + ":00Z" else it }).toEpochMilli()
        return SportsFixtures.nflGame(
            id = c.id.id, phase = phase, away = team("away"), home = team("home"),
            period = if (phase == GamePhase.SCHEDULED) null else 2, periodKind = if (phase == GamePhase.SCHEDULED) null else PeriodKind.REG,
            clock = if (live) GameClock(0, running = false, inIntermission = phase == GamePhase.INTERMISSION) else null,
            finalKind = if (phase == GamePhase.FINAL) PeriodKind.REG else null, dayKey = "w2026-2-12", startUtcMs = start,
            detail = if (live) LeagueDetail.Nfl(NflPlay.None, null, false, if (phase == GamePhase.INTERMISSION) NflBreak.HALFTIME else null, null, 2, 3) else null,
        )
    }

    fun box(c: Case): BoxScore? = EspnDetailMapper.box(NflHubFixtures.JSON.decodeFromString(EspnSummaryDto.serializer(), c.body))
}
