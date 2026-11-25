package app.thebox.data.sports

import app.thebox.core.sports.hub.VideoEntry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (NFL hub, docs/design/m6-nfl-hub.md §4.2, §5.4; P2) The in-memory clip catalog: offered ESPN [VideoEntry]s by clip id,
 * an LRU of `ESPN_CLIP_CATALOG_MAX`, `synchronized`. Written by `EspnDetailSource` after each summary parse and by
 * `EspnClipResolver` after a record lookup; never by the board; never persisted. The HLS URL lives only here and in the
 * `ClipStream` handed to `playClip`. On `onTrimMemory` ≥ RUNNING_LOW everything but the playing clip's entry is cleared.
 *
 * Commit 0: an empty stub (every [get] misses; [put] keeps nothing). P2 implements it.
 */
@Singleton
class EspnClipCatalog @Inject constructor() {
    /** The entry for [videoId], or null. */
    @Suppress("UNUSED_PARAMETER")
    fun get(videoId: Long): VideoEntry? = null // P2

    /** Adds or replaces [entries] (most recent last). */
    @Suppress("UNUSED_PARAMETER")
    fun put(entries: List<VideoEntry>) = Unit // P2

    /** Drops every entry but [keep] (the playing clip's id, or null). */
    @Suppress("UNUSED_PARAMETER")
    fun trim(keep: Long?) = Unit // P2
}
