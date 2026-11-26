package app.thebox.data.sports

import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.hub.BoxScore
import app.thebox.core.sports.hub.EspnSummaryDto
import app.thebox.core.sports.hub.EspnVideoMapper
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.TeamBox
import mockwebserver3.MockResponse
import okio.Buffer
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/**
 * (NFL hub P2) Shared pieces of the ESPN hub tests: a stand-in for P1's mapper (it reads only what the decode yields, so
 * these tests do not depend on `EspnDetailMapper`), fixture text, and MockWebServer answers.
 */
internal object EspnHubTestSupport {
    /** 2026-11-25T00:00Z: every MNF clip of the commit-0 fixture is offered (they expire on the 26th). */
    val NOV25: Long = Instant.parse("2026-11-25T00:00:00Z").toEpochMilli()

    fun text(path: String): String = SportsFixtures.text(path)

    /** A minimal [GameDetail] from the decoded DTO: the phase from the header, the clips through [EspnVideoMapper]. */
    class FakeMapper {
        val detailCalls = AtomicInteger()
        val boxCalls = AtomicInteger()
        @Volatile var lastAt: Long? = null
        @Volatile var lastDto: EspnSummaryDto? = null
        @Volatile var throwOnMap = false
        @Volatile var answerNull = false

        /** The last `httpOk` the source passed (code review 2026-11-26, data #2: the stand-in sees the production flag). */
        @Volatile var lastHttpOk: Boolean? = null

        val detail: (EspnSummaryDto, GameId, Long?, Boolean) -> GameDetail? = { dto, id, at, httpOk ->
            detailCalls.incrementAndGet()
            lastAt = at
            lastHttpOk = httpOk
            lastDto = dto
            if (throwOnMap) error("mapper bug")
            if (answerNull || dto.header == null) null else detailOf(dto, id, at)
        }
        val box: (EspnSummaryDto) -> BoxScore? = { dto ->
            boxCalls.incrementAndGet()
            if (dto.boxscore == null) null else BoxScore(TeamBox(emptyList(), emptyList()), TeamBox(emptyList(), emptyList()))
        }
    }

    fun detailOf(dto: EspnSummaryDto, id: GameId, at: Long?): GameDetail {
        val state = dto.header?.competitions?.firstOrNull()?.status?.type?.state
        val phase = when (state) {
            "pre" -> GamePhase.PRE
            "in" -> GamePhase.LIVE
            else -> GamePhase.FINAL
        }
        return GameDetail(
            id = id, fetchedPhase = phase, serverDateMs = null, awayScore = null, homeScore = null,
            venue = dto.gameInfo?.venue?.fullName, gameStats = emptyList(), season = null, periods = emptyList(),
            goals = emptyList(), stars = emptyList(), leaders = emptyList(), goalies = null, series = null, recapId = null,
            condensedId = null, broadcasts = emptyList(),
            clips = dto.videos.orEmpty().mapNotNull { EspnVideoMapper.entry(it, at, httpOk = true)?.clip },
        )
    }

    fun json(body: String, code: Int = 200, vararg headers: Pair<String, String>): MockResponse {
        val b = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json")
        for ((k, v) in headers) b.addHeader(k, v)
        return b.build()
    }

    fun gzipJson(body: String, vararg headers: Pair<String, String>): MockResponse {
        val out = Buffer()
        GZIPOutputStream(out.outputStream()).use { it.write(body.encodeToByteArray()) }
        val b = MockResponse.Builder().code(200).body(out).addHeader("Content-Type", "application/json").addHeader("Content-Encoding", "gzip")
        for ((k, v) in headers) b.addHeader(k, v)
        return b.build()
    }

    fun html(code: Int, vararg headers: Pair<String, String>): MockResponse {
        val b = MockResponse.Builder().code(code).body("<html><body>blocked</body></html>").addHeader("Content-Type", "text/html")
        for ((k, v) in headers) b.addHeader(k, v)
        return b.build()
    }

    /** ESPN's WAF challenge: a 202 with the action header and an HTML body. */
    fun wafChallenge(): MockResponse = html(202, EspnHttp.WAF_ACTION to "challenge")

    /** [summary] with a synthetic `drives` of about [kb] KB of play objects (strings and numbers, as ESPN's), first. */
    fun padded(summary: String, kb: Int, first: Boolean = true): String {
        val drives = drives(kb)
        val body = summary.trim().removePrefix("{").removeSuffix("}")
        return if (first) "{\"drives\":$drives,$body}" else "{$body,\"drives\":$drives}"
    }

    fun drives(kb: Int): String {
        val sb = StringBuilder(kb * 1024 + 256)
        sb.append("{\"previous\":[")
        var i = 0
        while (sb.length < kb * 1024) {
            if (i > 0) sb.append(',')
            sb.append("{\"id\":\"40187307800").append(i).append("\",\"description\":\"12 plays, 75 yards, 6:12\",")
                .append("\"team\":{\"id\":\"4\",\"abbreviation\":\"CIN\",\"logos\":[{\"href\":\"https://img.invalid/l.png\",\"width\":500}]},")
                .append("\"plays\":[{\"id\":\"").append(i).append("1\",\"text\":\"(Shotgun) J.Burrow pass short right to T.Higgins for 9 yards\",")
                .append("\"clock\":{\"displayValue\":\"9:41\"},\"start\":{\"yardLine\":35,\"down\":1,\"distance\":10},\"statYardage\":9,")
                .append("\"scoringPlay\":false,\"wallclock\":\"2026-11-24T02:12:41Z\"}]}")
            i++
        }
        sb.append("]}")
        return sb.toString()
    }
}
