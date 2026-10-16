package app.thebox.core.full.state

import app.thebox.core.catchup.archive.ArchiveFailure
import app.thebox.core.full.ErrorButton
import app.thebox.core.full.ErrorCardState
import app.thebox.core.guide.GuideChannel
import app.thebox.core.playback.NextStep
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SettingsRoute

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
 *
 * (M5, docs/design/m5-setup-settings.md §3.14) The M4 text steps become buttons: ACCOUNT_DETAILS, EDIT_LOGIN,
 * PLAYLIST_SETTINGS, DIAGNOSTICS and CHANGE_USER_AGENT open Settings on the step's route ([ErrorButton.OPEN_SETTINGS],
 * [ErrorCardState.route]; opened detached, S2); USE_SECURE_DNS, REFRESH_PLAYLIST and OPEN_EXTERNAL are their own buttons
 * (run by `ErrorRouteActions`). The card carries one route, so only the first route step becomes a button; a second one
 * is the text line. Without a channel (no playlist to open) the route steps stay text, and so does REFRESH_PLAYLIST.
 * TRY_BACKUP stays filtered (M4).
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
        var route: SettingsRoute? = null
        val playlistId = channel?.identity?.playlistId
        // (M5 integration, I-M5-21) The card carries one route: "Edit login" wins over "Account details" (the login is
        // what the user can fix; the account card stays the text line).
        val steps = if (NextStep.EDIT_LOGIN in error.actions && NextStep.ACCOUNT_DETAILS in error.actions) {
            error.actions.sortedBy { if (it == NextStep.EDIT_LOGIN) 0 else 1 }
        } else error.actions
        for (step in steps) {
            val b = button(step, error, decoder, variantLeft, playlistId)
            when {
                b == ErrorButton.OPEN_SETTINGS -> if (route == null) {
                    route = routeOf(step, error.code, playlistId!!)
                    buttons += b
                } else if (text == null) text = step
                b != null -> if (b !in buttons) buttons += b
                step == NextStep.TRY_SOFTWARE_DECODER -> Unit
                text == null -> text = step
            }
        }
        // P-S2 = U-S4: fill the next-step line from the buttons when the walk left it empty.
        if (text == null) {
            text = error.actions.firstOrNull { it != NextStep.RETRY && button(it, error, decoder, variantLeft, playlistId) != null } ?: NextStep.RETRY
        }
        // (M5 integration, §3.14, I-M5-21) A card whose Settings route edits the playlist (the login, or a focused
        // playlist row) also offers Retry after that button: BACK from the detached Settings layer returns to this card,
        // and Retry applies the fix. Account-only routes (Account details) keep M4's "no Retry that cannot help" (F5).
        val fixable = route is SettingsRoute.PlaylistSource || (route as? SettingsRoute.Playlist)?.focus != null
        if (fixable && ErrorButton.OPEN_SETTINGS in buttons && ErrorButton.RETRY !in buttons) buttons.add(buttons.indexOf(ErrorButton.OPEN_SETTINGS) + 1, ErrorButton.RETRY)
        buttons += ErrorButton.GUIDE
        return ErrorCardState(error, channel, buttons, focused = 0, textStep = text, archiveFailed = archiveFailed, route = route)
    }

    /** (M5 §3.14) The Settings route of a route step. */
    fun routeOf(step: NextStep, code: PlaybackErrorCode, playlistId: Long): SettingsRoute? = when (step) {
        NextStep.ACCOUNT_DETAILS -> SettingsRoute.Playlist(playlistId)
        NextStep.EDIT_LOGIN -> SettingsRoute.PlaylistSource(playlistId)
        NextStep.PLAYLIST_SETTINGS -> SettingsRoute.Playlist(
            playlistId,
            when (code) {
                PlaybackErrorCode.FORMAT_REFUSED -> ROW_STREAM_FORMAT
                PlaybackErrorCode.TLS_FAILURE -> ROW_IGNORE_TLS
                else -> null
            },
        )
        NextStep.DIAGNOSTICS -> SettingsRoute.NetworkTest(playlistId)
        NextStep.CHANGE_USER_AGENT -> SettingsRoute.Playlist(playlistId, ROW_USER_AGENT)
        else -> null
    }

    /** Row ids of WP-B's playlist page the routes focus (§3.3.1). */
    val ROW_STREAM_FORMAT = RowId("playlist.stream_format")
    val ROW_IGNORE_TLS = RowId("playlist.ignore_tls")
    val ROW_USER_AGENT = RowId("playlist.user_agent")

    private val ROUTE_STEPS = setOf(NextStep.ACCOUNT_DETAILS, NextStep.EDIT_LOGIN, NextStep.PLAYLIST_SETTINGS, NextStep.DIAGNOSTICS, NextStep.CHANGE_USER_AGENT)

    private fun button(step: NextStep, error: PlaybackError, decoder: VideoDecoderMode?, variantLeft: Boolean, playlistId: Long?): ErrorButton? = when (step) {
        NextStep.RETRY -> ErrorButton.RETRY
        NextStep.PREVIOUS_CHANNEL -> ErrorButton.PREVIOUS_CHANNEL
        NextStep.WATCH_LIVE -> ErrorButton.WATCH_LIVE
        NextStep.TRY_OTHER_CATCHUP_MODE -> if (variantLeft) ErrorButton.TRY_OTHER_CATCHUP_FORMAT else null
        NextStep.TRY_SOFTWARE_DECODER ->
            if (decoder == VideoDecoderMode.SOFTWARE_PREFERRED || error.detail.codec?.startsWith("audio/") == true) null
            else ErrorButton.TRY_SOFTWARE_DECODER
        NextStep.AUDIO_OPTIONS -> ErrorButton.AUDIO_OPTIONS
        NextStep.STOP_AND_WAIT -> ErrorButton.STOP
        // M5 routes (§3.14): Settings on the step's route; without a playlist they stay a text line.
        in ROUTE_STEPS -> if (playlistId != null) ErrorButton.OPEN_SETTINGS else null
        NextStep.USE_SECURE_DNS -> ErrorButton.USE_SECURE_DNS
        NextStep.REFRESH_PLAYLIST -> if (playlistId != null) ErrorButton.REFRESH_PLAYLIST else null
        NextStep.OPEN_EXTERNAL -> ErrorButton.OPEN_EXTERNAL
        NextStep.BACK_TO_AUTO -> ErrorButton.BACK_TO_AUTO
        NextStep.TRY_BACKUP -> null
        else -> null
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
