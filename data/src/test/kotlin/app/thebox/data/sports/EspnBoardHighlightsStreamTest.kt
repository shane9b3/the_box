package app.thebox.data.sports

import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.EspnBoardDto
import app.thebox.core.sports.EspnMapper
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.hub.NflHubFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant

/**
 * (NFL hub code review 2026-11-26, data "checked" list) The board's `highlights` tolerance (§16 #18, P1 note 5) under the
 * **production streaming decoder** (`EspnHttp.decodeCapped`, kotlinx `decodeFromStream`), not only the tree decoder of
 * `EspnMapperHubTest`: `null`, a string, a number, an object, a list of junk and a wrong-typed leaf each leave the board
 * intact with only the highlight absent; a junk entry before a good one keeps the good one.
 */
class EspnBoardHighlightsStreamTest {
    private val mnfNight = Instant.parse("2026-11-25T00:00:00Z").toEpochMilli()
    private val logoBase = "https://a.espncdn.com/"
    private val cinWsh = "401873078"
    private val pitPhi = "401873076"

    private fun tree(): JsonObject = Json.parseToJsonElement(SportsFixtures.text(NflHubFixtures.BOARD_WK11)).jsonObject

    /** Through the transport's own decode of the bytes, then the board mapper. */
    private fun streamed(o: JsonObject): DayBoard {
        val bytes = o.toString().toByteArray()
        val d = EspnHttp.decodeCapped(ByteArrayInputStream(bytes), EspnBoardDto.serializer(), 4L * bytes.size)
        assertTrue("decoded: $d", d is EspnHttp.Decoded.Ok)
        return EspnMapper.board((d as EspnHttp.Decoded.Ok).value, mnfNight, logoBase)!!
    }

    private fun withHighlights(v: JsonElement): JsonObject {
        val o = tree()
        val events = (o["events"] as JsonArray).map { e ->
            val eo = e as JsonObject
            if ((eo["id"] as JsonPrimitive).content != cinWsh) return@map eo
            val comps = (eo["competitions"] as JsonArray).mapIndexed { i, c -> if (i == 0) JsonObject((c as JsonObject) + ("highlights" to v)) else c }
            JsonObject(eo + ("competitions" to JsonArray(comps)))
        }
        return JsonObject(o + ("events" to JsonArray(events)))
    }

    @Test fun wrongShapedHighlightsNeverFailTheStreamedBoard() {
        val reference = streamed(tree())
        val ref = reference.games.single { it.id.id == cinWsh }
        assertEquals(50579688L, ref.media?.recapId)
        val shapes: List<JsonElement> = listOf(
            kotlinx.serialization.json.JsonNull,
            JsonPrimitive("Final Game Highlight"),
            JsonPrimitive(42),
            buildJsonObject { put("id", 50579688) },
            JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive(1), JsonArray(emptyList()))),
            JsonArray(listOf(buildJsonObject { put("headline", JsonArray(emptyList())); put("id", 1) })),
        )
        for (shape in shapes) {
            val b = streamed(withHighlights(shape))
            val g = b.games.single { it.id.id == cinWsh }
            assertNull("$shape", g.media)
            assertEquals("$shape: only the highlight is absent", ref.copy(media = null), g)
            assertEquals("$shape", reference.games.single { it.id.id == pitPhi }, b.games.single { it.id.id == pitPhi })
        }
        val good = ((tree()["events"] as JsonArray).first { ((it as JsonObject)["id"] as JsonPrimitive).content == cinWsh } as JsonObject)
            .let { ((it["competitions"] as JsonArray)[0] as JsonObject)["highlights"] as JsonArray }.single()
        val mixed = streamed(withHighlights(JsonArray(listOf(JsonPrimitive("junk"), buildJsonObject { put("headline", JsonArray(emptyList())) }, good))))
        assertEquals(50579688L, mixed.games.single { it.id.id == cinWsh }.media?.recapId)
    }
}
