package app.thebox.core.guide.feed

import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideLibrary
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideRowsCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** WP-B stub (docs/design/m3-guide.md §1.3, §3.2.1): 32-row blocks, LRU of 8. */
class GuideRowsCacheImpl(private val library: GuideLibrary, private val scope: CoroutineScope) : GuideRowsCache {
    override val size: Int get() = TODO("WP-B")
    override val version: Long get() = TODO("WP-B")
    override fun channel(index: Int): GuideChannel? = TODO("WP-B")
    override fun indexOf(rowId: Long): Int = TODO("WP-B")
    override fun setLineup(lineup: GuideLineup): Unit = TODO("WP-B")
    override fun ensure(from: Int, to: Int): Unit = TODO("WP-B")
    override val changes: StateFlow<Long> get() = TODO("WP-B")
    override suspend fun channelAt(index: Int): GuideChannel? = TODO("WP-B")
    override fun reloadLoaded(): Unit = TODO("WP-B")
}
