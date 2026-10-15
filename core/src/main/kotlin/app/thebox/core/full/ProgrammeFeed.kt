// Contract (M4 commit 0): docs/design/m4-player-ux.md §2.4.
package app.thebox.core.full

import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo

/**
 * Programme lookups for the player (info bar, channel list, archive timeline). One `box-player-epg` thread and one
 * EpgReader, generation-safe like GuideWindowPagerImpl (§3.3.3). Main-safe; every call is cancellable. Minutes are
 * **display** minutes (raw + channel shift), as in GuideWindowPager.at.
 */
interface ProgrammeFeed : AutoCloseable {
    /** Programmes of [channel] overlapping [fromMin, toMin) in start order, at most [limit]; CONTINUATION rows merged. */
    suspend fun range(channel: GuideChannel, fromMin: Int, toMin: Int, limit: Int = PROGRAMME_RANGE_LIMIT): List<ProgrammeInfo>
    /** The programme airing at [atMin] on each of [channels] (null = no EPG or a gap), in the same order. */
    suspend fun nowFor(channels: List<GuideChannel>, atMin: Int): List<ProgrammeInfo?>
    suspend fun at(channel: GuideChannel, atMin: Int): ProgrammeInfo?
    /** (reviewed 2026-10-15, S4/F4) Up to [before] programmes ending after [fromMin] and starting before [atMin], then the one airing at [atMin] and up to [after] later ones (display minutes); [nowIndex] indexes the airing one (−1 = gap). */
    suspend fun around(channel: GuideChannel, atMin: Int, fromMin: Int, before: Int = 12, after: Int = 12): ProgrammeSlice

    companion object {
        const val PROGRAMME_RANGE_LIMIT = 24
    }
}

/** (reviewed 2026-10-15, S4/F4) */
data class ProgrammeSlice(val programmes: List<ProgrammeInfo>, val nowIndex: Int)
