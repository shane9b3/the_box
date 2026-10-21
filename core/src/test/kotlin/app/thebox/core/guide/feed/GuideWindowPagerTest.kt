package app.thebox.core.guide.feed

import kotlin.test.assertSame
import app.thebox.core.guide.GuideTextStyle
import app.thebox.core.guide.CellFlags
import java.util.concurrent.atomic.AtomicBoolean
import app.thebox.core.common.BoxLog
import app.thebox.core.epg.EpgBuildRequest
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.epg.EpgPatchRequest
import app.thebox.core.epg.EpgReader
import app.thebox.core.epg.EpgSourceFile
import app.thebox.core.epg.EpgWhitelist
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.epg.EpgWindow
import app.thebox.core.epg.store.EpgStoreImpl
import app.thebox.core.epg.testutil.EpgTestDb
import app.thebox.core.guide.CellKind
import app.thebox.core.guide.CellSink
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideRows
import app.thebox.core.guide.GuideWindowData
import app.thebox.core.guide.RowCellsBuilder
import app.thebox.core.guide.RowStatus
import app.thebox.core.guide.fakes.FakeGuideMetrics
import app.thebox.core.guide.fakes.FakeRowCellsBuilder
import app.thebox.core.guide.fakes.FakeTextMeasurer
import app.thebox.core.guide.fakes.FakeTimeLabels
import app.thebox.core.guide.fakes.GuideFixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m3-guide.md §5.2 `GuideWindowPagerTest`, against a real `EpgStoreImpl` (testFixtures `EpgTestDb`). */
class GuideWindowPagerTest {
    @TempDir
    lateinit var tmp: File

    private val nowMs = EpgTestDb.minutes("2026-10-13T12:00:00Z") * 60_000L
    private val now = (nowMs / 60_000L).toInt()
    private val ws = GuideFixtures.floor30(now)
    private val pagers = ArrayList<GuideWindowPagerImpl>()
    private val stores = ArrayList<EpgStoreImpl>()

    @AfterEach
    fun tearDown() {
        pagers.forEach { it.close() }
    }

    /** Rows the test controls: channel i has EPG ref `ch<i>` unless overridden. */
    private class MutableRows(n: Int) : GuideRows {
        val list: Array<GuideChannel?> = Array(n) { GuideFixtures.channel(it, epg = EpgChannelRef(1, "ch$it")) }
        @Volatile override var version: Long = 1
        override val size: Int get() = list.size
        override fun channel(index: Int): GuideChannel? = list.getOrNull(index)
        override fun indexOf(rowId: Long): Int = list.indexOfFirst { it?.rowId == rowId }
        fun set(i: Int, c: GuideChannel?) { list[i] = c; version++ }
    }

    private fun xmltv(channels: Int, title: String = "Show", from: Int = now - 12 * 60, to: Int = now + 24 * 60, ids: (Int) -> String = { "ch$it" }) =
        EpgTestDb.xmltv {
            for (c in 0 until channels) {
                channel(ids(c), "Channel $c")
                var m = from + (c % 3) * 10
                while (m < to) {
                    programme(ids(c), m, m + 30, "$title $c")
                    m += 30
                }
            }
        }

    private fun request(file: File) = EpgBuildRequest(listOf(EpgSourceFile(1, file)), nowMs, pastDays = 1, futureDays = 2)

    private fun store(dir: String = "epg"): EpgStoreImpl =
        EpgTestDb.openStore(File(tmp, dir), clock = { nowMs }).also { stores += it }

    private fun EpgStoreImpl.buildWith(file: File, whitelist: EpgWhitelist = EpgWhitelist.ALL) =
        build(request(file), EpgWhitelistResolver { _, _ -> whitelist })

    private fun pager(
        store: EpgStoreImpl,
        rows: GuideRows,
        builder: RowCellsBuilder = FakeRowCellsBuilder(),
        openReader: (() -> EpgReader?)? = null,
    ): GuideWindowPagerImpl = GuideWindowPagerImpl(
        store, rows, { FakeGuideMetrics() }, { FakeTextMeasurer() }, builder, FakeTimeLabels(),
        openReader = openReader ?: store::openReader, busyRetryMs = 20,
    ).also { pagers += it }

