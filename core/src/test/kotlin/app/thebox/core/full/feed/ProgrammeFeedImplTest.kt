package app.thebox.core.full.feed

import app.thebox.core.epg.EpgBuildRequest
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.epg.EpgPatchRequest
import app.thebox.core.epg.EpgReader
import app.thebox.core.epg.EpgSourceFile
import app.thebox.core.epg.EpgWhitelist
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.epg.EpgWindow
import app.thebox.core.epg.ProgrammeDetail
import app.thebox.core.epg.ProgrammeFlags
import app.thebox.core.epg.store.EpgStoreImpl
import app.thebox.core.epg.testutil.EpgTestDb
import app.thebox.core.guide.fakes.GuideFixtures
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/design/m4-player-ux.md §5.3 `ProgrammeFeedImplTest`, against a real `EpgStoreImpl` (testFixtures `EpgTestDb`).
 * "Now" is 2026-10-13T12:00Z.
 */
class ProgrammeFeedImplTest {
    @TempDir
    lateinit var tmp: File

    private val nowMs = EpgTestDb.minutes("2026-10-13T12:00:00Z") * 60_000L
    private val now = (nowMs / 60_000L).toInt()
    private val feeds = ArrayList<ProgrammeFeedImpl>()

    @AfterEach
    fun tearDown() = feeds.forEach { it.close() }

    // ---- channels of the fixture
    private val shorts = GuideFixtures.channel(0, epg = EpgChannelRef(1, "shorts")) // 5-minute slots
    private val gap = GuideFixtures.channel(1, epg = EpgChannelRef(1, "gap")) // nothing 11:30–12:30
    private val films = GuideFixtures.channel(2, epg = EpgChannelRef(1, "films")) // 10:00–17:00 split at 16:00
    private val hourly = GuideFixtures.channel(3, epg = EpgChannelRef(1, "hourly"))
    private val noEpg = GuideFixtures.channel(4, epg = null)
    private val unknown = GuideFixtures.channel(5, epg = EpgChannelRef(1, "not-in-xmltv"))

    private fun xmltv(title: String = "Show") = EpgTestDb.xmltv {
        channel("shorts", "Shorts")
        var m = now - 12 * 60
        while (m < now + 24 * 60) {
            programme("shorts", m, m + 5, "$title $m")
            m += 5
        }
        channel("gap", "Gap")
        programme("gap", now - 90, now - 30, "$title before")
        programme("gap", now + 30, now + 90, "$title after")
        channel("films", "Films")
        programme("films", now - 180, now - 120, "Short film")
        programme("films", now - 120, now + 300, "Epic Saga") // 10:00–17:00 → rows 10:00–16:00 + 16:00–17:00
        programme("films", now + 300, now + 360, "After the saga")
        channel("hourly", "Hourly")
        m = now - 12 * 60
        while (m < now + 24 * 60) {
            programme("hourly", m, m + 60, "$title hour $m")
            m += 60
        }
    }

    private fun store(dir: String = "epg"): EpgStoreImpl = EpgTestDb.openStore(File(tmp, dir), clock = { nowMs })

    private fun EpgStoreImpl.buildWith(file: File, whitelist: EpgWhitelist = EpgWhitelist.ALL) =
        build(EpgBuildRequest(listOf(EpgSourceFile(1, file)), nowMs, pastDays = 1, futureDays = 2), EpgWhitelistResolver { _, _ -> whitelist })

    private fun built(title: String = "Show"): EpgStoreImpl = store().also { it.buildWith(xmltv(title).writeTo(File(tmp, "$title.xml"))) }

    private fun feed(s: EpgStoreImpl, openReader: (() -> EpgReader?)? = null) =
        ProgrammeFeedImpl(s, openReader ?: s::openReader, busyRetryMs = 20).also { feeds += it }

    // ------------------------------------------------------------------------------------------------ around

    @Test
    fun aroundOnFiveMinuteSlotsFindsNow() = runBlocking {
        val f = feed(built())
        val slice = f.around(shorts, atMin = now + 2, fromMin = now - 360)
        assertEquals(12, slice.nowIndex, "S4: now is found on a short-slot channel")
        assertEquals(25, slice.programmes.size, "12 before, now, 12 after")
        val cur = slice.programmes[slice.nowIndex]
        assertEquals(now, cur.startMin)
        assertEquals(now + 5, cur.endMin)
        assertEquals("Show $now", cur.title)
        assertEquals(slice.programmes.sortedBy { it.startMin }, slice.programmes)
        assertTrue(slice.programmes.zipWithNext().all { (a, b) -> a.endMin == b.startMin })

        val early = f.around(shorts, atMin = now, fromMin = now, before = 12, after = 3)
        assertEquals(0, early.nowIndex, "nothing before fromMin")
        assertEquals(4, early.programmes.size)
    }

