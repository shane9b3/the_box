package app.thebox.data.guide

import app.thebox.core.common.BoxLog
import app.thebox.core.text.ChannelNamePrefix
import app.thebox.core.text.NamePrefixRule
import app.thebox.core.text.NamePrefixRules
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.dao.PlaylistDao
import app.thebox.data.di.IoDispatcher
import app.thebox.data.repo.read
import app.thebox.data.repo.writeTransaction
import app.thebox.data.work.ImportMutex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `channel.display_name` recompute pass and the startup backfill (docs/design/m3-guide.md §3.12.3).
 *
 * Runs under [ImportMutex] (the merge holds it too, so they never interleave), pages by id ranges (N15) and writes
 * `UPDATE channel SET display_name = ? WHERE id = ?` only where the value changed, one transaction per page. It touches
 * nothing else: no stable key, no `channel_fts` row (FTS keeps the raw name), no `updated_at`, no content hash, so a rule
 * change is never a channel change for the differ.
 *
 * Triggers: app start (after `EpgRecovery`, after the first frame) calls [backfill]; M5's setting calls [setRule].
 */
@Singleton
class DisplayNames @Inject constructor(
    private val db: LibraryDatabase,
    private val playlistDao: PlaylistDao,
    private val prefix: ChannelNamePrefix,
    private val importMutex: ImportMutex,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /** Result of one pass (also logged). */
    data class Pass(val playlistId: Long, val scanned: Int, val changed: Int, val ms: Long)

    /** Recomputes every playlist whose `name_prefix_ver` is below `ChannelNamePrefix.RULE_VERSION`. */
    suspend fun backfill(): List<Pass> = withContext(io) {
        playlistDao.all().filter { it.namePrefixVer < ChannelNamePrefix.RULE_VERSION }.mapNotNull { recompute(it.id) }
    }

    /** Stores a new rule (forcing `name_prefix_ver = 0`) and recomputes the playlist. */
    suspend fun setRule(playlistId: Long, rule: NamePrefixRule): Pass? {
        val (mode, json) = NamePrefixRules.encode(rule)
        playlistDao.setNamePrefix(playlistId, mode, json)
        return recompute(playlistId)
    }

    /** How many channels of [playlistId] [rule] would rename (M5 editor preview, §3.12.2). Read-only. */
    suspend fun previewCount(playlistId: Long, rule: NamePrefixRule): Int = withContext(io) {
        var changed = 0
        forEachPage(playlistId) { page -> for (r in page) if (prefix.strip(r.name, r.group, rule) != null) changed++ }
        changed
    }

    suspend fun recompute(playlistId: Long): Pass? = importMutex.withLock {
        withContext(io) {
            val p = playlistDao.get(playlistId) ?: return@withContext null
            val rule = NamePrefixRules.parse(p.namePrefixMode, p.namePrefixCustom)
            val t0 = System.nanoTime()
            var scanned = 0
            var changed = 0
            forEachPage(playlistId) { page ->
                scanned += page.size
                val updates = ArrayList<Pair<Long, String?>>()
                for (r in page) {
                    val want = try {
                        prefix.strip(r.name, r.group, rule)
                    } catch (e: RuntimeException) {
                        null
                    }
                    if (want != r.displayName) updates += r.id to want
                }
                if (updates.isNotEmpty()) {
                    changed += updates.size
                    db.writeTransaction {
                        usePrepared("UPDATE channel SET display_name = ? WHERE id = ?") { st ->
                            for ((id, name) in updates) {
                                if (name == null) st.bindNull(1) else st.bindText(1, name)
                                st.bindLong(2, id)
                                st.step()
                                st.reset()
                                st.clearBindings()
                            }
                        }
                    }
                }
            }
            playlistDao.setNamePrefixVer(playlistId, ChannelNamePrefix.RULE_VERSION)
            val ms = (System.nanoTime() - t0) / 1_000_000
            BoxLog.i(TAG) { "display names: playlist=$playlistId scanned=$scanned changed=$changed in $ms ms" }
            Pass(playlistId, scanned, changed, ms)
        }
    }

    private class Row(val id: Long, val name: String, val displayName: String?, val group: String?)

    private suspend fun forEachPage(playlistId: Long, block: suspend (List<Row>) -> Unit) {
        var after = Long.MIN_VALUE
        while (true) {
            val page = db.read {
                usePrepared(PAGE_SQL) { st ->
                    st.bindLong(1, playlistId)
                    st.bindLong(2, after)
                    st.bindLong(3, PAGE.toLong())
                    buildList {
                        while (st.step()) {
                            add(Row(st.getLong(0), st.getText(1), if (st.isNull(2)) null else st.getText(2), if (st.isNull(3)) null else st.getText(3)))
                        }
                    }
                }
            }
            if (page.isEmpty()) return
            block(page)
            after = page.last().id
            if (page.size < PAGE) return
        }
    }

    companion object {
        private const val TAG = "DisplayNames"
        /** Recompute batch (Appendix B). */
        const val PAGE = 2_000

        private const val PAGE_SQL =
            "SELECT c.id, c.name, c.display_name, g.name FROM channel c LEFT JOIN channel_group g ON g.id = c.group_id " +
                "WHERE c.playlist_id = ? AND c.id > ? ORDER BY c.id LIMIT ?"
    }
}
