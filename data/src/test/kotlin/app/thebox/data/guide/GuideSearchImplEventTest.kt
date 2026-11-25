package app.thebox.data.guide

import android.util.Xml
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.thebox.core.catchup.CatchupResolverImpl
import app.thebox.core.epg.EpgBuildRequest
import app.thebox.core.epg.EpgSourceFile
import app.thebox.core.epg.EpgWhitelist
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.epg.store.EpgStoreImpl
import app.thebox.core.epg.testutil.EpgTestDb
import app.thebox.core.events.ChannelEvent
import app.thebox.core.events.EventEntry
import app.thebox.core.events.FakeEventIndex
import app.thebox.core.guide.ProgrammeHit
import app.thebox.core.guide.SearchResults
import app.thebox.core.guide.state.GuideTexts
import app.thebox.core.parental.legacyParentalAccess
import app.thebox.core.sports.League
import app.thebox.core.sports.TeamName
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Event channels, docs/design/m6-event-channels.md §7, §11.1 `GuideSearchImplEventTest`: placeholders dropped, events
 * from the in-memory index (with a team nickname's place), the ±5 min de-duplication (re-review E4), ended events
 * dropped, the slot word never lists an event, empty slots last, and event hits without a guide (U-E-S5).
 *
 * The index is a [FakeEventIndex], the placeholder test and the team table are injected: nothing here depends on WP-1's
 * parser, `EpgFiller` or `TeamNames`. Integration: re-run against the real `EventIndexImpl` with the fake panel's
 * `--event-catalog` (§11.4 state 3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GuideSearchImplEventTest {
    private lateinit var db: LibraryDatabase
    private lateinit var g: TestGraph
    private lateinit var store: EpgStoreImpl
    private lateinit var dir: File
    private val nowMs = EpgTestDb.minutes("2026-11-24T22:00:00Z") * 60_000L
    private val now = (nowMs / 60_000L).toInt()
    private var p = 0L
    private val ids = HashMap<String, Long>()
    private val index = FakeEventIndex()
    private val fillers = setOf("No Event Today", "Next Event: Vegas @ Edmonton at 08:00PM EST on Nov 24")
    private val oilers = TeamName(League.NHL, "EDM", listOf("Edmonton Oilers"), listOf("Oilers"), listOf("Edmonton"), listOf("EdmontonOilers"))

    @Before
    fun setUp() = runBlocking {
        db = TestDb.create(); g = TestGraph(db)
        dir = File(TestDb.context.cacheDir, "epg-ev-${System.nanoTime()}")
        store = EpgStoreImpl(dir, AndroidSQLiteDriver(), { Long.MAX_VALUE }, Xml::newPullParser, { nowMs }, forceFts4 = true)
        p = db.newPlaylist("main")
        val src = db.newSource("xmltv")
        var sort = 0
        suspend fun ch(key: String, name: String, epg: String? = null, adult: Boolean = false): Long {
            val id = db.newChannel(p, key, name, sortIndex = sort++, adult = adult)
            if (epg != null) db.match(p, key, src, epg)
            ids[key] = id
            return id
        }
        ch("sn09", "Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET")
        ch("nhl01", "NHL 01: Golden Knights vs. Oilers (11.24 8:00 PM ET)")
        ch("boss1", "ESPN+ 01: FAU vs. Texas @ Nov 24 6:00PM ET", epg = "boss1")
        ch("boss2", "ESPN+ 02: Woking vs. Worthing @ Nov 24 6:00PM ET", epg = "boss2")
        ch("boss3", "ESPN+ 03: NO EVENT", epg = "boss3")
        ch("ended", "Sportsnet+ 07: Oilers Classic @ 24 Nov 01:00 PM ET")
        ch("adult", "Sportsnet+ 08: Oilers After Dark @ 24 Nov 08:00 PM ET", adult = true)
        ch("sn11", "Sportsnet+ 11: NO EVENT")
        ch("snOne", "Sportsnet One")
        index.put(
            listOf(
                ev("sn09", "Vegas @ Edmonton", now + 60),
                ev("nhl01", "Golden Knights vs. Oilers", now + 60),
                ev("boss1", "FAU vs. Texas", now + 120),
                ev("boss2", "Woking vs. Worthing", now + 120),
                ev("ended", "Oilers Classic", now - 300, now - 10),
                ev("adult", "Oilers After Dark", now + 60),
                EventEntry(ids.getValue("sn11"), p, "Sportsnet+ 11", null, ChannelEvent.EMPTY, "Sportsnet+ 11: NO EVENT"),
            ),
        )
        val xml = EpgTestDb.xmltv {
            channel("boss1", "Boss 1")
            programme("boss1", now - 120, now + 120, "No Event Today")
            programme("boss1", now + 120, now + 360, "Florida Atlantic at Texas") // the same event, spelled otherwise
            channel("boss2", "Boss 2")
            programme("boss2", now - 120, now + 135, "No Event Today")
            programme("boss2", now + 135, now + 375, "Woking Highlights") // another event at exactly +15
            channel("boss3", "Boss 3")
            programme("boss3", now - 120, now + 120, "Next Event: Vegas @ Edmonton at 08:00PM EST on Nov 24")
            programme("boss3", now + 120, now + 360, "No Event Today")
        }.writeTo(File(dir.parentFile, "ev-${System.nanoTime()}.xml"))
        store.build(EpgBuildRequest(listOf(EpgSourceFile(src, xml)), nowMs, pastDays = 1, futureDays = 2), EpgWhitelistResolver { _, _ -> EpgWhitelist.ALL })
        Unit
    }

    @After fun tearDown() { db.close(); dir.deleteRecursively() }

    private fun ev(key: String, title: String, start: Int, end: Int = start + 180) =
        FakeEventIndex.event(ids.getValue(key), title, start, end, playlistId = p)

    private fun searchImpl(store: app.thebox.core.epg.EpgStore = this.store): GuideSearchImpl {
        val refs = EpgRefResolver(db, db.epgSourceDao(), db.playlistDao(), g.settings)
        val queries = GuideQueries(db, g.playlists, CatchupResolverImpl(), refs, events = index)
        return GuideSearchImpl(queries, store, Dispatchers.IO, index, isFiller = { it in fillers }, teams = { listOf(oilers) })
    }

    private fun search(q: String, impl: GuideSearchImpl = searchImpl()): SearchResults = runBlocking { impl.search(q, nowMs, legacyParentalAccess(false)) }
    private fun key(h: ProgrammeHit) = ids.entries.single { it.value == h.channel.rowId }.key

    @Test
    fun placeholderListingsAreNeverResults() {
        val r = search("vegas")
        assertTrue(r.programmes.none { it.info.title.startsWith("Next Event") })
        assertEquals(listOf("sn09"), r.programmes.map(::key))
        assertTrue(search("event").programmes.none { it.info.title in fillers })
    }

    @Test
    fun aNicknameFindsTheCityOnlyEventAndTheNicknamedOne() {
        val r = search("oilers")
        assertEquals(setOf("sn09", "nhl01"), r.programmes.map(::key).toSet())
        val hit = r.programmes.first { key(it) == "sn09" }
        assertEquals("Vegas @ Edmonton", hit.info.title)
        assertEquals(now + 60 to now + 240, hit.info.startMin to hit.info.endMin)
        assertEquals(GuideTexts.EVENT_FROM_NAME, hit.info.description)
        assertFalse(hit.inArchive)
        assertEquals(now + 240, hit.rawEndMin)
    }

    @Test
    fun endedEventsAndHiddenChannelsAreLeftOut() {
        val r = search("oilers")
        assertFalse(r.programmes.any { key(it) == "ended" })
        assertFalse("the parental predicate applies", r.programmes.any { key(it) == "adult" })
        assertTrue(search("oilers after").programmes.isEmpty())
    }

    @Test
    fun aListingWithinFiveMinutesIsTheSameEventAndAListingFifteenAwayIsAnother() {
        val texas = search("texas").programmes.filter { key(it) == "boss1" }
        assertEquals(listOf("Florida Atlantic at Texas"), texas.map { it.info.title })
        // "woking" matches the listing at +15 and the name's event: both are shown.
        val woking = search("woking").programmes.filter { key(it) == "boss2" }.map { it.info.title }.toSet()
        assertEquals(setOf("Woking Highlights", "Woking vs. Worthing"), woking)
    }

    @Test
    fun theSlotWordListsNoEventAndEmptySlotsRankLast() {
        val r = search("sportsnet")
        assertTrue(r.programmes.isEmpty())
        val names = r.channels.map { it.displayName }
        assertTrue(names.contains("Sportsnet+ 11: NO EVENT"))
        assertEquals("Sportsnet+ 11: NO EVENT", names.last())
    }

    @Test
    fun eventHitsNeedNoGuide() {
        val none = EpgStoreImpl(File(dir, "none").apply { mkdirs() }, AndroidSQLiteDriver(), { Long.MAX_VALUE }, Xml::newPullParser, { nowMs }, forceFts4 = true)
        val r = search("oilers", searchImpl(none))
        assertTrue(r.guideDataMissing)
        assertEquals(setOf("sn09", "nhl01"), r.programmes.map(::key).toSet())
    }

    @Test
    fun withoutAnIndexSearchIsAsBefore() {
        val refs = EpgRefResolver(db, db.epgSourceDao(), db.playlistDao(), g.settings)
        val plain = GuideSearchImpl(GuideQueries(db, g.playlists, CatchupResolverImpl(), refs), store, Dispatchers.IO)
        assertTrue(search("oilers", plain).programmes.isEmpty())
    }

    @Test
    fun theTermsExpandOnlyTeamNamesAndKeepOtherWords() {
        val t = EventSearchTerms.of("edmonton oilers tv", listOf(oilers))
        assertEquals(listOf(listOf(listOf("edmonton", "oilers"), listOf("edmonton")), listOf(listOf("tv"))), t)
        assertTrue(EventSearchTerms.matches(EventSearchTerms.of("oil", listOf(oilers)), EventSearchTerms.words("Golden Knights vs. Oilers")))
        assertFalse(EventSearchTerms.matches(EventSearchTerms.of("oilers", listOf(oilers)), EventSearchTerms.words("Calgary @ Seattle")))
        assertEquals(listOf("st", "louis", "montreal"), EventSearchTerms.words("St. Louis @ Montréal"))
    }
}
