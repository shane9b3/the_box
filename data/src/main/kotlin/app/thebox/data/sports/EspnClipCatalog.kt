package app.thebox.data.sports

import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.VideoEntry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (NFL hub, docs/design/m6-nfl-hub.md §4.2, §5.4; P2) The in-memory clip catalog: offered ESPN [VideoEntry]s by clip id,
 * an access-ordered LRU of `ESPN_CLIP_CATALOG_MAX`, `synchronized`. Written by `EspnDetailSource` after each summary parse
 * and (§20) with the shown game's board highlight, and by `EspnClipResolver` after a record lookup; never persisted. The
 * HLS URL lives here, in the `ClipStream` handed to `playClip` and (§20) in the board snapshot's in-memory
 * `GameMedia.highlight` (`VideoEntry.toString` prints the clip id only). On `onTrimMemory` ≥ RUNNING_LOW
 * (the repository's `trim()`) everything but the last clip handed to the player is cleared.
 *
 * It also carries [skewMs], the last ESPN answer's `Date` − wall clock (clamped to ±`HUB_CLOCK_SKEW_MAX_MS`), so the
 * resolver checks `ClipOffer` at `now + skew` (§4.3) without reading a summary.
 */
@Singleton
class EspnClipCatalog @Inject constructor() {
    private val max: Int = SportsConstants.ESPN_CLIP_CATALOG_MAX

    private val map = object : LinkedHashMap<Long, VideoEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, VideoEntry>?): Boolean = size > max
    }

    /** The newest ESPN server-date skew (ms); 0 until an answer carried a `Date`. */
    @Volatile var skewMs: Long = 0L
        private set

    /** The id of the last clip the resolver handed to the player (kept by [trim]); null for none. */
    @Volatile var lastServed: Long? = null
        private set

    /** The entry for [videoId], or null. */
    fun get(videoId: Long): VideoEntry? = synchronized(map) { map[videoId] }

    /** Adds or replaces [entries] (most recent last). */
    fun put(entries: List<VideoEntry>) {
        if (entries.isEmpty()) return
        synchronized(map) { for (e in entries) map[e.clip.videoId] = e }
    }

    /** Drops every entry but [keep] (the playing clip's id, or null). */
    fun trim(keep: Long?) {
        synchronized(map) { map.keys.retainAll { it == keep } }
    }

    /** [trim] keeping [lastServed]. */
    fun trimToServed() = trim(lastServed)

    /** Records the server-date skew of an ESPN answer received at [wallMs] (null [serverDateMs]: unchanged). */
    fun noteServerDate(serverDateMs: Long?, wallMs: Long): Long? {
        serverDateMs ?: return null
        val s = skewOf(serverDateMs, wallMs)
        skewMs = s
        return s
    }

    internal fun served(videoId: Long) {
        lastServed = videoId
    }

    /** For tests: the entry count. */
    internal val size: Int get() = synchronized(map) { map.size }

    internal companion object {
        /** `serverDate − wall`, clamped to ±`HUB_CLOCK_SKEW_MAX_MS` (§4.3). */
        fun skewOf(serverDateMs: Long, wallMs: Long): Long =
            (serverDateMs - wallMs).coerceIn(-SportsConstants.HUB_CLOCK_SKEW_MAX_MS, SportsConstants.HUB_CLOCK_SKEW_MAX_MS)
    }
}
