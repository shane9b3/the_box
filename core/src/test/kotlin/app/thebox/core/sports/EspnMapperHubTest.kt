package app.thebox.core.sports

import app.thebox.core.sports.hub.NflHubFixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/design/m6-nfl-hub.md §2.5, §4.3, §5.1, §16 #18 (P1): the board's highlight → `GameMedia(recapId, recapExpiresMs)`
 * at the board's server date; expired and non-settop highlights give none; an absent or wrong-shaped `highlights` never
 * fails the board; `delayed` from the status name. (§20) The whole entry is kept (`GameMedia.highlight`), and an
 * embargoed highlight is kept (listed, not offered) as the summary's clips are.
 */
class EspnMapperHubTest {
    private val logoBase = "https://a.espncdn.com/"
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    /** The evening after MNF: both week-11 highlights are past their embargo and a day from expiry. */
    private val mnfNight = ms("2026-11-25T00:00:00Z")
    private val pitPhi = "401873076"
    private val cinWsh = "401873078"

    private fun boardText(): String = SportsFixtures.text(NflHubFixtures.BOARD_WK11)
    private fun tree(): JsonObject = NflHubFixtures.JSON.parseToJsonElement(boardText()).jsonObject

    private fun board(o: JsonObject = tree(), at: Long? = mnfNight, base: String = logoBase): DayBoard =
        assertNotNull(EspnMapper.board(NflHubFixtures.JSON.decodeFromJsonElement(EspnBoardDto.serializer(), o), at, base))

    private fun game(b: DayBoard, id: String) = b.games.single { it.id.id == id }

    @Test fun boardHighlightBecomesTheMedia() {
        val b = board()
        assertEquals(2, b.games.size)
        val pit = assertNotNull(game(b, pitPhi).media)
        assertEquals(
            GameMedia(recapId = 50565318L, condensedId = null, goalClips = 0, recapExpiresMs = ms("2026-11-26T21:29:49Z"), highlight = pit.highlight),
            pit,
        )
        val cin = assertNotNull(game(b, cinWsh).media)
        assertEquals(
            GameMedia(recapId = 50579688L, condensedId = null, goalClips = 0, recapExpiresMs = ms("2026-11-26T04:32:10Z"), highlight = cin.highlight),
            cin,
        )
        // (§20) The full entry: what the summary mapper would make of the same video object.
        val e = assertNotNull(cin.highlight)
        assertEquals("https://clips.invalid/50579688/playlist.m3u8", e.hlsUrl)
        assertEquals(app.thebox.core.sports.hub.ClipKind.RECAP, e.clip.kind)
        assertEquals("Commanders storm back, stun Bengals 22-20 on MNF", e.clip.title)
        assertEquals(74_000L, e.clip.durationMs)
        assertEquals(ms("2026-11-24T04:32:10Z"), e.clip.embargoMs)
        assertEquals("https://img.invalid/50579688.jpg", e.clip.thumbUrl)
        assertEquals("VideoEntry(50579688, RECAP)", e.toString(), "the URL is never printed")
    }

    /**
     * (§20) GB @ LAR final (2026-11-26): the real board event with its highlight (**synthesized**: no saved board was
     * captured after ESPN posted it) maps to a full, playable entry; the summary of that moment never listed it.
     */
    @Test fun theBoardOnlyHighlightOfGbAtLar_synthesizedBoard() {
        val dto = NflHubFixtures.JSON.decodeFromString(EspnGameDto.serializer(), SportsFixtures.text(NflHubFixtures.BOARD_EVENT_FINAL_SYNTH))
        val g = assertNotNull(EspnMapper.game(dto, GameId(League.NFL, "401873079"), ms("2026-11-26T05:48:01Z"), logoBase))
        assertEquals(GamePhase.FINAL, g.phase)
        val e = assertNotNull(g.media?.highlight)
        assertEquals(50601743L, e.clip.videoId)
        assertEquals("Rams pick up important home win over the Packers", e.clip.title)
        assertEquals(80_000L, e.clip.durationMs)
        assertEquals("https://clips.invalid/50601743/playlist.m3u8", e.hlsUrl)
        assertEquals(50601743L, g.media?.recapId)
        assertEquals(e.clip.expiresMs, g.media?.recapExpiresMs)
        val summary = NflHubFixtures.summary(NflHubFixtures.FINAL_GB_LAR)
        assertTrue(summary.videos.orEmpty().none { it.tracking?.coverageType == "Final Game Highlight" }, "the summary lists no highlight")
    }

    @Test fun theFollowSnapshotCarriesTheMediaToo() {
        val dto = NflHubFixtures.JSON.decodeFromString(EspnGameDto.serializer(), boardText())
        val g = assertNotNull(EspnMapper.game(dto, GameId(League.NFL, cinWsh), mnfNight, logoBase))
        assertEquals(50579688L, g.media?.recapId)
    }

    @Test fun evaluatedAtTheBoardsServerDate() {
        // Before the MNF highlight's embargo (04:32:10Z): (§20) kept, as the summary's clips are (data #3), with its
        // embargo; every use checks ClipOffer.offered, so it is offered from the instant the embargo lifts.
        val early = board(at = ms("2026-11-24T04:00:00Z"))
        val embargoed = assertNotNull(game(early, cinWsh).media?.highlight, "embargoed: listed")
        assertTrue(!app.thebox.core.sports.hub.ClipOffer.offered(embargoed.clip.embargoMs, embargoed.clip.expiresMs, ms("2026-11-24T04:00:00Z")), "not offered")
        assertEquals(50565318L, game(early, pitPhi).media?.recapId)
        // Inside the 5 min margin of the MNF expiry, and past it.
        val late = board(at = ms("2026-11-26T04:28:00Z"))
        assertNull(game(late, cinWsh).media, "within the margin")
        assertEquals(50565318L, game(late, pitPhi).media?.recapId)
        assertTrue(board(at = ms("2026-11-27T00:00:00Z")).games.all { it.media == null }, "both expired")
        // No server date: the time check is skipped and the UI's ClipOffer decides at draw time.
        assertEquals(50579688L, game(board(at = null), cinWsh).media?.recapId)
    }

