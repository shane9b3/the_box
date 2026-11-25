// Stub (Event channels commit 0): docs/design/m6-event-channels.md §5.2. WP-2 owns the body (the prefiltered read, the
// two-pass parse with the learned zone, the sorted arrays, the change-only playlist writes and the serialised builds).
package app.thebox.data.events

import app.thebox.core.events.EventIndex
import app.thebox.core.events.EventSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The in-memory event index of every enabled playlist's live channels (§5.2), bound in `GuideDataModule`. Commit 0:
 * always [EventSnapshot.EMPTY] at version 0, so the guide, search and the hub behave exactly as before.
 */
@Singleton
class EventIndexImpl @Inject constructor() : EventIndex {
    private val _version = MutableStateFlow(EventSnapshot.EMPTY.version)
    override val version: StateFlow<Long> = _version.asStateFlow()

    override suspend fun current(): EventSnapshot = EventSnapshot.EMPTY

    override suspend fun rebuild(): EventSnapshot = EventSnapshot.EMPTY
}
