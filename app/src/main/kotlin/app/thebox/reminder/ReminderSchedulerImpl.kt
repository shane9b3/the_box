package app.thebox.reminder

import app.thebox.core.common.BoxLog
import app.thebox.core.epg.EpgStore
import app.thebox.core.events.ChannelEvent
import app.thebox.core.events.EventIndex
import app.thebox.core.events.EventSnapshot
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideSettingsSource
import app.thebox.core.guide.PendingReminder
import app.thebox.core.guide.ReminderAlert
import app.thebox.core.guide.ReminderAlerts
import app.thebox.core.guide.ReminderBook
import app.thebox.core.guide.ReminderDecision
import app.thebox.core.guide.ReminderPlanner
import app.thebox.core.guide.reminder.ReminderPlannerImpl
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.text.NameNormalizer
import app.thebox.data.guide.GuideQueries
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
 *
 * Event channels (docs/design/m6-event-channels.md §6.6, arch S5, U-E-S9, re-review E5): with an [eventCheck], a
 * reminder that comes due is checked against its channel's event before its card is queued (a suspend lookup, outside
 * [lock]): the same event fires as before; an event that moved to another slot of the playlist fires for that slot
 * ([ReminderAlert.movedFrom]); an event no slot lists any more fires with [ReminderAlert.eventGone] and no auto-switch.
 */
