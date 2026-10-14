package app.thebox.reminder

import app.thebox.core.guide.GuideSettingsSource
import app.thebox.core.guide.PendingReminder
import app.thebox.core.guide.ReminderAlert
import app.thebox.core.guide.ReminderAlerts
import app.thebox.core.guide.ReminderBook
import app.thebox.core.guide.ReminderDecision
import app.thebox.core.guide.ReminderPlanner
import app.thebox.core.guide.reminder.ReminderPlannerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The foreground reminder runtime and [ReminderAlerts] (docs/design/m3-guide.md §3.11.3, owner WP-D).
 *
 * Runs only while [foreground] is true (`ProcessLifecycleOwner` STARTED, see [ReminderModule]). Each pass reads
 * `book.pending`, asks the [planner], marks `Expire`d reminders fired and queues `Fire`d ones; one card is shown at a
 * time, FIFO. The loop sleeps until the planner's next wake, the current card's deadline or `now + 60 s`, whichever
 * comes first, and wakes early when `pending` changes.
 *
 * Decisions where the design is silent:
 * - **Background:** the loop is cancelled and the queue and [current] are cleared *without* writing to the book. The
 *   book stays the source of truth: a reminder whose card was showing is still pending, so the next start's catch-up
 *   pass shows it again (late if more than a minute after its start) or, when the programme ended, marks it fired and
 *   counts it as missed.
 * - **Missed count:** an `Expire` counts as missed when this foreground session never saw the reminder before it ended
 *   (so a `pending` list that loads after the first pass is still caught up correctly). Emitted on [missed] at most
 *   once per pass, only when > 0 — in practice once per start.
 * - A card's `firedAt` is the moment it becomes [current] (a queued card starts its countdown when shown).
 * - `autoSwitch` is read from [GuideSettingsSource] when the card is queued.
 * - A reminder that disappears from `pending` (cancelled, fired or dismissed elsewhere) is dropped from the queue and,
 *   when showing, from [current]. Every book write is `setStateIfPending` in the store, so races are harmless.
 */