    private fun await(p: GuideWindowPagerImpl, what: String = "snapshot", pred: (GuideWindowData) -> Boolean): GuideWindowData = runBlocking {
        try {
            withTimeout(5_000) { p.snapshots.first { it != null && pred(it) }!! }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for $what; last = ${p.snapshots.value?.let { "gen ${it.generation} rows ${it.firstRow}+${it.rowCount} seq ${it.seq}" }}")
        }
    }

    /** Lets every posted runnable on box-guide-epg finish. */
    private fun drain(p: GuideWindowPagerImpl) = runBlocking { p.info(GuideFixtures.channel(0, epg = null), 0) }

    private fun cellsOf(d: GuideWindowData, row: Int): List<Triple<Int, Int, Byte>> {
        val r = row - d.firstRow
        val f = d.rowFirstCell[r]
        return (f until f + d.rowCellCount[r]).map { Triple(d.cellStart[it], d.cellEnd[it], d.cellKind[it]) }
    }

    @Test
    fun coverageMarginsAndTarget() {
        val s = store().also { it.buildWith(xmltv(40).writeTo(File(tmp, "a.xml"))) }
        val rows = MutableRows(100)
        val p = pager(s, rows)
        p.ensure(20, ws)
        val d = await(p) { true }
        assertEquals(14, d.firstRow, "target rows [top − 6, top + 12)")
        assertEquals(18, d.rowCount)
        assertEquals(ws - 180, d.fromMin)
        assertEquals(ws + 360, d.toMin)
        assertEquals(s.info.value!!.generation, d.generation)
        assertTrue(d.covers(20, ws) && d.covers(25, ws + 179))
        // Cells tile each row and carry titles, widths, keys and time texts built off-main.
        val row = cellsOf(d, 20)
        assertTrue(row.first().first <= ws - 180 && row.last().second >= ws + 360, "row 20 tiles the coverage")
        assertTrue(row.zipWithNext().all { (a, b) -> a.second == b.first }, "contiguous")
        val firstProg = (0 until d.cellCount).first { d.cellKind[it] == CellKind.PROGRAMME }
        assertTrue(d.cellTitleWidth[firstProg] > 0f && d.cellTitleKey[firstProg] != 0L)
        assertNotNull(d.cellTimeText[firstProg])
        drain(p)
        val q = p.queries
        // Inside the margin need (rows [top − 2, top + 7) × [ws − 60, ws + 240)): no query.
        p.ensure(22, ws + 60)
        p.ensure(16, ws)
        drain(p)
        assertEquals(q, p.queries, "covered moves issue no query")
        // Leaving the margin: one query.
        p.ensure(26, ws)
        drain(p)
        assertEquals(q + 1, p.queries)
        p.ensure(26, ws + 150)
        drain(p)
        assertEquals(q + 2, p.queries)
    }

    @Test
    fun coalescingLatestWins() {
        val s = store().also { it.buildWith(xmltv(40).writeTo(File(tmp, "a.xml"))) }
        val gate = CountDownLatch(1)
        val first = AtomicInteger()
        val builder = object : RowCellsBuilder {
            val inner = FakeRowCellsBuilder()
            override fun build(w: EpgWindow, from: Int, to: Int, fromMin: Int, toMin: Int, noEpg: Boolean, out: CellSink): Int {
                if (first.getAndIncrement() == 0) gate.await(5, TimeUnit.SECONDS)
                return inner.build(w, from, to, fromMin, toMin, noEpg, out)
            }
        }
        val p = pager(s, MutableRows(2_000), builder)
        p.ensure(0, ws)
        Thread.sleep(100) // the first query is now blocked inside the builder
        for (i in 1..100) p.ensure(i * 10, ws + (i % 7) * 30)
        gate.countDown()
        val d = await(p, "latest target") { it.firstRow == 994 }
        drain(p)
        assertTrue(p.queries <= 2, "100 ensures → ${p.queries} queries")
        assertEquals(994, d.firstRow)
    }

    @Test
    fun pendingRowsRequeryWhenTheirBlockArrives() {
        val s = store().also { it.buildWith(xmltv(40).writeTo(File(tmp, "a.xml"))) }
        val rows = MutableRows(40)
        val saved = rows.list.copyOf()
        for (i in 0 until 40) rows.list[i] = null
        val p = pager(s, rows)
        p.ensure(0, ws)
        val d = await(p) { true }
        assertTrue((0 until d.rowCount).all { d.rowStatus[it] == RowStatus.PENDING && d.rowCellCount[it] == 0 })
        drain(p)
        val q = p.queries
        p.ensure(0, ws)
        drain(p)
        assertEquals(q, p.queries, "same coverage, same rows: no query")
        for (i in 0 until 40) rows.list[i] = saved[i]
        rows.version++
        p.ensure(0, ws)
        val d2 = await(p, "loaded rows") { it.rowStatus[0] == RowStatus.OK }
        assertTrue((0 until d2.rowCount).all { d2.rowStatus[it] == RowStatus.OK })
        assertTrue(d2.rowsVersion > d.rowsVersion)
    }

