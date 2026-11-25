package app.thebox.core.guide.state

import app.thebox.core.common.Hash64
import app.thebox.core.events.ChannelEvent
import app.thebox.core.events.EventZoneSource
import app.thebox.core.guide.CellFlags
import app.thebox.core.guide.CellKind
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.MenuItem
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.fakes.FakeTimeLabels
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.guide.fakes.GuideFixtures.Cell
import app.thebox.core.input.BoxAction.CONTEXT_MENU
import app.thebox.core.input.BoxAction.DOWN
import app.thebox.core.input.BoxAction.LEFT
import app.thebox.core.input.BoxAction.REMIND
import app.thebox.core.input.BoxAction.RIGHT
import app.thebox.core.input.BoxAction.SELECT
import app.thebox.core.input.BoxAction.START_OVER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Event channels, docs/design/m6-event-channels.md §6.3, §6.4 (U-E-S3, U-E-N2, arch S6): the info panel around an
 * event read from a channel name, its "about" time line, and catch-up gated on [CellFlags.EVENT_NAME].
 */
class GuideEventPanelTest {
    private val now = GuideWorld.NOW_MIN
    private val from = GuideWorld.FROM
    private val to = GuideWorld.TO
    private val labels = FakeTimeLabels()

    private fun ev(title: String, start: Int, end: Int) = ChannelEvent(ChannelEvent.Kind.EVENT, title, null, start, end, EventZoneSource.EXPLICIT)

    private val channels: List<GuideChannel> = List(10) { i ->
        val base = GuideFixtures.channel(i, archiveDays = if (i in setOf(0, 1, 4, 5)) 7 else 0, number = 101 + i)
        when (i) {
            1 -> base.copy(event = ev("Vegas @ Edmonton", now - 30, now + 150))
            2 -> base.copy(event = ChannelEvent.EMPTY)
            4 -> base.copy(event = ev("Calgary @ Seattle", now - 240, now - 60))
            5 -> base.copy(event = ev("Yesterday's game", now - 420, now - 240))
            6 -> base.copy(event = ev("Montreal @ Colorado", now + 90, now + 270))
            else -> base
        }
    }

    private val rows: List<List<Cell>> = GuideFixtures.syntheticRows(10, from, to, noEpgRows = setOf(3)).mapIndexed { i, synthetic ->
        val e = CellFlags.EVENT_NAME
        when (i) {
            1 -> listOf(Cell(from, now - 30, title = "Before"), Cell(now - 30, now + 150, title = "Vegas @ Edmonton", flags = e), Cell(now + 150, to, CellKind.GAP))
            2 -> listOf(Cell(from, to, CellKind.NO_EPG))
            4 -> listOf(Cell(from, now - 240, CellKind.GAP), Cell(now - 240, now - 60, title = "Calgary @ Seattle", flags = e), Cell(now - 60, to, CellKind.GAP))
            5 -> listOf(Cell(from, now - 420, CellKind.GAP), Cell(now - 420, now - 240, title = "Yesterday's game", flags = e), Cell(now - 240, to, CellKind.GAP))
            6 -> listOf(Cell(from, now + 90, CellKind.GAP), Cell(now + 90, now + 270, title = "Montreal @ Colorado", flags = e), Cell(now + 270, to, CellKind.GAP))
            else -> synthetic
        }
    }

    private fun world() = GuideWorld(channels = channels, data = GuideFixtures.windowData(rows, fromMin = from, toMin = to))

    private fun GuideWorld.toRow(r: Int) { repeat(r) { press(DOWN) }; assertEquals(r, machine.frame.focus!!.row) }

    @Test
    fun theEventCellsTimeLineSaysAboutAndFromTheChannelName() {
        val w = world()
        w.toRow(1)
        val p = w.ui.panel!!
        assertEquals("Vegas @ Edmonton", p.title)
        assertEquals(labels.hm(now - 30) + GuideTexts.DASH + GuideTexts.ABOUT + labels.hm(now + 150) + GuideTexts.SEP + GuideTexts.FROM_NAME, p.timeLine)
        assertFalse(p.noInfo)
    }

    @Test
    fun anEmptySlotSaysNoEventScheduled() {
        val w = world()
        w.toRow(2)
        val p = w.ui.panel!!
        assertEquals(GuideTexts.NO_EVENT, p.title)
        assertEquals(GuideTexts.NO_EVENT_NEXT, p.description)
        assertTrue(p.noInfo)
    }

    @Test
    fun afterAnEventItMayStillBeOnForThreeHoursThenItHasEnded() {
        val w = world()
        w.toRow(4)
        // (Code review 2026-11-25, UI m2) The event's own title beside "this event may still be on".
        assertEquals("Calgary @ Seattle", w.ui.panel!!.title)
        assertEquals(GuideTexts.EVENT_MAY_BE_ON, w.ui.panel!!.description)
        w.press(DOWN)
        assertEquals(GuideTexts.NO_EVENT, w.ui.panel!!.title)
        assertEquals(GuideTexts.EVENT_ENDED, w.ui.panel!!.description)
    }

