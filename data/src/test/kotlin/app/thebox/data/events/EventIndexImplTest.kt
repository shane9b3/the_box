package app.thebox.data.events

import app.thebox.core.events.ChannelEvent
import app.thebox.core.events.EventParseContext
import app.thebox.core.events.EventShape
import app.thebox.core.events.EventZoneSource
import app.thebox.core.events.ParsedName
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.dao.PlaylistDao
import app.thebox.data.guide.exec
import app.thebox.data.repo.writeTransaction
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newGroup
import app.thebox.data.testutil.newPlaylist
import app.thebox.data.testutil.scalar
import app.thebox.data.testutil.strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * docs/design/m6-event-channels.md §11.1 `EventIndexImplTest` (§3.3, §5.2): the prefilter, missing rows, the whole-playlist
 * tally with hysteresis, ASSUMED rows re-resolved, `startingIn` / `current` / `count`, the version on `refresh_seq`, the
 * change-only playlist writes (E9) and the serialised builds.
 *
 * Independent of the real parser: [FakeEventNames] reads a tiny test grammar. **Integration (re-point):** when WP-1's
 * `EventNameParser` lands, add one case building the index with the production constructor over the design's real name
 * shapes (§11.1 rows 3, 7, 21, 32, 38) and check the learned zone and the start minutes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventIndexImplTest {
    private lateinit var db: LibraryDatabase
    private lateinit var dao: CountingDao
    private val clock = FakeClock(start = NOW)
    private val parser = FakeEventNames()

    @Before fun setUp() { db = TestDb.create(); dao = CountingDao(db.playlistDao()) }
    @After fun tearDown() { db.close() }

    private fun index(p: (String, EventParseContext) -> ParsedName = parser::parse, version: Int = 1) =
        EventIndexImpl(db, dao, Dispatchers.IO, clock, p, version)

    private suspend fun playlist(name: String = "P", serverZone: String? = null, refreshAt: Long? = NOW): Long {
        val id = db.newPlaylist(name)
        db.exec("UPDATE playlist SET server_zone = ?, last_refresh_at = ? WHERE id = ?", serverZone, refreshAt, id)
        return id
    }

    private fun minuteOf(local: String, zone: String) = (LocalDateTime.parse(local).atZone(ZoneId.of(zone)).toInstant().toEpochMilli() / 60_000).toInt()

    @Test
    fun readsOnlyLiveClockNamesOfEnabledPlaylists() = runBlocking {
        val p = playlist()
        val off = playlist("off")
        db.exec("UPDATE playlist SET enabled = 0 WHERE id = ?", off)
        val g = db.newGroup(p, "g:nhl", "Sports | NHL")
        val a = db.newChannel(p, "a", "NHL 01: Kings @ Ducks @2026-11-24T19:00 America/New_York", groupId = g)
        db.newChannel(p, "b", "Sportsnet+ 11: NO EVENT") // no clock: never read
        db.newChannel(p, "c", "Edmonton Oilers")
        db.newChannel(p, "d", "NHL 02: Old @2026-11-20T19:00 UTC", missingSince = 5L)
        db.newChannel(p, "e", "Movie: Ocean @2026-11-24T19:00 UTC", kind = 2) // VOD kind
        db.newChannel(off, "f", "NHL 03: Off @2026-11-24T19:00 UTC")
        val s = index().current()
        assertEquals(listOf("NHL 01: Kings @ Ducks @2026-11-24T19:00 America/New_York"), parser.seen)
        val e = s.of(a)!!
        assertEquals("NHL 01", e.slot)
        assertEquals("Sports | NHL", e.group)
        assertEquals("NHL 01: Kings @ Ducks @2026-11-24T19:00 America/New_York", e.name)
        assertEquals(ChannelEvent.Kind.EVENT, e.event.kind)
        assertEquals("Kings @ Ducks", e.event.title)
        assertEquals(minuteOf("2026-11-24T19:00", "America/New_York"), e.event.startMin)
        assertEquals(1, s.count(p))
        assertEquals(0, s.count(off))
    }

    @Test
    fun anEmptyParseIsKeptForOfButNotCountedOrListed() = runBlocking {
        val p = playlist()
        val x = db.newChannel(p, "x", "(CA) (CBC 13) |  (2098-12-31 08:00:01)")
        val s = index().current()
        assertEquals(ChannelEvent.EMPTY, s.of(x)!!.event)
        assertEquals(0, s.count(p))
        assertTrue(s.current(0).isEmpty())
        assertTrue(s.startingIn(Int.MIN_VALUE, Int.MAX_VALUE).isEmpty())
    }

    @Test
    fun learnsTheMajorityZoneFromTheWholePlaylistAndResolvesAssumedNamesWithIt() = runBlocking {
        val p = playlist(serverZone = "Europe/London")
        repeat(21) { db.newChannel(p, "et$it", "S $it: Game $it @2026-11-24T19:00 America/New_York") }
        repeat(3) { db.newChannel(p, "lo$it", "L $it: Match $it @2026-11-24T17:00 Europe/London") }
        val assumed = db.newChannel(p, "as", "F 01: Untagged @2026-11-24T14:00")
        val idx = index()
        val s = idx.current()
        assertEquals(listOf("America/New_York", "21"), db.strings("SELECT event_zone FROM playlist WHERE id = ?", p) + db.strings("SELECT event_zone_n FROM playlist WHERE id = ?", p))
        assertEquals(minuteOf("2026-11-24T14:00", "America/New_York"), s.of(assumed)!!.event.startMin)
        assertEquals(EventZoneSource.ASSUMED, s.of(assumed)!!.event.zone)
        assertEquals(1, idx.lastBuild!!.reparsed) // pass 1 used the panel zone; pass 2 re-read the one ASSUMED name
        assertEquals(25, s.count(p))
        assertEquals(25L, db.scalar("SELECT event_count FROM playlist WHERE id = ?", p))
    }

    @Test
    fun belowTheSampleCountThePanelZoneIsUsedAndNothingIsStored() = runBlocking {
        val p = playlist(serverZone = "Europe/London")
        repeat(19) { db.newChannel(p, "et$it", "S $it: Game $it @2026-11-24T19:00 America/New_York") }
        val assumed = db.newChannel(p, "as", "F 01: Untagged @2026-11-24T14:00")
        val s = index().current()
        assertEquals(listOf("null"), db.strings("SELECT event_zone FROM playlist WHERE id = ?", p))
        assertEquals(0, dao.zoneWrites.get())
        assertEquals(minuteOf("2026-11-24T14:00", "Europe/London"), s.of(assumed)!!.event.startMin)
        // No panel zone either: UTC.
        val q = playlist("q")
        val a2 = db.newChannel(q, "as2", "F 02: Untagged @2026-11-24T14:00")
        assertEquals(minuteOf("2026-11-24T14:00", "UTC"), index().current().of(a2)!!.event.startMin)
    }

    @Test
    fun aLearnedZoneNeverFallsBackToThePanelZone() = runBlocking {
        // Arch B2 / owner ruling: a quiet playlist (few explicit names now) keeps the learned zone, not the panel's.
        val p = playlist(serverZone = "Europe/London")
        db.exec("UPDATE playlist SET event_zone = 'America/New_York', event_zone_n = 826 WHERE id = ?", p)
        repeat(3) { db.newChannel(p, "lo$it", "L $it: Match $it @2026-11-24T17:00 Europe/London") }
        val assumed = db.newChannel(p, "as", "F 01: Untagged @2026-11-24T14:00")
        val s = index().current()
        assertEquals(minuteOf("2026-11-24T14:00", "America/New_York"), s.of(assumed)!!.event.startMin)
        assertEquals(listOf("America/New_York"), db.strings("SELECT event_zone FROM playlist WHERE id = ?", p))
        assertEquals(0, dao.zoneWrites.get())
    }

    @Test
    fun hysteresisNeedsTwentyNamesAndTwiceTheStoredZonesCount() = runBlocking {
        val p = playlist()
        db.exec("UPDATE playlist SET event_zone = 'America/New_York', event_zone_n = 30 WHERE id = ?", p)
        repeat(15) { db.newChannel(p, "et$it", "S $it: Game $it @2026-11-24T19:00 America/New_York") }
        repeat(29) { db.newChannel(p, "lo$it", "L $it: Match $it @2026-11-24T17:00 Europe/London") }
        val idx = index()
        idx.current()
        assertEquals("29 < 2 × 15: kept", listOf("America/New_York"), db.strings("SELECT event_zone FROM playlist WHERE id = ?", p))
        db.newChannel(p, "lo29", "L 29: Match 29 @2026-11-24T17:00 Europe/London")
        db.exec("UPDATE playlist SET refresh_seq = refresh_seq + 1 WHERE id = ?", p)
        idx.current()
        assertEquals(listOf("Europe/London", "30"), db.strings("SELECT event_zone FROM playlist WHERE id = ?", p) + db.strings("SELECT event_zone_n FROM playlist WHERE id = ?", p))
        assertEquals(1, dao.zoneWrites.get())
    }

    @Test
    fun chooseZoneRules() {
        val c = EventIndexImpl.Companion
        assertNull(c.chooseZone(null, emptyMap()).store)
        assertNull(c.chooseZone(null, mapOf("A" to 19)).store)
        assertEquals("A", c.chooseZone(null, mapOf("A" to 20, "B" to 3)).store!!.zone)
        assertNull("same zone: nothing to store", c.chooseZone("A", mapOf("A" to 900)).store)
        assertNull("stored zone with no sample now: 19 is under the floor", c.chooseZone("A", mapOf("B" to 19)).store)
        assertEquals("B", c.chooseZone("A", mapOf("B" to 20)).store!!.zone)
        assertNull("a tie keeps the stored zone", c.chooseZone("A", mapOf("A" to 30, "B" to 30)).store)
        assertEquals("deterministic tie without a stored zone", "A", c.chooseZone(null, mapOf("B" to 25, "A" to 25)).store!!.zone)
    }

    @Test
    fun startingInAndCurrentAreInStartOrder() = runBlocking {
        val p = playlist()
        val late = db.newChannel(p, "l", "S 03: Late @2026-11-24T22:00 UTC")
        val early = db.newChannel(p, "e", "S 01: Early @2026-11-24T18:00 UTC")
        val mid = db.newChannel(p, "m", "S 02: Mid @2026-11-24T20:00 UTC")
        val s = index().current()
        val t18 = minuteOf("2026-11-24T18:00", "UTC")
        assertEquals(listOf(early, mid, late), s.startingIn(t18, t18 + 240).map { it.channelId })
        assertEquals(listOf(mid), s.startingIn(t18 + 1, t18 + 120).map { it.channelId })
        assertEquals("both ends inclusive", listOf(early, mid), s.startingIn(t18, t18 + 120).map { it.channelId })
        assertTrue(s.startingIn(t18 + 10, t18).isEmpty())
        // The fake gives 180 min: at 21:00 the 18:00 one has ended.
        assertEquals(listOf(mid, late), s.current(t18 + 180).map { it.channelId })
    }

    @Test
    fun theKeyDecidesWhenToRebuildAndTheVersionMovesWithIt() = runBlocking {
        val p = playlist()
        val a = db.newChannel(p, "a", "S 01: Old @2026-11-24T18:00 UTC")
        val idx = index()
        val s1 = idx.current()
        assertEquals(1, parser.calls.get())
        assertSame("unchanged key: no rebuild", s1, idx.current())
        assertEquals(1, parser.calls.get())
        db.exec("UPDATE channel SET name = 'S 01: New @2026-11-24T19:00 UTC' WHERE id = ?", a)
        assertSame("a rename alone does not move the key", s1, idx.current())
        db.exec("UPDATE playlist SET refresh_seq = refresh_seq + 1 WHERE id = ?", p)
        val s2 = idx.current()
        assertNotEquals(s1.version, s2.version)
        assertEquals(s2.version, idx.version.value)
        assertEquals("New", s2.of(a)!!.event.title)
        // last_refresh_at is in the key too.
        db.exec("UPDATE playlist SET last_refresh_at = ? WHERE id = ?", NOW + 60_000, p)
        val s3 = idx.current()
        assertEquals(2, parser.calls.get() - 1) // rebuilt once more
        assertNotEquals("a keying input changed: a new version (contract)", s2.version, s3.version)
        // rebuild() with nothing changed keeps the version: no reload for the guide.
        assertEquals(s3.version, idx.rebuild().version)
        // A parser version bump rebuilds.
        val other = index(version = 2)
        other.current()
        assertEquals(5, parser.calls.get())
    }

    @Test
    fun rebuildAlwaysRebuildsAndWritesEventCountOnlyOnAChange() = runBlocking {
        val p = playlist()
        repeat(3) { db.newChannel(p, "a$it", "S $it: Game @2026-11-24T18:00 UTC") }
        val idx = index()
        idx.rebuild()
        assertEquals(1, dao.countWrites.get())
        assertEquals(3L, db.scalar("SELECT event_count FROM playlist WHERE id = ?", p))
        idx.rebuild()
        assertEquals("unchanged: no write (E9)", 1, dao.countWrites.get())
        assertEquals(2, parser.calls.get() / 3)
        db.exec("DELETE FROM channel WHERE stable_key = 'a0'")
        idx.rebuild()
        assertEquals(2, dao.countWrites.get())
        assertEquals(2L, db.scalar("SELECT event_count FROM playlist WHERE id = ?", p))
    }

    @Test
    fun ourOwnZoneWriteDoesNotTriggerASecondBuild() = runBlocking {
        val p = playlist()
        repeat(20) { db.newChannel(p, "et$it", "S $it: Game @2026-11-24T19:00 America/New_York") }
        val idx = index()
        val s = idx.current()
        assertEquals(1, dao.zoneWrites.get())
        assertSame(s, idx.current())
        assertEquals(20, parser.calls.get())
    }

    @Test
    fun aParserExceptionLosesOneNameNotTheIndex() = runBlocking {
        val p = playlist()
        val ok = db.newChannel(p, "ok", "S 01: Fine @2026-11-24T18:00 UTC")
        db.newChannel(p, "bad", "S 02: Boom @2026-11-24T18:00 UTC")
        val s = index({ n, c -> if ("Boom" in n) throw IllegalStateException("bug") else parser.parse(n, c) }).current()
        assertEquals(1, s.count(p))
        assertEquals("Fine", s.of(ok)!!.event.title)
    }

    @Test
    fun aStaleReaderGetsThePreviousSnapshotWhileABuildRuns() = runBlocking {
        val p = playlist()
        db.newChannel(p, "a", "S 01: Game @2026-11-24T18:00 UTC")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = AtomicInteger(0)
        val idx = index({ n, c ->
            if (blocking.get() == 1) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            parser.parse(n, c)
        })
        val s1 = idx.current()
        db.exec("UPDATE playlist SET refresh_seq = refresh_seq + 1 WHERE id = ?", p)
        blocking.set(1)
        val build = async(Dispatchers.IO) { idx.rebuild() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertSame("the reader does not wait for the running build", s1, idx.current())
        release.countDown()
        val s2 = build.await()
        assertNotEquals(s1.version, s2.version)
        assertSame(s2, idx.current())
    }

    @Test
    fun buildTimeOn14kRows() = runBlocking {
        // §11.2 sizing: about 13k live rows, 1,700 clock names (1,543 events, 44 placeholders and unhandled ones).
        val p = playlist()
        db.writeTransaction {
            usePrepared(
                "INSERT INTO channel(playlist_id, stable_key, key_rule, kind, name, norm_name, sort_index, url, is_adult, content_hash, " +
                    "missing_count, first_seen_at, updated_at) VALUES (?, ?, 'URL', 0, ?, '', ?, 'http://h', 0, 0, 0, 1, 1)",
            ) { st ->
                for (i in 0 until 14_000) {
                    st.bindLong(1, p)
                    st.bindText(2, "k$i")
                    val name = when {
                        i < 1_550 -> "Slot ${i % 100}: Team $i @ Team ${i + 1} @2026-11-${10 + i % 20}T${10 + i % 12}:${if (i % 2 == 0) "00" else "30"} " +
                            if (i % 7 == 0) "" else "America/New_York"
                        i < 1_700 -> "Junk ${i % 100}: kick-off 19:45 later"
                        i < 5_500 -> "Slot ${i % 100}: NO EVENT"
                        else -> "Channel $i HD"
                    }.trim()
                    st.bindText(3, name)
                    st.bindLong(4, i.toLong())
                    st.step()
                    st.reset()
                    st.clearBindings()
                }
            }
        }
        val idx = index()
        idx.current() // warm the JIT and the statement cache
        val runs = (1..3).map { idx.rebuild(); idx.lastBuild!! }
        val best = runs.minOf { it.ms }
        println("EventIndex: ${runs.first().rowsRead} prefiltered rows of 14,000, ${runs.first().events} events, build ${runs.map { it.ms }} ms (best $best; Robolectric SQLite, fake parser)")
        assertEquals(1_700, runs.first().rowsRead)
        assertEquals(1_550, runs.first().events)
        assertTrue("build ${best} ms", best < 2_000)
    }

    /** Counts the change-only writes (E9). */
    private class CountingDao(private val d: PlaylistDao) : PlaylistDao by d {
        val zoneWrites = AtomicInteger()
        val countWrites = AtomicInteger()
        override suspend fun setEventZone(id: Long, zone: String?, n: Int) { zoneWrites.incrementAndGet(); d.setEventZone(id, zone, n) }
        override suspend fun setEventCount(id: Long, count: Int) { countWrites.incrementAndGet(); d.setEventCount(id, count) }
    }

    companion object {
        /** 2026-11-24T12:00Z. */
        const val NOW = 1_795_521_600_000L
    }
}

/**
 * A test stand-in for `EventNameParser.parse` (not the real grammar): `<slot>: <title> @<yyyy-MM-ddTHH:mm>[ <zone id>]`.
 * A zone id makes the name EXPLICIT; without one it is ASSUMED in `ctx.assumedZone`. A name containing "2098" is an empty
 * slot. 180 minutes long. Records what it was asked.
 */
class FakeEventNames {
    val seen = ArrayList<String>()
    val calls = AtomicInteger()

    fun parse(name: String, ctx: EventParseContext): ParsedName {
        calls.incrementAndGet()
        synchronized(seen) { seen += name }
        if ("2098" in name) return ParsedName.Empty(name.substringBefore(" |").trim())
        val at = name.lastIndexOf(" @")
        if (at < 0) return ParsedName.None
        val head = name.substring(0, at)
        val colon = head.indexOf(": ")
        if (colon < 0) return ParsedName.None
        val tail = name.substring(at + 2).trim().split(' ')
        val local = try { LocalDateTime.parse(tail[0]) } catch (e: Exception) { return ParsedName.None }
        val explicit = tail.size > 1
        val zone = if (explicit) ZoneId.of(tail[1]) else ctx.assumedZone
        val start = local.atZone(zone).toInstant().toEpochMilli()
        return ParsedName.Event(
            head.substring(0, colon), head.substring(colon + 2), null, start, start + 180 * 60_000L,
            if (explicit) EventZoneSource.EXPLICIT else EventZoneSource.ASSUMED, zone.id, EventShape.AT_DAY_MONTH,
        )
    }
}
