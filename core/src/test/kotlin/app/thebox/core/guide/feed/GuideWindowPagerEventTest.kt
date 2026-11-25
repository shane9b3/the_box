package app.thebox.core.guide.feed

import app.thebox.core.common.Hash64
import app.thebox.core.epg.EpgBuildRequest
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.epg.EpgSourceFile
import app.thebox.core.epg.EpgWhitelist
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.epg.EpgWindow
import app.thebox.core.epg.ProgrammeFlags
import app.thebox.core.epg.store.EpgStoreImpl
import app.thebox.core.epg.testutil.EpgTestDb
import app.thebox.core.events.ChannelEvent
import app.thebox.core.events.EventConstants
import app.thebox.core.events.EventZoneSource
import app.thebox.core.guide.CellFlags
import app.thebox.core.guide.CellKind
import app.thebox.core.guide.CellSink
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideRows
import app.thebox.core.guide.GuideWindowData
import app.thebox.core.guide.RowStatus
import app.thebox.core.guide.fakes.FakeGuideMetrics
import app.thebox.core.guide.fakes.FakeTextMeasurer
import app.thebox.core.guide.fakes.FakeTimeLabels
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.guide.layout.RowCellsBuilderImpl
import app.thebox.core.guide.state.GuideTexts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Event channels, docs/design/m6-event-channels.md §6.1, §6.2, §11.1 `GuideWindowPagerEventTest`: the read-time overlay
 * of an event read from a channel name. The placeholder test is injected (`{ it in FILLERS }`), so these tests never
 * depend on WP-1's `EpgFiller` answers. Integration re-points: none needed; the real `EpgFiller` is covered by WP-1.
 */
class GuideWindowPagerEventTest {
    @TempDir
    lateinit var tmp: File

    private val nowMs = EpgTestDb.minutes("2026-10-13T12:00:00Z") * 60_000L
    private val now = (nowMs / 60_000L).toInt()
    private val ws = GuideFixtures.floor30(now)
    private val pagers = ArrayList<GuideWindowPagerImpl>()
    private val labels = FakeTimeLabels()

    @AfterEach
    fun tearDown() = pagers.forEach { it.close() }

    private class Rows(val list: List<GuideChannel?>) : GuideRows {
        override val version: Long = 1
        override val size: Int get() = list.size
        override fun channel(index: Int): GuideChannel? = list.getOrNull(index)
        override fun indexOf(rowId: Long): Int = list.indexOfFirst { it?.rowId == rowId }
    }

    private fun event(title: String, start: Int, end: Int, zone: EventZoneSource = EventZoneSource.EXPLICIT) =
        ChannelEvent(ChannelEvent.Kind.EVENT, title, null, start, end, zone)

    private fun channel(i: Int, ev: ChannelEvent?, epg: EpgChannelRef? = EpgChannelRef(1, "ch$i")) =
        GuideFixtures.channel(i, epg = epg).copy(event = ev)

    private fun store(): EpgStoreImpl = EpgTestDb.openStore(File(tmp, "epg"), clock = { nowMs })

    private fun EpgStoreImpl.buildWith(b: EpgTestDb.XmltvBuilder) {
        build(EpgBuildRequest(listOf(EpgSourceFile(1, b.writeTo(File(tmp, "g.xml")))), nowMs, pastDays = 1, futureDays = 2),
            EpgWhitelistResolver { _, _ -> EpgWhitelist.ALL })
    }

    private fun pager(s: EpgStoreImpl, rows: GuideRows, noReader: Boolean = false): GuideWindowPagerImpl = GuideWindowPagerImpl(
        s, rows, { FakeGuideMetrics() }, { FakeTextMeasurer() }, RowCellsBuilderImpl(labels), labels,
        openReader = if (noReader) ({ null }) else s::openReader, busyRetryMs = 20, isFiller = { it in FILLERS },
    ).also { pagers += it }

