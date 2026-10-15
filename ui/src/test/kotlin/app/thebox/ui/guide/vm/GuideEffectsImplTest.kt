package app.thebox.ui.guide.vm

import app.thebox.core.guide.GroupAction
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.ReminderAlert
import app.thebox.core.guide.fakes.FakeGuideLibrary
import app.thebox.core.guide.fakes.FakeGuideRowsCache
import app.thebox.core.guide.fakes.FakeGuideSessionStore
import app.thebox.core.guide.fakes.FakeGuideSettings
import app.thebox.core.guide.fakes.FakeGuideWindowPager
import app.thebox.core.guide.fakes.FakeReminderAlerts
import app.thebox.core.guide.fakes.FakeReminderBook
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.player.api.TuneTarget
import app.thebox.ui.playback.CatchupFailure
import app.thebox.ui.playback.CatchupLaunch
import app.thebox.ui.shelltest.FakeCatchupLauncher
import app.thebox.ui.shelltest.FakePlaybackController
import app.thebox.ui.shelltest.RecordingShellEffects
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each effect calls the right collaborator (docs/design/m3-guide.md §5.3, §3.16.3). */
class GuideEffectsImplTest {
    private val channels = GuideFixtures.channels(10, archiveEvery = 0)
    private val ch = channels[3]
    private val info = GuideFixtures.info(GuideFixtures.NOW_MIN + 30, GuideFixtures.NOW_MIN + 90, "Late Edition", shiftMin = 60)

