package app.thebox.data.reminder

import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.PendingReminder
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.ReminderBook
import app.thebox.core.guide.ReminderIndex
import app.thebox.core.refresh.ChannelIdentity
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** WP-B stub (docs/design/m3-guide.md §1.3, §3.11.1): `ReminderBook` over `ReminderDao`. */
@Singleton
class ReminderBookImpl @Inject constructor() : ReminderBook {
    override val index: StateFlow<ReminderIndex> get() = TODO("WP-B")
    override val pending: StateFlow<List<PendingReminder>> get() = TODO("WP-B")
    override suspend fun set(channel: GuideChannel, info: ProgrammeInfo, leadSec: Int): Boolean = TODO("WP-B")
    override suspend fun cancel(identity: ChannelIdentity, rawStartMin: Int): Unit = TODO("WP-B")
    override suspend fun markFired(id: Long): Unit = TODO("WP-B")
    override suspend fun dismiss(id: Long): Unit = TODO("WP-B")
    override suspend fun markOrphaned(id: Long): Unit = TODO("WP-B")
}
