package app.thebox.data.guide

import app.thebox.core.guide.GroupAction
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideLibrary
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.refresh.ChannelIdentity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** WP-B stub (docs/design/m3-guide.md §1.3, §3.8): sources, lineups, blocks, number lookup, favourites/hide/group actions. */
@Singleton
class GuideLibraryImpl @Inject constructor() : GuideLibrary {
    override fun sources(unlocked: Boolean): Flow<List<GuideSourceEntry>> = TODO("WP-B")
    override fun lineup(source: GuideSource, unlocked: Boolean): Flow<GuideLineup> = TODO("WP-B")
    override suspend fun channels(rowIds: LongArray, from: Int, to: Int): List<GuideChannel?> = TODO("WP-B")
    override suspend fun rowIdOf(identity: ChannelIdentity): Long? = TODO("WP-B")
    override suspend fun byNumber(playlistId: Long?, number: Int, unlocked: Boolean): GuideChannel? = TODO("WP-B")
    override suspend fun isFavourite(identity: ChannelIdentity): Boolean = TODO("WP-B")
    override suspend fun setFavourite(identity: ChannelIdentity, on: Boolean): Unit = TODO("WP-B")
    override suspend fun hideChannel(identity: ChannelIdentity): Unit = TODO("WP-B")
    override suspend fun groupAction(entry: GuideSourceEntry, action: GroupAction): Unit = TODO("WP-B")
    override suspend fun lastWatched(): GuideChannel? = TODO("WP-B")
    override val rowContent: Flow<Long> get() = TODO("WP-B")
}