    private fun await(p: GuideWindowPagerImpl, pred: (GuideWindowData) -> Boolean): GuideWindowData = runBlocking {
        withTimeout(5_000) { p.snapshots.first { it != null && pred(it) }!! }
    }

    private data class C(val start: Int, val end: Int, val raw: Int, val kind: Byte, val flags: Int, val textId: Int, val title: String?)

    private fun cells(d: GuideWindowData, row: Int): List<C> {
        val r = row - d.firstRow
        val f = d.rowFirstCell[r]
        return (f until f + d.rowCellCount[r]).map {
            C(d.cellStart[it], d.cellEnd[it], d.cellRawStart[it], d.cellKind[it], d.cellFlags[it], d.cellTextId[it], d.cellTitle[it])
        }
    }

    private fun blocks(id: String, from: Int, to: Int, title: String = "No Event Today", step: Int = 240) = EpgTestDb.xmltv {
        channel(id, id)
        var m = from
        while (m < to) { programme(id, m, m + step, title); m += step }
    }

    // ---------------------------------------------------------------------------------------------- the overlay, pure

    @Test
    fun placeholdersAreClippedAroundTheEventAndASplitTailKeepsItsRawStart() {
        val src = window(
            Row(0, 0, 240, 0, "No Event Today"),
            Row(0, 240, 480, 240, "No Event Today"),
        )
        val out = EpgWindow(8)
        val ins = BooleanArray(1)
        val n = EventOverlay.apply(src, arrayOf<GuideChannel?>(channel(0, event("Vegas @ Edmonton", 300, 420))), -100, 1000, { it in FILLERS }, out, ins)
        assertEquals(1, n)
        assertTrue(ins[0])
        assertEquals(
            listOf(
                Row(0, 0, 240, 0, "No Event Today"),
                Row(0, 240, 300, 240, "No Event Today"),
                Row(0, 300, 420, 300, "Vegas @ Edmonton", CellFlags.EVENT_NAME, EventConstants.EVENT_TEXT_ID),
                Row(0, 420, 480, 240, "No Event Today"),
            ),
            rows(out),
            "the split tail keeps the placeholder's raw start, so info() still finds it",
        )
    }

    @Test
    fun aPlaceholderStartingWithTheEventLosesItsTailSoTheTailNeverShowsTheEvent() {
        // (Integration) The block starts at the event's minute: its tail would keep raw start 300, where info() answers
        // the event. The tail is dropped; its time is a gap ("No information").
        val src = window(
            Row(0, 60, 300, 60, "No Event Today"),
            Row(0, 300, 540, 300, "No Event Today"),
            Row(0, 540, 780, 540, "No Event Today"),
        )
        val out = EpgWindow(8)
        EventOverlay.apply(src, arrayOf<GuideChannel?>(channel(0, event("Montreal @ Colorado", 300, 480))), -100, 1000, { it in FILLERS }, out, BooleanArray(1))
        assertEquals(
            listOf(
                Row(0, 60, 300, 60, "No Event Today"),
                Row(0, 300, 480, 300, "Montreal @ Colorado", CellFlags.EVENT_NAME, EventConstants.EVENT_TEXT_ID),
                Row(0, 540, 780, 540, "No Event Today"),
            ),
            rows(out),
        )
    }

    @Test
    fun aRealListingOverTheSpanWinsAndTheRowsAreCopiedAsTheyAre() {
        val src = window(
            Row(0, 0, 240, 0, "No Event Today"),
            Row(0, 240, 480, 240, "NHL Hockey: Golden Knights at Oilers"),
        )
        val out = EpgWindow(8)
        val ins = BooleanArray(1)
        val n = EventOverlay.apply(src, arrayOf<GuideChannel?>(channel(0, event("Vegas @ Edmonton", 240, 420))), -100, 1000, { it in FILLERS }, out, ins)
        assertEquals(0, n)
        assertFalse(ins[0])
        assertEquals(rows(src), rows(out))
    }

