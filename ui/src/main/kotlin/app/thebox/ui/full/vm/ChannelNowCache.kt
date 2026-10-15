package app.thebox.ui.full.vm

import app.thebox.core.guide.ProgrammeInfo

/**
 * WP-D stub (docs/design/m4-player-ux.md §1.5, §3.3.2): "now" programmes of the channel list, an LRU of [capacity]
 * keyed by `identityHash`; an entry expires at its programme's end minute. Main thread only.
 */
class ChannelNowCache(private val capacity: Int = NOW_CACHE) {
    /** The cached programme on now at [nowMin], or null when missing or expired. */
    fun get(identityHash: Long, nowMin: Int): ProgrammeInfo? = TODO("WP-D")
    fun put(identityHash: Long, info: ProgrammeInfo?): Unit = TODO("WP-D")
    /** Bumped on every put; the view model forwards it as `PlayerEvent.ListNow`. */
    val version: Long get() = TODO("WP-D")

    companion object {
        const val NOW_CACHE = 256
    }
}
