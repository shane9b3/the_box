package app.thebox.core.guide.reminder

import app.thebox.core.guide.PendingReminder
import app.thebox.core.guide.ReminderDecision
import app.thebox.core.guide.fakes.GuideFixtures
import kotlin.test.Test
import kotlin.test.assertEquals

/** §3.11.2 (S1/F7, N5): fire, late, expire, lead, next wake, displayed-time firing on a shifted channel. */
class ReminderPlannerTest {
    private val p = ReminderPlannerImpl()
    private fun r(id: Long, rawStart: Int, rawEnd: Int = rawStart + 30, shift: Int = 0, lead: Int = 60) =
        PendingReminder(id, GuideFixtures.identity(id.toInt()), rawStart, rawEnd, shift, "T$id", "C$id", lead)
    private fun ms(min: Int) = min * 60_000L
    private fun decide(list: List<PendingReminder>, now: Long): List<ReminderDecision> = ArrayList<ReminderDecision>().also { p.decide(list, now, it) }

    @Test fun firesAtStartMinusLead() {
        val a = r(1, 1_000)
        assertEquals(emptyList<ReminderDecision>(), decide(listOf(a), ms(1_000) - 60_001))
        assertEquals(listOf<ReminderDecision>(ReminderDecision.Fire(a, late = false)), decide(listOf(a), ms(1_000) - 60_000))
        assertEquals(listOf<ReminderDecision>(ReminderDecision.Fire(a, late = false)), decide(listOf(a), ms(1_000) + 60_000))
    }

    @Test fun lateAfterSixtySeconds() {
        val a = r(1, 1_000)
        assertEquals(listOf<ReminderDecision>(ReminderDecision.Fire(a, late = true)), decide(listOf(a), ms(1_000) + 60_001))
    }

    @Test fun expiresAtDisplayedEnd() {
        val a = r(1, 1_000, 1_030)
        assertEquals(listOf<ReminderDecision>(ReminderDecision.Expire(a)), decide(listOf(a), ms(1_030)))
    }

    @Test fun leadIsHonoured() {
        val a = r(1, 1_000, lead = 300)
        assertEquals(1, decide(listOf(a), ms(1_000) - 300_000).size)
        assertEquals(ms(1_000) - 300_000, p.nextWakeMs(listOf(a), 0))
    }

    @Test fun shiftedChannelFiresAtDisplayedTime() {
        // A "+1" channel: raw key 1000, displayed (and aired) at 1060 (S1).
        val a = r(1, 1_000, 1_030, shift = 60)
        assertEquals(emptyList<ReminderDecision>(), decide(listOf(a), ms(1_000) - 60_000), "not at the raw time")
        assertEquals(emptyList<ReminderDecision>(), decide(listOf(a), ms(1_030)), "not expired at the raw end")
        assertEquals(listOf<ReminderDecision>(ReminderDecision.Fire(a, late = false)), decide(listOf(a), ms(1_060) - 60_000))
        assertEquals(ms(1_060) - 60_000, p.nextWakeMs(listOf(a), 0))
    }

    @Test fun missedOnStartIsCountable() {
        // Closed app: one ended (missed, expire), one running (late card), one future (nothing yet) (N5).
        val now = ms(2_000)
        val ended = r(1, 1_900, 1_950)
        val running = r(2, 1_980, 2_030)
        val future = r(3, 2_100)
        val out = decide(listOf(ended, running, future), now)
        assertEquals(listOf<ReminderDecision>(ReminderDecision.Expire(ended), ReminderDecision.Fire(running, late = true)), out)
        assertEquals(1, out.count { it is ReminderDecision.Expire })
        assertEquals(ms(2_100) - 60_000, p.nextWakeMs(listOf(ended, running, future), now))
    }

    @Test fun nextWakeNoneIsMax() {
        assertEquals(Long.MAX_VALUE, p.nextWakeMs(emptyList(), 0))
        assertEquals(Long.MAX_VALUE, p.nextWakeMs(listOf(r(1, 10)), ms(100)))
    }
}