    @Test
    fun generationSwapMidScrollClosesTheOldReaderOnTheEpgThread() {
        val s = store()
        s.buildWith(xmltv(40, title = "Old").writeTo(File(tmp, "a.xml")))
        val gen1 = s.info.value!!.generation
        val closedOn = Collections.synchronizedList(ArrayList<String>())
        val errors = Collections.synchronizedList(ArrayList<String>())
        val oldSink = BoxLog.sink
        BoxLog.sink = BoxLog.Sink { level, tag, message, error -> if (level >= BoxLog.WARN) errors += "$tag $message $error"; oldSink.log(level, tag, message, error) }
        try {
            val p = pager(s, MutableRows(60), openReader = { s.openReader()?.let { ClosingSpy(it, closedOn) } })
            p.ensure(0, ws)
            val d1 = await(p) { it.generation == gen1 }
            assertTrue(d1.cellTitle.any { it?.startsWith("Old") == true })
            p.ensure(10, ws) // scrolling while the rebuild runs
            s.buildWith(xmltv(40, title = "New").writeTo(File(tmp, "b.xml")))
            val gen2 = s.info.value!!.generation
            assertTrue(gen2 > gen1)
            p.ensure(20, ws)
            val d2 = await(p, "new generation") { it.generation == gen2 && it.firstRow == 14 }
            assertTrue(d2.cellTitle.filterNotNull().all { !it.startsWith("Old") }, "re-queried on the new reader")
            drain(p)
            assertEquals(1, closedOn.size, "old reader closed once")
            assertTrue(closedOn.single().startsWith(GuideWindowPagerImpl.THREAD_NAME), "closed on box-guide-epg: $closedOn")
            assertFalse(EpgTestDb.generationFile(File(tmp, "epg"), gen1).exists(), "old generation file deleted")
            assertTrue(errors.none { "IllegalState" in it || "closed" in it }, errors.joinToString())
        } finally {
            BoxLog.sink = oldSink
        }
    }

    @Test
    fun patchReResolves() {
        val s = store()
        val file = xmltv(10).writeTo(File(tmp, "a.xml"))
        s.buildWith(file, EpgWhitelist { it != "ch3" })
        val p = pager(s, MutableRows(10))
        p.ensure(0, ws)
        val d1 = await(p) { true }
        assertTrue(cellsOf(d1, 3).none { it.third == CellKind.PROGRAMME }, "no programmes for ch3 in this generation")
        assertTrue(cellsOf(d1, 2).any { it.third == CellKind.PROGRAMME })
        s.patch(EpgPatchRequest(listOf(EpgSourceFile(1, file)), listOf(EpgChannelRef(1, "ch3")), nowMs, 1, 2))
        val d2 = await(p, "patched snapshot") { it.patchSeq == 1 }
        assertEquals(RowStatus.OK, d2.rowStatus[3])
        assertTrue(cellsOf(d2, 3).any { it.third == CellKind.PROGRAMME })
    }

    @Test
    fun busyKeepsTheSnapshotAndRetries() {
        val s = store().also { it.buildWith(xmltv(20).writeTo(File(tmp, "a.xml"))) }
        val failures = AtomicInteger(0)
        val p = pager(s, MutableRows(20), openReader = { s.openReader()?.let { BusySpy(it, failures) } })
        p.ensure(0, ws)
        val d1 = await(p) { true }
        drain(p)
        failures.set(2) // the next two window() calls fail with SQLITE_BUSY
        p.ensure(0, ws + 600)
        drain(p)
        assertEquals(d1.seq, p.snapshots.value!!.seq, "busy keeps the old snapshot")
        val d2 = await(p, "retried snapshot") { it.fromMin == ws + 600 - 180 }
        assertTrue(d2.seq > d1.seq)
        assertEquals(0, failures.get())
        // Beyond BUSY_RETRIES the old snapshot stays until the next ensure.
        failures.set(10)
        p.ensure(0, ws + 1200)
        Thread.sleep(300)
        drain(p)
        assertEquals(d2.seq, p.snapshots.value!!.seq)
    }

