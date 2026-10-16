package app.thebox.data.db.dao

import app.thebox.core.epg.EpgChannelInfo
import app.thebox.core.epg.EpgStore
import app.thebox.core.text.NameNormalizer
import app.thebox.data.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The mapping picker's name search over the guide's channels (docs/design/m5-setup-settings.md §3.6.3; S13). Not a Room
 * DAO: EPG channels live in `epg.db` (raw androidx.sqlite, generation-named), so the search runs on a ref-counted
 * reader of the current generation (`EpgReader.findChannels`: the normalised-name prefix through the
 * `epg_channel_name_norm` index, or an `xmltv_id` prefix), restricted to the enabled sources of `library.db`.
 * No generation yet → empty. IO dispatcher; the reader is closed on every path.
 */
@Singleton
class EpgChannelSearchDao @Inject constructor(
    private val store: EpgStore,
    private val sources: EpgSourceDao,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    /** EPG channels of the current generation whose name or xmltv id matches [query], best first, at most [limit]. */
    suspend fun searchEpgChannels(query: String, limit: Int = 50): List<EpgChannelInfo> = withContext(io) {
        val q = query.trim()
        if (q.isEmpty() || limit <= 0) return@withContext emptyList()
        val enabled = sources.active().mapTo(HashSet()) { it.id }
        if (enabled.isEmpty()) return@withContext emptyList()
        val reader = store.openReader() ?: return@withContext emptyList()
        reader.use { r ->
            // Over-fetch: rows of disabled sources are dropped after the indexed query.
            val found = r.findChannels(q, (limit * OVERFETCH).coerceAtMost(MAX_FETCH))
            val norm = NameNormalizer.forMatch(q)
            found.asSequence()
                .filter { it.sourceId in enabled }
                .distinctBy { it.sourceId to it.xmltvId }
                // Exact name or id first, then the reader's order (imported, most programmes).
                .sortedByDescending { c -> c.xmltvId.equals(q, ignoreCase = true) || c.displayNames.any { NameNormalizer.forMatch(it) == norm } }
                .take(limit)
                .toList()
        }
    }

    private companion object {
        const val OVERFETCH = 3
        const val MAX_FETCH = 500
    }
}