    @Test
    fun beforeAnEventThePanelNamesTheNextOne() {
        val w = world()
        w.toRow(6)
        assertEquals(GuideTexts.NO_INFORMATION, w.ui.panel!!.title)
        assertEquals(GuideTexts.nextEvent("Montreal @ Colorado", labels.hmPeriod(now + 90)), w.ui.panel!!.description)
    }

    /** Code review 2026-11-25, UI m2: the time carries AM / PM in the 12 h form, and the day when it is not today. */
    @Test
    fun theNextEventsTimeHasItsPeriodAndDay() {
        val zone = java.time.ZoneId.of("America/New_York")
        val t = app.thebox.core.guide.layout.TimeTables(zone, clock24 = false, { now * 60_000L }, java.util.Locale.US)
        val at = java.time.ZonedDateTime.of(2026, 11, 25, 22, 0, 0, 0, zone).toEpochSecond().toInt() / 60
        assertEquals("10:00 PM", t.hmPeriod(at))
        val t24 = app.thebox.core.guide.layout.TimeTables(zone, clock24 = true, { now * 60_000L }, java.util.Locale.US)
        assertEquals("22:00", t24.hmPeriod(at))
        assertEquals("Next on this channel: Sharks vs. Ducks, tomorrow at 10:00 PM.", GuideTexts.nextEvent("Sharks vs. Ducks", "10:00 PM", GuideTexts.TOMORROW_WORD))
        assertEquals("Next on this channel: Sharks vs. Ducks at 10:00 PM.", GuideTexts.nextEvent("Sharks vs. Ducks", "10:00 PM", null))
    }

    @Test
    fun anOrdinaryChannelsGapsAreUnchanged() {
        val w = world()
        w.toRow(3)
        assertEquals(GuideTexts.NO_INFORMATION, w.ui.panel!!.title)
        assertEquals(GuideTexts.NO_GUIDE_DATA_CHANNEL, w.ui.panel!!.description)
    }

    @Test
    fun startOverAndWatchFromStartAreNeverOfferedOnAnEventCell() {
        val w = world()
        // Control: the tuned row 0 has a 7-day archive and an ordinary programme on now.
        w.press(CONTEXT_MENU)
        assertTrue(MenuItem.WATCH_FROM_START in assertIs<Overlay.Menu>(w.ui.overlay).items)
        val c = GuideWorld(channels = channels, data = GuideFixtures.windowData(rows, fromMin = from, toMin = to))
        c.toRow(1)
        c.press(START_OVER)
        assertEquals(GuideToast.NO_CATCHUP, c.calls.last("toast")!!.args[0])
        assertNull(c.calls.last("playArchive"))
        c.press(CONTEXT_MENU)
        val items = assertIs<Overlay.Menu>(c.ui.overlay).items
        assertFalse(MenuItem.WATCH_FROM_START in items)
        assertFalse(MenuItem.WATCH in items)
    }

    @Test
    fun aPastEventCellOnAnArchiveChannelIsNotInCatchUp() {
        val w = world()
        w.toRow(4)
        w.press(LEFT)
        val f = w.machine.frame.focus!!
        assertEquals(now - 240 to now - 60, f.cellStart to f.cellEnd)
        assertTrue(w.ui.panel!!.timeLine.endsWith(GuideTexts.SEP + GuideTexts.NOT_IN_CATCHUP), w.ui.panel!!.timeLine)
        assertTrue(w.ui.panel!!.timeLine.contains(GuideTexts.ABOUT))
        w.press(CONTEXT_MENU)
        assertFalse(MenuItem.WATCH in assertIs<Overlay.Menu>(w.ui.overlay).items)
        w.press(app.thebox.core.input.BoxAction.BACK)
        w.press(SELECT)
        assertNull(w.calls.last("playArchive"), "OK on a past event tunes the channel, never its archive")
    }

    /** §6.2 / arch S10: a loaded detail with another title (a renamed slot at the same start) is not reused. */
    @Test
    fun aLoadedInfoWithAnotherTitleIsNotReusedForTheReminder() {
        val w = world()
        w.toRow(6)
        w.press(RIGHT)
        assertEquals("Montreal @ Colorado", w.ui.panel!!.title)
        w.advance(200)
        val token = w.calls.last("loadInfo")!!.args[0] as Long
        val stale = ProgrammeInfo(now + 90, now + 90, now + 270, "Detroit @ Orlando", null, "old", null, null, 0, Hash64.of("Detroit @ Orlando"))
        w.send(GuideEvent.Info(token, stale))
        w.press(REMIND)
        val info = w.calls.last("setReminder")!!.args[1] as ProgrammeInfo
        assertEquals("Montreal @ Colorado", info.title)
        assertEquals(now + 90, info.rawStartMin)
    }
}