    @Test
    fun aContinuationAtTheEventsEndIsNeverMergedIntoTheEventCell() {
        // A 7 h placeholder split at 6 h: head [0, 360) CONTINUED, continuation [360, 420). The event covers [300, 360).
        val src = window(
            Row(0, 0, 360, 0, "Signing Off", ProgrammeFlags.CONTINUED),
            Row(0, 360, 420, 360, "Signing Off", ProgrammeFlags.CONTINUATION),
        )
        val out = EpgWindow(8)
        EventOverlay.apply(src, arrayOf<GuideChannel?>(channel(0, event("Late show", 300, 360))), 0, 480, { it in FILLERS }, out, BooleanArray(1))
        val tail = rows(out).last()
        assertEquals(0, tail.flags and ProgrammeFlags.CONTINUATION)
        val sink = RecordingSink()
        RowCellsBuilderImpl(labels).build(out, 0, out.size, 0, 480, false, sink)
        val ev = sink.cells.single { (it.flags and CellFlags.EVENT_NAME) != 0 }
        assertEquals(300 to 360, ev.start to ev.end, "the event cell keeps its own end")
    }

    @Test
    fun eventsOutsideTheWindowAndEmptySlotsAreNotInserted() {
        val out = EpgWindow(8)
        val ins = BooleanArray(3)
        val chans = arrayOf<GuideChannel?>(
            channel(0, event("Tomorrow", 2_000, 2_100)),
            channel(1, ChannelEvent.EMPTY),
            channel(2, null),
        )
        assertEquals(0, EventOverlay.apply(EpgWindow(1), chans, 0, 1_000, { false }, out, ins))
        assertEquals(0, out.size)
        assertFalse(EventOverlay.anyEvent(arrayOf<GuideChannel?>(channel(1, ChannelEvent.EMPTY), null, channel(2, null))))
        assertTrue(EventOverlay.anyEvent(chans))
    }

    // ---------------------------------------------------------------------------------------------- through the pager

    @Test
    fun theEventIsDrawnOverPlaceholdersWithTheSyntheticTextId() {
        val s = store().also { it.buildWith(blocks("ch0", now - 240, now + 24 * 60)) }
        val ev = event("Vegas @ Edmonton", now + 60, now + 180)
        val p = pager(s, Rows(listOf(channel(0, ev))))
        p.ensure(0, ws)
        val d = await(p) { it.generation > 0 }
        val row = cells(d, 0)
        val e = row.single { (it.flags and CellFlags.EVENT_NAME) != 0 }
        assertEquals(C(now + 60, now + 180, now + 60, CellKind.PROGRAMME, CellFlags.EVENT_NAME, EventConstants.EVENT_TEXT_ID, "Vegas @ Edmonton"), e)
        val i = d.rowFirstCell[0] + row.indexOf(e)
        assertEquals(Hash64.of("Vegas @ Edmonton"), d.cellTitleKey[i])
        assertTrue(d.cellTitleWidth[i] > 0f)
        val before = row[row.indexOf(e) - 1]
        val after = row[row.indexOf(e) + 1]
        assertEquals("No Event Today" to (now + 60), before.title to before.end)
        assertEquals("No Event Today" to now, after.title to after.raw, "the tail keeps the block's raw start")
        assertEquals(RowStatus.OK, d.rowStatus[0])
    }

    @Test
    fun aRealListingAtTheSameMinuteIsShownInstead() {
        val xml = EpgTestDb.xmltv {
            channel("ch0", "ch0")
            programme("ch0", now - 120, now + 60, "No Event Today")
            programme("ch0", now + 60, now + 300, "NHL Hockey: Golden Knights at Oilers")
        }
        val s = store().also { it.buildWith(xml) }
        val ev = event("Vegas @ Edmonton", now + 60, now + 240)
        val p = pager(s, Rows(listOf(channel(0, ev))))
        p.ensure(0, ws)
        val d = await(p) { it.generation > 0 }
        assertTrue(cells(d, 0).none { (it.flags and CellFlags.EVENT_NAME) != 0 })
        assertTrue(cells(d, 0).any { it.title == "NHL Hockey: Golden Knights at Oilers" })
        val info = runBlocking { p.info(channel(0, ev), now + 60) }
        assertEquals("NHL Hockey: Golden Knights at Oilers", info?.title, "the listing's details, not the name's")
    }

