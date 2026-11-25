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
import app.thebox.core.sports.NhlTeamPlaces
import app.thebox.core.sports.TeamLine
import app.thebox.core.sports.TvBroadcast
import app.thebox.core.sports.TeamNames
import app.thebox.data.di.WallClock
import app.thebox.data.events.EventIndexImpl
import app.thebox.data.testutil.newGroup
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.EpgMatchEntity
import app.thebox.data.guide.GuideGraph
import app.thebox.data.guide.newSource
import app.thebox.data.repo.writeTransaction
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import kotlin.random.Random

/**
 * (Sports Hub §5.7, A-N8, R-S4; WP-3. Event channels §11.2, WP-4: 14,000 channels as the owner's ~13,100 live rows,
 * 1,750 event-shaped names indexed by the real `EventIndexImpl`, 64 NHL / NFL team channels; stages T and E timed.)
 * The matcher on a synthetic 14,000-channel library (two playlists, 10,000 + 4,000; country-tagged names with
 * Sportsnet / TSN / ESPN / TNT spellings, "SN+" event channels, shape A event names and the team channels) and an
 * EPG of 8,000 channels × 7 days of 2-hour programmes (672,000 programmes, ~40 texts plus 40 NHL game titles), with
 * the final query shape (A: one FTS4 phrase query per code, `LIMIT 200`, no `ORDER BY`; C: one window read; B: two
 * live and two past EPG FTS queries; `channelsForRefs`). JVM timings on Robolectric's SQLite: an indication only; the
 * Fire TV Stick gate (p95 ≤ 250 ms) is measured on the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GameChannelMatcherBenchmarkTest {
    private lateinit var db: LibraryDatabase
    private lateinit var dir: File

    @After fun tearDown() { if (::db.isInitialized) db.close(); if (::dir.isInitialized) dir.deleteRecursively() }

    private val startMs = Instant.parse("2026-11-25T01:00:00Z").toEpochMilli()
    private val nowMs = startMs - 10 * 60_000L
    private val gameMin = (startMs / 60_000L).toInt()
    private val N = 14_000

    private val teams = NhlTeamPlaces.TABLE.keys.sorted()
    private val nick = mapOf(
        "ANA" to "Ducks", "BOS" to "Bruins", "BUF" to "Sabres", "CGY" to "Flames", "CAR" to "Hurricanes", "CHI" to "Blackhawks",
        "COL" to "Avalanche", "CBJ" to "Blue Jackets", "DAL" to "Stars", "DET" to "Red Wings", "EDM" to "Oilers", "FLA" to "Panthers",
        "LAK" to "Kings", "MIN" to "Wild", "MTL" to "Canadiens", "NSH" to "Predators", "NJD" to "Devils", "NYI" to "Islanders",
        "NYR" to "Rangers", "OTT" to "Senators", "PHI" to "Flyers", "PIT" to "Penguins", "SJS" to "Sharks", "SEA" to "Kraken",
        "STL" to "Blues", "TBL" to "Lightning", "TOR" to "Maple Leafs", "UTA" to "Mammoth", "VAN" to "Canucks", "VGK" to "Golden Knights",
        "WSH" to "Capitals", "WPG" to "Jets",
    )

    @Test fun twentyThousandChannels() = runBlocking {
        val t0 = System.nanoTime()
        db = TestDb.create()
        val g = TestGraph(db)
        dir = File(TestDb.context.cacheDir, "hub-bench-${System.nanoTime()}")
        val store = EpgStoreImpl(dir, AndroidSQLiteDriver(), { Long.MAX_VALUE }, Xml::newPullParser, { nowMs }, forceFts4 = true)
        val gg = GuideGraph(g, store)
        val p1 = db.newPlaylist("Big") { it.copy(lastRefreshAt = nowMs) }
        val p2 = db.newPlaylist("Small") { it.copy(sortIndex = 1, lastRefreshAt = nowMs) }
        val nhlTeams = db.newGroup(p1, "g:nhl", "Sports | NHL Teams")
        val nflTeams = db.newGroup(p1, "g:nfl", "Sports | NFL Teams")
        val evGroup = db.newGroup(p1, "g:ev", "Sports | Sportsnet+")
        val src = db.newSource("xmltv")
        val rnd = Random(20_000)

        val prefixes = listOf("CA| ", "US: ", "UK: ", "DE: ", "FR| ", "[EX-YU] ", "AR: ", "IT: ", "", "LAT: ")
        val quality = listOf("", " HD", " FHD", " 4K", " SD", " HEVC", " (backup)")
        val sports = listOf(
            "SPORTSNET ONTARIO", "SPORTSNET EAST", "SPORTSNET WEST", "SPORTSNET PACIFIC", "SPORTSNET ONE", "SPORTSNET 360", "SPORTSNET",
            "SN ONTARIO", "TSN 1", "TSN 2", "TSN 3", "TSN 4", "TSN 5", "TVA SPORTS", "TVA SPORTS 2", "RDS", "RDS 2", "ESPN", "ESPN 2", "TNT",
            "TNT SPORTS 1", "TNT SPORTS 2", "NHL NETWORK", "MSG", "MSG SPORTSNET", "NBC SPORTS CALIFORNIA", "ABC 7 NEW YORK", "FOX 5 WNYW",
            "CBC TORONTO", "CBS 2", "SPORTSNET WORLD", "SKY SPORTS MAIN EVENT", "BEIN SPORTS 1", "DAZN 1",
        )
        val generic = listOf("News", "Movies", "Kids", "Music", "Docs", "Cinema", "Drama", "Comedy", "Nature", "History", "Series", "Local")
        data class Row(val pl: Long, val key: String, val name: String, val epg: String?, val group: Long? = null)
        val rows = ArrayList<Row>(N)
        // Shape A event names over the evening of Nov 24 ET (the game is 8:00 PM ET); a few name VGK @ EDM.
        val places = teams.map { NhlTeamPlaces.of(it)!! }
        val clocks = listOf("06:00 PM", "06:30 PM", "07:00 PM", "07:30 PM", "08:00 PM", "08:30 PM", "09:00 PM", "10:00 PM", "11:00 PM")
        for (i in 0 until N) {
            val pl = if (i < N * 5 / 7) p1 else p2
            val name = when {
                i % 8 == 3 -> {
                    val k = i / 8
                    val (a, h) = if (k % 250 == 0) "Vegas" to "Edmonton" else places[k % 32] to places[(k * 7 + 5) % 32]
                    "Sportsnet+ %02d: %s @ %s @ 24 Nov %s ET".format(k % 100, a, h, if (k % 250 == 0) "08:00 PM" else clocks[k % clocks.size])
                }
                i % 33 == 0 -> prefixes[rnd.nextInt(prefixes.size)] + sports[rnd.nextInt(sports.size)] + quality[rnd.nextInt(quality.size)]
                i % 67 == 1 -> "CA| SN+ %02d".format(i % 100) // event channels
                else -> prefixes[rnd.nextInt(prefixes.size)] + generic[rnd.nextInt(generic.size)] + " " + i + quality[rnd.nextInt(quality.size)]
            }
            rows += Row(pl, "k$i", name, if (i % 5 < 2 && i % 8 != 3) "e${i % 8_000}" else null, if (i % 8 == 3) evGroup else null)
        }
        // 64 team channels (§8.2).
        TeamNames.all(League.NHL).forEachIndexed { k, t -> rows += Row(p1, "nhl$k", t.fullNames.first(), null, nhlTeams) }
        TeamNames.all(League.NFL).forEachIndexed { k, t -> rows += Row(p1, "nfl$k", t.fullNames.first(), null, nflTeams) }
        db.writeTransaction {
            usePrepared(
                "INSERT INTO channel(playlist_id, stable_key, key_rule, kind, name, norm_name, sort_index, url, is_adult, content_hash, missing_count, first_seen_at, updated_at, group_id) " +
                    "VALUES (?, ?, 'URL', 0, ?, ?, ?, ?, 0, ?, 0, 1, 1, ?)",
            ) { st ->
                rows.forEachIndexed { i, r ->
                    st.reset(); st.clearBindings()
                    st.bindLong(1, r.pl); st.bindText(2, r.key); st.bindText(3, r.name); st.bindText(4, r.name.lowercase()); st.bindLong(5, i.toLong())
                    st.bindText(6, "http://h/${r.key}"); st.bindLong(7, r.key.hashCode().toLong())
                    if (r.group == null) st.bindNull(8) else st.bindLong(8, r.group)
                    st.step()
                }
            }
            usePrepared("INSERT INTO channel_fts(rowid, name) SELECT id, name FROM channel") { it.step() }
            usePrepared("UPDATE channel SET catchup_type = 'shift', catchup_days = 3 WHERE id % 7 = 0") { it.step() }
        }
        db.epgMatchDao().insertAll(rows.filter { it.epg != null }.map { EpgMatchEntity(it.pl, it.key, src, it.epg!!, 0, "EXACT_ID", 1f, true, 0L, 1L) })

        // EPG: 8,000 channels × 7 days of 2-hour programmes.
        val s = (startMs / 60_000L).toInt()
        val titles = listOf("Morning News", "Evening News", "Quiz Time", "Documentary", "Match Day", "Late Movie", "Cooking", "Weather",
            "Talk Show", "Nature Wild", "Kids Cartoon Hour", "Music Mix", "Drama Series", "NHL Tonight", "Hockey Central", "NHL Hockey",
            "SportsCentre", "Hockey Night in Canada", "NFL Football", "Premier League Football")
        val games = (0 until 40).map { k -> "NHL Hockey: ${nick.getValue(teams[k % 32])} at ${nick.getValue(teams[(k * 7 + 3) % 32])}" } +
            "NHL Hockey: Golden Knights at Oilers"
        val xml = File(dir.parentFile, "hub-bench-${System.nanoTime()}.xml")
        xml.bufferedWriter().use { w ->
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
            for (c in 0 until 8_000) w.write("<channel id=\"e$c\"><display-name>e$c</display-name></channel>\n")
            for (c in 0 until 8_000) {
                var m = s - 3 * 1440
                var k = c
                while (m < s + 4 * 1440) {
                    val title = when {
                        c % 50 == 0 && m == s - 60 -> games.last()
                        k % 23 == 0 -> games[k % games.size]
                        else -> titles[k % titles.size]
                    }
                    w.write("<programme start=\"${EpgTestDb.xmltvTime(m)}\" stop=\"${EpgTestDb.xmltvTime(m + 120)}\" channel=\"e$c\"><title>$title</title></programme>\n")
                    m += 120; k += 7
                }
            }
            w.write("</tv>\n")
        }
        store.build(EpgBuildRequest(listOf(EpgSourceFile(src, xml)), nowMs, pastDays = 7, futureDays = 7), EpgWhitelistResolver { _, _ -> EpgWhitelist.ALL })
        val setupMs = (System.nanoTime() - t0) / 1_000_000

        val index = EventIndexImpl(db, db.playlistDao(), Dispatchers.IO, WallClock { nowMs })
        val ti = System.nanoTime()
        val snap = index.current()
        val indexMs = (System.nanoTime() - ti) / 1e6
        val indexed = index.lastBuild
        val matcher = GameChannelMatcherImpl(gg.queries, store, db, index, Dispatchers.IO)
        fun gm(id: String, a: String, h: String, bs: List<TvBroadcast>, start: Long = startMs, phase: GamePhase = GamePhase.LIVE) =
            GameSnapshot(GameId(League.NHL, id), "", start, phase, TeamLine(a, nick.getValue(a), null, null), TeamLine(h, nick.getValue(h), null, null), null, null, null, null, null, broadcasts = bs)
        val n = Market.NATIONAL
        val slate = listOf(
            gm("385", "VGK", "EDM", listOf(TvBroadcast("TNT", n, "US"), TvBroadcast("HBO MAX", n, "US"), TvBroadcast("SN", n, "CA"), TvBroadcast("TVAS", n, "CA"))),
            gm("384", "MIN", "SJS", listOf(TvBroadcast("SN", n, "CA"), TvBroadcast("SN360", n, "CA"), TvBroadcast("Wild+", Market.AWAY, "US"), TvBroadcast("NBCSCA", Market.HOME, "US"))),
            gm("378", "CBJ", "NJD", listOf(TvBroadcast("NHLN", n, "US"), TvBroadcast("CBJHN", Market.AWAY, "US"), TvBroadcast("MSG", Market.HOME, "US"))),
            gm("382", "UTA", "WPG", listOf(TvBroadcast("SN+", n, "CA"), TvBroadcast("Mammoth+", Market.AWAY, "US"), TvBroadcast("Utah16", Market.AWAY, "US")),
                start = startMs - 86_400_000L, phase = GamePhase.FINAL),
            gm("400", "MTL", "TOR", listOf(TvBroadcast("SN", n, "CA"), TvBroadcast("TSN4", Market.HOME, "CA"), TvBroadcast("RDS", Market.AWAY, "CA")),
                start = startMs + 2 * 86_400_000L, phase = GamePhase.SCHEDULED),
        )
        repeat(3) { for (gme in slate) { matcher.clear(); matcher.match(gme, nowMs, ParentalAccess.LOCKED_AUTO) } } // warm-up
        val totals = ArrayList<Double>()
        val stages = ArrayList<GameChannelMatcherImpl.Stats>()
        val found = LinkedHashMap<String, Int>()
        val lastOut = HashMap<String, List<app.thebox.core.sports.hub.ChannelCandidate>>()
        repeat(12) {
            for (gme in slate) {
                matcher.clear()
                val t = System.nanoTime()
                val out = matcher.match(gme, nowMs, ParentalAccess.LOCKED_AUTO)
                totals += (System.nanoTime() - t) / 1e6
                stages += matcher.lastStats!!
                found[gme.id.id] = out.size
                lastOut[gme.id.id] = out
            }
        }
        // Stage T within a page session: the first game reads the team map, the others use it (clear() only on page open).
        val teamWarm = ArrayList<Double>()
        repeat(12) {
            matcher.clear()
            for (gme in slate) {
                matcher.match(gme, nowMs, ParentalAccess.LOCKED_AUTO)
                val st = matcher.lastStats!!
                if (!st.teamQueried && gme.id.league == League.NHL) teamWarm += st.teamNs / 1e6
            }
        }
        val teamCold = stages.filter { it.teamQueried }.map { it.teamNs / 1e6 }
        fun pct(xs: List<Double>, p: Double): Double = xs.sorted()[((xs.size - 1) * p).toInt()]
        fun ms(f: (GameChannelMatcherImpl.Stats) -> Long) = stages.map { f(it) / 1e6 }
        val report = buildString {
            append("GameChannelMatcher benchmark: ${rows.size} channels, 672000 programmes, setup ${setupMs} ms, ${totals.size} runs\n")
            append("  event index cold build %.1f ms (%s); events in the game window %d\n".format(indexMs, indexed, snap.startingIn(gameMin - 90, gameMin + 45).size))
            append("  total  p50 %.1f ms  p95 %.1f ms  max %.1f ms\n".format(pct(totals, 0.5), pct(totals, 0.95), totals.max()))
            append("  A alias  p50 %.1f  p95 %.1f | C window p50 %.1f p95 %.1f | B guide p50 %.1f p95 %.1f | rank p50 %.1f p95 %.1f\n".format(
                pct(ms { it.aliasNs }, 0.5), pct(ms { it.aliasNs }, 0.95), pct(ms { it.windowNs }, 0.5), pct(ms { it.windowNs }, 0.95),
                pct(ms { it.guideNs }, 0.5), pct(ms { it.guideNs }, 0.95), pct(ms { it.rankNs }, 0.5), pct(ms { it.rankNs }, 0.95)))
            append("  T team cold p50 %.1f p95 %.1f max %.1f | T team cached p50 %.1f p95 %.1f | E event p50 %.1f p95 %.1f\n".format(
                pct(teamCold, 0.5), pct(teamCold, 0.95), teamCold.max(), pct(teamWarm, 0.5), pct(teamWarm, 0.95),
                pct(ms { it.eventNs }, 0.5), pct(ms { it.eventNs }, 0.95)))
            append("  VGK @ EDM: ${lastOut.getValue("385").joinToString { it.channel.displayName.take(24) + "/" + it.confidence.name.take(4) }}\n")
            append("  candidates per game $found; FTS queries per game ${slate.indices.map { stages[it].ftsQueries }}, EPG searches ${slate.indices.map { stages[it].epgSearches }}\n")
        }
        println(report)
        File(System.getProperty("java.io.tmpdir"), "hub-matcher-benchmark.txt").writeText(report)
        assertTrue("VGK @ EDM finds its channels", (found["385"] ?: 0) > 0)
        val vgk = lastOut.getValue("385")
        assertTrue("both team channels lead", vgk.take(2).all { c -> c.reasons.first() is app.thebox.core.sports.hub.MatchReason.TeamChannel })
        assertTrue("the Vegas @ Edmonton event row", vgk.any { c -> c.reasons.any { it is app.thebox.core.sports.hub.MatchReason.EventName } })
        // (Sports Hub code review 2026-11-25, L5) Was `size <= 8`, always true under the old cap of 8 (removed by the owner rulings of 2026-11-25). Guide-confirmed
        // candidates are legitimate for an SN+-only game; a broadcaster reason is not.
        assertTrue("the SN+-only game ran", "382" in lastOut)
        val snPlusOnly = lastOut.getValue("382")
        assertTrue(
            "SN+-only game: no alias candidate, got ${snPlusOnly.map { it.reasons }}",
            snPlusOnly.none { c -> c.reasons.any { it is app.thebox.core.sports.hub.MatchReason.Broadcaster } },
        )
        assertTrue("JVM sanity bound (not the device gate): p95 ${pct(totals, 0.95)} ms", pct(totals, 0.95) < 3_000)
    }
}
