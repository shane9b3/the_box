package app.thebox.core.guide.fakes

import kotlinx.coroutines.flow.receiveAsFlow

import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.PendingReminder
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.ReminderAlert
import app.thebox.core.guide.ReminderAlerts
import app.thebox.core.guide.ReminderBook
import app.thebox.core.guide.ReminderDecision
import app.thebox.core.guide.ReminderIndex
import app.thebox.core.guide.ReminderPlanner
import app.thebox.core.refresh.ChannelIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory [ReminderBook] with the §2.6 semantics: one pending per (identity, rawStart); shift = start − rawStart. */
class FakeReminderBook : ReminderBook {
    private val rows = LinkedHashMap<Long, PendingReminder>()
    private val states = HashMap<Long, String>()
    private var nextId = 1L
    private val idx = MutableStateFlow(ReminderIndex.EMPTY)
    private val pend = MutableStateFlow<List<PendingReminder>>(emptyList())
    override val index: StateFlow<ReminderIndex> = idx.asStateFlow()
    override val pending: StateFlow<List<PendingReminder>> = pend.asStateFlow()

    fun state(id: Long): String? = states[id]

    override suspend fun set(channel: GuideChannel, info: ProgrammeInfo, leadSec: Int): Boolean {
        val existing = rows.values.firstOrNull { it.identity == channel.identity && it.rawStartMin == info.rawStartMin }
        if (existing != null && states[existing.id] == PENDING) return false
        if (existing != null) { rows.remove(existing.id); states.remove(existing.id) }
        val id = nextId++
        rows[id] = PendingReminder(
            id = id, identity = channel.identity, rawStartMin = info.rawStartMin,
            rawEndMin = info.rawStartMin + (info.endMin - info.startMin), shiftMin = info.startMin - info.rawStartMin,
            title = info.title, channelName = channel.displayName, leadSec = leadSec,
        )
        states[id] = PENDING
        publish()
        return true
    }

    override suspend fun cancel(identity: ChannelIdentity, rawStartMin: Int) {
        rows.values.filter { it.identity == identity && it.rawStartMin == rawStartMin }.forEach { rows.remove(it.id); states.remove(it.id) }
        publish()
    }

    override suspend fun markFired(id: Long) = mark(id, "FIRED")
    override suspend fun dismiss(id: Long) = mark(id, "DISMISSED")
    override suspend fun markOrphaned(id: Long) = mark(id, "ORPHANED")

    private fun mark(id: Long, state: String) {
        if (states[id] == PENDING) states[id] = state
        publish()
    }

    private fun publish() {
        val p = rows.values.filter { states[it.id] == PENDING }.sortedBy { it.rawStartMin }
        pend.value = p
        idx.value = ReminderIndex.of(p.map { GuideChannel.hashOf(it.identity) to it.rawStartMin })
    }

    private companion object { const val PENDING = "PENDING" }
}

/** Returns [decisions] from every [decide] call and [wakeMs] from [nextWakeMs]. */
class FakeReminderPlanner(var decisions: List<ReminderDecision> = emptyList(), var wakeMs: Long = Long.MAX_VALUE) : ReminderPlanner {
    var calls = 0
    override fun decide(pending: List<PendingReminder>, nowMs: Long, out: MutableList<ReminderDecision>) { calls++; out += decisions }
    override fun nextWakeMs(pending: List<PendingReminder>, nowMs: Long): Long = wakeMs
}

class FakeReminderAlerts : ReminderAlerts {
    val mutable = MutableStateFlow<ReminderAlert?>(null)
    val accepted = ArrayList<Long>()
    val dismissed = ArrayList<Long>()
    override val current: StateFlow<ReminderAlert?> = mutable.asStateFlow()
    override suspend fun accept(reminderId: Long) { accepted += reminderId; mutable.value = null }
    override suspend fun dismiss(reminderId: Long) { dismissed += reminderId; mutable.value = null }
    /** Missed counts, buffered until collected (like the scheduler's channel). */
    val missedCounts = kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.BUFFERED)
    override val missed: kotlinx.coroutines.flow.Flow<Int> = missedCounts.receiveAsFlow()
}