    @Test
    fun withNoReaderTheEventIsStillDrawnAndItsRowIsNeverNoEpg() {
        val s = store() // no generation at all
        val ev = event("Vegas @ Edmonton", now + 60, now + 240)
        val p = pager(s, Rows(listOf(channel(0, ev, epg = null), channel(1, null, epg = null))), noReader = true)
        p.ensure(0, ws)
        val d = await(p) { it.rowCount == 2 }
        assertEquals(-1L, d.generation)
        assertEquals(RowStatus.OK, d.rowStatus[0])
        val row = cells(d, 0)
        val k = row.indexOfFirst { it.kind == CellKind.PROGRAMME }
        assertTrue(k > 0 && row.take(k).all { it.kind == CellKind.GAP } && row.drop(k + 1).all { it.kind == CellKind.GAP }, "$row")
        assertEquals("Vegas @ Edmonton", row.single { it.kind == CellKind.PROGRAMME }.title)
        assertEquals(RowStatus.NO_EPG, d.rowStatus[1], "an ordinary channel is unchanged")
    }

    @Test
    fun infoAndAtSynthesiseTheEventWithNoReader() = runBlocking {
        val s = store()
        val ev = event("Grizzlies @ Hornets", now + 60, now + 240, EventZoneSource.ASSUMED)
        val ch = channel(0, ev, epg = null)
        val p = pager(s, Rows(listOf(ch)), noReader = true)
        val info = assertNotNull(p.info(ch, now + 60))
        assertEquals("Grizzlies @ Hornets", info.title)
        assertEquals(now + 60 to now + 240, info.startMin to info.endMin)
        assertEquals(now + 60, info.rawStartMin)
        assertEquals(GuideTexts.EVENT_FROM_NAME + " " + GuideTexts.EVENT_ZONE_ASSUMED, info.description)
        assertNull(info.genre)
        assertEquals(0, info.flags)
        assertEquals(Hash64.of("Grizzlies @ Hornets"), info.titleHash)
        assertNull(p.info(ch, now + 61), "only on the event's start minute")
        assertEquals("Grizzlies @ Hornets", p.at(ch, now + 100)?.title)
        assertNull(p.at(ch, now + 240), "the end is exclusive")
        val explicit = channel(0, ev.copy(zone = EventZoneSource.EXPLICIT), epg = null)
        assertEquals(GuideTexts.EVENT_FROM_NAME, p.info(explicit, now + 60)?.description)
    }

    @Test
    fun infoOnAPlaceholderAtTheEventsStartIsTheEventAndTheCacheKeysOnTheEventBasis() = runBlocking {
        val s = store().also { it.buildWith(blocks("ch0", now - 240, now + 24 * 60)) }
        val a = channel(0, event("Montreal @ Colorado", now, now + 180))
        val p = pager(s, Rows(listOf(a)))
        p.ensure(0, ws)
        await(p) { it.generation > 0 }
        assertEquals("Montreal @ Colorado", p.info(a, now)?.title, "a placeholder detail gives way to the event")
        // (Integration) The block starts with the event: its tail [now + 180, now + 240) is not drawn, so no cell there
        // asks info(a, now) and shows the event's details; the time reads as a gap.
        val d = await(p) { it.generation > 0 && it.rowCellCount.isNotEmpty() }
        val tail = cells(d, 0).filter { it.start < now + 240 && it.end > now + 180 }
        assertTrue(tail.none { it.raw == now && (it.flags and CellFlags.EVENT_NAME) == 0 }, "tail cells: $tail")
        // The provider renamed the slot: same start, another event. The cached answer must not come back (arch S10).
        val b = channel(0, event("Detroit @ Orlando", now, now + 180))
        assertEquals("Detroit @ Orlando", p.info(b, now)?.title)
        assertEquals("Detroit @ Orlando", p.at(b, now + 10)?.title)
        // An ordinary channel on the same guide gets the placeholder as before.
        assertEquals("No Event Today", p.info(channel(0, null), now)?.title)
    }

