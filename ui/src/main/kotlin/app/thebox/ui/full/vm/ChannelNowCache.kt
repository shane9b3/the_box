package app.thebox.ui.full.vm

import app.thebox.core.guide.ProgrammeInfo

/**
 * "Now" programmes of the channel list (docs/design/m4-player-ux.md §3.3.2, §4.3): an LRU of [capacity] entries keyed
 * by `identityHash`. A programme entry expires at its programme's end minute; a "nothing on" entry (no EPG or a gap)
 * expires at the next minute, so the minute tick asks again only for those and for programmes that ended. Display
 * minutes throughout. Main thread only.
 */
class ChannelNowCache(private val capacity: Int = NOW_CACHE) {
    private class Entry(val info: ProgrammeInfo?, val untilMin: Int)

    private val map = object : LinkedHashMap<Long, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?): Boolean = size > capacity
    }

    /** Bumped on every put; the view model forwards it as `PlayerEvent.ListNow`. */
    var version: Long = 0
        private set

    /** The cached programme on now at [nowMin], or null when missing, expired, or known to be "nothing on". */
    fun get(identityHash: Long, nowMin: Int): ProgrammeInfo? {
        val e = map[identityHash] ?: return null
        return if (nowMin < e.untilMin) e.info else null
    }

    /** True when [identityHash] has an entry valid at [nowMin] (a known "nothing on" counts: no need to ask again). */
    fun isFresh(identityHash: Long, nowMin: Int): Boolean {
        val e = map[identityHash] ?: return false
        return nowMin < e.untilMin && (e.info == null || nowMin >= e.info.startMin)
    }

    /** Stores [info] (null = nothing on at [nowMin]); the entry expires at the programme's end, else the next minute. */
    fun put(identityHash: Long, info: ProgrammeInfo?, nowMin: Int = Int.MIN_VALUE) {
        val until = info?.endMin ?: if (nowMin == Int.MIN_VALUE) Int.MIN_VALUE else nowMin + 1
        map[identityHash] = Entry(info, until)
        version++
    }

    val size: Int get() = map.size

    fun clear() {
        map.clear()
        version++
    }

    companion object {
        const val NOW_CACHE = 256
    }
}
