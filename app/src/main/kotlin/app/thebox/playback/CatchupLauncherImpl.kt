package app.thebox.playback

import app.thebox.core.catchup.archive.ArchiveFailure
import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.catchup.archive.ArchiveStart
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.model.TimeBase
import app.thebox.ui.playback.CatchupFailure
import app.thebox.ui.playback.CatchupLaunch
import app.thebox.ui.playback.CatchupLauncher

/** The playlist's clock model (`PlaylistRepository.timeBase`); null when the playlist no longer exists. */
fun interface TimeBaseLookup {
    suspend fun timeBase(playlistId: Long): TimeBase?
}

/**
 * The M3 launcher contract (docs/design/m3-guide.md §2.8) as a thin adapter over [ArchivePlayback]
 * (docs/design/m4-player-ux.md §1.3, §3.8.9): `play` → `playProgramme`, [ArchiveFailure] → [CatchupFailure]. Variant
 * probing, the panel clock and the request itself all live in `ArchivePlaybackImpl`. Bound in [ArchiveModule].
 */
class CatchupLauncherImpl(private val archive: ArchivePlayback) : CatchupLauncher {
    override suspend fun play(channel: GuideChannel, info: ProgrammeInfo, rawEndMin: Int): CatchupLaunch =
        when (val r = archive.playProgramme(channel, info, rawEndMin)) {
            ArchiveStart.Started -> CatchupLaunch.Started
            is ArchiveStart.Failed -> CatchupLaunch.Failed(map(r.reason))
        }

    internal companion object {
        fun map(reason: ArchiveFailure): CatchupFailure = when (reason) {
            ArchiveFailure.CHANNEL_GONE -> CatchupFailure.CHANNEL_GONE
            ArchiveFailure.NO_ARCHIVE -> CatchupFailure.NO_ARCHIVE
            ArchiveFailure.OUTSIDE_ARCHIVE -> CatchupFailure.OUTSIDE_ARCHIVE
            ArchiveFailure.UNSUPPORTED_MODE -> CatchupFailure.UNSUPPORTED_MODE
            ArchiveFailure.CREDENTIALS -> CatchupFailure.CREDENTIALS
            // Only NO_PROGRAMME of these is returned synchronously (a programme the panel's clock has not reached,
            // §3.8.1): "not in the archive" is the nearest M3 reason.
            ArchiveFailure.NO_PROGRAMME, ArchiveFailure.NOT_IN_ARCHIVE -> CatchupFailure.OUTSIDE_ARCHIVE
            ArchiveFailure.PLAYBACK -> CatchupFailure.UNSUPPORTED_MODE
        }
    }
}