    private class Rig(scope: TestScope, channels: List<app.thebox.core.guide.GuideChannel>) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val controller = FakePlaybackController()
        val library = FakeGuideLibrary(channels)
        val rows = FakeGuideRowsCache(channels)
        val pager = FakeGuideWindowPager()
        val book = FakeReminderBook()
        val alerts = FakeReminderAlerts()
        val catchup = FakeCatchupLauncher()
        val session = FakeGuideSessionStore()
        val settings = FakeGuideSettings()
        val shell = RecordingShellEffects()
        val events = ArrayList<GuideEvent>()
        val lineups = ArrayList<GuideLineup>()
        val effects = GuideEffectsImpl(
            scope = scope, io = dispatcher, controller = controller, library = library, rows = { rows }, pager = { pager },
            reminders = book, alerts = alerts, catchup = catchup, session = session, settings = settings,
            dispatch = { events += it }, shell = shell, onLineup = { lineups += it },
        )
    }

    @Test
    fun loadLineupFeedsRowsPagerMachineAndZapList() = runTest {
        val r = Rig(this, channels)
        r.effects.loadLineup(GuideSource.All(1))
        advanceUntilIdle()
        val ev = r.events.filterIsInstance<GuideEvent.Lineup>().single()
        assertEquals(10, ev.lineup.rowIds.size)
        assertEquals(10, r.rows.size)
        assertEquals(1, r.pager.invalidations)
        assertEquals(listOf(ev.lineup), r.lineups)
        // A re-emission (hide) reaches everyone again.
        r.library.hideChannel(ch.identity)
        advanceUntilIdle()
        assertEquals(9, r.events.filterIsInstance<GuideEvent.Lineup>().last().lineup.rowIds.size)
        r.effects.close()
    }

    @Test
    fun rowsAndWindowAreForwarded() = runTest {
        val r = Rig(this, channels)
        r.effects.ensureRows(0, 18)
        r.effects.ensureWindow(4, 1_000, -1)
        assertEquals(0 until 18, r.rows.ensured.single())
        assertEquals(FakeGuideWindowPager.Ensure(4, 1_000, -1), r.pager.ensures.single())
    }

    @Test
    fun infoAndTunedNowComeBackAsEvents() = runTest {
        val r = Rig(this, channels)
        r.pager.infos[ch.rowId to info.rawStartMin] = info
        r.pager.airing[ch.rowId] = info
        r.effects.loadInfo(7L, ch, info.rawStartMin)
        r.effects.loadTunedNow(ch, GuideFixtures.NOW_MIN)
        advanceUntilIdle()
        assertEquals(GuideEvent.Info(7L, info), r.events[0])
        assertEquals(GuideEvent.TunedNow(info), r.events[1])
    }

    @Test
    fun tuneUsesTheCapturedLineupAsZapList() = runTest {
        val r = Rig(this, channels)
        val lineup = GuideLineup(GuideSource.All(1), 5, channels.map { it.rowId }.toLongArray(), 0)
        r.effects.tune(ch, lineup)
        val (target, zap) = r.controller.tunes.single()
        assertEquals(TuneTarget.Channel(ch.identity), target)
        assertEquals("lineup:5", zap!!.id)
    }

    @Test
    fun controllerPassThroughs() = runTest {
        val r = Rig(this, channels)
        r.effects.zap(+1, isRepeat = true)
        r.effects.previousChannel()
        r.effects.retry()
        assertEquals(listOf("zap:1:true", "previousChannel", "retry"), r.controller.calls)
    }

    @Test
    fun playArchivePassesTheRawEndAndToastsFailures() = runTest {
        val r = Rig(this, channels)
        r.effects.playArchive(ch, info)
        advanceUntilIdle()
        assertEquals(info.rawStartMin + 60, r.catchup.calls.single().third)
        assertTrue(r.shell.toasts.isEmpty())
        r.catchup.result = CatchupLaunch.Failed(CatchupFailure.OUTSIDE_ARCHIVE)
        r.effects.playArchive(ch, info)
        advanceUntilIdle()
        assertEquals(GuideToast.CATCHUP_FAILED to "OUTSIDE_ARCHIVE", r.shell.toasts.single())
    }

    @Test
    fun firstReminderExplainsOnceThenPlainToast() = runTest {
        val r = Rig(this, channels)
        r.effects.setReminder(ch, info)
        advanceUntilIdle()
        assertEquals(GuideToast.REMINDER_SET_FIRST_TIME, r.shell.toasts.last().first)
        assertEquals(60, r.book.pending.value.single().shiftMin)
        r.effects.setReminder(channels[4], info)
        advanceUntilIdle()
        assertEquals(GuideToast.REMINDER_SET, r.shell.toasts.last().first)
        r.effects.cancelReminder(ch, info.rawStartMin)
        advanceUntilIdle()
        assertEquals(GuideToast.REMINDER_CANCELLED, r.shell.toasts.last().first)
        assertEquals(1, r.book.pending.value.size)
    }

    @Test
    fun acceptingAReminderTunesAndGoneChannelsAreOrphaned() = runTest {
        val r = Rig(this, channels)
        val a11 = ReminderAlert(11, ch.identity, ch.displayName, "Late Edition", 0, late = false, autoSwitch = false)
        r.alerts.mutable.value = a11
        r.effects.answerReminder(a11, accept = true)
        advanceUntilIdle()
        assertEquals(listOf(11L), r.alerts.accepted)
        assertEquals(TuneTarget.Channel(ch.identity), r.controller.tunes.single().first)

        val gone = GuideFixtures.channel(99)
        val a12 = ReminderAlert(12, gone.identity, "Gone TV", "X", 0, late = false, autoSwitch = false)
        r.alerts.mutable.value = a12
        r.effects.answerReminder(a12, accept = true)
        advanceUntilIdle()
        assertEquals(listOf(12L), r.alerts.dismissed)
        assertEquals(GuideToast.REMINDER_CHANNEL_GONE to "Gone TV", r.shell.toasts.last())
        assertEquals(1, r.controller.tunes.size)

        val a13 = ReminderAlert(13, ch.identity, ch.displayName, "Y", 0, late = false, autoSwitch = false)
        r.alerts.mutable.value = a13
        r.effects.answerReminder(a13, accept = false)
        advanceUntilIdle()
        assertEquals(listOf(12L, 13L), r.alerts.dismissed)
    }

    /**
     * Code review 2026-10-14, H-S7: the card's alert may already be gone from `alerts.current` (its lifetime ended, the
     * next card replaced it) when the accept runs. The effect tunes the alert it was given, never orphaning a valid one.
     */
    @Test
    fun acceptUsesTheShownAlertNotTheCurrentOne() = runTest {
        val r = Rig(this, channels)
        val shown = ReminderAlert(21, ch.identity, ch.displayName, "News", 0, late = false, autoSwitch = false)
        r.alerts.mutable.value = null // the card already cleared
        r.effects.answerReminder(shown, accept = true)
        advanceUntilIdle()
        assertEquals(listOf(21L), r.alerts.accepted)
        assertTrue(r.alerts.dismissed.isEmpty())
        assertEquals(TuneTarget.Channel(ch.identity), r.controller.tunes.single().first)
        assertTrue("no channel-gone toast", r.shell.toasts.none { it.first == GuideToast.REMINDER_CHANNEL_GONE })

        val other = ReminderAlert(22, channels[3].identity, channels[3].displayName, "Other", 0, late = false, autoSwitch = false)
        r.alerts.mutable.value = other // a newer card replaced it
        r.effects.answerReminder(shown.copy(reminderId = 23), accept = true)
        advanceUntilIdle()
        assertEquals(TuneTarget.Channel(ch.identity), r.controller.tunes.last().first)
    }

    @Test
    fun favouritesHideAndGroups() = runTest {
        val r = Rig(this, channels)
        r.effects.queryFavourite(5L, ch)
        advanceUntilIdle()
        assertEquals(GuideEvent.Favourite(5L, false), r.events.last())
        r.effects.setFavourite(ch, true)
        advanceUntilIdle()
        assertTrue(ch.identity in r.library.favourites)
        assertEquals(GuideToast.ADDED_FAVOURITE to ch.displayName, r.shell.toasts.last())
        r.effects.hideChannel(ch)
        advanceUntilIdle()
        assertTrue(ch.identity in r.library.hidden)
        assertEquals(GuideToast.CHANNEL_HIDDEN, r.shell.toasts.last().first)
        val entry = r.library.sourceEntries.value.first()
        r.effects.groupAction(entry, GroupAction.MOVE_UP)
        advanceUntilIdle()
        assertEquals(entry to GroupAction.MOVE_UP, r.library.groupActions.single())
    }

    @Test
    fun numberLookupReportsTheLineupIndex() = runTest {
        val r = Rig(this, channels)
        r.effects.loadLineup(GuideSource.All(1))
        advanceUntilIdle()
        r.effects.lookupNumber(9L, 5) // channel(i).number = i + 1 → index 4
        advanceUntilIdle()
        val n = r.events.filterIsInstance<GuideEvent.Number>().single()
        assertEquals(channels[4], n.channel)
        assertEquals(4, n.lineupIndex)
        r.effects.lookupNumber(10L, 999)
        advanceUntilIdle()
        assertEquals(GuideEvent.Number(10L, null, -1), r.events.last())
        r.effects.close()
    }

    @Test
    fun persistSourceAndShellPassThroughs() = runTest {
        val r = Rig(this, channels)
        r.effects.persistSource(GuideSource.Recent)
        advanceUntilIdle()
        assertEquals(GuideSource.Recent, r.session.source)
        r.effects.setFullScreen(true)
        r.effects.openSearch()
        r.effects.openSettings()
        r.effects.exitApp()
        r.effects.announce("x")
        r.effects.toast(GuideToast.NO_FAVOURITES)
        assertEquals(
            listOf("setFullScreen:true", "openSearch", "openSettings", "exitApp", "announce", "toast:NO_FAVOURITES"),
            r.shell.calls,
        )
    }
}
