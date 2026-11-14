package app.thebox.core.sports

import app.thebox.core.playback.MonoClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * (CHL emulator pass, defect 2; review fix 2) [ScoreBugMachine.followIfKept]: one wall-clock reading decides both the
 * refusal and the step after it, so a follow at the hard-expiry boundary is either refused or kept, never accepted and then
 * dropped by the same call. The removal rule itself is unchanged.
 */
class ScoreBugMachineFollowIfKeptTest {
    private val start = 1_794_612_600_000L
    private val boundary = start + SportsConstants.HARD_EXPIRY_MS
    private val game = FollowedGame(League.OHL, "29191", start, "PBO", "OSH")
    private val other = FollowedGame(League.OHL, "29192", boundary - 3_600_000L, "LDN", "KIT")

    private fun machine(wall: () -> Long) = ScoreBugMachine(MonoClock { 0L }, wall)

    @Test
    fun exactlyAtTheBoundaryIsRefusedAndOneMsBeforeIsKept() {
        val at = machine { boundary }
        assertFalse(at.followIfKept(game))
        assertNull(at.followed())
        val before = machine { boundary - 1 }
        assertTrue(before.followIfKept(game))
        assertEquals(game, before.followed())
    }

    /** A clock that ticks 1 ms on every read, starting 1 ms before the boundary: the decision and the step agree. */
    @Test
    fun aClockCrossingTheBoundaryBetweenReadsCannotSplitTheDecision() {
        for (startAt in listOf(boundary - 2, boundary - 1, boundary)) {
            var t = startAt
            val m = machine { t++ }
            val kept = m.followIfKept(game)
            assertEquals(kept, m.followed() == game, "start $startAt: the answer is what the machine holds")
            assertEquals(startAt < boundary, kept, "start $startAt: decided on the first reading")
        }
    }

    @Test
    fun aRefusedFollowLeavesTheCurrentOneUntouched() {
        val m = machine { boundary }
        assertTrue(m.followIfKept(other))
        assertFalse(m.followIfKept(game))
        assertEquals(other, m.followed())
    }

    @Test
    fun aTbdStartCountsFromItsDayEnd() {
        val placeholder = java.time.LocalDate.parse("2026-11-13").atStartOfDay(SportsDays.zone(League.OHL)).toInstant().toEpochMilli()
        val tbd = FollowedGame(League.OHL, "29300", placeholder, "PBO", "OSH", startTbd = true)
        val m = machine { placeholder + 14 * 3_600_000L }
        assertTrue(m.followIfKept(tbd))
        assertEquals(tbd, m.followed())
    }
}