    // ---------------------------------------------------------------------------------------------- cost (§6.7, §11.1)

    /** §11.1: a 300-row window with 60 events builds its overlay in under 1 ms on the JVM (median of 200 runs). */
    @Test
    fun overlayOf60EventsOver300RowsIsUnderAMillisecond() {
        val ms = overlayMedianMs(rows = 300, events = 60)
        println("EVENT_OVERLAY_COST rows=300 events=60 median_ms=${"%.4f".format(ms)}")
        assertTrue(ms < 1.0, "median $ms ms")
    }

    /**
     * The owner's whole index at once (~1,700 events, one per row, each over 4 h placeholder blocks): far beyond any one
     * window (a window holds at most ~26 rows), as an upper bound on the overlay's cost per build.
     */
    @Test
    fun overlayOf1700EventsStaysCheap() {
        val ms = overlayMedianMs(rows = 1_700, events = 1_700)
        println("EVENT_OVERLAY_COST rows=1700 events=1700 median_ms=${"%.4f".format(ms)}")
        assertTrue(ms < 10.0, "median $ms ms")
    }

    private fun overlayMedianMs(rows: Int, events: Int): Double {
        val from = 0
        val to = 540
        val chans = Array<GuideChannel?>(rows) { i ->
            channel(i, if (i % (rows / events) == 0) event("Event $i", 120 + (i % 7) * 15, 300 + (i % 7) * 15) else null)
        }
        val src = EpgWindow(rows * 4)
        for (i in 0 until rows) {
            var m = -120
            while (m < to) {
                val k = src.size
                src.ensureCapacity(k + 1)
                src.channelIndex[k] = i; src.startMin[k] = m; src.endMin[k] = m + 240; src.rawStartMin[k] = m
                src.textId[k] = 1 + (i and 1); src.flags[k] = 0; src.titles[k] = if (i % 2 == 0) "No Event Today" else "Real show $i"
                src.size = k + 1
                m += 240
            }
        }
        val out = EpgWindow(512)
        val filler: (String) -> Boolean = { it in FILLERS }
        val times = DoubleArray(200)
        repeat(300) { EventOverlay.apply(src, chans, from, to, filler, out, BooleanArray(rows)) } // warm-up (JIT)
        for (r in times.indices) {
            val ins = BooleanArray(rows)
            val t0 = System.nanoTime()
            if (EventOverlay.anyEvent(chans)) EventOverlay.apply(src, chans, from, to, filler, out, ins)
            times[r] = (System.nanoTime() - t0) / 1e6
        }
        times.sort()
        return times[times.size / 2]
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private data class Row(val ch: Int, val start: Int, val end: Int, val raw: Int, val title: String?, val flags: Int = 0, val textId: Int = 7)

    private fun window(vararg rs: Row): EpgWindow {
        val w = EpgWindow(rs.size)
        for ((k, r) in rs.withIndex()) {
            w.channelIndex[k] = r.ch; w.startMin[k] = r.start; w.endMin[k] = r.end; w.rawStartMin[k] = r.raw
            w.titles[k] = r.title; w.flags[k] = r.flags; w.textId[k] = r.textId
        }
        w.size = rs.size
        return w
    }

    private fun rows(w: EpgWindow) = (0 until w.size).map {
        Row(w.channelIndex[it], w.startMin[it], w.endMin[it], w.rawStartMin[it], w.titles[it], w.flags[it], w.textId[it])
    }

    private class RecordingSink : CellSink {
        val cells = ArrayList<C>()
        override fun add(start: Int, end: Int, rawStart: Int, kind: Byte, flags: Int, textId: Int, title: String?) {
            cells += C(start, end, rawStart, kind, flags, textId, title)
        }
    }

    companion object {
        val FILLERS = setOf("No Event Today", "Signing Off")
    }
}
