package app.thebox.core.editor

import app.thebox.core.parental.ParentalAccess
import kotlinx.coroutines.flow.Flow

/**
 * The editor's read and write path over `library.db` (docs/design/m6-channel-editor.md §3.4–§3.6). Implemented in
 * `:data` (`ChannelEditorRepositoryImpl`, raw statements like `GuideQueries`). Lists are ordered process-local row ids
 * (never persisted); writes are keyed by `(playlist_id, stable_key)` / `(playlist_id, kind, group_key)` only, one
 * write transaction per [EditorWrite].
 */
interface ChannelEditorRepository {
    /** Groups are few (typically < 2,000 short strings). */
    class GroupList(val keys: List<String>, val hidden: Int)
    class ChannelList(val ids: LongArray, val hidden: Int, val lockedOut: Int, val listLocked: Boolean)

    suspend fun playlistExists(playlistId: Long): Boolean
    /** Ordered LIVE group keys (user order, then provider order); hidden groups included when [showHidden]. */
    suspend fun groups(playlistId: Long, showHidden: Boolean, access: ParentalAccess): GroupList
    suspend fun groupRows(playlistId: Long, keys: List<String>, access: ParentalAccess): List<EditorGroupRow>
    /** Ordered process-local channel row ids of a list (the guide's LongArray approach, GuideQueries.lineup). */
    suspend fun channels(playlistId: Long, list: EditorList, filter: EditorFilter, access: ParentalAccess): ChannelList
    suspend fun channelRows(ids: LongArray, from: Int, to: Int): List<EditorChannelRow?>
    suspend fun indexOf(ids: LongArray, playlistId: Long, stableKey: String): Int?
    suspend fun summarize(playlistId: Long, target: ChannelTarget, access: ParentalAccess): SelectionSummary
    suspend fun locate(playlistId: Long, list: EditorList, filter: EditorFilter, access: ParentalAccess, number: Int): Int?
    suspend fun write(write: EditorWrite, access: ParentalAccess): WriteResult
    /**
     * Emits on changes of channel, channel_group, group_prefs, favourite, hide_rule, playlist, and of channel_prefs
     * filtered through the guide's prefs signature (§3.7), so watch-history writes never re-run a list query.
     */
    fun changes(playlistId: Long): Flow<Unit>
}
