package app.thebox.core.events

import app.thebox.core.epg.ProgrammeFlags
import app.thebox.core.guide.CellFlags
import app.thebox.core.guide.epgBasisOf
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.ChannelHorizon
import app.thebox.core.text.ChannelNamePrefix
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-event-channels.md §4 (commit 0): the flag bit, the row basis, the horizon and the empty snapshot. */
class EventContractsTest {
    private fun flagsOf(c: Class<*>): Map<String, Int> = c.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
        .associate { it.isAccessible = true; "${c.simpleName}.${it.name}" to it.getInt(null) }

    @Test
    fun everyCellFlagIsOneBitAndDisjointFromEveryOther() {
        val all = flagsOf(ProgrammeFlags::class.java) + flagsOf(CellFlags::class.java)
        assertTrue(all.size >= 17, "found ${all.keys}")
        assertEquals(1 shl 27, CellFlags.EVENT_NAME)
        var seen = 0
        for ((name, v) in all) {
            assertEquals(1, Integer.bitCount(v), "$name is one bit")
            assertEquals(0, seen and v, "$name overlaps another flag")
            seen = seen or v
        }
    }

    @Test
    fun basisIsUnchangedWithoutAnEventAndFollowsARenamedSlot() {
        val ch = GuideFixtures.channel(3)
        val before = epgBasisOf(ch)
        assertEquals(before, epgBasisOf(ch.copy(event = null)))
        val a = ChannelEvent(ChannelEvent.Kind.EVENT, "Vegas @ Edmonton", null, 29_000_000, 29_000_180, EventZoneSource.EXPLICIT)
        val withA = epgBasisOf(ch.copy(event = a))
        assertNotEquals(before, withA)
        assertNotEquals(withA, epgBasisOf(ch.copy(event = a.copy(title = "Detroit @ Orlando"))), "a renamed slot re-queries")
        assertNotEquals(withA, epgBasisOf(ch.copy(event = a.copy(startMin = a.startMin + 30))), "a moved start re-queries")
        assertNotEquals(withA, epgBasisOf(ch.copy(event = ChannelEvent.EMPTY)))
        assertEquals(withA, epgBasisOf(ch.copy(event = a.copy())), "stable for equal events")
        assertNotEquals(ch, ch.copy(event = a), "the event is part of equality")
    }

    @Test
    fun horizon() {
        val now = 1_800_000_000_000L
        val floor = now + SportsConstants.HUB_LOOKAHEAD_MIN * 60_000L
        // No guide: only the 12 h floor counts.
        assertFalse(ChannelHorizon.beyondGuide(floor - 1, null, now))
        assertTrue(ChannelHorizon.beyondGuide(floor, null, now))
        // A guide ending before now (stale): the floor still keeps tonight.
        assertFalse(ChannelHorizon.beyondGuide(now + 3_600_000L, now - 1, now))
        assertTrue(ChannelHorizon.beyondGuide(floor, now - 1, now))
        // A guide ending between now and now + 12 h: the floor wins.
        assertFalse(ChannelHorizon.beyondGuide(floor - 1, now + 3_600_000L, now))
        // A deep guide: a start exactly at its end is beyond it; one minute before is not.
        val end = now + 30 * 3_600_000L
        assertTrue(ChannelHorizon.beyondGuide(end, end, now))
        assertFalse(ChannelHorizon.beyondGuide(end - 60_000L, end, now))
    }

    @Test
    fun emptySnapshotAndTheVersionLink() {
        // (Not the stubs' answers: WP-1 replaces those, and its own tests pin them.)
        assertNull(EventSnapshot.EMPTY.of(1L))
        assertEquals(emptyList(), EventSnapshot.EMPTY.startingIn(Int.MIN_VALUE, Int.MAX_VALUE))
        assertEquals(emptyList(), EventSnapshot.EMPTY.current(0))
        assertEquals(0, EventSnapshot.EMPTY.count(1L))
        assertEquals(1 + EventNameParser.VERSION, ChannelNamePrefix.RULE_VERSION)
    }
}