    @Test fun nonSettopAndNonHttpsHighlightsAreDropped() {
        val noSettop = editHighlight(tree(), cinWsh) { h ->
            JsonObject(h + ("deviceRestrictions" to buildJsonObject {
                put("type", "whitelist")
                put("devices", JsonArray(listOf(JsonPrimitive("tablet"), JsonPrimitive("handset"))))
            }))
        }
        assertNull(game(board(noSettop), cinWsh).media)
        assertEquals(50565318L, game(board(noSettop), pitPhi).media?.recapId, "the other game keeps its highlight")

        val http = editHighlight(tree(), cinWsh) { h -> rewrite(h) { s -> s.replace("https://clips.invalid", "http://10.0.2.2:8080") } as JsonObject }
        assertNull(game(board(http), cinWsh).media, "http on the production base")
        assertEquals(50579688L, game(board(http, base = "http://10.0.2.2:8080/espn-logo/"), cinWsh).media?.recapId, "the debug origin")

        val play = editHighlight(tree(), cinWsh) { h -> JsonObject(h + ("tracking" to buildJsonObject { put("coverageType", "OnePlay") })) }
        assertNull(game(board(play), cinWsh).media, "only a Final Game Highlight is the board's media")
    }

    @Test fun absentHighlightsGiveNoMedia() {
        val none = editCompetition(tree(), cinWsh) { c -> JsonObject(c - "highlights") }
        assertNull(game(board(none), cinWsh).media)
        val empty = editCompetition(tree(), cinWsh) { c -> JsonObject(c + ("highlights" to JsonArray(emptyList()))) }
        assertNull(game(board(empty), cinWsh).media)
    }

    /** §16 #18: a wrong-shaped `highlights` drops the highlight only; the board, its scores and the other game stand. */
    @Test fun wrongShapedHighlightsNeverFailTheBoard() {
        val shapes: List<JsonElement> = listOf(
            JsonPrimitive("Final Game Highlight"),
            JsonPrimitive(42),
            buildJsonObject { put("id", 50579688) },
            JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive(1), JsonArray(emptyList()))),
            // An object whose leaves are the wrong type (a list where a string goes) is dropped on its own.
            JsonArray(listOf(buildJsonObject { put("headline", JsonArray(emptyList())); put("id", 1) })),
        )
        val reference = board()
        for (shape in shapes) {
            val b = board(editCompetition(tree(), cinWsh) { c -> JsonObject(c + ("highlights" to shape)) })
            val g = game(b, cinWsh)
            assertNull(g.media, "$shape")
            assertEquals(game(reference, cinWsh).copy(media = null), g, "$shape: only the highlight is absent")
            assertEquals(game(reference, pitPhi), game(b, pitPhi), "$shape")
        }
        // A junk entry before a good one: the good one still maps.
        val mixed = editCompetition(tree(), cinWsh) { c ->
            val good = (c["highlights"] as JsonArray).single()
            JsonObject(c + ("highlights" to JsonArray(listOf(JsonPrimitive("junk"), buildJsonObject { put("headline", JsonArray(emptyList())) }, good))))
        }
        assertEquals(50579688L, game(board(mixed), cinWsh).media?.recapId)
    }

    @Test fun delayedFromTheStatusName() {
        for ((name, delayed) in listOf("STATUS_DELAYED" to true, "STATUS_RAIN_DELAY" to true, "STATUS_IN_PROGRESS" to false)) {
            val o = editCompetition(tree(), cinWsh) { c ->
                val status = buildJsonObject {
                    put("period", 3)
                    put("clock", 332.0)
                    put("displayClock", "5:32")
                    put("type", buildJsonObject { put("id", "2"); put("name", name); put("state", "in") })
                }
                JsonObject(c + ("status" to status))
            }
            val g = game(board(o), cinWsh)
            assertEquals(GamePhase.LIVE, g.phase, name)
            assertEquals(delayed, assertIs<LeagueDetail.Nfl>(g.detail).delayed, name)
        }
        assertTrue(board().games.none { (it.detail as LeagueDetail.Nfl).delayed }, "a final is never delayed")
    }

    // ------------------------------------------------------------------ helpers

    private fun editCompetition(o: JsonObject, eventId: String, f: (JsonObject) -> JsonObject): JsonObject {
        val events = (o["events"] as JsonArray).map { e ->
            val eo = e as JsonObject
            if ((eo["id"] as JsonPrimitive).content != eventId) return@map eo
            val comps = (eo["competitions"] as JsonArray).mapIndexed { i, c -> if (i == 0) f(c as JsonObject) else c }
            JsonObject(eo + ("competitions" to JsonArray(comps)))
        }
        return JsonObject(o + ("events" to JsonArray(events)))
    }

    private fun editHighlight(o: JsonObject, eventId: String, f: (JsonObject) -> JsonObject): JsonObject = editCompetition(o, eventId) { c ->
        JsonObject(c + ("highlights" to JsonArray((c["highlights"] as JsonArray).map { f(it as JsonObject) })))
    }

    private fun rewrite(e: JsonElement, f: (String) -> String): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { rewrite(it.value, f) })
        is JsonArray -> JsonArray(e.map { rewrite(it, f) })
        is JsonPrimitive -> if (e.isString) JsonPrimitive(f(e.content)) else e
    }
}
