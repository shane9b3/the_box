// Contract (M3 commit 0): docs/design/m3-guide.md §2.7. Verbatim; changes go through the lead.
package app.thebox.core.guide

data class ProgrammeHit(
    val channel: GuideChannel,
    val info: ProgrammeInfo,
    val rawEndMin: Int,
    /** Past and inside the channel's archive (catch-up glyph, OK plays catch-up). */
    val inArchive: Boolean,
)

data class SearchResults(val query: String, val channels: List<GuideChannel>, val programmes: List<ProgrammeHit>)

interface GuideSearch {
    /** Empty results for fewer than 2 letters/digits (product S9). Main-safe. Cancellable (checks between stages). */
    suspend fun search(query: String, nowMs: Long, unlocked: Boolean): SearchResults
}
