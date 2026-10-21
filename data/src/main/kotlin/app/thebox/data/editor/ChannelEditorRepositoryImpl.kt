package app.thebox.data.editor

import androidx.room.PooledConnection
import app.thebox.core.common.BoxLog
import app.thebox.core.editor.ChannelEditorRepository
import app.thebox.core.editor.ChannelSort
import app.thebox.core.editor.ChannelTarget
import app.thebox.core.editor.EditorChannelRow
import app.thebox.core.editor.EditorFilter
import app.thebox.core.editor.EditorGroupRow
import app.thebox.core.editor.EditorList
import app.thebox.core.editor.EditorMoves
import app.thebox.core.editor.EditorWrite
import app.thebox.core.editor.GroupSort
import app.thebox.core.editor.GroupTarget
import app.thebox.core.editor.SelectionSummary
import app.thebox.core.editor.WriteFailure
import app.thebox.core.editor.WriteResult
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.ParentalControls
import app.thebox.core.settings.tree.RuleDraft
import app.thebox.core.settings.tree.RuleTarget
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.dao.ChannelPrefsDao
import app.thebox.data.db.dao.EditorDao
import app.thebox.data.db.dao.GroupDao
import app.thebox.data.di.IoDispatcher
import app.thebox.data.di.WallClock
import app.thebox.data.editor.EditorQueries.Col
import app.thebox.data.editor.EditorQueries.marks
import app.thebox.data.guide.GuideQueries
import app.thebox.data.repo.ChannelRepository
import app.thebox.data.repo.FavouritesRepository
import app.thebox.data.repo.exec
import app.thebox.data.repo.forEachBound
import app.thebox.data.repo.queryLong
import app.thebox.data.repo.read
import app.thebox.data.repo.writeTransaction
import app.thebox.data.rules.RegexBudgetExceeded
import app.thebox.data.rules.RuleMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ChannelEditorRepository] over `library.db` (docs/design/m6-channel-editor.md §3.4–§3.7, §4). Main-safe: everything
 * runs on [io]. Lists are ordered `LongArray`s of process-local row ids built by one raw statement ([EditorQueries]);
 * rows are fetched per page. Writes are keyed by `(playlist_id, stable_key)` / `(playlist_id, kind, group_key)` only.
 *
 * Each [write] is **one** `writeTransaction` on Room's writer connection (no `ImportMutex`, revision log item 1): an
 * AllOf target is re-resolved inside it under the same access. Locks go through [controls] (the one parental write path),
 * which resolves an AllOf inside its own transaction. Unlock, Reset and a move out of a locked group are refused under
 * [ParentalControls.refusesLowering] (`REFUSED`). Logs carry no names, keys or queries.
 */
