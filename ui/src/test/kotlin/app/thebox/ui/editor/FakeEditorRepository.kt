package app.thebox.ui.editor

import app.thebox.core.editor.ChannelEditorRepository
import app.thebox.core.editor.ChannelTarget
import app.thebox.core.editor.EditorChannelRow
import app.thebox.core.editor.EditorFilter
import app.thebox.core.editor.EditorGroupRow
import app.thebox.core.editor.EditorList
import app.thebox.core.editor.EditorWrite
import app.thebox.core.editor.SelectionSummary
import app.thebox.core.editor.WriteResult
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalSession
import app.thebox.core.parental.ParentalState
import app.thebox.core.parental.RelockReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An in-memory [ChannelEditorRepository] for the editor's `:ui` tests: groups in order, channels per group in order,
 * process-local ids. Hide writes apply (and emit [changes]); every write is recorded. [rowsGate] / [summaryGate] hold
 * the page and summary reads until completed (placeholders and the menu's loading state).
 */
class FakeEditorRepository : ChannelEditorRepository {
    val groupRows = ArrayList<EditorGroupRow>()
    /** Channels per group key, in the group's order. */
    val byGroup = LinkedHashMap<String, MutableList<EditorChannelRow>>()
    var exists = true
    /** groups() throws (a storage error) while set. */
    var failGroups = false
    var rowsGate: CompletableDeferred<Unit>? = null
    var summaryGate: CompletableDeferred<Unit>? = null
    val writes = ArrayList<EditorWrite>()
    /** Thrown by the next write (once), e.g. a CancellationException from inside a transaction (review S3). */
    var throwOnNextWrite: Throwable? = null
    /** Thrown by summarize (review S4). */
    var summaryError: Throwable? = null
    var groupsCalls = 0
    var channelsCalls = 0
    val changesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    private val ids = HashMap<String, Long>()
    private val byId = HashMap<Long, EditorChannelRow>()
    private var nextId = 1L

    fun group(row: EditorGroupRow, vararg channels: EditorChannelRow) {
        groupRows += row
        byGroup[row.groupKey] = channels.toMutableList()
        channels.forEach { index(it) }
    }

    private fun index(r: EditorChannelRow) {
        val id = ids.getOrPut(r.stableKey) { nextId++ }
        byId[id] = r
    }

    private fun locked(a: ParentalAccess) = a == ParentalAccess.LOCKED_AUTO || a == ParentalAccess.LOCKED_MANUAL_ONLY

    private fun base(list: EditorList): List<EditorChannelRow> = when (list) {
        EditorList.All -> byGroup.values.flatten()
        is EditorList.Find -> byGroup.values.flatten().filter { it.name.contains(list.query, ignoreCase = true) }
        is EditorList.Group -> byGroup[list.groupKey].orEmpty()
    }

    override suspend fun playlistExists(playlistId: Long): Boolean = exists

    override suspend fun groups(playlistId: Long, showHidden: Boolean, access: ParentalAccess): ChannelEditorRepository.GroupList {
        groupsCalls++
        if (failGroups) throw IllegalStateException("storage")
        val keys = groupRows.filter { showHidden || !(it.hidden || it.ruleHidden) }.map { it.groupKey }
        return ChannelEditorRepository.GroupList(keys, groupRows.count { it.hidden || it.ruleHidden })
    }

    override suspend fun groupRows(playlistId: Long, keys: List<String>, access: ParentalAccess): List<EditorGroupRow> =
        keys.mapNotNull { k -> groupRows.firstOrNull { it.groupKey == k } }

    override suspend fun channels(playlistId: Long, list: EditorList, filter: EditorFilter, access: ParentalAccess): ChannelEditorRepository.ChannelList {
        channelsCalls++
        val group = (list as? EditorList.Group)?.let { g -> groupRows.firstOrNull { it.groupKey == g.groupKey } }
        if (group != null && group.locked && locked(access)) return ChannelEditorRepository.ChannelList(LongArray(0), 0, 0, listLocked = true)
        val all = base(list)
        val hidden = all.count { it.hidden || it.ruleHidden }
        val shown = all.filter {
            when (filter) {
                EditorFilter.ALL -> true
                EditorFilter.SHOWN -> !it.hidden && !it.ruleHidden
                EditorFilter.HIDDEN -> it.hidden || it.ruleHidden
            }
        }
        val visible = if (locked(access)) shown.filter { !it.locked && it.lockedByGroup == null } else shown
        return ChannelEditorRepository.ChannelList(visible.map { ids.getValue(it.stableKey) }.toLongArray(), hidden, shown.size - visible.size, false)
    }

    override suspend fun channelRows(ids: LongArray, from: Int, to: Int): List<EditorChannelRow?> {
        rowsGate?.await()
        return (from until to).map { byId[ids[it]] }
    }

    override suspend fun indexOf(ids: LongArray, playlistId: Long, stableKey: String): Int? {
        val id = this.ids[stableKey] ?: return null
        return ids.indexOf(id).takeIf { it >= 0 }
    }

    override suspend fun summarize(playlistId: Long, target: ChannelTarget, access: ParentalAccess): SelectionSummary {
        summaryGate?.await()
        summaryError?.let { throw it }
        val rows = when (target) {
            is ChannelTarget.Keys -> target.keys.mapNotNull { k -> this.ids[k]?.let { byId[it] } }
            is ChannelTarget.AllOf -> base(target.list).filter { it.stableKey !in target.except }
        }
        return SelectionSummary(
            total = rows.size, hidden = rows.count { it.hidden }, ruleHidden = rows.count { it.ruleHidden && !it.hidden },
            locked = rows.count { it.locked || it.lockedByGroup != null }, lockedByGroup = rows.count { it.lockedByGroup != null },
            favorite = rows.count { it.favorite }, fromLockedGroup = rows.count { it.lockedByGroup != null },
        )
    }

    override suspend fun locate(playlistId: Long, list: EditorList, filter: EditorFilter, access: ParentalAccess, number: Int): Int? =
        base(list).indexOfFirst { it.number == number }.takeIf { it >= 0 }

    override suspend fun write(write: EditorWrite, access: ParentalAccess): WriteResult {
        writes += write
        throwOnNextWrite?.let { throwOnNextWrite = null; throw it }
        var changed = 0
        if (write is EditorWrite.HideChannels && write.target is ChannelTarget.Keys) {
            val keys = (write.target as ChannelTarget.Keys).keys
            for (list in byGroup.values) for (i in list.indices) if (list[i].stableKey in keys) {
                list[i] = list[i].copy(hidden = write.hidden)
                index(list[i])
                changed++
            }
            changesFlow.tryEmit(Unit)
        }
        return WriteResult.Done(changed)
    }

    override fun changes(playlistId: Long): Flow<Unit> = changesFlow

    companion object {
        fun group(
            key: String, name: String = key, channels: Int = 0, hiddenChannels: Int = 0, hidden: Boolean = false,
            ruleHidden: Boolean = false, locked: Boolean = false, adult: Boolean = false, notProtected: Boolean = false,
        ) = EditorGroupRow(key, name, name, channels, hiddenChannels, hidden, ruleHidden, locked, adult, notProtected, renamed = false, customOrder = false)

        fun channel(
            key: String, name: String, number: Int?, hidden: Boolean = false, ruleHidden: Boolean = false, rulePattern: String? = null,
            locked: Boolean = false, lockedByGroup: String? = null, favorite: Boolean = false, movedFrom: String? = null,
            providerName: String = name,
        ) = EditorChannelRow(
            key, name, providerName, number, customNumber = null, logoUrl = null, hidden = hidden, ruleHidden = ruleHidden,
            rulePattern = rulePattern, locked = locked, lockedByGroup = lockedByGroup, favorite = favorite, movedFrom = movedFrom,
            renamed = providerName != name,
        )

        /** The E1/E2 world: four groups, the first with the mock's badges. */
        fun sample(): FakeEditorRepository = FakeEditorRepository().apply {
            group(
                group("ent", "UK | Entertainment", channels = 6, hiddenChannels = 1),
                channel("bbc1", "BBC One HD", 101),
                channel("bbc2", "BBC Two HD", 102),
                channel("itv1", "ITV 1 HD", 103, providerName = "ITV HD UK"),
                channel("ch4", "Channel 4 HD", 104, hidden = true),
                channel("sky", "Sky Mix", 105, favorite = true),
                channel("dave", "Dave", 106, movedFrom = "UK | Sport"),
            )
            group(group("sport", "UK | Sport", channels = 2), channel("sp1", "Sky Sports 1", 201), channel("sp2", "Sky Sports 2", 202))
            group(group("news", "US | News", channels = 1, hidden = true), channel("cnn", "CNN", 301))
            group(group("adult", "Adult", channels = 1, locked = true, adult = true), channel("x1", "Late", 901, lockedByGroup = "Adult"))
            group(group("vod", "XXX VOD", channels = 1, ruleHidden = true), channel("v1", "Vod 1", 950, ruleHidden = true, rulePattern = "XXX*"))
            group(group("kids", "UK | Kids"))
        }
    }
}

/** A parental session whose state the test sets. */
class FakeParentalSession(pinSet: Boolean = false, unlocked: Boolean = true) : ParentalSession {
    override val state = MutableStateFlow(ParentalState(pinSet, unlocked, ParentalPolicy()))
    override fun unlock(nowMs: Long) { state.value = state.value.copy(unlocked = true) }
    override fun relock(reason: RelockReason) { state.value = state.value.copy(unlocked = false) }
    override fun onTick(nowMs: Long) = Unit
}
