package app.thebox.core.events

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * (Event channels commit 0, docs/design/m6-event-channels.md §5.2) An [EventIndex] over entries the test sets. [put]
 * replaces the snapshot and bumps [version], as a rebuild would; [current] and [rebuild] answer the latest snapshot and
 * are counted. No database, no clock.
 */
class FakeEventIndex(entries: List<EventEntry> = emptyList()) : EventIndex {
    override val version = MutableStateFlow(0L)
    private var list: List<EventEntry> = entries
    private var snapshot: EventSnapshot = FakeEventSnapshot(entries, 0L)
    var currentCalls = 0
        private set
    var rebuilds = 0
        private set

    /** Publishes [entries] as a new snapshot with the next version. */
    fun put(entries: List<EventEntry>) {
        val v = version.value + 1
        list = entries
        snapshot = FakeEventSnapshot(entries, v)
        version.value = v
    }

    /** The entries of the latest [put] (or the constructor's). */
    fun entries(): List<EventEntry> = list

    override suspend fun current(): EventSnapshot { currentCalls++; return snapshot }
    override suspend fun rebuild(): EventSnapshot { rebuilds++; return snapshot }
    /** The latest snapshot; [peekNull] makes it answer null (a cold index), as before the first build. */
    var peekNull = false
    var prefetches = 0
        private set
    override fun peek(): EventSnapshot? = if (peekNull) null else snapshot
    override fun prefetch() { prefetches++ }

    companion object {
        /** An EVENT entry for tests: [startMin] / [endMin] are UTC epoch minutes. */
        fun event(
            channelId: Long,
            title: String,
            startMin: Int,
            endMin: Int = startMin + EventConstants.EVENT_MIN_MATCHUP,
            playlistId: Long = 1L,
            slot: String? = null,
            group: String? = null,
            subtitle: String? = null,
            zone: EventZoneSource = EventZoneSource.EXPLICIT,
            name: String = (slot?.let { "$it: " } ?: "") + title,
        ): EventEntry = EventEntry(
            channelId, playlistId, slot, group, ChannelEvent(ChannelEvent.Kind.EVENT, title, subtitle, startMin, endMin, zone), name,
        )
    }
}

/** A sorted, immutable [EventSnapshot] over [entries]: linear scans are fine at test sizes. */
class FakeEventSnapshot(entries: List<EventEntry>, override val version: Long) : EventSnapshot {
    private val sorted = entries.sortedBy { it.event.startMin }
    private val byId = entries.associateBy { it.channelId }

    override fun of(channelId: Long): EventEntry? = byId[channelId]
    override fun startingIn(fromMin: Int, toMin: Int): List<EventEntry> =
        sorted.filter { it.event.kind == ChannelEvent.Kind.EVENT && it.event.startMin in fromMin..toMin }
    override fun current(nowMin: Int): List<EventEntry> =
        sorted.filter { it.event.kind == ChannelEvent.Kind.EVENT && it.event.endMin > nowMin }
    override fun count(playlistId: Long): Int =
        sorted.count { it.playlistId == playlistId && it.event.kind == ChannelEvent.Kind.EVENT }
}
