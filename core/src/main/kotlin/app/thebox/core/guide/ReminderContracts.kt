// Contract (M3 commit 0): docs/design/m3-guide.md §2.6. Verbatim; changes go through the lead.
package app.thebox.core.guide

import app.thebox.core.refresh.ChannelIdentity
import kotlinx.coroutines.flow.StateFlow

/** A reminder as the planner sees it (reminder row, M1 ReminderEntity). (reviewed 2026-10-14, S1/F7) */
data class PendingReminder(
    val id: Long,
    val identity: ChannelIdentity,
    /** Key (raw, unshifted). */
    val rawStartMin: Int,
    val rawEndMin: Int,
    /** Guide shift at set time (reminder.shift_min); the programme airs at rawStart + shiftMin on this channel. */
    val shiftMin: Int,
    val title: String,
    val channelName: String,
    val leadSec: Int,
)

interface ReminderBook {
    /** Pending reminders as an index for cell markers; re-emits on every change. */
    val index: StateFlow<ReminderIndex>
    val pending: StateFlow<List<PendingReminder>>
    /** False when a *pending* one already exists for (identity, rawStartMin); a fired/dismissed row for the key is replaced (N5). Stores shift_min = info.startMin − info.rawStartMin. */
    suspend fun set(channel: GuideChannel, info: ProgrammeInfo, leadSec: Int): Boolean
    suspend fun cancel(identity: ChannelIdentity, rawStartMin: Int)
    suspend fun markFired(id: Long)
    suspend fun dismiss(id: Long)
    suspend fun markOrphaned(id: Long)
}

data class ReminderAlert(
    val reminderId: Long,
    val identity: ChannelIdentity,
    val channelName: String,
    val title: String,
    /** Displayed (shifted) start, ms: (rawStart + shiftMin) × 60_000. (reviewed 2026-10-14, S1) */
    val startAtMs: Long,
    /** True when shown after the start (missed while the app was closed or in the background). */
    val late: Boolean,
    val autoSwitch: Boolean,
)

sealed interface ReminderDecision {
    data class Fire(val reminder: PendingReminder, val late: Boolean) : ReminderDecision
    /** The programme already ended: mark fired without a card. */
    data class Expire(val reminder: PendingReminder) : ReminderDecision
}

/** Pure scheduling (§3.11.2). */
interface ReminderPlanner {
    fun decide(pending: List<PendingReminder>, nowMs: Long, out: MutableList<ReminderDecision>)
    /** Next wall-clock ms at which [decide] could return something new; Long.MAX_VALUE when none. */
    fun nextWakeMs(pending: List<PendingReminder>, nowMs: Long): Long
}

/** The runtime (WP-D, :app; N16). Active only while the process is STARTED. */
interface ReminderAlerts {
    val current: StateFlow<ReminderAlert?>
    /** The user chose "Switch now" (or auto-switch fired): marks fired and clears the card; the caller tunes. */
    suspend fun accept(reminderId: Long)
    suspend fun dismiss(reminderId: Long)
    /**
     * (amended 2026-10-14, §2.13 item 5) Missed-reminder counts found by the start-up catch-up pass (N5), each
     * delivered once to one collector and buffered until collected; the guide forwards it as
     * [GuideEvent.MissedReminders].
     */
    val missed: kotlinx.coroutines.flow.Flow<Int> get() = kotlinx.coroutines.flow.emptyFlow()
}
