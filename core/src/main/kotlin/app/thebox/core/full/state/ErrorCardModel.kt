package app.thebox.core.full.state

import app.thebox.core.catchup.archive.ArchiveFailure
import app.thebox.core.full.ErrorButton
import app.thebox.core.full.ErrorCardState
import app.thebox.core.guide.GuideChannel
import app.thebox.core.playback.NextStep
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.VideoDecoderMode

/**
 * `PlaybackError` → buttons + the first text step (docs/design/m4-player-ux.md §3.7; review S8/F5).
 *
 * Walk `error.actions` in order: a step whose M4 button applies adds it; the first step without one becomes
 * [ErrorCardState.textStep]. GUIDE is always appended; nothing is ever prepended, so RETRY appears only when the code's
 * own actions contain it. A button does not apply when:
 * - TRY_SOFTWARE_DECODER: the channel already runs SOFTWARE_PREFERRED, or `detail.codec` starts with `audio/`. The step
 *   is then dropped, not shown as text: telling the user to try what cannot help would be a loop (F5's intent);
 * - TRY_OTHER_CATCHUP_MODE: no variant is left (the step then becomes the text line, §3.7 table);
 * - PREVIOUS_CHANNEL: never filtered here, the ladder already filtered it.
 *
 * P-S2 = U-S4: every card carries a next-step line. When every step became a button (CONNECTION_LIMIT, AUDIO_OUTPUT_FAILED,
 * a ladder-narrowed SERVER_ERROR, …) the text line is the sentence of the first action other than RETRY that became a
 * button (STOP_AND_WAIT: "Stop watching on another device, then try again"); when only RETRY (and GUIDE) remain, the
 * RETRY sentence.
 */
object ErrorCardModel {
    /** [variantLeft]: ArchivePlayback has another catch-up variant; [decoder]: the channel's decoder override. */
    fun card(
        error: PlaybackError,
        channel: GuideChannel?,
        decoder: VideoDecoderMode?,
        variantLeft: Boolean,
        archiveFailed: Boolean,
    ): ErrorCardState {
        val buttons = ArrayList<ErrorButton>(4)
        var text: NextStep? = null
        for (step in error.actions) {
            val b = button(step, error, decoder, variantLeft)
            when {
                b != null -> if (b !in buttons) buttons += b
                step == NextStep.TRY_SOFTWARE_DECODER -> Unit
                text == null -> text = step
            }
        }
        // P-S2 = U-S4: fill the next-step line from the buttons when the walk left it empty.
        if (text == null) {
            text = error.actions.firstOrNull { it != NextStep.RETRY && button(it, error, decoder, variantLeft) != null } ?: NextStep.RETRY
        }
        buttons += ErrorButton.GUIDE
        return ErrorCardState(error, channel, buttons, focused = 0, textStep = text, archiveFailed = archiveFailed)
    }

    private fun button(step: NextStep, error: PlaybackError, decoder: VideoDecoderMode?, variantLeft: Boolean): ErrorButton? = when (step) {
        NextStep.RETRY -> ErrorButton.RETRY
        NextStep.PREVIOUS_CHANNEL -> ErrorButton.PREVIOUS_CHANNEL
        NextStep.WATCH_LIVE -> ErrorButton.WATCH_LIVE
        NextStep.TRY_OTHER_CATCHUP_MODE -> if (variantLeft) ErrorButton.TRY_OTHER_CATCHUP_FORMAT else null
        NextStep.TRY_SOFTWARE_DECODER ->
            if (decoder == VideoDecoderMode.SOFTWARE_PREFERRED || error.detail.codec?.startsWith("audio/") == true) null
            else ErrorButton.TRY_SOFTWARE_DECODER
        NextStep.AUDIO_OPTIONS -> ErrorButton.AUDIO_OPTIONS
        NextStep.STOP_AND_WAIT -> ErrorButton.STOP
        // M5 routes (§7 item 8): a text line, never a button to a placeholder.
        NextStep.ACCOUNT_DETAILS, NextStep.EDIT_LOGIN, NextStep.PLAYLIST_SETTINGS, NextStep.DIAGNOSTICS,
        NextStep.CHANGE_USER_AGENT, NextStep.USE_SECURE_DNS, NextStep.REFRESH_PLAYLIST, NextStep.OPEN_EXTERNAL,
        NextStep.TRY_BACKUP -> null
    }

    /**
     * The card for `ArchiveStatus.phase == FAILED` without a controller error (§3.7 last paragraph): NOT_IN_ARCHIVE →
     * CATCHUP_NOT_IN_ARCHIVE with its own steps; the build failures carry no `PlaybackErrorCode` of their own
     * (contract gap, reported): they map to the nearest code with the steps Watch live (and Guide).
     */
    fun archiveFailure(failure: ArchiveFailure?): PlaybackError = when (failure) {
        ArchiveFailure.CHANNEL_GONE -> PlaybackError(PlaybackErrorCode.CHANNEL_NOT_FOUND, actions = listOf(NextStep.WATCH_LIVE))
        ArchiveFailure.CREDENTIALS -> PlaybackError(PlaybackErrorCode.CREDENTIALS_UNAVAILABLE, actions = listOf(NextStep.WATCH_LIVE))
        ArchiveFailure.NOT_IN_ARCHIVE, null -> PlaybackError(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE)
        ArchiveFailure.NO_ARCHIVE, ArchiveFailure.OUTSIDE_ARCHIVE, ArchiveFailure.UNSUPPORTED_MODE, ArchiveFailure.NO_PROGRAMME,
        ArchiveFailure.PLAYBACK -> PlaybackError(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE, actions = listOf(NextStep.WATCH_LIVE))
    }
}
