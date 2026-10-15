package app.thebox.core.full.feed

import app.thebox.core.epg.EpgStore
import app.thebox.core.full.ProgrammeFeed
import app.thebox.core.full.ProgrammeSlice
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo

/**
 * WP-C stub (docs/design/m4-player-ux.md §1.4, §3.3.3): `ProgrammeFeed` over [store] on one `box-player-epg` thread,
 * generation-safe like GuideWindowPagerImpl.
 */
class ProgrammeFeedImpl(private val store: EpgStore) : ProgrammeFeed {
    override suspend fun range(channel: GuideChannel, fromMin: Int, toMin: Int, limit: Int): List<ProgrammeInfo> = TODO("WP-C")
    override suspend fun nowFor(channels: List<GuideChannel>, atMin: Int): List<ProgrammeInfo?> = TODO("WP-C")
    override suspend fun at(channel: GuideChannel, atMin: Int): ProgrammeInfo? = TODO("WP-C")
    override suspend fun around(channel: GuideChannel, atMin: Int, fromMin: Int, before: Int, after: Int): ProgrammeSlice = TODO("WP-C")
    override fun close(): Unit = TODO("WP-C")

    companion object {
        /** `around` reads one window from `fromMin` to `atMin` + this (S4). */
        const val AROUND_AHEAD_MIN = 1_440
        /** identityHash → generation-local channel id cache size, per generation. */
        const val ID_CACHE = 1_024
    }
}
