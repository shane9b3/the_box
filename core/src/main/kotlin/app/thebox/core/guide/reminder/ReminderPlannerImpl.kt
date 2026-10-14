package app.thebox.core.guide.reminder

import app.thebox.core.guide.PendingReminder
import app.thebox.core.guide.ReminderDecision
import app.thebox.core.guide.ReminderPlanner

/** WP-D stub (docs/design/m3-guide.md §1.5, §3.11.2). */
class ReminderPlannerImpl : ReminderPlanner {
    override fun decide(pending: List<PendingReminder>, nowMs: Long, out: MutableList<ReminderDecision>): Unit = TODO("WP-D")
    override fun nextWakeMs(pending: List<PendingReminder>, nowMs: Long): Long = TODO("WP-D")
}
