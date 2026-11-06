package app.thebox.core.sports

import app.thebox.core.settings.Domains
import app.thebox.core.settings.state.NOT_BACKED_UP_DOMAINS
import app.thebox.core.settings.state.SportsDomain
import app.thebox.core.settings.state.SportsFollowDomain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Score bug commit 0 (docs/design/m6-score-bug.md §2.6, §7, §9): the persisted shapes, the domains and the fixtures. */
class SportsContractsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun domainsAreRegisteredAsDesigned() {
        assertEquals(Domains.SPORTS, SportsDomain.name)
        assertTrue(SportsDomain.backedUp)
        assertEquals(SportsPrefs(), SportsDomain.default)
        assertEquals(Domains.SPORTS_FOLLOW, SportsFollowDomain.name)
        assertFalse(SportsFollowDomain.backedUp)
        assertEquals(SportsFollowDoc(), SportsFollowDomain.default)
        assertTrue(Domains.SPORTS_FOLLOW in NOT_BACKED_UP_DOMAINS)
        assertFalse(Domains.SPORTS in NOT_BACKED_UP_DOMAINS)
    }

    @Test
    fun defaultsAreNotStoredAndOldDocumentsStillRead() {
        assertEquals("{}", json.encodeToString(SportsPrefs.serializer(), SportsPrefs()))
        assertEquals("{}", json.encodeToString(SportsFollowDoc.serializer(), SportsFollowDoc()))
        val prefs = json.decodeFromString(SportsPrefs.serializer(), """{"corner":"BOTTOM_LEFT","delaySec":30,"future":1}""")
        assertEquals(SportsPrefs(corner = ScoreCorner.BOTTOM_LEFT, delaySec = 30), prefs)
        val f = FollowedGame(League.NHL, "2026020251", 1_793_923_200_000L, "UTA", "MTL")
        val doc = SportsFollowDoc(game = f, pollPhaseMs = 1_400L, firstFollowShown = true)
        assertEquals(doc, json.decodeFromString(SportsFollowDoc.serializer(), json.encodeToString(SportsFollowDoc.serializer(), doc)))
        // Clearing keeps the install facts (SportsFollowDoc KDoc).
        assertEquals(SportsFollowDoc(pollPhaseMs = 1_400L, firstFollowShown = true), doc.copy(game = null))
        assertEquals(GameId(League.NHL, "2026020251"), f.id)
    }

    @Test
    fun recordedFixturesAreOnTheClasspath() {
        val today = json.parseToJsonElement(SportsFixtures.text(SportsFixtures.SCORE_2026_11_05)).jsonObject
        assertEquals("2026-11-05", today["currentDate"]!!.jsonPrimitive.content)
        assertEquals(11, today["games"]!!.jsonArray.size)
        val yesterday = json.parseToJsonElement(SportsFixtures.text(SportsFixtures.SCORE_2026_11_04)).jsonObject
        assertTrue(yesterday["games"]!!.jsonArray.all { (it as JsonObject)["gameState"]!!.jsonPrimitive.content == "OFF" })
        val landing = json.parseToJsonElement(SportsFixtures.text(SportsFixtures.LANDING_LIVE_INT)).jsonObject
        assertEquals("true", landing["clock"]!!.jsonObject["inIntermission"]!!.jsonPrimitive.content)
        assertTrue(SportsFixtures.text(SportsFixtures.SCORE_NOW_307_HEADERS).startsWith("HTTP/2 307"))
    }

    @Test
    fun fakeRepositoryRecordsAndRoundTrips() {
        val repo = FakeScoreRepository(raiseFollowEvents = true)
        val g = SportsFixtures.game()
        repo.follow(g)
        assertEquals(SportsFixtures.followedOf(g), repo.followed.value)
        repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        assertTrue(repo.seen)
        repo.reportDemand(DemandOwner.SPORTS_TAB, false)
        assertFalse(repo.seen)
        repo.follow(null)
        assertNull(repo.followed.value)
        assertEquals(listOf(g, null), repo.followCalls)
    }

    @Test
    fun boxIntersection() {
        val a = Box(0f, 0f, 10f, 10f)
        assertTrue(a.intersects(Box(5f, 5f, 15f, 15f)))
        assertFalse(a.intersects(Box(10f, 0f, 20f, 10f)))
    }

    @Test
    fun delayStepsMatchAppendixA() {
        assertEquals(listOf(0, 5, 10, 15, 20, 30, 45, 60, 90, 120), SportsConstants.DELAY_STEPS_S)
        assertEquals(SportsConstants.SCORE_W, 404f)
        assertEquals(88f, kotlin.math.round(SportsConstants.SCORE_H * SportsConstants.SCORE_LARGE_SCALE))
    }
}