    @Test
    fun aroundAtAGap() = runBlocking {
        val f = feed(built())
        val slice = f.around(gap, atMin = now, fromMin = now - 360)
        assertEquals(-1, slice.nowIndex)
        assertEquals(listOf("Show before", "Show after"), slice.programmes.map { it.title })
        assertTrue(f.around(noEpg, now, now - 360).programmes.isEmpty())
        assertEquals(-1, f.around(unknown, now, now - 360).nowIndex)
    }

    @Test
    fun aroundStaysOneStatement() = runBlocking {
        val counter = Counting()
        val s = built()
        val f = feed(s) { s.openReader()?.let { counter.wrap(it) } }
        f.around(hourly, now, now - 360)
        counter.reset()
        val t0 = System.nanoTime()
        val slice = f.around(hourly, now, now - 360)
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue(slice.nowIndex >= 0)
        assertEquals(1, counter.windows.get(), "one window statement")
        assertEquals(0, counter.resolves.get(), "the id is cached per generation")
        assertEquals(0, counter.ats.get())
        assertTrue(ms < 200, "around took $ms ms") // generous for CI; the device target is ≤ 2 ms (N1)
    }

    // ------------------------------------------------------------------------------------------------ range, at, nowFor

    @Test
    fun rangeOrderingLimitAndShift() = runBlocking {
        val f = feed(built())
        val r = f.range(hourly, now - 30, now + 240, limit = 3)
        assertEquals(listOf(now - 60, now, now + 60), r.map { it.startMin }, "start order, overlapping the range, limited")
        assertEquals(24, f.range(shorts, now, now + 24 * 60).size, "default limit PROGRAMME_RANGE_LIMIT")
        assertTrue(f.range(hourly, now, now).isEmpty())

        val shifted = GuideFixtures.channel(3, epg = EpgChannelRef(1, "hourly"), epgShiftMin = 30)
        val s = f.range(shifted, now, now + 60)
        assertEquals(listOf(now - 30, now + 30), s.map { it.startMin }, "display minutes = raw + shift")
        assertEquals(listOf(now - 60, now), s.map { it.rawStartMin }, "raw start is the catch-up/reminder key")
        assertEquals("Show hour ${now - 60}", s.first().title)
        val a = assertNotNull(f.at(shifted, now + 31))
        assertEquals(now + 30, a.startMin)
        assertEquals(now, a.rawStartMin)
    }

    @Test
    fun continuationsAreMerged() = runBlocking {
        val f = feed(built())
        // The saga is stored as 10:00–16:00 (CONTINUED) + 16:00–17:00 (CONTINUATION).
        val all = f.range(films, now - 180, now + 360)
        assertEquals(listOf("Short film", "Epic Saga", "After the saga"), all.map { it.title })
        val saga = all[1]
        assertEquals(now - 120, saga.startMin)
        assertEquals(now + 300, saga.endMin)
        assertEquals(0, saga.flags and (ProgrammeFlags.CONTINUATION or ProgrammeFlags.CONTINUED))

        val tailOnly = f.range(films, now + 250, now + 330)
        assertEquals(now - 120, tailOnly.first().startMin, "the head is looked up when only the tail is in range")
        assertEquals(now - 120, tailOnly.first().rawStartMin)
        val headOnly = f.range(films, now, now + 10)
        assertEquals(now + 300, headOnly.single().endMin, "the tail is looked up when only the head is in range")

        val inTail = assertNotNull(f.at(films, now + 270))
        assertEquals(now - 120 to now + 300, inTail.startMin to inTail.endMin)
        val nowInTail = f.nowFor(listOf(films), now + 270).single()
        assertEquals(now - 120 to now + 300, nowInTail!!.startMin to nowInTail.endMin)
        val slice = f.around(films, now + 270, now)
        assertEquals("Epic Saga", slice.programmes[slice.nowIndex].title)
        assertEquals(now - 120, slice.programmes[slice.nowIndex].startMin)
    }

