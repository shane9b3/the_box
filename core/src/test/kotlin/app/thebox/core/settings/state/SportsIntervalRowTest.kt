package app.thebox.core.settings.state

import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.Visibility
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.tree.SportsRows
import app.thebox.core.sports.League
import app.thebox.core.sports.PollMeasure
import app.thebox.core.sports.PollMeter
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/design/m6-sports-update-interval.md §5 (rulings 29–32; review S7, S10, N7, N8): the Sports interval row. */
class SportsIntervalRowTest {
    private val store = FakeSettingsStore()
    private val pages = WpaPages(store, SettingsTexts.KEYS, FakePageEnv())

    private fun row(): SettingsRow =
        pages.build(SettingsRoute.Section(SectionId.SPORTS), advanced = true).rows.first { it.id == SportsRows.INTERVAL }

    private fun options(r: SettingsRow) = (r.kind as RowKind.Choice).options

    private fun meter(league: League, workMs: Int, n: Int = 20, endpoint: String = PollMeter.NFL_EVENT, maxAge: Int = 1, bytes: Long = 2_200): PollMeasure {
        var m: PollMeasure? = null
        repeat(n) { m = PollMeter.add(m, league, endpoint, workMs.toLong(), bytes, maxAge) }
        return m!!
    }

    @Test
    fun theRowIsAdvancedLiveAfterTheDelay() {
        val rows = pages.build(SettingsRoute.Section(SectionId.SPORTS), advanced = true).rows
        assertEquals(listOf(SportsRows.SHOW, SportsRows.CORNER, SportsRows.DELAY, SportsRows.INTERVAL, SportsRows.SIZE), rows.map { it.id })
        assertEquals(Visibility.ADVANCED, row().visibility)
        assertEquals(EffectTiming.LIVE, row().effect)
        assertTrue(pages.build(SettingsRoute.Section(SectionId.SPORTS), advanced = false).rows.isEmpty())
    }

    @Test
    fun nhlUnmeasuredShowsAutoAtTwentyAndTheFloor() {
        val r = row()
        assertEquals("SPORTS_INTERVAL(NHL)", r.label)
        assertEquals("SPORTS_INTERVAL_AUTO(20)", r.value)
        assertEquals(
            listOf("SPORTS_INTERVAL_AUTO(20)", "SPORTS_INTERVAL_RECOMMENDED(SPORTS_INTERVAL_EVERY(20))", "SPORTS_INTERVAL_EVERY(40)", "SPORTS_INTERVAL_EVERY(60)"),
            options(r).map { it.label },
        )
        assertEquals(listOf("0", "20", "40", "60"), options(r).map { it.id })
        assertEquals(
            "SPORTS_INTERVAL_DESC_UNMEASURED(20,0.3) SPORTS_INTERVAL_NHL_FLOOR SPORTS_INTERVAL_LEAGUE(NHL,NFL)",
            r.description,
        )
    }

    @Test
    fun nflMeasuredShowsTheRecommendationTheWorkTimeAndMegabytes() {
        store.update(SportsDomain) { it.copy(league = League.NFL) }
        store.update(SportsFollowDomain) { it.copy(meters = mapOf(League.NFL to meter(League.NFL, 400))) }
        val r = row()
        assertEquals("SPORTS_INTERVAL(NFL)", r.label)
        assertEquals("SPORTS_INTERVAL_AUTO_AFTER(5)", r.value)
        assertEquals("SPORTS_INTERVAL_RECOMMENDED(SPORTS_INTERVAL_AFTER(5))", options(r)[1].label)
        assertEquals(listOf("0", "5", "10", "15", "20", "30"), options(r).map { it.id })
        // 720 polls an hour × 2 200 B = 1.6 MB; "fetch and read" (S7: the draw is not timed).
        assertEquals("SPORTS_INTERVAL_DESC(5,0.4,1.6) SPORTS_INTERVAL_LEAGUE(NFL,NHL)", r.description)
    }