    @Test
    fun noGuideDataGivesNoEpgRows() {
        val s = store("empty")
        assertNull(s.info.value)
        val p = pager(s, MutableRows(12))
        p.ensure(0, ws)
        val d = await(p) { true }
        assertEquals(-1L, d.generation)
        assertTrue((0 until d.rowCount).all { d.rowStatus[it] == RowStatus.NO_EPG })
        assertTrue((0 until d.cellCount).all { d.cellKind[it] == CellKind.NO_EPG })
    }

    @Test
    fun snapshotsAreFreshArrays() {
        val s = store().also { it.buildWith(xmltv(20).writeTo(File(tmp, "a.xml"))) }
        val p = pager(s, MutableRows(20))
        p.ensure(0, ws)
        val a = await(p) { true }
        val starts = a.cellStart.copyOf()
        val titles = a.cellTitle.copyOf()
        p.ensure(0, ws + 600)
        val b = await(p, "second snapshot") { it.seq > a.seq }
        for ((x, y) in listOf(
            a.cellStart to b.cellStart, a.cellEnd to b.cellEnd, a.cellRawStart to b.cellRawStart, a.cellKind to b.cellKind,
            a.cellFlags to b.cellFlags, a.cellTextId to b.cellTextId, a.cellTitle to b.cellTitle, a.cellTitleWidth to b.cellTitleWidth,
            a.cellTitleKey to b.cellTitleKey, a.cellTimeText to b.cellTimeText, a.cellTimeWidth to b.cellTimeWidth,
            a.rowStatus to b.rowStatus, a.rowFirstCell to b.rowFirstCell, a.rowCellCount to b.rowCellCount, a.rowEpgBasis to b.rowEpgBasis,
        )) assertNotSame(x, y)
        assertTrue(starts.contentEquals(a.cellStart) && titles.contentEquals(a.cellTitle), "a published snapshot is never written again")
    }

    @Test
    fun holdBiasesTheTarget() {
        val s = store().also { it.buildWith(xmltv(10).writeTo(File(tmp, "a.xml"))) }
        val p = pager(s, MutableRows(200))
        p.ensure(50, ws, holdDirection = 1)
        val down = await(p) { true }
        assertEquals(48, down.firstRow)
        assertEquals(26, down.rowCount, "rows [top − 2, top + 24)")
        p.ensure(150, ws, holdDirection = -1)
        val up = await(p, "up hold") { it.firstRow != 48 }
        assertEquals(150 + 5 - 24, up.firstRow, "mirrored: [top + 5 − 24, top + 5 + 2)")
        assertEquals(26, up.rowCount)
        drain(p)
        // A hold keeps a 6-row margin: top + 5 + 6 must stay covered, so 3 rows further down re-queries.
        val q = p.queries
        p.ensure(51, ws, holdDirection = 1)
        drain(p)
        assertEquals(q + 1, p.queries)
    }

    /** Code review 2026-10-14, G-S1: a steady hold re-queries about every 12 rows, not on every row step. */
    @Test
    fun steadyHoldRequeriesAboutEveryTwelveRows() {
        val s = store().also { it.buildWith(xmltv(10).writeTo(File(tmp, "a.xml"))) }
        val p = pager(s, MutableRows(400))
        p.ensure(50, ws, holdDirection = 1)
        await(p) { true }
        drain(p)
        val q0 = p.queries
        for (top in 51..70) { p.ensure(top, ws, holdDirection = 1); drain(p) }
        val down = p.queries - q0
        assertTrue(down <= 2, "down hold re-queried $down times in 20 one-row steps")
        val q1 = p.queries
        for (top in 69 downTo 50) { p.ensure(top, ws, holdDirection = -1); drain(p) }
        val up = p.queries - q1
        assertTrue(up <= 2, "up hold re-queried $up times in 20 one-row steps")
        // The hold target still covers what the next steps need.
        val d = p.snapshots.value!!
        assertTrue(d.firstRow <= 50 - GuideWindowPagerImpl.HOLD_MARGIN_ROWS && d.firstRow + d.rowCount >= 50 + GuideWindowPagerImpl.VISIBLE_ROWS, "covers the up margin")
    }

