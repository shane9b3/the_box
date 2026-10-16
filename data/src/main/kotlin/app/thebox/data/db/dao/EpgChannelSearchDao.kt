package app.thebox.data.db.dao

import app.thebox.core.epg.EpgChannelInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The mapping picker's name search over the guide's channels (docs/design/m5-setup-settings.md §3.6.3; S13). Not a Room
 * DAO: EPG channels live in `epg.db` (raw androidx.sqlite, generation-named), and `searchEpgChannels` does not exist
 * today. Commit-0 signature with a placeholder body; WP-B owns this file (and may add the search to `EpgReaderImpl`).
 */
@Singleton
class EpgChannelSearchDao @Inject constructor() {
    /** EPG channels of the current generation whose name or xmltv id matches [query], best first, at most [limit]. */
    suspend fun searchEpgChannels(query: String, limit: Int = 50): List<EpgChannelInfo> = emptyList()
}