    @Test
    fun nowForWithGapsAndNoEpgInOrder() = runBlocking {
        val f = feed(built())
        val r = f.nowFor(listOf(hourly, gap, noEpg, unknown, shorts, hourly), now + 7)
        assertEquals(6, r.size)
        assertEquals(now, r[0]!!.startMin)
        assertNull(r[1], "gap")
        assertNull(r[2], "no EPG")
        assertNull(r[3], "not in this generation")
        assertEquals(now + 5, r[4]!!.startMin)
        assertEquals(r[0], r[5])
        assertTrue(f.nowFor(emptyList(), now).isEmpty())
    }

    @Test
    fun atReturnsTheFullDetail() = runBlocking {
        val s = store()
        s.buildWith(
            EpgTestDb.xmltv {
                channel("hourly", "Hourly")
                programme("hourly", now, now + 60, "Detailed", subtitle = "Part 2", extra = "    <desc>About it</desc>\n    <category>News</category>\n")
            }.writeTo(File(tmp, "d.xml")),
        )
        val f = feed(s)
        val a = assertNotNull(f.at(hourly, now + 10))
        assertEquals("Detailed", a.title)
        assertEquals("Part 2", a.subtitle)
        assertEquals("About it", a.description)
        assertEquals("News", a.genre)
        assertNull(f.at(hourly, now + 60), "end is exclusive")
        val fromWindow = f.range(hourly, now, now + 60).single()
        assertEquals(a.titleHash, fromWindow.titleHash, "window rows carry the same reminder key")
        assertNull(fromWindow.description, "window rows carry titles only")
    }

    // ------------------------------------------------------------------------------------------------ generations

    @Test
    fun generationSwapBetweenCallsClosesTheOldReaderOnTheFeedThread() = runBlocking {
        val s = built("Old")
        val gen1 = s.info.value!!.generation
        val closedOn = Collections.synchronizedList(ArrayList<String>())
        val f = feed(s) { s.openReader()?.let { ClosingSpy(it, closedOn) } }
        assertTrue(f.at(hourly, now)!!.title.startsWith("Old"))
        s.buildWith(xmltv("New").writeTo(File(tmp, "new.xml")))
        assertTrue(s.info.value!!.generation > gen1)
        assertTrue(f.at(hourly, now)!!.title.startsWith("New"), "re-queried on the new reader")
        assertTrue(f.around(shorts, now, now - 60).programmes.all { it.title.startsWith("New") })
        f.nowFor(emptyList(), now) // drains the feed thread
        assertEquals(1, closedOn.size, "old reader closed once: $closedOn")
        assertTrue(closedOn.single().startsWith(ProgrammeFeedImpl.THREAD_NAME), "closed on box-player-epg: $closedOn")
        assertFalse(EpgTestDb.generationFile(File(tmp, "epg"), gen1).exists(), "old generation file deleted")
    }

    @Test
    fun generationSwapMidCallFinishesOnTheOldReader() = runBlocking {
        val s = built("Old")
        val gen1 = s.info.value!!.generation
        val swapOnce = AtomicBoolean(true)
        val closedOn = Collections.synchronizedList(ArrayList<String>())
        val f = feed(s) {
            s.openReader()?.let { r ->
                object : EpgReader by ClosingSpy(r, closedOn) {
                    override fun window(channels: IntArray, shiftMin: IntArray, fromMin: Int, toMin: Int, out: EpgWindow) {
                        r.window(channels, shiftMin, fromMin, toMin, out)
                        if (swapOnce.compareAndSet(true, false)) {
                            // A refresh publishes a new generation while this query is between its statement and its
                            // result: the ref-counted handle keeps the old one valid.
                            val t = Thread { s.buildWith(xmltv("New").writeTo(File(tmp, "mid.xml"))) }
                            t.start()
                            t.join()
                        }
                    }
                }
            }
        }
        val first = f.around(hourly, now, now - 120)
        assertTrue(first.programmes.isNotEmpty() && first.programmes.all { it.title.startsWith("Old") }, "the running call keeps its reader")
        assertTrue(s.info.value!!.generation > gen1)
        val second = f.around(hourly, now, now - 120)
        assertTrue(second.programmes.all { it.title.startsWith("New") }, "the next call uses the new generation")
        f.nowFor(emptyList(), now)
        assertTrue(closedOn.isNotEmpty() && closedOn.all { it.startsWith(ProgrammeFeedImpl.THREAD_NAME) }, "$closedOn")
        assertFalse(EpgTestDb.generationFile(File(tmp, "epg"), gen1).exists())
    }