class ReminderSchedulerImpl(
    private val book: ReminderBook,
    private val planner: ReminderPlanner,
    private val settings: GuideSettingsSource,
    private val foreground: StateFlow<Boolean>,
    scope: CoroutineScope,
    /**
     * (Event channels §6.6) Null: every reminder fires on its own channel, as before event channels. Before [clock], so
     * callers that pass the clock as a trailing lambda keep working.
     */
    private val eventCheck: ReminderEventCheck? = null,
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
     * `ReminderAlerts.missed` (§2.13 item 5): "Missed while The Box was closed: {n} reminders". Each count is delivered
     * once, to one collector, buffered until collected (so a VM that subscribes after the first pass still gets it).
     */
    override val missed: Flow<Int> = missedChannel.receiveAsFlow()

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
            val fresh = ArrayList<ReminderDecision.Fire>()
            val toFire = pass(pending, now, fresh)
            for (id in toFire) book.markFired(id)
            if (fresh.isNotEmpty()) enqueueChecked(fresh)
            val cardDeadline = synchronized(lock) { showing?.deadlineMs } ?: Long.MAX_VALUE
            val wakeAt = minOf(planner.nextWakeMs(pending, now), cardDeadline, now + MAX_SLEEP_MS)
            val sleepMs = (wakeAt - now).coerceAtLeast(1L)
            withTimeoutOrNull(sleepMs) { book.pending.first { it != pending } }
        }
    }

    /**
     * One planner pass; returns the ids to mark fired (expired programmes and timed-out cards). With an [eventCheck],
     * newly due reminders go to [fresh] instead of the queue: [enqueueChecked] checks them outside the lock.
     */
    private fun pass(pending: List<PendingReminder>, now: Long, fresh: MutableList<ReminderDecision.Fire>): List<Long> {
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
                        if (eventCheck != null) fresh += d else queue.addLast(slotFor(d.reminder, d.late, EventVerdict.Same))
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

    /**
     * (Event channels §6.6) Checks each newly due reminder against its channel's event (suspend, outside [lock]; a
     * failed lookup fires as before), then queues the cards that are still wanted and shows the next one.
     */
    private suspend fun enqueueChecked(fresh: List<ReminderDecision.Fire>) {
        val check = eventCheck ?: return
        val slots = fresh.map { d ->
            val verdict = try {
                check.check(d.reminder)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.w(TAG, "reminder event check failed: ${e.javaClass.simpleName}")
                EventVerdict.Same
            }
            slotFor(d.reminder, d.late, verdict)
        }
        synchronized(lock) {
            for (s in slots) {
                if (s.id in retired || showing?.id == s.id || queue.any { it.id == s.id }) continue
                queue.addLast(s)
            }
            promote(clock())
        }
    }

    private fun slotFor(r: PendingReminder, late: Boolean, verdict: EventVerdict): Slot {
        val startMs = ReminderPlannerImpl.startMs(r)
        val autoSwitch = settings.settings.value.reminderAutoSwitch
        val alert = ReminderAlert(
            reminderId = r.id,
            identity = r.identity,
            channelName = r.channelName,
            title = r.title,
            startAtMs = startMs,
            late = late,
            autoSwitch = autoSwitch,
        )
        return Slot(
            when (verdict) {
                EventVerdict.Same -> alert
                // The title and time match, so auto-switch to the new slot is allowed.
                is EventVerdict.Moved -> alert.copy(identity = verdict.identity, channelName = verdict.channelName, movedFrom = verdict.movedFrom)
                EventVerdict.Gone -> alert.copy(autoSwitch = false, eventGone = true)
            },
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
        private const val TAG = "Reminders"
        /** Card countdown after `max(startMs, firedAt)` (Appendix B). */
        const val REMINDER_CARD_MS = 60_000L
        /** Longest sleep between passes, so a wall-clock change is noticed within a minute (§3.11.3). */
        const val MAX_SLEEP_MS = 60_000L
    }
}

/** (Event channels §6.6) What a due reminder's channel says about its event. */
sealed interface EventVerdict {
    /** Fire as before: an ordinary channel, a guide listing with the title, or the same event. */
    data object Same : EventVerdict
    /** The event is on another slot of the playlist now: fire for [identity]; [movedFrom] is the old short name. */
    data class Moved(val identity: ChannelIdentity, val channelName: String, val movedFrom: String) : EventVerdict
    /** No slot of the playlist lists the event any more. */
    data object Gone : EventVerdict
}

/** (Event channels §6.6) The fire-time check, a suspend lookup; [GuideReminderEventCheck] in the app. */
fun interface ReminderEventCheck {
    suspend fun check(r: PendingReminder): EventVerdict
}

/**
 * (Event channels, docs/design/m6-event-channels.md §6.6, re-review E5) The pure rule. [channel] is the reminder's channel
 * as the guide loads it now (null: gone, which the orphan path handles, so [EventVerdict.Same]); [listingTitle] is the
 * guide's programme on it at the reminder's raw start (null: none); [snapshot] is the event index. Returns the verdict and,
 * for a move, the found entry's channel id ([Decision.movedTo]).
 *
 * The check applies only when the channel's `event` is not null (an EVENT, or an EMPTY slot):
 * 0. the guide lists a programme at the raw start with the same folded title → same;
 * 1. the channel's event has the same folded title and starts within ± [WINDOW_MIN] → same;
 * 2. another slot of the same playlist has an event with the same folded title within ± [WINDOW_MIN] → moved (the
 *    nearest start wins);
 * 3. otherwise → gone.
 */
object ReminderEventRule {
    const val WINDOW_MIN = 30

    class Decision(val verdict: Kind, val movedTo: Long = -1L)
    enum class Kind { SAME, MOVED, GONE }

    /** Folded, with runs of whitespace collapsed to one space. */
    fun fold(title: String): String {
        val f = NameNormalizer.fold(title).trim()
        val sb = StringBuilder(f.length)
        var space = false
        for (c in f) {
            if (c.isWhitespace()) space = true
            else { if (space && sb.isNotEmpty()) sb.append(' '); space = false; sb.append(c) }
        }
        return sb.toString()
    }

    fun decide(r: PendingReminder, channel: GuideChannel?, listingTitle: String?, snapshot: EventSnapshot): Decision {
        val ev = channel?.event ?: return Decision(Kind.SAME)
        val want = fold(r.title)
        if (listingTitle != null && fold(listingTitle) == want) return Decision(Kind.SAME)
        val start = r.rawStartMin + r.shiftMin
        val evTitle = ev.title
        if (ev.kind == ChannelEvent.Kind.EVENT && evTitle != null && fold(evTitle) == want && kotlin.math.abs(ev.startMin - start) <= WINDOW_MIN) {
            return Decision(Kind.SAME)
        }
        var best: Long = -1L
        var bestDelta = Int.MAX_VALUE
        for (e in snapshot.startingIn(start - WINDOW_MIN, start + WINDOW_MIN)) {
            if (e.playlistId != r.identity.playlistId || e.channelId == channel.rowId) continue
            val t = e.event.title ?: continue
            if (e.event.kind != ChannelEvent.Kind.EVENT || fold(t) != want) continue
            val delta = kotlin.math.abs(e.event.startMin - start)
            if (delta < bestDelta) { bestDelta = delta; best = e.channelId }
        }
        return if (best >= 0) Decision(Kind.MOVED, best) else Decision(Kind.GONE)
    }
}

/**
 * (Event channels §6.6) The production [ReminderEventCheck]: loads the reminder's channel through the guide's own query
 * (so `event` is filled exactly as the guide fills it), the guide listing at its raw start (step 0) and the event index,
 * then applies [ReminderEventRule]. Runs on the caller's coroutine; every read is a suspend or short IO call.
 */
class GuideReminderEventCheck(
    private val queries: GuideQueries,
    private val events: EventIndex,
    private val store: EpgStore,
) : ReminderEventCheck {
    override suspend fun check(r: PendingReminder): EventVerdict {
        val rowId = queries.rowIdOf(r.identity) ?: return EventVerdict.Same
        val ch = queries.channels(longArrayOf(rowId), 0, 1).firstOrNull() ?: return EventVerdict.Same
        if (ch.event == null) return EventVerdict.Same
        val d = ReminderEventRule.decide(r, ch, listingTitle(ch, r.rawStartMin), events.current())
        return when (d.verdict) {
            ReminderEventRule.Kind.SAME -> EventVerdict.Same
            ReminderEventRule.Kind.GONE -> EventVerdict.Gone
            ReminderEventRule.Kind.MOVED -> {
                val to = queries.channels(longArrayOf(d.movedTo), 0, 1).firstOrNull() ?: return EventVerdict.Gone
                EventVerdict.Moved(to.identity, to.displayName, ch.displayName)
            }
        }
    }

    private fun listingTitle(ch: GuideChannel, rawStartMin: Int): String? {
        val ref = ch.epg ?: return null
        val reader = store.openReader() ?: return null
        return try {
            val id = reader.resolve(listOf(ref))[0]
            if (id < 0) null else reader.detail(id, rawStartMin, ch.epgShiftMin)?.title
        } finally {
            reader.close()
        }
    }
}