@Singleton
class ChannelEditorRepositoryImpl @Inject constructor(
    private val db: LibraryDatabase,
    private val controls: ParentalControls,
    private val clock: WallClock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ChannelEditorRepository {

    override suspend fun playlistExists(playlistId: Long): Boolean = withContext(io) {
        db.read { queryLong("SELECT COUNT(*) FROM playlist WHERE id = ?") { bindLong(1, playlistId) } > 0 }
    }

    override suspend fun groups(playlistId: Long, showHidden: Boolean, access: ParentalAccess): ChannelEditorRepository.GroupList = withContext(io) {
        val all = db.read { EditorQueries.groupKeys(this, playlistId) }
        ChannelEditorRepository.GroupList(all.filter { showHidden || !it.second }.map { it.first }, all.count { it.second })
    }

    override suspend fun groupRows(playlistId: Long, keys: List<String>, access: ParentalAccess): List<EditorGroupRow> = withContext(io) {
        if (keys.isEmpty()) return@withContext emptyList()
        db.read {
            val facts = HashMap<String, EditorGroupRow>(keys.size * 2)
            for (chunk in keys.chunked(EditorQueries.CHUNK)) {
                usePrepared(
                    "SELECT g.group_key, COALESCE(gp.custom_name, g.name), g.name, COALESCE(gp.hidden, 0), g.rule_hidden, gp.locked, g.is_adult, " +
                        "gp.custom_name IS NOT NULL ${EditorQueries.GROUPS_FROM}" +
                        "WHERE g.playlist_id = ?1 AND g.kind = 0 AND g.group_key IN (${marks(chunk.size, 2)})",
                ) { st ->
                    st.bindLong(1, playlistId)
                    chunk.forEachIndexed { i, k -> st.bindText(i + 2, k) }
                    while (st.step()) {
                        val explicit = if (st.isNull(5)) null else st.getLong(5) != 0L
                        val adult = st.getLong(6) != 0L
                        val locked = explicit ?: (adult && access != ParentalAccess.LOCKED_MANUAL_ONLY)
                        val key = st.getText(0)
                        facts[key] = EditorGroupRow(
                            groupKey = key, name = st.getText(1), providerName = st.getText(2), channels = 0, hiddenChannels = 0,
                            hidden = st.getLong(3) != 0L, ruleHidden = st.getLong(4) != 0L, locked = locked, adult = adult,
                            notProtected = locked && access == ParentalAccess.FILTERED_NO_PIN, renamed = st.getLong(7) != 0L,
                            customOrder = false,
                        )
                    }
                }
            }
            val countSql = "SELECT COUNT(*), TOTAL(x_h), TOTAL(x_o) FROM (\n" +
                GuideQueries.groupMembersSql(
                    "${EditorQueries.COL_HIDDEN.expr} AS x_h, (cp.custom_sort_index IS NOT NULL) AS x_o",
                    GuideQueries.LINEUP_BASE,
                    "",
                ) + ")"
            keys.mapNotNull { key ->
                val f = facts[key] ?: return@mapNotNull null
                usePrepared(countSql) { st ->
                    st.bindLong(2, playlistId); st.bindText(3, key)
                    st.step()
                    f.copy(channels = st.getLong(0).toInt(), hiddenChannels = st.getDouble(1).toInt(), customOrder = st.getDouble(2) > 0)
                }
            }
        }
    }

    override suspend fun channels(playlistId: Long, list: EditorList, filter: EditorFilter, access: ParentalAccess): ChannelEditorRepository.ChannelList =
        withContext(io) { db.read { EditorQueries.channelList(this, playlistId, list, filter, access) } }

    override suspend fun channelRows(ids: LongArray, from: Int, to: Int): List<EditorChannelRow?> = withContext(io) {
        val lo = from.coerceIn(0, ids.size)
        val hi = to.coerceIn(lo, ids.size)
        if (lo >= hi) return@withContext emptyList()
        val wanted = LongArray(hi - lo) { ids[lo + it] }
        val raw = HashMap<Long, RawRow>(wanted.size * 2)
        db.read {
            for (chunk in wanted.asList().chunked(EditorQueries.CHUNK)) {
                usePrepared("$ROW_SELECT WHERE c.id IN (${marks(chunk.size, 1)})") { st ->
                    chunk.forEachIndexed { i, id -> st.bindLong(i + 1, id) }
                    while (st.step()) RawRow(st).let { raw[it.id] = it }
                }
            }
            val ruleRows = raw.values.filter { it.ruleHidden }
            if (ruleRows.isNotEmpty()) {
                val matchers = channelRules(this, ruleRows.map { it.playlistId }.toSet())
                for (r in ruleRows) r.rulePattern = matchers[r.playlistId]?.firstOrNull { (m, _) -> safeMatch(m, r.rawName) }?.second
            }
        }
        wanted.map { id -> raw[id]?.toRow() }
    }

    override suspend fun indexOf(ids: LongArray, playlistId: Long, stableKey: String): Int? = withContext(io) {
        val rowId = db.read {
            usePrepared("SELECT id FROM channel WHERE playlist_id = ? AND stable_key = ?") { st ->
                st.bindLong(1, playlistId); st.bindText(2, stableKey)
                if (st.step()) st.getLong(0) else null
            }
        } ?: return@withContext null
        ids.indexOf(rowId).takeIf { it >= 0 }
    }

    override suspend fun summarize(playlistId: Long, target: ChannelTarget, access: ParentalAccess): SelectionSummary = withContext(io) {
        db.read {
            when (target) {
                is ChannelTarget.Keys -> {
                    var acc = Sums()
                    val cols = summaryCols(EditorQueries.defaultListId(this))
                    for (chunk in target.keys.chunked(EditorQueries.CHUNK)) {
                        acc += sums(
                            "SELECT $SUMMARY_SUMS FROM (SELECT ${cols.joinToString { "${it.expr} AS ${it.alias}" }} ${EditorQueries.LIST_FROM} " +
                                "WHERE c.playlist_id = ?2 AND c.missing_since IS NULL AND c.stable_key IN (${marks(chunk.size, 3)}))",
                        ) { st -> st.bindLong(1, EditorQueries.accessOrdinal(access)); st.bindLong(2, playlistId); chunk.forEachIndexed { i, k -> st.bindText(i + 3, k) } }
                    }
                    acc.toSummary()
                }
                is ChannelTarget.AllOf -> {
                    if (target.list is EditorList.Group && EditorQueries.groupLockedOut(this, playlistId, (target.list as EditorList.Group).groupKey, access)) {
                        return@read SelectionSummary(0, 0, 0, 0, 0, 0, 0)
                    }
                    val s = EditorQueries.listSql(playlistId, target.list, access, summaryCols(EditorQueries.defaultListId(this)))
                    val where = "x_l = 0${EditorQueries.filterSql(target.filter)}"
                    var acc = sums("SELECT $SUMMARY_SUMS FROM (${s.sql}) WHERE $where", s.bind)
                    // "All of this list except these": the list's sums minus those of the excepted rows still in it.
                    for (chunk in target.except.chunked(EditorQueries.CHUNK)) {
                        acc -= sums("SELECT $SUMMARY_SUMS FROM (${s.sql}) WHERE $where AND x_k IN (${marks(chunk.size, s.next)})") { st ->
                            s.bind(st); chunk.forEachIndexed { i, k -> st.bindText(s.next + i, k) }
                        }
                    }
                    acc.toSummary()
                }
            }
        }
    }

    override suspend fun locate(playlistId: Long, list: EditorList, filter: EditorFilter, access: ParentalAccess, number: Int): Int? = withContext(io) {
        db.read {
            val mode = EditorQueries.numberingMode(this, playlistId) ?: return@read null
            val ids = EditorQueries.channelList(this, playlistId, list, filter, access).ids
            val hits = HashSet<Long>()
            usePrepared(
                "SELECT c.id FROM channel c LEFT JOIN channel_prefs cp ON cp.playlist_id = c.playlist_id AND cp.stable_key = c.stable_key " +
                    "WHERE c.playlist_id = ? AND c.missing_since IS NULL AND c.kind IN (0, 1) AND ${EditorQueries.shownNumberSql(mode)} = ?",
            ) { st ->
                st.bindLong(1, playlistId); st.bindLong(2, number.toLong())
                while (st.step()) hits += st.getLong(0)
            }
            if (hits.isEmpty()) null else ids.indexOfFirst { it in hits }.takeIf { it >= 0 }
        }
    }

    @OptIn(FlowPreview::class)
    override fun changes(playlistId: Long): Flow<Unit> {
        // §3.7: the tables plus channel_prefs through the guide's prefs signature (watch history and learned playback
        // writes leave it unchanged, so they never re-run the editor's list queries). Room's tracker is per table, so the
        // flow is not narrowed to [playlistId]; a change elsewhere re-runs at most one list query.
        val base = db.invalidationTracker.createFlow(*CHANGE_TABLES, emitInitialState = false).map { }
        val prefs = db.invalidationTracker.createFlow("channel_prefs")
            .map { GuideQueries.prefsSignature(db, withRecentCount = false) }
            .distinctUntilChanged()
            .drop(1) // the subscription's own value; only changes trigger
            .map { }
        // A refresh merge commits one transaction per batch (about 40 for 20,000 channels): debounce, so the open editor
        // re-runs its list once per merge, not once per batch (data review 2026-10-18; the guide's row tick does the same).
        return merge(base, prefs).debounce(CHANGES_DEBOUNCE_MS).conflate().flowOn(io)
    }

    // ------------------------------------------------------------------------------------------------ writes

    override suspend fun write(write: EditorWrite, access: ParentalAccess): WriteResult = withContext(io) {
        try {
            try {
                attempt(write, access, 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!busy(e)) throw e
                BoxLog.w(TAG, "editor write busy; retrying once")
                delay(BUSY_RETRY_DELAY_MS)
                attempt(write, access, 1)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "editor write ${write.javaClass.simpleName} failed: ${e.javaClass.simpleName}")
            WriteResult.Failed(if (busy(e)) WriteFailure.BUSY else WriteFailure.STORAGE)
        }
    }

    /** Test seam: called before each attempt (0, then 1 on the busy retry); a throw stands for the driver's. */
    internal var beforeAttempt: (suspend (Int) -> Unit)? = null

    private suspend fun attempt(write: EditorWrite, access: ParentalAccess, n: Int): WriteResult {
        beforeAttempt?.invoke(n)
        return run(write, access)
    }

    private suspend fun run(write: EditorWrite, access: ParentalAccess): WriteResult {
        val pid = write.playlistId
        val now = clock.now()
        return when (write) {
            is EditorWrite.HideChannels -> hideChannels(pid, write.target, write.hidden, access, now)
            is EditorWrite.HideGroups -> db.writeTransaction {
                val keys = groupTargetKeys(this, pid, write.target)
                if (keys.isEmpty()) return@writeTransaction GONE
                forEachBound(EditorQueries.UPSERT_GROUP_HIDDEN, keys) { k -> bindLong(1, pid); bindText(2, k); bindLong(3, if (write.hidden) 1 else 0); bindLong(4, now) }
                val skipped = if (write.hidden) 0 else countGroups(this, pid, keys, "g.rule_hidden = 1")
                WriteResult.Done(keys.size, skippedByRule = skipped)
            }
            is EditorWrite.LockChannels -> {
                if (!write.locked && controls.refusesLowering()) return REFUSED
                val n = db.read { EditorQueries.resolveKeys(this, pid, write.target, access).size }
                if (n == 0) return GONE
                controls.setChannelsLocked(pid, write.target, access, write.locked)
                WriteResult.Done(n)
            }
            is EditorWrite.LockGroups -> {
                if (!write.locked && controls.refusesLowering()) return REFUSED
                val keys = db.read { groupTargetKeys(this, pid, write.target) }
                if (keys.isEmpty()) return GONE
                controls.setGroupsLocked(pid, keys, write.locked)
                WriteResult.Done(keys.size)
            }
            is EditorWrite.MoveChannels -> moveChannels(pid, write.target, write.toGroupKey, access, now)
            is EditorWrite.ReorderChannels -> db.writeTransaction {
                val full = EditorQueries.members(this, pid, write.groupKey).map { it.key }
                val visible = visibleKeys(this, pid, write.groupKey, access)
                val visibleSet = visible.toHashSet()
                val block = write.block.filter { it in visibleSet }
                if (block.isEmpty()) return@writeTransaction GONE
                val spliced = EditorMoves.splice(visible, block, write.toIndex)
                // Hidden-from-this-list members (missing, or locked out) keep their slots; the visible ones take the
                // new order in the remaining slots.
                var vi = 0
                val order = full.map { if (it in visibleSet) spliced[vi++] else it }
                rewriteChannelOrder(this, pid, order, now)
                WriteResult.Done(block.size)
            }
            is EditorWrite.ReorderGroups -> db.writeTransaction {
                // The pane's coordinates: every non-missing group in the user's order (the Groups pane with hidden
                // groups shown, the default), the key set the Groups page and the guide rewrite too.
                val all = EditorQueries.groupKeys(this, pid)
                val full = all.map { it.first }
                val present = full.toHashSet()
                val block = write.block.filter { it in present }
                if (block.isEmpty()) return@writeTransaction GONE
                // With "Hidden groups: Not shown" the pane's index counts shown groups only: the drop lands before the
                // same shown neighbour in the full order (hidden groups keep their slots between).
                val toIndex = if (write.showHidden) write.toIndex else {
                    val inBlock = block.toHashSet()
                    val shownRest = all.filter { !it.second && it.first !in inBlock }.map { it.first }
                    val anchor = shownRest.getOrNull(write.toIndex.coerceIn(0, shownRest.size))
                    val fullRest = full.filter { it !in inBlock }
                    if (anchor == null) fullRest.size else fullRest.indexOf(anchor)
                }
                rewriteGroupOrder(this, pid, EditorMoves.splice(full, block, toIndex), now)
                WriteResult.Done(block.size)
            }
            is EditorWrite.SortChannels -> db.writeTransaction {
                if (!groupExists(this, pid, write.groupKey)) return@writeTransaction GONE
                val members = EditorQueries.members(this, pid, write.groupKey)
                when (write.sort) {
                    ChannelSort.PROVIDER -> {
                        exec(EditorDao.CLEAR_CHANNEL_ORDER) { bindLong(1, now); bindLong(2, pid); bindText(3, write.groupKey) }
                        WriteResult.Done(members.size)
                    }
                    ChannelSort.NAME_AZ, ChannelSort.NAME_ZA -> {
                        val collator = collator()
                        val byName = compareBy<EditorQueries.Member, String>(collator) { it.name }
                        val sorted = members.sortedWith(if (write.sort == ChannelSort.NAME_AZ) byName else byName.reversed())
                        rewriteChannelOrder(this, pid, sorted.map { it.key }, now)
                        WriteResult.Done(members.size)
                    }
                    ChannelSort.NUMBER -> {
                        val mode = EditorQueries.numberingMode(this, pid) ?: return@writeTransaction GONE
                        val sorted = members.sortedBy { it.customNumber ?: ChannelRepository.number(mode, it.number, it.position) }
                        rewriteChannelOrder(this, pid, sorted.map { it.key }, now)
                        WriteResult.Done(members.size)
                    }
                }
            }
            is EditorWrite.SortGroups -> db.writeTransaction {
                when (write.sort) {
                    GroupSort.PROVIDER -> {
                        exec(EditorDao.CLEAR_GROUP_ORDER) { bindLong(1, now); bindLong(2, pid) }
                        WriteResult.Done(EditorQueries.groupKeys(this, pid).size)
                    }
                    GroupSort.NAME_AZ -> {
                        val named = usePrepared(
                            "SELECT g.group_key, COALESCE(gp.custom_name, g.name) ${EditorQueries.GROUPS_FROM}" +
                                "WHERE g.playlist_id = ? AND g.kind = 0 AND g.missing_since IS NULL ORDER BY ${EditorQueries.GROUP_ORDER}",
                        ) { st -> st.bindLong(1, pid); buildList { while (st.step()) add(st.getText(0) to st.getText(1)) } }
                        if (named.isEmpty()) return@writeTransaction GONE
                        val collator = collator()
                        rewriteGroupOrder(this, pid, named.sortedWith(compareBy(collator) { it.second }).map { it.first }, now)
                        WriteResult.Done(named.size)
                    }
                }
            }
            is EditorWrite.RenameChannel -> db.writeTransaction {
                if (!channelExists(this, pid, write.stableKey)) return@writeTransaction GONE
                exec(ChannelPrefsDao.UPSERT_CUSTOM_NAME) {
                    bindLong(1, pid); bindText(2, write.stableKey); bindLong(3, now)
                    val name = write.name?.trim()?.ifEmpty { null }
                    if (name == null) bindNull(4) else bindText(4, name)
                }
                WriteResult.Done(1)
            }
            is EditorWrite.RenameGroup -> db.writeTransaction {
                if (!groupExists(this, pid, write.groupKey)) return@writeTransaction GONE
                exec(GroupDao.UPSERT_GROUP_CUSTOM_NAME) {
                    bindLong(1, pid); bindLong(2, 0); bindText(3, write.groupKey)
                    val name = write.name?.trim()?.ifEmpty { null }
                    if (name == null) bindNull(4) else bindText(4, name)
                    bindLong(5, now)
                }
                WriteResult.Done(1)
            }
            is EditorWrite.SetNumber -> db.writeTransaction {
                if (!channelExists(this, pid, write.stableKey)) return@writeTransaction GONE
                exec(ChannelPrefsDao.UPSERT_CUSTOM_NUMBER) {
                    bindLong(1, pid); bindText(2, write.stableKey); bindLong(3, now)
                    if (write.number == null) bindNull(4) else bindLong(4, write.number!!.toLong())
                }
                val clash = write.number?.let { n ->
                    val mode = EditorQueries.numberingMode(this, pid) ?: return@let null
                    usePrepared(
                        "SELECT COALESCE(cp.custom_name, c.display_name, c.name) FROM channel c " +
                            "LEFT JOIN channel_prefs cp ON cp.playlist_id = c.playlist_id AND cp.stable_key = c.stable_key " +
                            "WHERE c.playlist_id = ? AND c.stable_key != ? AND c.missing_since IS NULL AND c.kind IN (0, 1) " +
                            "AND ${EditorQueries.shownNumberSql(mode)} = ? ORDER BY c.sort_index LIMIT 1",
                    ) { st ->
                        st.bindLong(1, pid); st.bindText(2, write.stableKey); st.bindLong(3, n.toLong())
                        if (st.step()) st.getText(0) else null
                    }
                }
                WriteResult.Done(1, numberClash = clash)
            }
            is EditorWrite.Favorite -> favorite(pid, write.target, write.on, access, now)
            is EditorWrite.ResetChannels -> db.writeTransaction {
                val keys = EditorQueries.resolveKeys(this, pid, write.target, access)
                if (keys.isEmpty()) return@writeTransaction GONE
                if (controls.refusesLowering()) {
                    // A Reset clears locks and returns moved channels home (possibly out of a locked group).
                    val info = EditorQueries.targetInfo(this, pid, keys)
                    if (info.values.any { it.explicitLock || (it.moved && it.groupLock) }) return@writeTransaction REFUSED
                }
                forEachBound(EditorDao.RESET_CHANNEL, keys) { k -> bindLong(1, now); bindLong(2, pid); bindText(3, k) }
                WriteResult.Done(keys.size)
            }
            is EditorWrite.ResetGroups -> db.writeTransaction {
                val keys = groupTargetKeys(this, pid, write.target)
                if (keys.isEmpty()) return@writeTransaction GONE
                if (controls.refusesLowering() && countGroups(this, pid, keys, "gp.locked = 1") > 0) return@writeTransaction REFUSED
                // GroupDao.deletePrefs: hidden, lock, order and name. Moves into or out of the group stay (§3.5).
                forEachBound("DELETE FROM group_prefs WHERE playlist_id = ? AND kind = 0 AND group_key = ?", keys) { k -> bindLong(1, pid); bindText(2, k) }
                WriteResult.Done(keys.size)
            }
        }
    }

    private suspend fun hideChannels(pid: Long, target: ChannelTarget, hidden: Boolean, access: ParentalAccess, now: Long): WriteResult = db.writeTransaction {
        if (target is ChannelTarget.AllOf && target.except.isEmpty()) {
            // One aggregate over the same list statement (no key list held), then the one upsert (data review 2026-10-18).
            val c = EditorQueries.listSql(pid, target.list, access, listOf(EditorQueries.COL_KEY, EditorQueries.COL_HIDDEN, EditorQueries.COL_LOCKED_OUT, EditorQueries.COL_RULE))
            val (n, rule) = usePrepared("SELECT COUNT(*), TOTAL(x_rh) FROM (${c.sql}) WHERE x_l = 0${EditorQueries.filterSql(target.filter)}") { st ->
                c.bind(st); st.step(); st.getLong(0).toInt() to st.getDouble(1).toInt()
            }
            if (n == 0) return@writeTransaction GONE
            val s = EditorQueries.hideAllSql(pid, target, access)
            usePrepared(s.sql) { st ->
                s.bind(st); st.bindLong(s.next - 2, if (hidden) 1 else 0); st.bindLong(s.next - 1, now)
                st.step()
            }
            return@writeTransaction WriteResult.Done(n, skippedByRule = if (hidden) 0 else rule)
        }
        val keys = EditorQueries.resolveKeys(this, pid, target, access)
        if (keys.isEmpty()) return@writeTransaction GONE
        forEachBound(ChannelPrefsDao.UPSERT_HIDDEN, keys) { k -> bindLong(1, pid); bindText(2, k); bindLong(3, if (hidden) 1 else 0); bindLong(4, now) }
        WriteResult.Done(keys.size, skippedByRule = if (hidden) 0 else countRuleHidden(this, pid, keys))
    }

    /**
     * Move to group (§3.5 rules a, b; §3.6). Into a custom-ordered target: `max + 1 …` in list order. Into one without a
     * custom order: its provider order is materialised densely first (which freezes it; Sort › Provider order undoes it),
     * then the moved channels follow. A move to a channel's own provider group writes NULL group and NULL order.
     */
    private suspend fun moveChannels(pid: Long, target: ChannelTarget, toKey: String, access: ParentalAccess, now: Long): WriteResult = db.writeTransaction {
        if (!groupExists(this, pid, toKey)) return@writeTransaction GONE
        val keys = EditorQueries.resolveKeys(this, pid, target, access)
        if (keys.isEmpty()) return@writeTransaction GONE
        val info = EditorQueries.targetInfo(this, pid, keys)
        // C-B2 (data review 2026-10-18): a move that would leave a locked channel unlocked lowers protection (out of a
        // locked group, or an adult channel into a group whose lock was turned off). Locked to locked is fine.
        if (controls.refusesLowering()) {
            val target = EditorQueries.groupLockFacts(this, pid, toKey) ?: return@writeTransaction GONE
            if (info.values.any { it.lockedNow && !it.lockedIn(target.first, target.second) }) return@writeTransaction REFUSED
        }
        val movers = keys.filter { k -> info[k]?.let { it.effectiveKey != toKey || (it.moved && it.providerKey == toKey) } ?: false }
        val (back, into) = movers.partition { info[it]?.providerKey == toKey }
        if (into.isNotEmpty()) {
            val members = EditorQueries.members(this, pid, toKey)
            val custom = members.any { it.sortIndex != null }
            val base = if (custom) {
                members.maxOf { it.sortIndex ?: -1 } + 1
            } else {
                rewriteChannelOrder(this, pid, members.map { it.key }, now)
                members.size
            }
            forEachBound(ChannelPrefsDao.UPSERT_CUSTOM_GROUP, into.withIndex()) { (i, k) ->
                bindLong(1, pid); bindText(2, k); bindLong(3, now); bindText(4, toKey); bindLong(5, (base + i).toLong())
            }
        }
        forEachBound(ChannelPrefsDao.UPSERT_CUSTOM_GROUP, back) { k ->
            bindLong(1, pid); bindText(2, k); bindLong(3, now); bindNull(4); bindNull(5)
        }
        WriteResult.Done(movers.size)
    }

    /** Favorites in the default list (§3.5): created inside the transaction when none exists; adds keep list order. */
    private suspend fun favorite(pid: Long, target: ChannelTarget, on: Boolean, access: ParentalAccess, now: Long): WriteResult = db.writeTransaction {
        val keys = EditorQueries.resolveKeys(this, pid, target, access)
        if (keys.isEmpty()) return@writeTransaction GONE
        var list = EditorQueries.defaultListId(this)
        if (!on) {
            val id = list ?: return@writeTransaction WriteResult.Done(0)
            forEachBound("DELETE FROM favourite WHERE list_id = ? AND playlist_id = ? AND stable_key = ?", keys) { k -> bindLong(1, id); bindLong(2, pid); bindText(3, k) }
            return@writeTransaction WriteResult.Done(keys.size)
        }
        if (list == null) {
            exec("INSERT INTO favourite_list(name, sort_index, created_at) VALUES (?, 0, ?)") { bindText(1, FavouritesRepository.DEFAULT_NAME); bindLong(2, now) }
            list = queryLong("SELECT last_insert_rowid()")
        }
        val present = HashSet<String>()
        for (chunk in keys.chunked(EditorQueries.CHUNK)) {
            usePrepared("SELECT stable_key FROM favourite WHERE list_id = ?1 AND playlist_id = ?2 AND stable_key IN (${marks(chunk.size, 3)})") { st ->
                st.bindLong(1, list); st.bindLong(2, pid); chunk.forEachIndexed { i, k -> st.bindText(i + 3, k) }
                while (st.step()) present += st.getText(0)
            }
        }
        val add = keys.filter { it !in present }
        val base = queryLong("SELECT COALESCE(MAX(sort_index), -1) FROM favourite WHERE list_id = ?") { bindLong(1, list) } + 1
        forEachBound("INSERT OR IGNORE INTO favourite(list_id, playlist_id, stable_key, sort_index, added_at) VALUES (?, ?, ?, ?, ?)", add.withIndex()) { (i, k) ->
            bindLong(1, list); bindLong(2, pid); bindText(3, k); bindLong(4, base + i); bindLong(5, now)
        }
        WriteResult.Done(add.size)
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The dense rewrite of a group's channel order (`custom_sort_index` 0 … n − 1), one batch in the transaction. */
    private suspend fun rewriteChannelOrder(conn: PooledConnection, pid: Long, orderedKeys: List<String>, now: Long) {
        conn.forEachBound(EditorDao.UPSERT_SORT_INDEX, orderedKeys.withIndex()) { (i, k) ->
            bindLong(1, pid); bindText(2, k); bindLong(3, now); bindLong(4, i.toLong())
        }
    }

    /** `GroupDao.setGroupOrder` over the full ordered key list, batched in the transaction. */
    private suspend fun rewriteGroupOrder(conn: PooledConnection, pid: Long, orderedKeys: List<String>, now: Long) {
        conn.forEachBound(EditorQueries.UPSERT_GROUP_SORT_INDEX, orderedKeys.withIndex()) { (i, k) ->
            bindLong(1, pid); bindText(2, k); bindLong(3, i.toLong()); bindLong(4, now)
        }
    }

    /** The group's channels as the editor lists them (filter All, [access]): the coordinates of a channel Reorder. */
    private suspend fun visibleKeys(conn: PooledConnection, pid: Long, groupKey: String, access: ParentalAccess): List<String> {
        val s = EditorQueries.listSql(pid, EditorList.Group(groupKey), access, listOf(EditorQueries.COL_KEY, EditorQueries.COL_LOCKED_OUT))
        return conn.usePrepared(s.sql) { st ->
            s.bind(st)
            buildList { while (st.step()) if (st.getLong(1) == 0L) add(st.getText(0)) }
        }
    }

    private suspend fun groupTargetKeys(conn: PooledConnection, pid: Long, target: GroupTarget): List<String> {
        val all = EditorQueries.groupKeys(conn, pid)
        return when (target) {
            is GroupTarget.Keys -> all.map { it.first }.filter { it in target.keys }
            is GroupTarget.AllOf -> all.filter { (k, hidden) -> (target.showHidden || !hidden) && k !in target.except }.map { it.first }
        }
    }

    private suspend fun countGroups(conn: PooledConnection, pid: Long, keys: List<String>, predicate: String): Int {
        var n = 0
        for (chunk in keys.chunked(EditorQueries.CHUNK)) {
            n += conn.queryLong(
                "SELECT COUNT(*) ${EditorQueries.GROUPS_FROM}WHERE g.playlist_id = ?1 AND g.kind = 0 AND g.group_key IN (${marks(chunk.size, 2)}) AND $predicate",
            ) { bindLong(1, pid); chunk.forEachIndexed { i, k -> bindText(i + 2, k) } }.toInt()
        }
        return n
    }

    private suspend fun countRuleHidden(conn: PooledConnection, pid: Long, keys: List<String>): Int {
        var n = 0
        for (chunk in keys.chunked(EditorQueries.CHUNK)) {
            n += conn.queryLong("SELECT COUNT(*) FROM channel WHERE playlist_id = ?1 AND rule_hidden = 1 AND stable_key IN (${marks(chunk.size, 2)})") {
                bindLong(1, pid); chunk.forEachIndexed { i, k -> bindText(i + 2, k) }
            }.toInt()
        }
        return n
    }

    private suspend fun groupExists(conn: PooledConnection, pid: Long, key: String): Boolean =
        conn.queryLong("SELECT COUNT(*) FROM channel_group WHERE playlist_id = ? AND kind = 0 AND group_key = ?") { bindLong(1, pid); bindText(2, key) } > 0

    private suspend fun channelExists(conn: PooledConnection, pid: Long, key: String): Boolean =
        conn.queryLong("SELECT COUNT(*) FROM channel WHERE playlist_id = ? AND stable_key = ?") { bindLong(1, pid); bindText(2, key) } > 0

    /** Enabled channel-name rules per playlist, compiled (for a rule-hidden row's detail, §2.6). */
    private suspend fun channelRules(conn: PooledConnection, playlists: Set<Long>): Map<Long, List<Pair<RuleMatcher, String>>> {
        val out = HashMap<Long, MutableList<Pair<RuleMatcher, String>>>()
        for (pid in playlists) {
            conn.usePrepared("SELECT id, pattern, is_regex FROM hide_rule WHERE playlist_id = ? AND enabled = 1 AND target = ? ORDER BY id") { st ->
                st.bindLong(1, pid); st.bindLong(2, RuleTarget.CHANNEL_NAME.ordinal.toLong())
                while (st.step()) {
                    val pattern = st.getText(1)
                    val r = RuleMatcher.compile(st.getLong(0), RuleDraft(RuleTarget.CHANNEL_NAME, pattern, st.getLong(2) != 0L))
                    if (r is RuleMatcher.Result.Ok) out.getOrPut(pid) { ArrayList() } += r.matcher to pattern
                }
            }
        }
        return out
    }

    private fun safeMatch(m: RuleMatcher, text: String): Boolean = try {
        m.matches(text, System.nanoTime() + RULE_MATCH_BUDGET_NS)
    } catch (e: RegexBudgetExceeded) {
        false
    }

    private fun collator(): Collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }

    internal fun busy(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val m = t.message ?: ""
            if ("SQLITE_BUSY" in m || "database is locked" in m) return true
            t = t.cause
        }
        return false
    }

    private class Sums(val total: Long = 0, val hidden: Long = 0, val ruleHidden: Long = 0, val locked: Long = 0, val lockedByGroup: Long = 0, val favorite: Long = 0) {
        operator fun plus(o: Sums) = Sums(total + o.total, hidden + o.hidden, ruleHidden + o.ruleHidden, locked + o.locked, lockedByGroup + o.lockedByGroup, favorite + o.favorite)
        operator fun minus(o: Sums) = Sums(total - o.total, hidden - o.hidden, ruleHidden - o.ruleHidden, locked - o.locked, lockedByGroup - o.lockedByGroup, favorite - o.favorite)
        fun toSummary() = SelectionSummary(
            total = total.toInt(), hidden = hidden.toInt(), ruleHidden = ruleHidden.toInt(), locked = locked.toInt(),
            lockedByGroup = lockedByGroup.toInt(), favorite = favorite.toInt(), fromLockedGroup = lockedByGroup.toInt(),
        )
    }

    private suspend fun PooledConnection.sums(sql: String, bind: (androidx.sqlite.SQLiteStatement) -> Unit): Sums = usePrepared(sql) { st ->
        bind(st)
        if (!st.step()) Sums() else Sums(st.getLong(0), st.getDouble(1).toLong(), st.getDouble(2).toLong(), st.getDouble(3).toLong(), st.getDouble(4).toLong(), st.getDouble(5).toLong())
    }

    private class RawRow(st: androidx.sqlite.SQLiteStatement) {
        val id = st.getLong(0)
        val playlistId = st.getLong(1)
        val stableKey: String = st.getText(2)
        val name: String = st.getText(3)
        val providerName: String = st.getText(4)
        val customNumber: Int? = if (st.isNull(5)) null else st.getLong(5).toInt()
        val number: Int? = if (st.isNull(6)) null else st.getLong(6).toInt()
        val sortIndex = st.getLong(7).toInt()
        val logo: String? = if (st.isNull(8)) null else st.getText(8)
        val hidden = st.getLong(9) != 0L
        val ruleHidden = st.getLong(10) != 0L
        val ownLock = st.getLong(11) != 0L
        val groupLock = st.getLong(12) != 0L
        val groupName: String? = if (st.isNull(13)) null else st.getText(13)
        val favorite = st.getLong(14) != 0L
        val movedFrom: String? = if (st.isNull(15)) null else st.getText(15)
        val renamed = st.getLong(16) != 0L
        val mode: String = st.getText(17)
        val rawName: String = st.getText(18)
        var rulePattern: String? = null

        fun toRow() = EditorChannelRow(
            stableKey = stableKey, name = name, providerName = providerName,
            number = customNumber ?: ChannelRepository.number(mode, number, sortIndex), customNumber = customNumber, logoUrl = logo,
            hidden = hidden, ruleHidden = ruleHidden, rulePattern = rulePattern,
            locked = ownLock, lockedByGroup = if (groupLock) groupName ?: "" else null,
            favorite = favorite, movedFrom = movedFrom, renamed = renamed,
        )
    }

    companion object {
        private const val TAG = "ChannelEditor"
        const val CHANGES_DEBOUNCE_MS = 300L
        /** A short pause before the one retry after SQLITE_BUSY, so the other writer can finish. */
        const val BUSY_RETRY_DELAY_MS = 75L
        private const val RULE_MATCH_BUDGET_NS = 50_000_000L
        private val GONE = WriteResult.Failed(WriteFailure.GONE)
        private val REFUSED = WriteResult.Failed(WriteFailure.REFUSED)

        /** §3.7: every table but `channel_prefs`, which is watched through the prefs signature. */
        val CHANGE_TABLES = arrayOf("channel", "channel_group", "group_prefs", "favourite", "hide_rule", "playlist")

        /** A page of rows (§4 item 1): the guide's block shape plus prefs, the effective group and the favorite flag. */
        private const val ROW_SELECT =
            "SELECT c.id, c.playlist_id, c.stable_key, COALESCE(cp.custom_name, c.display_name, c.name), COALESCE(c.display_name, c.name), " +
                "cp.custom_number, c.number, c.sort_index, COALESCE(cp.custom_logo, c.logo_url), COALESCE(cp.hidden, 0), c.rule_hidden, " +
                "${EditorQueries.OWN_LOCK}, ${EditorQueries.GROUP_LOCK}, COALESCE(gp.custom_name, g.name), ${EditorQueries.FAVORITE}, " +
                "CASE WHEN gt.id IS NOT NULL AND (g0.id IS NULL OR gt.id != g0.id) THEN COALESCE(gp0.custom_name, g0.name, '') END, " +
                "cp.custom_name IS NOT NULL, p.numbering_mode, c.name " +
                EditorQueries.ALL_FROM +
                "LEFT JOIN group_prefs gp0 ON gp0.playlist_id = c.playlist_id AND gp0.kind = g0.kind AND gp0.group_key = g0.group_key "

        private fun summaryCols(defaultListId: Long?) = listOf(
            EditorQueries.COL_KEY,
            EditorQueries.COL_HIDDEN,
            EditorQueries.COL_LOCKED_OUT,
            Col("COALESCE(cp.hidden, 0) != 0", "x_uh"),
            Col("(c.rule_hidden = 1 AND COALESCE(cp.hidden, 0) = 0)", "x_rh"),
            Col("(${EditorQueries.OWN_LOCK} OR ${EditorQueries.GROUP_LOCK})", "x_lk"),
            Col(EditorQueries.GROUP_LOCK, "x_gl"),
            Col(EditorQueries.favoriteSql(defaultListId), "x_fav"),
        )
        private const val SUMMARY_SUMS = "COUNT(*), TOTAL(x_uh), TOTAL(x_rh), TOTAL(x_lk), TOTAL(x_gl), TOTAL(x_fav)"
    }
}