    @Test
    fun patchReResolves() = runBlocking {
        val s = store()
        val file = xmltv().writeTo(File(tmp, "a.xml"))
        s.buildWith(file, EpgWhitelist { it != "hourly" })
        val f = feed(s)
        assertNull(f.at(hourly, now), "hourly not imported in this generation")
        assertEquals(listOf(null), f.nowFor(listOf(hourly), now))
        s.patch(EpgPatchRequest(listOf(EpgSourceFile(1, file)), listOf(EpgChannelRef(1, "hourly")), nowMs, 1, 2))
        assertNotNull(f.at(hourly, now), "the id cache is cleared on patchSeq")
        assertNotNull(f.nowFor(listOf(hourly), now).single())
    }

    @Test
    fun noGenerationGivesEmptyAnswers() = runBlocking {
        val f = feed(store("empty"))
        assertTrue(f.range(hourly, now, now + 60).isEmpty())
        assertEquals(listOf(null, null), f.nowFor(listOf(hourly, shorts), now))
        assertNull(f.at(hourly, now))
        assertEquals(-1, f.around(hourly, now, now).nowIndex)
    }

    // ------------------------------------------------------------------------------------------------ busy, close

    @Test
    fun busyRetriesThenAnswersFromTheLastProgrammes() = runBlocking {
        val s = built()
        val failures = AtomicInteger(0)
        val f = feed(s) { s.openReader()?.let { BusySpy(it, failures) } }
        val before = f.around(hourly, now, now - 360)
        assertTrue(before.nowIndex >= 0)

        failures.set(2) // two busy answers, then the statement goes through
        val retried = f.range(hourly, now, now + 120)
        assertEquals(2, retried.size)
        assertEquals(0, failures.get())

        failures.set(100) // beyond BUSY_RETRIES
        val kept = f.around(hourly, now, now - 360)
        assertEquals(before, kept, "the last slice is kept")
        assertEquals(now, f.at(hourly, now + 1)!!.startMin, "at answers from the kept programmes")
        assertEquals(now, f.nowFor(listOf(hourly, shorts), now + 1)[0]!!.startMin)
        assertNull(f.nowFor(listOf(hourly, shorts), now + 1)[1], "nothing kept for a channel never read")
        assertTrue(f.range(shorts, now, now + 60).isEmpty())
    }

    @Test
    fun cancellationReachesTheCallerAndCloseEndsEverything() = runBlocking {
        val s = built()
        val f = feed(s)
        val job = async { f.around(hourly, now, now - 360) }
        assertTrue(job.await().nowIndex >= 0)
        f.close()
        assertTrue(f.range(hourly, now, now + 60).isEmpty())
        assertNull(f.at(hourly, now))
        f.close() // idempotent
    }

    // ------------------------------------------------------------------------------------------------ spies

    private class ClosingSpy(private val d: EpgReader, private val closedOn: MutableList<String>) : EpgReader by d {
        override fun close() {
            closedOn += Thread.currentThread().name
            d.close()
        }
    }

    private class BusySpy(private val d: EpgReader, private val failures: AtomicInteger) : EpgReader by d {
        private fun maybeBusy() {
            if (failures.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) throw RuntimeException("Error code: 5, message: database is locked")
        }

        override fun window(channels: IntArray, shiftMin: IntArray, fromMin: Int, toMin: Int, out: EpgWindow) {
            maybeBusy()
            d.window(channels, shiftMin, fromMin, toMin, out)
        }

        override fun at(channel: Int, atMin: Int, shiftMin: Int): ProgrammeDetail? {
            maybeBusy()
            return d.at(channel, atMin, shiftMin)
        }
    }

    private class Counting {
        val windows = AtomicInteger()
        val resolves = AtomicInteger()
        val ats = AtomicInteger()
        fun reset() { windows.set(0); resolves.set(0); ats.set(0) }

        fun wrap(d: EpgReader): EpgReader = object : EpgReader by d {
            override fun window(channels: IntArray, shiftMin: IntArray, fromMin: Int, toMin: Int, out: EpgWindow) {
                windows.incrementAndGet()
                d.window(channels, shiftMin, fromMin, toMin, out)
            }

            override fun resolve(refs: List<EpgChannelRef>, caseInsensitive: Boolean): IntArray {
                resolves.incrementAndGet()
                return d.resolve(refs, caseInsensitive)
            }

            override fun at(channel: Int, atMin: Int, shiftMin: Int): ProgrammeDetail? {
                ats.incrementAndGet()
                return d.at(channel, atMin, shiftMin)
            }
        }
    }
}
