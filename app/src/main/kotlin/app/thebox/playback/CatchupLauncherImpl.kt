package app.thebox.playback

import app.thebox.core.catchup.ArchiveWindow
import app.thebox.core.catchup.CatchupBuild
import app.thebox.core.catchup.CatchupUrlBuilder
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.model.TimeBase
import app.thebox.player.api.ArchiveTargets
import app.thebox.player.api.ArchiveTune
import app.thebox.player.api.CatchupTargetResult
import app.thebox.player.api.PlaybackController
import app.thebox.ui.playback.CatchupFailure
import app.thebox.ui.playback.CatchupLaunch
import app.thebox.ui.playback.CatchupLauncher
import java.time.Instant

/** The playlist's clock model (`PlaylistRepository.timeBase`); null when the playlist no longer exists. */
fun interface TimeBaseLookup {
    suspend fun timeBase(playlistId: Long): TimeBase?
}

/**
 * docs/design/m3-guide.md §3.13.6 (WP-D): one `ArchiveRequest` (default variant) from [ArchiveTargets],
 * [CatchupUrlBuilder] and the playlist's [TimeBase], then [PlaybackController.tuneArchive]. The viewport is the root's
 * (it follows the displayed mode; §2.13 item 11).
 * Panel times are never computed here: the builder applies the panel zone, correction and skew (CLAUDE.md timeshift
 * rule). Window times are the raw (unshifted) programme times. Bound in [app.thebox.reminder.ReminderModule].
 */
class CatchupLauncherImpl(
    private val targets: ArchiveTargets,
    private val controller: PlaybackController,
    private val builder: CatchupUrlBuilder,
    private val timeBases: TimeBaseLookup,
    private val clock: () -> Long,
) : CatchupLauncher {
    override suspend fun play(channel: GuideChannel, info: ProgrammeInfo, rawEndMin: Int): CatchupLaunch {
        val identity = channel.identity
        val target = when (val r = targets.catchupTarget(identity)) {
            CatchupTargetResult.ChannelGone -> return failed(CatchupFailure.CHANNEL_GONE)
            CatchupTargetResult.CredentialsUnavailable -> return failed(CatchupFailure.CREDENTIALS)
            is CatchupTargetResult.Found -> r.target
        }
        val now = clock()
        val catchup = target.catchup
        if (!catchup.isAvailable) return failed(CatchupFailure.NO_ARCHIVE)
        val nowMin = Math.floorDiv(now, MINUTE_MS)
        if (info.rawStartMin.toLong() < nowMin - catchup.days.toLong() * 1440L) return failed(CatchupFailure.OUTSIDE_ARCHIVE)
        // The playlist vanished between the target lookup and here: the channel went with it.
        val timeBase = timeBases.timeBase(identity.playlistId) ?: return failed(CatchupFailure.CHANNEL_GONE)
        val rawEndMs = rawEndMin.toLong() * MINUTE_MS
        val window = ArchiveWindow(
            programmeStart = Instant.ofEpochMilli(info.rawStartMin.toLong() * MINUTE_MS),
            programmeStop = if (rawEndMs <= now) Instant.ofEpochMilli(rawEndMs) else null,
            deviceNow = Instant.ofEpochMilli(now),
        )
        return when (val built = builder.build(target, timeBase, window)) {
            is CatchupBuild.Ok -> {
                controller.tuneArchive(ArchiveTune(identity, built.request))
                // No setViewport here (§2.13 item 11): the machine goes FULL on the first archive Tuned and the root
                // sets the viewport from the displayed mode, so a launch that fails later never leaves FULL behind.
                CatchupLaunch.Started
            }
            is CatchupBuild.Unsupported -> failed(CatchupFailure.UNSUPPORTED_MODE)
        }
    }

    private fun failed(reason: CatchupFailure) = CatchupLaunch.Failed(reason)

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
