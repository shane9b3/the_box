package app.thebox.core.sports

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-sports-update-interval.md §3.2, §3.3, §3.7; owner rulings 29–32; review S2, S6, S9. */
class PollMeterTest {
    private fun fill(league: League, workMs: Int, n: Int, maxAge: Int? = 1, endpoint: String = PollMeter.NFL_EVENT, start: PollMeasure? = null): PollMeasure {
        var m = start
        repeat(n) { m = PollMeter.add(m, league, endpoint, workMs.toLong(), 2_200, maxAge) }
        return m!!
    }

    @Test
    fun nearestRankP90NeedsTenSamples() {
        assertNull(PollMeter.p90(List(9) { 100 }))
        assertEquals(900, PollMeter.p90((1..10).map { it * 100 }))
        // 20 samples: rank ceil(18) = 18.
        assertEquals(1_800, PollMeter.p90((1..20).map { it * 100 }.shuffled()))
    }

    @Test
    fun feedRefreshFromMaxAge() {
        assertEquals(1, PollMeter.feedRefreshS(PollMeasure()))
        assertEquals(19, PollMeter.feedRefreshS(PollMeasure(maxAgeS = 19)))
        assertEquals(1, PollMeter.feedRefreshS(PollMeasure(maxAgeS = 0)))
    }

    @Test
    fun fewerThanTenSamplesGiveTheDefault() {
        assertEquals(5, PollMeter.recommended(null, League.NFL))
        assertEquals(20, PollMeter.recommended(null, League.NHL))
        assertEquals(5, PollMeter.recommended(fill(League.NFL, 4_000, 9), League.NFL), "a slow box, but too few samples")
        assertFalse(PollMeter.measured(fill(League.NFL, 400, 9)))
        assertTrue(PollMeter.measured(fill(League.NFL, 400, 10)))
    }

    @Test
    fun theDesignExamplesAtTheFiveSecondFloor() {
        // S1: 0.4 s → 5 s, 1.2 s → 5 s, 2 s → 10 s (3 × 2 s = 6 s > 5 s).
        assertEquals(5, PollMeter.recommended(fill(League.NFL, 400, 20), League.NFL))
        assertEquals(5, PollMeter.recommended(fill(League.NFL, 1_200, 20), League.NFL))
        assertEquals(10, PollMeter.recommended(fill(League.NFL, 2_000, 20), League.NFL))
        assertEquals(15, PollMeter.recommended(fill(League.NFL, 4_000, 20), League.NFL))
        assertEquals(30, PollMeter.recommended(fill(League.NFL, 20_000, 20), League.NFL), "nothing past the list's end")
        // The NHL list starts at its 20 s feed.
        assertEquals(20, PollMeter.recommended(fill(League.NHL, 400, 20, maxAge = 19, endpoint = PollMeter.NHL_LANDING), League.NHL))
        assertEquals(40, PollMeter.recommended(fill(League.NHL, 8_000, 20, maxAge = 19, endpoint = PollMeter.NHL_LANDING), League.NHL))
    }

    @Test
    fun theBoardFallbacksTenSecondFeedKeepsAutoAtTenOrMore() {
        val board = fill(League.NFL, 300, 3, maxAge = 10, endpoint = PollMeter.NFL_BOARD)
        assertEquals(10, PollMeter.recommended(board, League.NFL), "unmeasured, but the feed refresh is known")
        assertEquals(10, PollMeter.recommended(fill(League.NFL, 300, 20, maxAge = 10, endpoint = PollMeter.NFL_BOARD), League.NFL))
    }

    @Test
    fun anEndpointChangeResetsTheWindowButKeepsSlowed() {
        val ev = fill(League.NFL, 2_000, 20).copy(slowed = true)
        assertEquals(10, PollMeter.recommended(ev, League.NFL))
        val board = PollMeter.add(ev, League.NFL, PollMeter.NFL_BOARD, 300, 20_000, 10)
        assertEquals(PollMeter.NFL_BOARD, board.endpoint)
        assertEquals(listOf(300), board.workMs)
        assertEquals(listOf(20_000), board.wireBytes)
        assertEquals(10, board.maxAgeS)
        assertTrue(board.slowed)
    }

    @Test
    fun aFailureOnAnotherEndpointLeavesTheWindowAndTheHysteresis() {
        // Fix pass 2: a 2 s box, held at 10 s on per-event.
        val m = fill(League.NFL, 2_000, 20)
        assertEquals(10, m.recommendedS)
        val before = m
        val failed = PollMeter.add(m, League.NFL, PollMeter.NFL_BOARD, 10_000, null, null, failed = true)
        assertEquals(before, failed, "a failed sample on another endpoint is dropped")
        // A good sample there is a real switch: a fresh window.
        val switched = PollMeter.add(m, League.NFL, PollMeter.NFL_BOARD, 300, 20_000, 10)
        assertEquals(PollMeter.NFL_BOARD, switched.endpoint)
        assertEquals(listOf(300), switched.workMs)
    }

    @Test
    fun theWindowKeepsTwentyAndFailuresCountCappedWithoutBytes() {
        val m = fill(League.NFL, 100, 25)
        assertEquals(SportsConstants.METER_WINDOW, m.workMs.size)
        assertEquals(SportsConstants.METER_WINDOW, m.wireBytes.size)
        val f = PollMeter.add(m, League.NFL, PollMeter.NFL_EVENT, 45_000, null, null, failed = true)
        assertEquals(10_000, f.workMs.last(), "a failure counts at its elapsed time, capped at the call timeout")
        assertEquals(m.wireBytes, f.wireBytes)
        // A network that times out on every third poll recommends slower (S6).
        var bad: PollMeasure? = null
        repeat(20) { i -> bad = PollMeter.add(bad, League.NFL, PollMeter.NFL_EVENT, if (i % 3 == 0) 10_000 else 300, 2_000, 1, failed = i % 3 == 0) }
        assertEquals(30, PollMeter.recommended(bad, League.NFL))
    }

