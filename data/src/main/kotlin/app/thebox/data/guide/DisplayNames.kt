package app.thebox.data.guide

import javax.inject.Inject
import javax.inject.Singleton

/** WP-B stub (docs/design/m3-guide.md §1.3, §3.12.3): the `display_name` recompute pass and the startup backfill. */
@Singleton
class DisplayNames @Inject constructor() {
    /** Recomputes every playlist whose `name_prefix_ver` is below `ChannelNamePrefix.RULE_VERSION`, under ImportMutex. */
    suspend fun backfill(): Unit = TODO("WP-B")
    suspend fun recompute(playlistId: Long): Unit = TODO("WP-B")
}