class ReminderSchedulerImpl(
    private val book: ReminderBook,
    private val planner: ReminderPlanner,
    private val settings: GuideSettingsSource,
    private val foreground: StateFlow<Boolean>,
    scope: CoroutineScope,
    private val clock: () -> Long,
) : ReminderAlerts {

    private class Slot(val alert: ReminderAlert, val startMs: Long, var shownAtMs: Long = 0L) {
        val id: Long get() = alert.reminderId
        val deadlineMs: Long get() = maxOf(startMs, shownAtMs) + REMINDER_CARD_MS
    }

    private val lock = Any()
    // Guarded by [lock].
    private val queue = ArrayDeque<Slot>()
    private var showing: Slot? = null
    /** Ids already accepted/dismissed/auto-dismissed/expired whose write may not be visible in `pending` yet. */
    private val retired = HashSet<Long>()
    /** Ids seen not-yet-ended during this foreground session (missed-count rule). */
    private val seenLive = HashSet<Long>()

    private val currentState = MutableStateFlow<ReminderAlert?>(null)
    override val current: StateFlow<ReminderAlert?> = currentState.asStateFlow()

    private val missedChannel = Channel<Int>(Channel.BUFFERED)

    /**
     * Contract gap (§2.6 `ReminderAlerts` has no member for it): "Missed while The Box was closed: {n} reminders".
     * Each count is delivered once, to one collector, buffered until collected (so a VM that subscribes after the first
     * pass still gets it). The guide VM must forward it as `GuideEvent.MissedReminders(n)`; until the contract gains a
     * member, nothing in `:ui` can see this (it is `:app`-only).
     */
    val missed: Flow<Int> = missedChannel.receiveAsFlow()

    private val decisions = ArrayList<ReminderDecision>()

    init {
        scope.launch {
            foreground.collectLatest { fg ->
                if (fg) {
                    try {
                        runLoop()
                    } finally {
                        clearAll()
                    }
                }
            }
        }
    }

    override suspend fun accept(reminderId: Long) {
        retire(reminderId)
        book.markFired(reminderId)
    }

    override suspend fun dismiss(reminderId: Long) {
        retire(reminderId)
        book.dismiss(reminderId)
    }

    private suspend fun runLoop() {
        while (true) {
            val pending = book.pending.value
            val now = clock()
            val toFire = pass(pending, now)
            for (id in toFire) book.markFired(id)
            val cardDeadline = synchronized(lock) { showing?.deadlineMs } ?: Long.MAX_VALUE
            val wakeAt = minOf(planner.nextWakeMs(pending, now), cardDeadline, now + MAX_SLEEP_MS)
            val sleepMs = (wakeAt - now).coerceAtLeast(1L)
            withTimeoutOrNull(sleepMs) { book.pending.first { it != pending } }
        }
    }

    /** One planner pass; returns the ids to mark fired (expired programmes and timed-out cards). */
    private fun pass(pending: List<PendingReminder>, now: Long): List<Long> {
        decisions.clear()
        planner.decide(pending, now, decisions)
        val pendingIds = HashSet<Long>(pending.size * 2)
        for (r in pending) pendingIds += r.id
        val toFire = ArrayList<Long>()
        var missedCount = 0
        synchronized(lock) {
            retired.retainAll(pendingIds)
            queue.removeAll { it.id !in pendingIds }
            showing?.let { if (it.id !in pendingIds) showing = null }
            showing?.let {
                if (now >= it.deadlineMs) { // auto-dismiss
                    retired += it.id
                    toFire += it.id
                    showing = null
                }
            }
            val expired = HashSet<Long>()
            for (d in decisions) {
                when (d) {
                    is ReminderDecision.Expire -> {
                        val id = d.reminder.id
                        expired += id
                        if (id in retired) continue
                        retired += id
                        toFire += id
                        queue.removeAll { it.id == id }
                        if (showing?.id == id) showing = null
                        if (id !in seenLive) missedCount++
                    }
                    is ReminderDecision.Fire -> {
                        val id = d.reminder.id
                        seenLive += id
                        if (id in retired || showing?.id == id || queue.any { it.id == id }) continue
                        queue.addLast(slotFor(d.reminder, d.late))
                    }
                }
            }
            // Pending but not yet due: seen live this session.
            for (r in pending) if (r.id !in expired) seenLive += r.id
            promote(now)
        }
        if (missedCount > 0) missedChannel.trySend(missedCount)
        return toFire
    }

    private fun slotFor(r: PendingReminder, late: Boolean): Slot {
        val startMs = ReminderPlannerImpl.startMs(r)
        return Slot(
            ReminderAlert(
                reminderId = r.id,
                identity = r.identity,
                channelName = r.channelName,
                title = r.title,
                startAtMs = startMs,
                late = late,
                autoSwitch = settings.settings.value.reminderAutoSwitch,
            ),
            startMs,
        )
    }

    private fun retire(id: Long) {
        synchronized(lock) {
            retired += id
            queue.removeAll { it.id == id }
            if (showing?.id == id) {
                showing = null
                if (foreground.value) promote(clock())
            }
            currentState.value = showing?.alert
        }
    }

    /** Lock held. Shows the next queued card when none is showing, and publishes [current]. */
    private fun promote(now: Long) {
        if (showing == null) {
            showing = queue.removeFirstOrNull()?.also { it.shownAtMs = now }
        }
        currentState.value = showing?.alert
    }

    private fun clearAll() {
        synchronized(lock) {
            queue.clear()
            showing = null
            retired.clear()
            seenLive.clear()
            currentState.value = null
        }
    }

    companion object {
        /** Card countdown after `max(startMs, firedAt)` (Appendix B). */
        const val REMINDER_CARD_MS = 60_000L
        /** Longest sleep between passes, so a wall-clock change is noticed within a minute (§3.11.3). */
        const val MAX_SLEEP_MS = 60_000L
    }
}
