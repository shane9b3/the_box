package app.thebox.data.reminder

import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.PendingReminder
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.ReminderBook
import app.thebox.core.guide.ReminderIndex
import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.data.db.entity.ReminderEntity
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.IoDispatcher
import app.thebox.data.repo.ReminderRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import app.thebox.data.di.StartupGate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ReminderBook] over `reminder` (docs/design/m3-guide.md §3.11.1; review S1, N5). Keys stay raw
 * `(playlist_id, stable_key, start_min)`, so a reminder survives every EPG rebuild and shows its stored title;
 * `shift_min` records the guide shift at set time so the planner fires at the displayed start. Main-safe.
 */
@Singleton
class ReminderBookImpl @Inject constructor(
    private val repo: ReminderRepository,
    @ApplicationScope scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
    /** (code review 2026-10-14, O-7) The Room query starts once the first frame has drawn. */
    gate: StartupGate = StartupGate.opened(),
) : ReminderBook {
    override val pending: StateFlow<List<PendingReminder>> = flow { gate.awaitOpen(); emitAll(repo.allPending()) }
        .map { rows -> rows.map(::toPending) }
        .flowOn(io)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val index: StateFlow<ReminderIndex> = pending
        .map { list -> ReminderIndex.of(list.map { GuideChannel.hashOf(it.identity) to it.rawStartMin }) }
        .stateIn(scope, SharingStarted.Eagerly, ReminderIndex.EMPTY)

    override suspend fun set(channel: GuideChannel, info: ProgrammeInfo, leadSec: Int): Boolean = withContext(io) {
        // N5: a fired/dismissed row for the key is replaced (the identity index ignores state); a pending one makes
        // this a duplicate and add() returns false. The planner only reads pending rows, so the gap is invisible.
        repo.clearFinished(channel.identity, info.rawStartMin)
        repo.add(
            channel.identity,
            rawStartMin = info.rawStartMin,
            rawEndMin = info.rawStartMin + (info.endMin - info.startMin),
            title = info.title,
            channelName = channel.displayName,
            leadSec = leadSec,
            shiftMin = info.startMin - info.rawStartMin,
        )
    }

    override suspend fun cancel(identity: ChannelIdentity, rawStartMin: Int) = withContext(io) { repo.remove(identity, rawStartMin) }

    override suspend fun markFired(id: Long) = finish(id, ReminderEntity.STATE_FIRED)
    override suspend fun dismiss(id: Long) = finish(id, ReminderEntity.STATE_DISMISSED)
    override suspend fun markOrphaned(id: Long) = finish(id, ReminderEntity.STATE_ORPHANED)

    private suspend fun finish(id: Long, state: Int) {
        withContext(io) { repo.finishIfPending(id, state) }
    }

    companion object {
        fun toPending(e: ReminderEntity) = PendingReminder(
            id = e.id,
            identity = ChannelIdentity(e.playlistId, StableKey(e.stableKey)),
            rawStartMin = e.startMin,
            rawEndMin = e.endMin,
            shiftMin = e.shiftMin,
            title = e.title,
            channelName = e.channelName,
            leadSec = e.leadSec,
        )
    }
}
