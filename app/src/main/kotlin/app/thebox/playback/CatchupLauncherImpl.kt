package app.thebox.playback

import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.player.api.ArchiveTargets
import app.thebox.player.api.PlaybackController
import app.thebox.ui.playback.CatchupLaunch
import app.thebox.ui.playback.CatchupLauncher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WP-D stub (docs/design/m3-guide.md §1.5, §3.13.6): one `ArchiveRequest` (default variant) from `ArchiveTargets`,
 * `CatchupUrlBuilder` and the playlist's `TimeBase`, then `PlaybackController.tuneArchive`.
 */
@Singleton
class CatchupLauncherImpl @Inject constructor(
    private val targets: ArchiveTargets,
    private val controller: PlaybackController,
) : CatchupLauncher {
    override suspend fun play(channel: GuideChannel, info: ProgrammeInfo, rawEndMin: Int): CatchupLaunch = TODO("WP-D")
}
