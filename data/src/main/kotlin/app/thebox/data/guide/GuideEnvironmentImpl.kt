package app.thebox.data.guide

import app.thebox.core.guide.GuideDataWindow
import app.thebox.core.guide.GuideDataWindowSource
import app.thebox.core.guide.SyncFeed
import app.thebox.core.guide.SyncFeedState
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** WP-B stub (docs/design/m3-guide.md §1.3, §2.4a, review B7): [SyncFeed] over `SyncStatusRepository`. */
@Singleton
class SyncFeedImpl @Inject constructor() : SyncFeed {
    override val state: StateFlow<SyncFeedState> get() = TODO("WP-B")
}

/** WP-B stub (§2.4a, B7): [GuideDataWindowSource] over `DataSettings` (epgPastDays / epgFutureDays). */
@Singleton
class GuideDataWindowSourceImpl @Inject constructor() : GuideDataWindowSource {
    override val window: StateFlow<GuideDataWindow> get() = TODO("WP-B")
}