    /** Code review 2026-10-14, G-S2: a patch can hand a freed `epg_text` id to another string; widths and flags follow it. */
    @Test
    fun patchClearsTextCaches() {
        val s = store()
        val file = xmltv(10).writeTo(File(tmp, "a.xml"))
        s.buildWith(file)
        val retitle = AtomicBoolean(false)
        val p = pager(s, MutableRows(10), openReader = { s.openReader()?.let { RetitleSpy(it, retitle) } })
        p.ensure(0, ws)
        val d1 = await(p) { true }
        val i = (0 until d1.cellCount).first { d1.cellKind[it] == CellKind.PROGRAMME && d1.cellTextId[it] >= 0 }
        val id = d1.cellTextId[i]
        assertEquals(0, d1.cellFlags[i] and CellFlags.RTL)
        retitle.set(true) // from now on the same ids carry other strings, as after an id reuse
        s.patch(EpgPatchRequest(listOf(EpgSourceFile(1, file)), listOf(EpgChannelRef(1, "ch3")), nowMs, 1, 2))
        val d2 = await(p, "patched snapshot") { it.patchSeq == 1 }
        val j = (0 until d2.cellCount).first { d2.cellTextId[it] == id && d2.cellKind[it] == CellKind.PROGRAMME }
        val t = d2.cellTitle[j]!!
        assertTrue(t.startsWith(RetitleSpy.PREFIX))
        assertEquals(FakeTextMeasurer().width(t, 0, t.length, GuideTextStyle.CELL_TITLE), d2.cellTitleWidth[j], "width of the new string")
        assertTrue(d2.cellFlags[j] and CellFlags.RTL != 0, "RTL flag of the new string")
        assertEquals(app.thebox.core.common.Hash64.of(t), d2.cellTitleKey[j], "title key of the new string")
    }

    /** Code review 2026-10-14, G-S4: the RTL flag is the paragraph direction (first strong character). */
    @Test
    fun rtlFlagFollowsTheFirstStrongCharacter() {
        fun rtl(t: String) = GuideWindowPagerImpl.textFlagsOf(t) and CellFlags.RTL != 0
        assertFalse(rtl("BBC News عربي"))
        assertFalse(rtl("Match of the Day (مباراة)"))
        assertFalse(rtl("123 Show"))
        assertTrue(rtl("الأخبار المسائية"))
        assertTrue(rtl("חדשות ערב"))
        assertTrue(rtl("2026 الأخبار"), "digits are weak: the first strong character decides")
    }

    /** Code review 2026-10-14, O-1: time lines are shared per minute pair and follow a TimeLabels reset. */
    @Test
    fun timeTextsAreCachedAndFollowTheClockForm() {
        val s = store().also { it.buildWith(xmltv(10).writeTo(File(tmp, "a.xml"))) }
        val labels = FakeTimeLabels()
        val p = GuideWindowPagerImpl(s, MutableRows(10), { FakeGuideMetrics() }, { FakeTextMeasurer() }, FakeRowCellsBuilder(), labels, busyRetryMs = 20)
            .also { pagers += it }
        p.ensure(0, ws)
        val d1 = await(p) { true }
        val i = (0 until d1.cellCount).first { d1.cellKind[it] == CellKind.PROGRAMME }
        p.ensure(0, ws + 1440) // the same times of day, a day later
        val d2 = await(p, "second") { it.seq > d1.seq }
        val j = (0 until d2.cellCount).first { d2.cellKind[it] == CellKind.PROGRAMME && labels.minuteOfDay(d2.cellStart[it]) == labels.minuteOfDay(d1.cellStart[i]) && labels.minuteOfDay(d2.cellEnd[it]) == labels.minuteOfDay(d1.cellEnd[i]) }
        assertSame(d1.cellTimeText[i], d2.cellTimeText[j], "one string per minute pair")
        labels.reset(java.time.ZoneId.of("UTC"), clock24 = false)
        p.invalidate()
        val d3 = await(p, "12 h") { it.seq > d2.seq }
        val k = (0 until d3.cellCount).first { d3.cellKind[it] == CellKind.PROGRAMME }
        assertEquals(labels.hm(d3.cellStart[k]) + " – " + labels.hm(d3.cellEnd[k]), d3.cellTimeText[k])
    }