    @Test
    fun aMeasuredTimeUnderATenthReadsUnder() {
        store.update(SportsDomain) { it.copy(league = League.NFL) }
        store.update(SportsFollowDomain) { it.copy(meters = mapOf(League.NFL to meter(League.NFL, 40))) }
        assertTrue(row().description!!.startsWith("SPORTS_INTERVAL_DESC_FAST(5,1.6)"), row().description)
    }

    @Test
    fun aFixedChoiceFasterThanRecommendedSaysSo() {
        store.update(SportsDomain) { it.copy(league = League.NFL, nflIntervalS = 5) }
        store.update(SportsFollowDomain) { it.copy(meters = mapOf(League.NFL to meter(League.NFL, 2_500))) }
        val r = row()
        assertEquals("SPORTS_INTERVAL_AFTER(5)", r.value)
        assertEquals("SPORTS_INTERVAL_RECOMMENDED(SPORTS_INTERVAL_AFTER(10))", options(r).first { it.id == "10" }.label)
        assertTrue(r.description!!.contains("SPORTS_INTERVAL_FASTER"))
        assertTrue(r.description!!.startsWith("SPORTS_INTERVAL_DESC(10,2.5,"))
    }

    @Test
    fun slowedAndFallbackDetails() {
        store.update(SportsDomain) { it.copy(league = League.NFL) }
        val board = meter(League.NFL, 300, n = 3, endpoint = PollMeter.NFL_BOARD, maxAge = 10, bytes = 19_700).copy(slowed = true)
        store.update(SportsFollowDomain) { it.copy(meters = mapOf(League.NFL to board)) }
        val r = row()
        assertEquals("SPORTS_INTERVAL_AUTO_AFTER(10)", r.value, "the board's 10 s feed")
        val d = r.description!!
        assertTrue(d.contains("SPORTS_INTERVAL_SLOWED"), d)
        assertTrue(d.contains("SPORTS_INTERVAL_FALLBACK(20)"), d)
        // 360 polls an hour × 19.7 KB ≈ 7.1 MB.
        assertTrue(d.startsWith("SPORTS_INTERVAL_DESC_UNMEASURED(10,7.1)"), d)
    }

    @Test
    fun aStoredValueNotInTheListReadsAsTheNextAbove() {
        store.update(SportsDomain) { it.copy(league = League.NFL, nflIntervalS = 2) }
        assertEquals("SPORTS_INTERVAL_RECOMMENDED(SPORTS_INTERVAL_AFTER(5))", row().value)
    }

    @Test
    fun theIntentWritesTheLeagueOnScreen() {
        assertTrue(pages.apply(RowIntent.Choose(SportsRows.INTERVAL, "40")))
        assertEquals(40, store.flow(SportsDomain).value.nhlIntervalS)
        assertEquals(0, store.flow(SportsDomain).value.nflIntervalS)
        store.update(SportsDomain) { it.copy(league = League.NFL) }
        pages.apply(RowIntent.Choose(SportsRows.INTERVAL, "15"))
        assertEquals(15, store.flow(SportsDomain).value.nflIntervalS)
        assertEquals(40, store.flow(SportsDomain).value.nhlIntervalS)
        pages.apply(RowIntent.Choose(SportsRows.INTERVAL, "0"))
        assertEquals(0, store.flow(SportsDomain).value.nflIntervalS)
    }

    @Test
    fun meterWritesRefreshThePageButFollowWritesDoNot() = runTest {
        val seen = ArrayList<Unit>()
        val job = launch { pages.changes.collect { seen += it } }
        runCurrent()
        store.update(SportsFollowDomain) { it.copy(pollPhaseMs = 1_700L) }
        runCurrent()
        assertTrue(seen.isEmpty(), "N8: a follow write rebuilds nothing")
        store.update(SportsFollowDomain) { it.copy(meters = mapOf(League.NHL to PollMeasure(endpoint = PollMeter.NHL_LANDING))) }
        runCurrent()
        assertEquals(1, seen.size)
        job.cancel()
    }
}
