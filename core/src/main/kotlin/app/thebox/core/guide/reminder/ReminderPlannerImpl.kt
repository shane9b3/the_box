package app.thebox.core.guide.reminder

import app.thebox.core.guide.PendingReminder
import app.thebox.core.guide.ReminderDecision
import app.thebox.core.guide.ReminderPlanner

/**
 * Pure due/next computation (docs/design/m3-guide.md §3.11.2; reviewed 2026-10-14, S1/F7). Keys stay raw; firing uses
 * the **displayed** times: `start = (rawStart + shiftMin) × 60 000`, `fireAt = start − leadSec × 1000`,
 * `end = (rawEnd + shiftMin) × 60 000`. Ended → [ReminderDecision.Expire]; due → [ReminderDecision.Fire], late when
 * more than [LATE_AFTER_MS] after the displayed start.
 */
class ReminderPlannerImpl : ReminderPlanner {
    override fun decide(pending: List<PendingReminder>, nowMs: Long, out: MutableList<ReminderDecision>) {
        for (r in pending) {
            when {
                nowMs >= endMs(r) -> out += ReminderDecision.Expire(r)
                nowMs >= fireAtMs(r) -> out += ReminderDecision.Fire(r, late = nowMs > startMs(r) + LATE_AFTER_MS)
            }
        }
    }

    override fun nextWakeMs(pending: List<PendingReminder>, nowMs: Long): Long {
        var next = Long.MAX_VALUE
        for (r in pending) {
            val f = fireAtMs(r)
            if (f > nowMs && f < next) next = f
        }
        return next
    }

    companion object {
        /** A card shown more than this after the displayed start reads "started n min ago" (Appendix B). */
        const val LATE_AFTER_MS = 60_000L

        fun startMs(r: PendingReminder): Long = (r.rawStartMin.toLong() + r.shiftMin) * 60_000L
        fun endMs(r: PendingReminder): Long = (r.rawEndMin.toLong() + r.shiftMin) * 60_000L
        fun fireAtMs(r: PendingReminder): Long = startMs(r) - r.leadSec * 1_000L
    }
}
