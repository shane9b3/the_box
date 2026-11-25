package app.thebox.reminder

import app.thebox.core.events.ChannelEvent
import app.thebox.core.events.EventZoneSource
import app.thebox.core.events.FakeEventIndex
import app.thebox.core.events.FakeEventSnapshot
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.PendingReminder
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.ReminderBook
import app.thebox.core.guide.ReminderIndex
import app.thebox.core.guide.fakes.FakeGuideSettings
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.guide.reminder.ReminderPlannerImpl
import app.thebox.core.identity.StableKeyDeriverImpl
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.refresh.KeyContext
import app.thebox.core.refresh.KeyRule
import app.thebox.core.source.EntryKind
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.PlaylistType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event channels, docs/design/m6-event-channels.md §6.6, §11.1 `ReminderSchedulerEventTest` (arch S5, U-E-S9, re-review
 * E5): at fire time a reminder on an event channel fires as before for the same event, follows an event that moved to
 * another slot, and says the event is gone otherwise; M3U TVG and NAME_GROUP keys put the name in the key, so a renamed
 * slot orphans its reminders (stated, pinned here). The index is a [FakeEventSnapshot]: no WP-1 / WP-2 code is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderSchedulerEventTest {
    private val base = GuideFixtures.NOW_MIN
    private val baseMs = base * 60_000L

    private fun reminder(id: Long = 1, ch: Int = 9, title: String = "Montreal @ Colorado", start: Int = base + 60) = PendingReminder(
        id = id, identity = GuideFixtures.identity(ch), rawStartMin = start, rawEndMin = start + 180, shiftMin = 0,
        title = title, channelName = "Sportsnet+ 09", leadSec = 60,
    )

    private fun ev(title: String, start: Int) = ChannelEvent(ChannelEvent.Kind.EVENT, title, null, start, start + 180, EventZoneSource.EXPLICIT)

    private fun channel(i: Int, event: ChannelEvent?, name: String = "Sportsnet+ %02d".format(i)): GuideChannel =
        GuideFixtures.channel(i, name = name).copy(event = event)

    private fun snapshot(vararg e: app.thebox.core.events.EventEntry) = FakeEventSnapshot(e.toList(), 1)
    private fun entry(ch: Int, title: String, start: Int, playlist: Long = 1) =
        FakeEventIndex.event(GuideFixtures.channel(ch).rowId, title, start, start + 180, playlistId = playlist)

    // ------------------------------------------------------------------------------------------------ the rule

    @Test
    fun anOrdinaryChannelOrAGoneChannelFiresAsBefore() {
        assertEquals(ReminderEventRule.Kind.SAME, ReminderEventRule.decide(reminder(), channel(9, null), null, snapshot()).verdict)
        assertEquals(ReminderEventRule.Kind.SAME, ReminderEventRule.decide(reminder(), null, null, snapshot()).verdict)
    }

    @Test
    fun aGuideListingWithTheTitleFiresAsBefore() {
        val ch = channel(9, ev("Detroit @ Orlando", base + 60))
        val d = ReminderEventRule.decide(reminder(), ch, "MONTREAL  @ colorado", snapshot())
        assertEquals(ReminderEventRule.Kind.SAME, d.verdict)
    }

    @Test
    fun theSameEventWithinHalfAnHourFiresAsBefore() {
        assertEquals(ReminderEventRule.Kind.SAME, ReminderEventRule.decide(reminder(), channel(9, ev("Montreal @ Colorado", base + 90)), null, snapshot()).verdict)
        assertNotEquals(ReminderEventRule.Kind.SAME, ReminderEventRule.decide(reminder(), channel(9, ev("Montreal @ Colorado", base + 91)), null, snapshot()).verdict)
    }

    @Test
    fun anEventThatMovedIsFollowedToTheNearestSlotOfTheSamePlaylist() {
        val ch = channel(9, ev("Detroit @ Orlando", base + 60))
        val snap = snapshot(
            entry(25, "Montreal @ Colorado", base + 80),
            entry(26, "Montreal @ Colorado", base + 65),
            entry(27, "Montreal @ Colorado", base + 60, playlist = 2), // another playlist
            entry(28, "Montreal @ Colorado", base + 100), // outside ± 30
        )
        val d = ReminderEventRule.decide(reminder(), ch, null, snap)
        assertEquals(ReminderEventRule.Kind.MOVED, d.verdict)
        assertEquals(GuideFixtures.channel(26).rowId, d.movedTo)
    }

    @Test
    fun anEventNoSlotListsIsGoneAndAnEmptySlotIsCheckedToo() {
        val gone = ReminderEventRule.decide(reminder(), channel(9, ev("Detroit @ Orlando", base + 60)), null, snapshot())
        assertEquals(ReminderEventRule.Kind.GONE, gone.verdict)
        // (E5) "Sportsnet+ 09: NO EVENT" after the refresh: EMPTY through the isEmptySlot fallback.
        val empty = ReminderEventRule.decide(reminder(), channel(9, ChannelEvent.EMPTY), null, snapshot(entry(25, "Montreal @ Colorado", base + 60)))
        assertEquals(ReminderEventRule.Kind.MOVED, empty.verdict)
    }

    /**
     * Code review 2026-11-25, UI m5: a guide listing or a slot that re-spells the same game at the same start is the
     * same programme, not "gone"; another game is still gone.
     */
    @Test
    fun aReSpelledTitleForTheSameSlotAndStartIsTheSame() {
        val r = reminder(title = "NHL Hockey: Oilers at Flames")
        val ch = channel(9, ev("Detroit @ Orlando", base + 60))
        assertEquals(ReminderEventRule.Kind.SAME, ReminderEventRule.decide(r, ch, "NHL: Oilers @ Flames", snapshot()).verdict)
        val vgk = reminder(title = "Golden Knights vs. Oilers")
        assertEquals(
            ReminderEventRule.Kind.SAME,
            ReminderEventRule.decide(vgk, channel(9, ev("Vegas Golden Knights vs. Edmonton Oilers", base + 60)), null, snapshot()).verdict,
        )
        assertEquals("sides in the other order", ReminderEventRule.Kind.SAME, ReminderEventRule.decide(vgk, channel(9, ev("Edmonton @ Vegas", base + 60)), null, snapshot()).verdict)
        assertEquals(ReminderEventRule.Kind.GONE, ReminderEventRule.decide(vgk, channel(9, ev("Golden Knights vs. Kraken", base + 60)), null, snapshot()).verdict)
        assertEquals(ReminderEventRule.Kind.GONE, ReminderEventRule.decide(r, ch, "NHL: Flames @ Canucks", snapshot()).verdict)
        // A re-spelled event on another slot is followed.
        val moved = ReminderEventRule.decide(vgk, channel(9, ev("Detroit @ Orlando", base + 60)), null, snapshot(entry(25, "Vegas Golden Knights @ Edmonton Oilers", base + 60)))
        assertEquals(ReminderEventRule.Kind.MOVED, moved.verdict)
        assertFalse(ReminderEventRule.sameMatchup("New York @ Boston", "New York Rangers @ Boston"))
    }

    // ------------------------------------------------------------------------------------------------ the runtime

    private class TestBook(vararg rs: PendingReminder) : ReminderBook {
        val rows = MutableStateFlow(rs.toList())
        override val index: StateFlow<ReminderIndex> = MutableStateFlow(ReminderIndex.EMPTY)
        override val pending: StateFlow<List<PendingReminder>> get() = rows
        override suspend fun set(channel: GuideChannel, info: ProgrammeInfo, leadSec: Int): Boolean = error("unused")
        override suspend fun cancel(identity: ChannelIdentity, rawStartMin: Int) = Unit
        override suspend fun markFired(id: Long) { rows.value = rows.value.filter { it.id != id } }
        override suspend fun dismiss(id: Long) { rows.value = rows.value.filter { it.id != id } }
        override suspend fun markOrphaned(id: Long) = Unit
    }

    private fun TestScope.scheduler(check: ReminderEventCheck, autoSwitch: Boolean = true, vararg rs: PendingReminder): ReminderSchedulerImpl {
        val s = ReminderSchedulerImpl(
            TestBook(*rs), ReminderPlannerImpl(), FakeGuideSettings(GuideSettings(reminderAutoSwitch = autoSwitch)), MutableStateFlow(true),
            backgroundScope, check,
        ) { baseMs + testScheduler.currentTime }
        runCurrent()
        return s
    }

    private fun TestScope.atMinute(m: Int) { advanceTimeBy(m * 60_000L - testScheduler.currentTime); runCurrent() }

    @Test
    fun aMovedEventFiresForItsNewSlotWithAutoSwitch() = runTest {
        val moved = GuideFixtures.identity(25)
        val checked = ArrayList<Long>()
        val s = scheduler({ r -> checked += r.id; EventVerdict.Moved(moved, "Sportsnet+ 25", "Sportsnet+ 09") }, true, reminder())
        atMinute(58)
        assertNull(s.current.value)
        atMinute(59)
        val a = s.current.value!!
        assertEquals(moved, a.identity)
        assertEquals("Sportsnet+ 25", a.channelName)
        assertEquals("Sportsnet+ 09", a.movedFrom)
        assertTrue(a.autoSwitch)
        assertFalse(a.eventGone)
        assertEquals(listOf(1L), checked)
    }

    @Test
    fun aGoneEventFiresWithoutAutoSwitch() = runTest {
        val s = scheduler({ EventVerdict.Gone }, true, reminder())
        atMinute(59)
        val a = s.current.value!!
        assertTrue(a.eventGone)
        assertFalse(a.autoSwitch)
        assertEquals(GuideFixtures.identity(9), a.identity)
        assertNull(a.movedFrom)
    }

    @Test
    fun theSameEventOrAFailedLookupFiresAsBefore() = runTest {
        val s = scheduler({ throw IllegalStateException("db closed") }, true, reminder(id = 1), reminder(id = 2, ch = 3, start = base + 60))
        atMinute(59)
        val a = s.current.value!!
        assertEquals(GuideFixtures.identity(9), a.identity)
        assertTrue(a.autoSwitch)
        assertFalse(a.eventGone)
        assertNull(a.movedFrom)
        s.dismiss(a.reminderId)
        runCurrent()
        assertEquals("the second card is queued too", 2L, s.current.value?.reminderId)
    }

    // ------------------------------------------------------------------------------------------------ stated limitation

    /** §6.6: an M3U event slot keyed by its TVG id (or by name and group) gets a new key when its name changes. */
    @Test
    fun tvgAndNameGroupKeysChangeWithTheNameSoTheirRemindersAreOrphaned() {
        val keys = StableKeyDeriverImpl()
        val m3u = KeyContext(PlaylistType.M3U_URL, xtreamShaped = false)
        fun slot(name: String, tvg: String?, url: String = "") =
            ParsedChannel(EntryKind.LIVE, name, url, 0, groupTitle = "Sports | Sportsnet+", tvgId = tvg)
        val a = keys.derive(slot("Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET", "BossSports.Sportsnet.09"), m3u)
        val b = keys.derive(slot("Sportsnet+ 09: Montreal @ Colorado @ 25 Nov 09:30 PM ET", "BossSports.Sportsnet.09"), m3u)
        assertEquals(KeyRule.TVG, a.rule)
        assertNotEquals("TVG: orphaned by a rename", a.key, b.key)
        val c = keys.derive(slot("Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET", null), m3u)
        val d = keys.derive(slot("Sportsnet+ 09: Montreal @ Colorado @ 25 Nov 09:30 PM ET", null), m3u)
        assertEquals(KeyRule.NAME_GROUP, c.rule)
        assertNotEquals("NAME_GROUP: orphaned by a rename", c.key, d.key)
        // Xtream keys are the stream id: a rename keeps them, so the fire-time check above applies.
        val x = KeyContext(PlaylistType.XTREAM, xtreamShaped = true)
        val e = keys.derive(slot("Sportsnet+ 09: Vegas @ Edmonton", null).copy(xtreamStreamId = 4242), x)
        val f = keys.derive(slot("Sportsnet+ 09: Montreal @ Colorado", null).copy(xtreamStreamId = 4242), x)
        assertEquals(e.key, f.key)
    }
}
