package app.thebox.playback

import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.catchup.archive.ArchiveStart
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * WP-B stub (docs/design/m4-player-ux.md §1.3, §3.8.2): the catch-up orchestrator. Owns the session, executes the
 * planner's plans against the controller, polls `position()` at [ARCHIVE_POLL_MS] while active and publishes [status]
 * (incl. `ownedTuneId` and `settledSeq`, B1/B3). WP-B gives it its constructor dependencies in `ArchiveModule`.
 */
class ArchivePlaybackImpl : ArchivePlayback {
    override val status: StateFlow<ArchiveStatus> get() = TODO("WP-B")
    override suspend fun playProgramme(channel: GuideChannel, info: ProgrammeInfo, rawEndMin: Int, timeShift: Boolean): ArchiveStart = TODO("WP-B")
    override suspend fun startOver(channel: GuideChannel): ArchiveStart = TODO("WP-B")
    override suspend fun rewindLive(channel: GuideChannel, backMs: Long): ArchiveStart = TODO("WP-B")
    override fun pauseLive(channel: GuideChannel): Unit = TODO("WP-B")
    override fun pause(): Unit = TODO("WP-B")
    override fun resume(): Unit = TODO("WP-B")
    override fun seekTo(programmeMs: Long, seq: Long): Unit = TODO("WP-B")
    override fun jumpToLive(): Unit = TODO("WP-B")
    override fun tryNextVariant(): Boolean = TODO("WP-B")

    companion object {
        const val ARCHIVE_POLL_MS = 1_000L
        const val ARCHIVE_MIDPLAY_RETRIES = 1
        /** Seek settle bounds (B3): native seeks settle no sooner than MIN and no later than MAX. */
        const val SEEK_SETTLE_MIN_MS = 250L
        const val SEEK_SETTLE_MAX_MS = 5_000L
        /** A native seek has settled once the position advanced this far; polled at [SEEK_SETTLE_POLL_MS] while SEEKING. */
        const val SEEK_SETTLE_PROGRESS_MS = 200L
        const val SEEK_SETTLE_POLL_MS = 250L
        /** Mid-play retry budget resets after this long of clean playback (S3). */
        const val MIDPLAY_RESET_MS = 300_000L
        /** An archive pause longer than this rebuilds the request on resume (§3.8.7). */
        const val ARCHIVE_PAUSE_REBUILD_MS = 600_000L
    }
}
