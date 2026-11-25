package app.thebox.data.sports

import android.util.Xml
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.thebox.core.epg.EpgBuildRequest
import app.thebox.core.epg.EpgSourceFile
import app.thebox.core.epg.EpgWhitelist
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.epg.store.EpgStoreImpl
import app.thebox.core.epg.testutil.EpgTestDb
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.Market
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.TeamLine
import app.thebox.core.sports.TvBroadcast
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.HubFixtures
import app.thebox.core.sports.hub.MatchConfidence
import app.thebox.core.sports.hub.MatchReason
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.guide.GuideGraph
import app.thebox.data.guide.exec
import app.thebox.data.guide.match
import app.thebox.data.guide.newSource
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * docs/design/m6-sports-hub.md §5.2 – §5.4, §10.2 `GameChannelMatcherTest`: in-memory Room and a real `EpgStoreImpl`
 * (Robolectric SQLite, the FTS4 path), two playlists, the fake catalog's channel names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GameChannelMatcherTest {
    private lateinit var db: LibraryDatabase
    private lateinit var g: TestGraph
    private lateinit var gg: GuideGraph
    private lateinit var store: EpgStoreImpl
    private lateinit var dir: File
    private lateinit var matcher: GameChannelMatcherImpl

    /** VGK @ EDM, 2026020385: 2026-11-25 01:00 UTC; "now" is ten minutes before. */
    private val startMs = Instant.parse("2026-11-25T01:00:00Z").toEpochMilli()
    private val nowMs = startMs - 10 * 60_000L
    private val s = (startMs / 60_000L).toInt()
    private val ids = HashMap<String, Long>()
    private var p1 = 0L
    private var p2 = 0L

    private fun team(abbrev: String, name: String) = TeamLine(abbrev, name, null, null)
    private fun game(
        id: String, away: TeamLine, home: TeamLine, startMs: Long = this.startMs, phase: GamePhase = GamePhase.SCHEDULED,
        broadcasts: List<TvBroadcast> = emptyList(), league: League = League.NHL,
    ) = GameSnapshot(GameId(league, id), "", startMs, phase, away, home, null, null, null, null, null, broadcasts = broadcasts)

    private val vgkEdm = game(
        "2026020385", team("VGK", "Golden Knights"), team("EDM", "Oilers"),
        broadcasts = listOf(TvBroadcast("TNT", Market.NATIONAL, "US"), TvBroadcast("HBO MAX", Market.NATIONAL, "US"), TvBroadcast("SN", Market.NATIONAL, "CA"), TvBroadcast("TVAS", Market.NATIONAL, "CA")),
    )

    @Before
    fun setUp() = runBlocking {
        db = TestDb.create(); g = TestGraph(db)
        dir = File(TestDb.context.cacheDir, "hub-match-${System.nanoTime()}")
        store = EpgStoreImpl(dir, AndroidSQLiteDriver(), { Long.MAX_VALUE }, Xml::newPullParser, { nowMs }, forceFts4 = true)
        gg = GuideGraph(g, store)
        p1 = db.newPlaylist("Main")
        p2 = db.newPlaylist("Second") { it.copy(sortIndex = 1) }
        val src = db.newSource("xmltv")
        var sort = 0
        suspend fun ch(key: String, name: String, epg: String? = null, playlist: Long = p1, archiveDays: Int = 0): Long {
            val id = db.newChannel(playlist, key, name, sortIndex = sort++)
            if (epg != null) db.match(playlist, key, src, epg)
            if (archiveDays > 0) db.exec("UPDATE channel SET catchup_type = 'shift', catchup_days = ? WHERE id = ?", archiveDays, id)
            ids[key] = id
            return id
        }
        // The fake catalog's channels (§10.3).
        ch("son", "CA| SPORTSNET ONTARIO HD", "sno", archiveDays = 3)
        ch("sonf", "CA| SN ONTARIO FHD", "sno")
        ch("son2", "SN - Ontario")
        ch("s360", "CA| SPORTSNET 360", "s360")
        ch("tvas", "CA| TVA SPORTS", "tvas")
        ch("tnt", "US: TNT")
        ch("tntuk", "UK: TNT SPORTS 1", "tntuk")
        ch("abc7", "US: ABC 7 NEW YORK", "abc7")
        ch("espn2", "US: ESPN 2")
        ch("snw", "CA| SPORTSNET WEST", "snw")
        ch("nhln", "US: NHL NETWORK", "nhln")
        db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, updated_at) VALUES (?, 'snw', 1, 1)", p1)
        db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, locked, updated_at) VALUES (?, 'nhln', 0, 1, 1)", p1)
        // A conflicting listing, the R-S4 window cases, a nickname collision.
        ch("sne", "CA| SPORTSNET EAST", "sne")
        ch("snp", "CA| SPORTSNET PACIFIC", "snp")
        ch("sn1", "CA| SPORTSNET ONE", "sn1")
        ch("jx", "US: SPORTS EXTRA", "jx")
        ch("mx", "CA| HOCKEY CENTRAL", "mx")
        // 12 quality variants of one regional feed in each playlist (24 airings of one text).
        val suffix = listOf("HD", "FHD", "4K", "UHD", "HEVC", "SD", "RAW", "720p", "1080p", "50fps", "60fps", "(backup)")
        for ((pl, tag) in listOf(p1 to "a", p2 to "b")) suffix.forEachIndexed { i, q -> ch("rs$tag$i", "US: Regional Sports $q", "rs$tag$i", playlist = pl) }
        // The second playlist's own Sportsnet Ontario.
        ch("p2son", "CA| SPORTSNET ONTARIO HD", "p2sno", playlist = p2)

        val xml = EpgTestDb.xmltv {
            for (id in listOf("sno", "s360", "tvas", "tntuk", "abc7", "snw", "nhln", "sne", "snp", "sn1", "jx", "mx", "p2sno")) channel(id, id)
            programme("sno", s - 15, s + 180, "NHL Hockey: Golden Knights at Oilers")
            programme("p2sno", s - 15, s + 180, "NHL Hockey: Golden Knights at Oilers")
            programme("snw", s - 15, s + 180, "NHL Hockey: Golden Knights at Oilers") // hidden: never found
            programme("nhln", s - 15, s + 180, "NHL Hockey: Golden Knights at Oilers") // locked: only under OPEN
            programme("tvas", s - 30, s + 180, "Hockey LNH") // a sport listing on a channel A matches (R-N4)
            programme("sne", s - 30, s + 180, "NHL Hockey: Flames at Canucks") // covers the start, two other teams
            programme("s360", s, s + 180, "Hockey Central") // SN360 is not listed for this game
            programme("tntuk", s, s + 180, "Premier League Football")
            programme("abc7", s - 60, s + 120, "Eyewitness News")
            programme("abc7", s + 150, s + 330, "NHL Hockey: Kings at Ducks") // LAK @ ANA on ABC (guide only)
            // R-S4: 100 "NHL Tonight" airings from now, then a game three days ahead on the same channel.
            for (k in 0 until 100) programme("snp", s - 10 + k * 30, s - 10 + k * 30 + 30, "NHL Tonight")
            programme("snp", s + 3 * 1440, s + 3 * 1440 + 180, "NHL Hockey")
            programme("sn1", s + 180, s + 360, "NHL Hockey") // tonight 23:00 ET (04:00 UTC)
            programme("mx", s, s + 180, "Hockey Night in Canada", subtitle = "Golden Knights at Oilers") // teams in the subtitle only
            programme("jx", s, s + 180, "NFL Football: Jets at Bills") // nickname collision for UTA @ WPG
            programme("jx", s + 200, s + 380, "NHL: Mammoth at Jets")
            for (tag in listOf("a", "b")) for (i in 0 until 12) {
                channel("rs$tag$i", "rs$tag$i")
                programme("rs$tag$i", s, s + 180, "NHL Hockey: Bruins at Maple Leafs")
            }
            // Yesterday's SEA @ VGK: in Sportsnet Ontario's 3-day archive; Sportsnet 360 has no archive.
            programme("sno", s - 1440, s - 1440 + 180, "NHL Hockey: Kraken at Golden Knights")
            programme("s360", s - 1440, s - 1440 + 180, "NHL Hockey: Kraken at Golden Knights")
        }.writeTo(File(dir.parentFile, "hub-match-${System.nanoTime()}.xml"))
        store.build(EpgBuildRequest(listOf(EpgSourceFile(src, xml)), nowMs, pastDays = 7, futureDays = 7), EpgWhitelistResolver { _, _ -> EpgWhitelist.ALL })
        matcher = GameChannelMatcherImpl(gg.queries, store, db, app.thebox.core.events.FakeEventIndex(), Dispatchers.IO)
    }

    @After fun tearDown() { db.close(); dir.deleteRecursively() }

    private fun run(game: GameSnapshot, access: ParentalAccess = ParentalAccess.LOCKED_AUTO, now: Long = nowMs) =
        runBlocking { matcher.match(game, now, access) }

    private fun key(c: ChannelCandidate) = ids.entries.single { it.value == c.channel.rowId }.key
    private fun byKey(list: List<ChannelCandidate>) = list.associateBy(::key)

    /** Emulator check 9 in JVM form (§10.4, R-N4, R-S3). */
    @Test fun featuredGame() {
        val out = run(vgkEdm)
        val m = byKey(out)
        val son = m.getValue("son")
        assertEquals(MatchConfidence.CONFIRMED, son.confidence)
        assertEquals("Main", son.playlistName)
        assertTrue(son.reasons.first() is MatchReason.Guide)
        assertTrue(son.reasons.any { it is MatchReason.Broadcaster && it.network == "SN" })
        assertEquals("NHL Hockey: Golden Knights at Oilers", son.airing?.title)
        // (Owner rulings 2026-11-25) The other feeds of Sportsnet Ontario are rows right under it, in provider order.
        assertTrue(son.variants.isEmpty())
        assertEquals("the first candidate", "son", key(out.first()))
        assertEquals(listOf("sonf", "son2"), out.subList(1, 3).map(::key))
        assertTrue(out.subList(1, 3).all { it.anotherFeed })

        val tvas = m.getValue("tvas")
        assertEquals(MatchConfidence.LIKELY, tvas.confidence)
        assertTrue(tvas.reasons.first() is MatchReason.SportListing)
        assertEquals(MatchConfidence.LIKELY, m.getValue("tnt").confidence)
        assertTrue(m.getValue("tnt").reasons.single() is MatchReason.Broadcaster)
        assertEquals("teams in the subtitle", MatchConfidence.CONFIRMED, m.getValue("mx").confidence)
        assertEquals("Second", m.getValue("p2son").playlistName)

        for (absent in listOf("s360", "tntuk", "abc7", "espn2", "snw", "nhln", "sne")) assertFalse(absent, absent in m)
    }

    @Test fun affiliateOnlyThroughTheGuide() {
        val lakAna = game("2026020390", team("LAK", "Kings"), team("ANA", "Ducks"), startMs = startMs + 150 * 60_000L,
            broadcasts = listOf(TvBroadcast("ABC", Market.NATIONAL, "US")))
        val m = byKey(run(lakAna))
        assertEquals(MatchConfidence.CONFIRMED, m.getValue("abc7").confidence)
        assertTrue(m.getValue("abc7").reasons.none { it is MatchReason.Broadcaster })
        // Without a listing (VGK @ EDM listing ABC): nothing.
        assertNull(byKey(run(vgkEdm.copy(id = GameId(League.NHL, "x1"), broadcasts = listOf(TvBroadcast("ABC", Market.NATIONAL, "US")))))["abc7"])
    }

    /** R-S4: the window read sees a listing tonight and three days ahead, whatever the "NHL Tonight" airings before it. */
    @Test fun windowReadFindsSportListingsLaterAndDaysAhead() {
        val ahead = game("2026020400", team("MTL", "Canadiens"), team("TOR", "Maple Leafs"), startMs = startMs + 3 * 86_400_000L,
            broadcasts = listOf(TvBroadcast("SN", Market.NATIONAL, "CA")))
        val snp = byKey(run(ahead)).getValue("snp")
        assertEquals(MatchConfidence.LIKELY, snp.confidence)
        val reason = snp.reasons.first() as MatchReason.SportListing
        assertEquals("NHL Hockey", reason.title)
        assertEquals(ahead.startUtcMs, reason.startMs)

        val tonight = game("2026020401", team("CGY", "Flames"), team("VAN", "Canucks"), startMs = startMs + 180 * 60_000L,
            broadcasts = listOf(TvBroadcast("SN1", Market.NATIONAL, "CA")))
        val sn1 = byKey(run(tonight)).getValue("sn1")
        assertEquals(MatchConfidence.LIKELY, sn1.confidence)
        assertTrue(sn1.reasons.first() is MatchReason.SportListing)
    }

    /** A-B3: the recorded slate's three SN+-only games produce no Sportsnet channel and no LIKELY candidate. */
    @Test fun snPlusGamesOfTheRecordedSlate() {
        val games = Json.parseToJsonElement(SportsFixtures.text(HubFixtures.SCORE_2026_11_23)).jsonObject["games"]!!.jsonArray.associate { e ->
            val o = e.jsonObject
            fun t(k: String) = o[k]!!.jsonObject.let { x -> team(x["abbrev"]!!.jsonPrimitive.content, x["name"]!!.jsonObject["default"]!!.jsonPrimitive.content) }
            val bs = o["tvBroadcasts"]!!.jsonArray.map { x ->
                val b = x.jsonObject
                val market = when (b["market"]!!.jsonPrimitive.content) { "N" -> Market.NATIONAL; "H" -> Market.HOME; else -> Market.AWAY }
                TvBroadcast(b["network"]!!.jsonPrimitive.content.trim(), market, b["countryCode"]?.jsonPrimitive?.content)
            }
            val id = o["id"]!!.jsonPrimitive.content
            id to game(id, t("awayTeam"), t("homeTeam"), Instant.parse(o["startTimeUTC"]!!.jsonPrimitive.content).toEpochMilli(), GamePhase.FINAL, bs)
        }
        val sportsnet = setOf("son", "sonf", "son2", "s360", "snw", "sne", "snp", "sn1", "p2son")
        for (id in listOf("2026020379", "2026020380", "2026020382")) {
            val out = run(games.getValue(id), ParentalAccess.OPEN, now = games.getValue(id).startUtcMs + 30 * 60_000L)
            assertTrue("$id: ${out.map(::key)}", out.none { key(it) in sportsnet })
            if (id != "2026020379") assertTrue("$id: no LIKELY", out.none { it.confidence == MatchConfidence.LIKELY })
        }
        // 0379 also lists TVAS: TVA Sports is a fair LIKELY there.
        assertEquals(listOf("tvas"), run(games.getValue("2026020379"), ParentalAccess.OPEN).map(::key))
    }

    @Test fun nicknameCollision() {
        val utaWpg = game("2026020382", team("UTA", "Mammoth"), team("WPG", "Jets"), startMs = startMs + 200 * 60_000L)
        val m = byKey(run(utaWpg))
        assertEquals("NHL: Mammoth at Jets", m.getValue("jx").airing?.title)
        // The NFL listing at the collision time is not the game.
        val early = byKey(run(utaWpg.copy(startUtcMs = startMs)))
        assertNull(early["jx"]?.takeIf { it.airing?.title == "NFL Football: Jets at Bills" })
    }

    /**
     * 24 airings of one text across 2 playlists × 12 variants are kept whole (perText 40). (Owner rulings 2026-11-25) Each
     * is a row; a playlist's 12 sit together, the first with its reason and the rest as other feeds.
     */
    @Test fun everyVariantIsARowGroupedPerPlaylist() {
        val bosTor = game("2026020402", team("BOS", "Bruins"), team("TOR", "Maple Leafs"))
        val out = run(bosTor)
        assertEquals(24, out.size)
        assertEquals(List(12) { "Main" } + List(12) { "Second" }, out.map { it.playlistName })
        assertEquals(listOf(false) + List(11) { true } + listOf(false) + List(11) { true }, out.map { it.anotherFeed })
        assertTrue(out.all { it.variants.isEmpty() && it.confidence == MatchConfidence.CONFIRMED })
    }

    @Test fun lockedFollowsTheAccessAndHiddenNever() {
        val nhlnGame = vgkEdm.copy(id = GameId(League.NHL, "2026020378"), broadcasts = listOf(TvBroadcast("NHLN", Market.NATIONAL, "US")))
        assertNull(byKey(run(nhlnGame, ParentalAccess.LOCKED_AUTO))["nhln"])
        assertNull(byKey(run(nhlnGame, ParentalAccess.FILTERED_NO_PIN))["nhln"])
        val open = byKey(run(nhlnGame, ParentalAccess.OPEN))
        assertEquals(MatchConfidence.CONFIRMED, open.getValue("nhln").confidence)
        for (a in ParentalAccess.entries) assertNull(byKey(run(vgkEdm, a))["snw"])
        // The featured game under OPEN: the locked channel's guide listing confirms it too.
        assertNotNull(byKey(run(vgkEdm, ParentalAccess.OPEN))["nhln"])
    }

    @Test fun cacheKeyAndClear() {
        run(vgkEdm)
        val n = matcher.computations
        run(vgkEdm)
        assertEquals("cached", n, matcher.computations)
        run(vgkEdm, ParentalAccess.OPEN)
        assertEquals("the access is part of the key", n + 1, matcher.computations)
        // A relock between visits: clear() reads again, and a lock made meanwhile is seen.
        runBlocking { db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, locked, updated_at) VALUES (?, 'tnt', 0, 1, 1)", p1) }
        assertNotNull("still cached", byKey(run(vgkEdm))["tnt"])
        matcher.clear()
        assertNull(byKey(run(vgkEdm))["tnt"])
        assertEquals(n + 2, matcher.computations)
    }

    @Test fun finishedGameInTheArchive() {
        val seaVgk = game("2026020360", team("SEA", "Kraken"), team("VGK", "Golden Knights"), startMs = startMs - 86_400_000L, phase = GamePhase.FINAL)
        val m = byKey(run(seaVgk))
        val son = m.getValue("son")
        assertEquals(MatchConfidence.CONFIRMED, son.confidence)
        assertTrue((son.reasons.first() as MatchReason.Guide).inArchive)
        assertEquals("NHL Hockey: Kraken at Golden Knights", son.airing?.title)
        assertNull("no archive: an ended airing is not a candidate", m["s360"])
    }

    @Test fun emptyWhenNothingMatches() {
        val g = game("2026020403", team("NSH", "Predators"), team("COL", "Avalanche"), broadcasts = listOf(TvBroadcast("SN+", Market.NATIONAL, "CA"), TvBroadcast("Prime Video", Market.NATIONAL, "US")))
        assertEquals(emptyList<ChannelCandidate>(), run(g))
    }

    private fun allIds(list: List<ChannelCandidate>) = list.flatMap { c -> listOf(c.channel.rowId) + c.variants.map { it.rowId } }.toSet()

    /**
     * (Sports Hub code review 2026-11-25, M3) More than 200 channels share the "espn" token ahead of the national one (lower
     * docids, the FTS answer's first rows). The alias pages read past them, so "US: ESPN" is still found.
     */
    @Test fun theNationalChannelIsFoundBehindMoreThan200NearMisses() = runBlocking {
        val brands = listOf("BRASIL", "DEPORTES", "CARIBBEAN", "NEWS", "EXTRA", "AFRICA")
        for (i in 0 until 260) db.newChannel(p1, "near$i", "LAT: ESPN ${brands[i % brands.size]} ${i / brands.size + 1}", sortIndex = 1_000 + i)
        val espn = db.newChannel(p1, "espn", "US: ESPN", sortIndex = 2_000)
        val g = game("2026020410", team("BOS", "Bruins"), team("TOR", "Maple Leafs"), broadcasts = listOf(TvBroadcast("ESPN", Market.NATIONAL, "US")))
        val out = matcher.match(g, nowMs, ParentalAccess.LOCKED_AUTO)
        assertTrue("US: ESPN behind 260 near misses", espn in allIds(out))
        assertTrue("no near miss is a candidate", out.none { it.channel.displayName.contains("ESPN ") })
    }

    /**
     * (L3) A channel the user renamed is found by its new name, though channel_fts keeps the provider name ("CA: CH 42"
     * shares no token with any Sportsnet alias).
     */
    @Test fun aRenamedChannelIsFoundByItsCustomName() = runBlocking {
        val renamed = db.newChannel(p1, "ch42", "CA: CH 42", sortIndex = 3_000)
        db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, custom_name, updated_at) VALUES (?, 'ch42', 'Sportsnet Ontario', 2)", p1)
        val g = game("2026020411", team("MTL", "Canadiens"), team("TOR", "Maple Leafs"), broadcasts = listOf(TvBroadcast("SN", Market.NATIONAL, "CA")))
        val out = matcher.match(g, nowMs, ParentalAccess.LOCKED_AUTO)
        assertTrue("the renamed channel is a candidate or a variant", renamed in allIds(out))
    }
}