    @Test
    fun hysteresisMovesUpAtOnceAndDownOnlyAfterAStreak() {
        val fast = fill(League.NFL, 400, 20)
        assertEquals(5, fast.recommendedS)
        // One slow spell: 3 slow samples push p90 over 1.67 s → 10 s at once.
        var m = fast
        repeat(3) { m = PollMeter.add(m, League.NFL, PollMeter.NFL_EVENT, 2_000, 2_200, 1) }
        assertEquals(10, m.recommendedS)
        // p90 hovering just under 1.67 s (raw 5 s) never flips back: 3 × 1.6 s = 4.8 s > 0.8 × 5 s.
        var hover = m
        repeat(40) { hover = PollMeter.add(hover, League.NFL, PollMeter.NFL_EVENT, 1_600, 2_200, 1) }
        assertEquals(10, hover.recommendedS, "no flapping between 5 and 10 s")
        // Clearly fast again (3 × 0.4 s fits in 4 s): down after the streak of 10, not before.
        var down = m
        val seen = ArrayList<Int>()
        repeat(30) { down = PollMeter.add(down, League.NFL, PollMeter.NFL_EVENT, 400, 2_200, 1); seen += down.recommendedS!! }
        assertEquals(5, down.recommendedS)
        val firstDown = seen.indexOf(5)
        assertTrue(firstDown >= SportsConstants.METER_DOWN_STREAK - 1, "moved down at sample $firstDown")
    }

    @Test
    fun choicesAutoAndValuesNotInTheList() {
        assertEquals(listOf(5, 10, 15, 20, 30), SportsConstants.INTERVAL_CHOICES_NFL_S)
        assertEquals(listOf(20, 40, 60), SportsConstants.INTERVAL_CHOICES_NHL_S)
        assertEquals(0, PollMeter.normalize(0, League.NFL))
        assertEquals(10, PollMeter.normalize(10, League.NFL))
        assertEquals(5, PollMeter.normalize(2, League.NFL), "a restored 2 s (a newer build) reads as the next listed value")
        assertEquals(40, PollMeter.normalize(25, League.NHL))
        assertEquals(0, PollMeter.normalize(90, League.NHL), "above the list: Auto")
        assertEquals(20, PollMeter.effectiveS(0, League.NHL, null))
        assertEquals(60, PollMeter.effectiveS(60, League.NHL, null))
        assertEquals(5, PollMeter.effectiveS(0, League.NFL, null))
        assertEquals(15, PollMeter.effectiveS(15, League.NFL, fill(League.NFL, 2_000, 20)), "a fixed choice is obeyed")
    }

    @Test
    fun the429FloorAppliesToEveryNflChoice() {
        val slowed = PollMeasure(endpoint = PollMeter.NFL_EVENT, slowed = true)
        assertEquals(10, PollMeter.effectiveS(0, League.NFL, slowed))
        assertEquals(10, PollMeter.effectiveS(5, League.NFL, slowed), "S2: a fixed 5 s too")
        assertEquals(20, PollMeter.effectiveS(20, League.NFL, slowed))
        assertEquals(20, PollMeter.effectiveS(0, League.NHL, slowed.copy(endpoint = PollMeter.NHL_LANDING)))
    }

    @Test
    fun megabytesAnHour() {
        assertEquals(3600.0 / 5 * 2_500 / 1e6, PollMeter.mbPerHour(null, League.NFL, 5), 1e-9)
        assertEquals(3600.0 / 20 * 1_500 / 1e6, PollMeter.mbPerHour(null, League.NHL, 20), 1e-9)
        val m = PollMeasure(wireBytes = listOf(2_000, 2_200, 9_000))
        assertEquals(2_200, PollMeter.bytesPerPoll(m, League.NFL), "the median")
        assertEquals(1.584, PollMeter.mbPerHour(m, League.NFL, 5), 1e-9)
    }

    @Test
    fun theMeasureIsSerializableAndOldDocsRead() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val doc = SportsFollowDoc(pollPhaseMs = 1_500L, meters = mapOf(League.NFL to fill(League.NFL, 400, 12)))
        assertEquals(doc, json.decodeFromString(SportsFollowDoc.serializer(), json.encodeToString(SportsFollowDoc.serializer(), doc)))
        assertEquals(SportsFollowDoc(pollPhaseMs = 1_400L), json.decodeFromString(SportsFollowDoc.serializer(), """{"pollPhaseMs":1400}"""))
    }

    @Test
    fun theIntervalPrefsAreBackedUpWithDefaults() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        assertTrue(app.thebox.core.settings.state.SportsDomain.backedUp)
        assertEquals("{}", json.encodeToString(SportsPrefs.serializer(), SportsPrefs()))
        assertEquals(0, SportsPrefs().nhlIntervalS)
        assertEquals(0, SportsPrefs().nflIntervalS)
        val p = SportsPrefs(nhlIntervalS = 40, nflIntervalS = 10)
        val text = json.encodeToString(SportsPrefs.serializer(), p)
        assertEquals(p, json.decodeFromString(SportsPrefs.serializer(), text))
        assertEquals(40, p.intervalS(League.NHL))
        assertEquals(10, p.intervalS(League.NFL))
        // An older backup (no interval keys) restores to Auto.
        assertEquals(SportsPrefs(delaySec = 30), json.decodeFromString(SportsPrefs.serializer(), """{"delaySec":30}"""))
    }
}
