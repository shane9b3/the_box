// Contract (M3 commit 0): docs/design/m3-guide.md §2.8. Verbatim; changes go through the lead.
package app.thebox.ui.playback

sealed interface CatchupLaunch {
    data object Started : CatchupLaunch
    /** Reason + next step (CLAUDE.md error rule): shown as a toast/card by the caller. */
    data class Failed(val reason: CatchupFailure) : CatchupLaunch
}

enum class CatchupFailure { CHANNEL_GONE, NO_ARCHIVE, OUTSIDE_ARCHIVE, UNSUPPORTED_MODE, CREDENTIALS }

interface CatchupLauncher {
    /**
     * Builds one ArchiveRequest (default variant) and calls PlaybackController.tuneArchive. (amended 2026-10-14,
     * §2.13 item 11) It does not set the viewport: the state machine goes FULL on the first archive Tuned and the root
     * sets the viewport from the displayed mode.
     */
    suspend fun play(channel: app.thebox.core.guide.GuideChannel, info: app.thebox.core.guide.ProgrammeInfo, rawEndMin: Int): CatchupLaunch
}
