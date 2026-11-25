package app.thebox.data.sports

import android.util.Xml
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.thebox.core.epg.EpgBuildRequest
import app.thebox.core.epg.EpgGenerationInfo
import app.thebox.core.epg.EpgSourceFile
import app.thebox.core.epg.EpgStore
import app.thebox.core.epg.EpgWhitelist
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.epg.store.EpgStoreImpl
import app.thebox.core.epg.testutil.EpgTestDb
import app.thebox.core.events.EventEntry
import app.thebox.core.events.EventZoneSource
import app.thebox.core.events.FakeEventIndex
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.Market
import app.thebox.core.sports.Side
import app.thebox.core.sports.TeamLine
import app.thebox.core.sports.TvBroadcast
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.ChannelHorizon
import app.thebox.core.sports.hub.MatchConfidence
import app.thebox.core.sports.hub.MatchReason
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.FavouriteEntity
import app.thebox.data.db.entity.FavouriteListEntity
import app.thebox.data.guide.GuideGraph
import app.thebox.data.guide.exec
import app.thebox.data.guide.match
import app.thebox.data.guide.newSource
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newGroup
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import app.thebox.data.testutil.strings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * docs/design/m6-event-channels.md §8, §11.1 `GameChannelMatcherImplTest`: team channels on both sides, the user's team
 * first and never capped; a regional alias without guide evidence is absent, with a listing it is CONFIRMED; a "Next
 * Event:" placeholder is no evidence; the event stage (grades, groups, hidden and locked); the ranking classes; the E3
 * collapse; the reserved event row; the cache key; `teamChannels` and `guideEndMs`. In-memory Room, a real
 * `EpgStoreImpl` (Robolectric SQLite, FTS4) and a [FakeEventIndex] (the real `EventGameRule` and `TeamChannelRule`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GameChannelMatcherEventTest {
    private lateinit var db: LibraryDatabase
    private lateinit var gg: GuideGraph
    private lateinit var store: EpgStoreImpl
    private lateinit var dir: File
    private lateinit var events: FakeEventIndex
    private lateinit var matcher: GameChannelMatcherImpl

    /** VGK @ EDM: 2026-11-25 01:00 UTC (8:00 PM ET); "now" is ten minutes before. */
    private val startMs = Instant.parse("2026-11-25T01:00:00Z").toEpochMilli()
    private val nowMs = startMs - 10 * 60_000L
    private val s = (startMs / 60_000L).toInt()
    private val ids = HashMap<String, Long>()
    private var p1 = 0L
    private val entries = ArrayList<EventEntry>()

    private fun team(abbrev: String, name: String) = TeamLine(abbrev, name, null, null)
    private fun game(id: String, away: TeamLine, home: TeamLine, broadcasts: List<TvBroadcast> = emptyList(), league: League = League.NHL, start: Long = startMs) =
        GameSnapshot(GameId(league, id), "", start, GamePhase.SCHEDULED, away, home, null, null, null, null, null, broadcasts = broadcasts)

    private val vgkEdm = game(
        "2026020385", team("VGK", "Golden Knights"), team("EDM", "Oilers"),
        listOf(TvBroadcast("TNT", Market.NATIONAL, "US"), TvBroadcast("SNW", Market.HOME, "CA"), TvBroadcast("SNP", Market.AWAY, "CA")),
    )
    private val mtlTor = game("2026020386", team("MTL", "Canadiens"), team("TOR", "Maple Leafs"))

    @Before
    fun setUp() = runBlocking {
        db = TestDb.create()
        val g = TestGraph(db)
        dir = File(TestDb.context.cacheDir, "hub-event-${System.nanoTime()}")
        store = EpgStoreImpl(dir, AndroidSQLiteDriver(), { Long.MAX_VALUE }, Xml::newPullParser, { nowMs }, forceFts4 = true)
        gg = GuideGraph(g, store)
        p1 = db.newPlaylist("Main")
        val src = db.newSource("xmltv")
        val teams = db.newGroup(p1, "g:t", "Sports | NHL Teams")
        val teams2 = db.newGroup(p1, "g:t2", "Sports | NHL Teams (2)")
        val nhl = db.newGroup(p1, "g:n", "Sports | NHL")
        val nhl2 = db.newGroup(p1, "g:n2", "Sports | NHL (2)")
        val nhl3 = db.newGroup(p1, "g:n3", "Sports | NHL (3)")
        val snplus = db.newGroup(p1, "g:s", "Sports | Sportsnet+")
        val gp = db.newGroup(p1, "g:gp", "Sports | NHL Game Pass")
        val ca = db.newGroup(p1, "g:ca", "CA | Sports")
        val nba = db.newGroup(p1, "g:nba", "Sports | NBA Teams")
        var sort = 0
        suspend fun ch(key: String, name: String, group: Long? = null, epg: String? = null, tvgId: String? = null, short: String? = null): Long {
            val id = db.newChannel(p1, key, name, groupId = group, sortIndex = sort++, tvgId = tvgId)
            if (epg != null) db.match(p1, key, src, epg)
            if (short != null) db.exec("UPDATE channel SET display_name = ? WHERE id = ?", short, id)
            ids[key] = id
            return id
        }
        // Team channels (§8.2): EDM twice (a backup group), VGK, MTL; TOR by its tvg id; never "Sportsnet Oilers" or an NBA club.
        ch("eo", "Edmonton Oilers", teams)
        ch("vgk", "Vegas Golden Knights", teams)
        ch("mtl", "Montreal Canadiens", teams)
        ch("eo2", "Edmonton Oilers", teams2)
        ch("tor", "Toronto Maple Leafs", nhl, tvgId = "TorontoMapleLeafs.nhl")
        ch("sno", "Sportsnet Oilers", ca)
        ch("pistons", "Detroit Pistons", nba)
        // Broadcasters: national TNT (LIKELY), regional SN West with a listing (CONFIRMED) and SN Pacific without (absent).
        ch("tnt", "US: TNT")
        ch("snw", "CA| SPORTSNET WEST", ca, epg = "snw")
        ch("snp", "CA| SPORTSNET PACIFIC", ca, epg = "snp")
        // A provider-linked event slot whose guide holds only a "Next Event:" placeholder naming both teams.
        ch("boss", "ESPN+ 01: Long Beach State vs. Arkansas State @ Nov 24 3:30PM ET", epg = "boss", short = "ESPN+ 01")
        // Event channels (§8.3), shown by their short names.
        ch("ev1", "NHL 01: Golden Knights vs. Oilers (11.24 8:00 PM ET)", nhl, short = "NHL 01")
        ch("ev2", "NHL 01: NHL on TNT: Golden Knights at Oilers @ 24 Nov 07:30 PM ET", nhl2, short = "NHL 01")
        ch("ev1dup", "NHL 01: Golden Knights vs. Oilers (11.24 8:00 PM ET)", nhl3, short = "NHL 01")
        ch("ev3", "Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET", snplus, short = "Sportsnet+ 09")
        ch("decoy", "Sportsnet+ 08: Detroit @ Orlando @ 24 Nov 08:00 PM ET", snplus, short = "Sportsnet+ 08")
        ch("ev5", "NHL GP 01 | Golden Knights @ Oilers (2026-11-24 20:00:00)", gp, short = "NHL GP 01")
        ch("evhid", "NHL 02: Golden Knights vs. Oilers (11.24 8:00 PM ET)", nhl, short = "NHL 02")
        ch("ev7", "NHL 05: Canadiens vs. Maple Leafs (11.24 8:00 PM ET)", nhl, short = "NHL 05")
        db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, updated_at) VALUES (?, 'evhid', 1, 1)", p1)
        // Nine guide listings of MTL @ TOR: more than the cap.
        for (i in 0 until 9) ch("gl$i", "CA| GUIDE $i", ca, epg = "gl$i")

        val xml = EpgTestDb.xmltv {
            for (id in listOf("snw", "snp", "boss") + (0 until 9).map { "gl$it" }) channel(id, id)
            programme("snw", s - 15, s + 180, "NHL Hockey: Golden Knights at Oilers")
            programme("snp", s - 15, s + 180, "Sportsnet Central")
            programme("boss", s - 60, s + 180, "Next Event: Golden Knights at Oilers at 08:00PM EST on Nov 24")
            for (i in 0 until 9) programme("gl$i", s - 15, s + 180, "NHL Hockey: Canadiens at Maple Leafs")
        }.writeTo(File(dir.parentFile, "hub-event-${System.nanoTime()}.xml"))
        store.build(EpgBuildRequest(listOf(EpgSourceFile(src, xml)), nowMs, pastDays = 7, futureDays = 7), EpgWhitelistResolver { _, _ -> EpgWhitelist.ALL })

        fun ev(key: String, title: String, start: Int, group: String, slot: String, zone: EventZoneSource = EventZoneSource.EXPLICIT, name: String) =
            FakeEventIndex.event(ids.getValue(key), title, start, playlistId = p1, slot = slot, group = group, zone = zone, name = name)
        entries += ev("ev1", "Golden Knights vs. Oilers", s, "Sports | NHL", "NHL 01", name = "NHL 01: Golden Knights vs. Oilers (11.24 8:00 PM ET)")
        entries += ev("ev2", "NHL on TNT: Golden Knights at Oilers", s - 30, "Sports | NHL (2)", "NHL 01", name = "NHL 01: NHL on TNT: Golden Knights at Oilers @ 24 Nov 07:30 PM ET")
        entries += ev("ev1dup", "Golden Knights vs. Oilers", s, "Sports | NHL (3)", "NHL 01", name = "NHL 01: Golden Knights vs. Oilers (11.24 8:00 PM ET)")
        entries += ev("ev3", "Vegas @ Edmonton", s, "Sports | Sportsnet+", "Sportsnet+ 09", name = "Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET")
        entries += ev("decoy", "Detroit @ Orlando", s, "Sports | Sportsnet+", "Sportsnet+ 08", name = "Sportsnet+ 08: Detroit @ Orlando @ 24 Nov 08:00 PM ET")
        entries += ev("ev5", "Golden Knights @ Oilers", s, "Sports | NHL Game Pass", "NHL GP 01", EventZoneSource.ASSUMED, name = "NHL GP 01 | Golden Knights @ Oilers (2026-11-24 20:00:00)")
        entries += ev("evhid", "Golden Knights vs. Oilers", s, "Sports | NHL", "NHL 02", name = "NHL 02: Golden Knights vs. Oilers (11.24 8:00 PM ET)")
        entries += ev("ev7", "Canadiens vs. Maple Leafs", s, "Sports | NHL", "NHL 05", name = "NHL 05: Canadiens vs. Maple Leafs (11.24 8:00 PM ET)")
        events = FakeEventIndex(entries)
        matcher = GameChannelMatcherImpl(gg.queries, store, db, events, Dispatchers.IO)
    }

    @After fun tearDown() { db.close(); dir.deleteRecursively() }

    private fun run(game: GameSnapshot, access: ParentalAccess = ParentalAccess.LOCKED_AUTO, board: List<GameSnapshot> = emptyList()) =
        runBlocking { matcher.match(game, nowMs, access, board) }

    private fun key(id: Long) = ids.entries.single { it.value == id }.key
    private fun key(c: ChannelCandidate) = key(c.channel.rowId)
    private fun byKey(list: List<ChannelCandidate>) = list.associateBy(::key)

    /** (Integration) A same-name twin in the SAME group is never "Also in <its own group>". */
    @Test fun aTwinInTheSameGroupIsNotAlsoIn() = runBlocking {
        val teams = db.strings("SELECT id FROM channel_group WHERE group_key = 'g:t'").single().toLong()
        val dup = db.newChannel(p1, "vgk2", "Vegas Golden Knights", groupId = teams, sortIndex = 999)
        val out = GameChannelMatcherImpl(gg.queries, store, db, events, Dispatchers.IO).match(vgkEdm, nowMs, ParentalAccess.LOCKED_AUTO, emptyList())
        val vgk = out.single { it.channel.rowId == ids.getValue("vgk") || it.channel.rowId == dup }
        assertEquals(emptyList<Long>(), vgk.variants.map { it.rowId }.filter { it == dup || it == ids.getValue("vgk") })
        assertEquals("Sports | NHL Teams", vgk.group)
        // The other-group twin still reads "Also in".
        assertEquals("Sports | NHL Teams (2)", out.single { it.channel.rowId == ids.getValue("eo") }.group)
    }

    @Test fun theFeaturedGameInRankOrder() {
        val out = run(vgkEdm)
        val keys = out.map(::key)
        // Team channels first, the home side by default; then the guide listing; then the CONFIRMED event names; then LIKELY.
        assertEquals(listOf("eo", "vgk"), keys.take(2))
        assertEquals("snw", keys[2])
        assertEquals(setOf("ev1", "ev2", "ev3"), keys.subList(3, 6).toSet())
        assertEquals(setOf("tnt", "ev5"), keys.drop(6).toSet())
        for (absent in listOf("snp", "boss", "sno", "pistons", "decoy", "evhid", "ev1dup", "eo2", "mtl", "tor")) assertFalse(absent, absent in keys)

        val m = byKey(out)
        val eo = m.getValue("eo")
        assertEquals(MatchConfidence.CONFIRMED, eo.confidence)
        assertEquals(MatchReason.TeamChannel("Edmonton Oilers", "Oilers", Side.HOME), eo.reasons.first())
        assertEquals("the backup group's twin folds in", listOf("eo2"), eo.variants.map { key(it.rowId) })
        assertEquals("Also in: the twin's group", "Sports | NHL Teams (2)", eo.group)
        assertEquals(MatchReason.TeamChannel("Vegas Golden Knights", "Golden Knights", Side.AWAY), m.getValue("vgk").reasons.first())
        assertEquals("Sports | NHL Teams", m.getValue("vgk").group)

        // §8.1: the regional alias shows only on its guide's evidence.
        val snw = m.getValue("snw")
        assertEquals(MatchConfidence.CONFIRMED, snw.confidence)
        assertTrue(snw.reasons.first() is MatchReason.Guide)

        // E3: NHL 01 twice, two different events: two rows; the same event in a third group folds into the first.
        val ev1 = m.getValue("ev1")
        assertEquals(listOf("ev1dup"), ev1.variants.map { key(it.rowId) })
        assertEquals("Sports | NHL (3)", ev1.group)
        assertEquals(MatchReason.EventName("Golden Knights vs. Oilers", startMs, citiesOnly = false, zoneAssumed = false), ev1.reasons.single())
        assertEquals("Sports | NHL (2)", m.getValue("ev2").group)
        assertEquals((s - 30) * 60_000L, (m.getValue("ev2").reasons.single() as MatchReason.EventName).startMs)
        val ev3 = m.getValue("ev3")
        assertEquals(MatchConfidence.CONFIRMED, ev3.confidence)
        assertTrue("cities only", (ev3.reasons.single() as MatchReason.EventName).citiesOnly)
        val ev5 = m.getValue("ev5")
        assertEquals(MatchConfidence.LIKELY, ev5.confidence)
        assertTrue("zone assumed", (ev5.reasons.single() as MatchReason.EventName).zoneAssumed)
        assertEquals(MatchConfidence.LIKELY, m.getValue("tnt").confidence)
        assertTrue(out.none { it.channel.displayName == "NHL 02" })
    }

    @Test fun aPlaceholderIsNoEvidenceButARealListingIs() = runBlocking {
        assertNull(byKey(run(vgkEdm))["boss"])
        // Without the regional broadcast or a listing, Sportsnet West is not a candidate (no "local channel" guess).
        val noListing = vgkEdm.copy(id = GameId(League.NHL, "x2"), broadcasts = listOf(TvBroadcast("SNP", Market.AWAY, "CA")))
        assertNull(byKey(run(noListing))["snp"])
    }

    @Test fun theUsersTeamComesFirst() = runBlocking {
        // A favourite VGK channel puts the away side first.
        val list = db.favouriteDao().insertList(FavouriteListEntity(name = "Fav", sortIndex = 0, createdAt = 0))
        db.favouriteDao().insert(FavouriteEntity(list, p1, "vgk", 0, 0))
        assertEquals(listOf("vgk", "eo"), run(vgkEdm).take(2).map(::key))
        // Without a favourite, the more-watched side.
        db.exec("DELETE FROM favourite")
        db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, watch_count, updated_at) VALUES (?, 'vgk', 7, 1)", p1)
        matcher.clear()
        assertEquals(listOf("vgk", "eo"), run(vgkEdm).take(2).map(::key))
        db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, watch_count, updated_at) VALUES (?, 'eo2', 9, 1)", p1)
        matcher.clear()
        assertEquals("a twin's watch count counts for its side", listOf("eo2", "vgk"), run(vgkEdm).take(2).map(::key))
    }

    @Test fun teamChannelsAreNeverCappedAndOneEventRowIsKept() {
        val out = run(mtlTor)
        val keys = out.map(::key)
        assertEquals(listOf("tor", "mtl"), keys.take(2))
        assertEquals("2 team channels + 8", 10, out.size)
        assertEquals("the 8th row is the event", "ev7", keys.last())
        assertEquals(7, keys.count { it.startsWith("gl") })
        assertEquals(MatchConfidence.CONFIRMED, out.last().confidence)
    }

    @Test fun lockedAndHiddenFollowTheAccess() = runBlocking {
        db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, locked, updated_at) VALUES (?, 'vgk', 0, 1, 1)", p1)
        db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, locked, updated_at) VALUES (?, 'ev3', 0, 1, 1)", p1)
        val locked = byKey(run(vgkEdm, ParentalAccess.LOCKED_AUTO))
        assertNull(locked["vgk"]); assertNull(locked["ev3"])
        val open = byKey(run(vgkEdm, ParentalAccess.OPEN))
        assertNotNull(open["vgk"]); assertNotNull(open["ev3"])
        for (a in ParentalAccess.entries) assertNull(byKey(run(vgkEdm, a))["evhid"])
    }

    @Test fun theBoardBlocksTheCitiesOnlyUpgrade() {
        // Another Edmonton game within 3 h on the board: "Vegas @ Edmonton" is LIKELY, not CONFIRMED (§8.4).
        val other = game("2026020399", team("EDM", "Oilers"), team("SEA", "Kraken"), start = startMs + 60 * 60_000L)
        val ev3 = byKey(run(vgkEdm, board = listOf(vgkEdm, other))).getValue("ev3")
        assertEquals(MatchConfidence.LIKELY, ev3.confidence)
    }

    @Test fun otherLeaguesRunNoTeamOrEventStage() {
        val ohl = game("ohl1", team("KIT", "Rangers"), team("LDN", "Knights"), league = League.OHL)
        assertEquals(emptyList<ChannelCandidate>(), run(ohl))
        assertFalse(matcher.lastStats!!.teamQueried)
        assertEquals(0, matcher.lastStats!!.eventHits)
    }

    @Test fun theCacheKeyFollowsTheIndexTheLibraryAndTheBoard() = runBlocking {
        run(vgkEdm)
        val n = matcher.computations
        assertTrue(matcher.lastStats!!.teamQueried)
        run(vgkEdm)
        assertEquals("cached", n, matcher.computations)
        events.put(entries.filter { key(it.channelId) != "ev3" })
        assertNull("a new index version reads again", byKey(run(vgkEdm))["ev3"])
        assertEquals(n + 1, matcher.computations)
        assertFalse("the team map is cached within the library state", matcher.lastStats!!.teamQueried)
        db.exec("UPDATE playlist SET refresh_seq = refresh_seq + 1 WHERE id = ?", p1)
        run(vgkEdm)
        assertEquals("a refresh reads again", n + 2, matcher.computations)
        assertTrue("and re-reads the team map", matcher.lastStats!!.teamQueried)
        run(vgkEdm, board = listOf(game("2026020399", team("EDM", "Oilers"), team("SEA", "Kraken"))))
        assertEquals("another board reads again", n + 3, matcher.computations)
        matcher.clear()
        run(vgkEdm, board = listOf(game("2026020399", team("EDM", "Oilers"), team("SEA", "Kraken"))))
        assertEquals(n + 4, matcher.computations)
        assertTrue("clear() drops the team maps", matcher.lastStats!!.teamQueried)
    }

    @Test fun teamChannelsAloneForTheBeyondGuideLine() = runBlocking {
        val out = matcher.teamChannels(vgkEdm, ParentalAccess.LOCKED_AUTO)
        assertEquals(listOf("eo", "vgk"), out.map(::key))
        assertTrue(out.all { c -> c.reasons.single() is MatchReason.TeamChannel })
        assertEquals(emptyList<ChannelCandidate>(), matcher.teamChannels(game("ohl2", team("KIT", "Rangers"), team("LDN", "Knights"), league = League.OHL), ParentalAccess.OPEN))
        val nfl = game("401", team("DAL", "Cowboys"), team("DEN", "Broncos"), league = League.NFL)
        assertEquals("no NFL team channels in this library", emptyList<ChannelCandidate>(), matcher.teamChannels(nfl, ParentalAccess.OPEN))
    }

    /** §8.5: `dataToMin`, else `windowToMin` (a generation built before WP-3), else null without a guide. */
    @Test fun theGuideEnd() = runBlocking {
        val built = store.info.value!!
        // (Integration) WP-3's build writes dataToMin, so the real store answers it; a pre-WP-3 generation has none.
        assertEquals(built.dataToMin!! * 60_000L, matcher.guideEndMs())
        val info = MutableStateFlow<EpgGenerationInfo?>(built.copy(dataToMin = null))
        val fake = object : EpgStore by store { override val info: StateFlow<EpgGenerationInfo?> = info }
        val m = GameChannelMatcherImpl(gg.queries, fake, db, events, Dispatchers.IO)
        assertEquals("an older generation: the window's end", built.windowToMin * 60_000L, m.guideEndMs())
        info.value = built.copy(dataToMin = s + 24 * 60)
        assertEquals((s + 24 * 60) * 60_000L, m.guideEndMs())
        assertFalse(ChannelHorizon.beyondGuide(startMs + 20 * 3_600_000L, m.guideEndMs(), nowMs))
        assertTrue(ChannelHorizon.beyondGuide(startMs + 24 * 3_600_000L, m.guideEndMs(), nowMs))
        info.value = null
        assertNull(m.guideEndMs())
        assertFalse("no guide: the 12 h floor", ChannelHorizon.beyondGuide(nowMs + 11 * 3_600_000L, m.guideEndMs(), nowMs))
        assertTrue(ChannelHorizon.beyondGuide(nowMs + 12 * 3_600_000L, m.guideEndMs(), nowMs))
    }
}