    @Test
    fun basisChangeRequeriesButANameChangeDoesNot() {
        val s = store().also { it.buildWith(xmltv(20).writeTo(File(tmp, "a.xml"))) }
        val rows = MutableRows(20)
        val p = pager(s, rows)
        p.ensure(0, ws)
        val d1 = await(p) { true }
        drain(p)
        val q = p.queries
        rows.set(2, rows.list[2]!!.copy(displayName = "Renamed"))
        p.ensure(0, ws)
        drain(p)
        assertEquals(q, p.queries, "display name only: no query")
        rows.set(2, rows.list[2]!!.copy(epg = EpgChannelRef(1, "ch4")))
        p.ensure(0, ws)
        val d2 = await(p, "re-based snapshot") { it.seq > d1.seq }
        assertTrue(d2.rowEpgBasis[2] != d1.rowEpgBasis[2])
        assertEquals(cellsOf(d2, 4).map { it.first }, cellsOf(d2, 2).map { it.first }, "row 2 now shows ch4's programmes")
        assertTrue(cellsOf(d1, 4).map { it.first } != cellsOf(d1, 2).map { it.first })
        rows.set(4, rows.list[4]!!.copy(epgShiftMin = 60))
        p.ensure(0, ws)
        val d3 = await(p, "shifted snapshot") { it.seq > d2.seq }
        val shifted = cellsOf(d3, 4).filter { it.third == CellKind.PROGRAMME }.map { it.first }
        val base = cellsOf(d1, 4).filter { it.third == CellKind.PROGRAMME }.map { it.first + 60 }
        assertTrue(shifted.intersect(base.toSet()).size >= 10, "row 4 shifted by an hour")
    }

    @Test
    fun infoAndAtOnTheEpgThread() = runBlocking {
        val s = store().also { it.buildWith(xmltv(5).writeTo(File(tmp, "a.xml"))) }
        val rows = MutableRows(5)
        val p = pager(s, rows)
        p.ensure(0, ws)
        await(p) { true }
        val ch = rows.list[1]!!
        val airing = assertNotNull(p.at(ch, now))
        assertTrue(airing.startMin <= now && airing.endMin > now)
        val info = assertNotNull(p.info(ch, airing.rawStartMin))
        assertEquals(airing.title, info.title)
        assertEquals("Show 1", info.title)
        assertNull(p.info(ch, now + 7))
        assertNull(p.at(ch.copy(epg = null), now))
    }

    @Test
    fun isBusyOnConstructedMessages() {
        fun ex(code: Int) = RuntimeException("Error code: $code, message: database is locked")
        assertTrue(SqliteBusy.isBusy(ex(5)))
        assertTrue(SqliteBusy.isBusy(ex(261)), "SQLITE_BUSY_RECOVERY")
        assertTrue(SqliteBusy.isBusy(ex(517)), "SQLITE_BUSY_SNAPSHOT")
        assertFalse(SqliteBusy.isBusy(ex(1)))
        assertFalse(SqliteBusy.isBusy(ex(6)), "SQLITE_LOCKED is not busy")
        assertTrue(SqliteBusy.isBusy(IllegalStateException("wrapped", ex(5))))
        assertFalse(SqliteBusy.isBusy(RuntimeException("no code")))
    }

    /** Records the thread each close() runs on. */
    private class ClosingSpy(private val d: EpgReader, private val closedOn: MutableList<String>) : EpgReader by d {
        override fun close() {
            closedOn += Thread.currentThread().name
            d.close()
        }
    }

    /** While [on], window() returns other strings for the same text ids (an `epg_text` id reused by a patch). */
    private class RetitleSpy(private val d: EpgReader, private val on: AtomicBoolean) : EpgReader by d {
        override fun window(channels: IntArray, shiftMin: IntArray, fromMin: Int, toMin: Int, out: EpgWindow) {
            d.window(channels, shiftMin, fromMin, toMin, out)
            if (on.get()) for (k in 0 until out.size) out.titles[k] = out.titles[k]?.let { PREFIX + it + " (extended edition)" }
        }
        companion object { const val PREFIX = "عربي " }
    }

    /** window() throws SQLITE_BUSY while [failures] > 0. */
    private class BusySpy(private val d: EpgReader, private val failures: AtomicInteger) : EpgReader by d {
        override fun window(channels: IntArray, shiftMin: IntArray, fromMin: Int, toMin: Int, out: EpgWindow) {
            if (failures.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) throw RuntimeException("Error code: 5, message: database is locked")
            d.window(channels, shiftMin, fromMin, toMin, out)
        }
    }
}
