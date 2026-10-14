package app.thebox.ui.playback

import androidx.annotation.StringRes
import app.thebox.core.playback.NextStep
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.ui.R

/**
 * Headline (the reason) and next-step line for every [PlaybackErrorCode] and [CatchupFailure]
 * (docs/design/m3-guide.md §1.4, §3.13.4, §3.13.6; CLAUDE.md "errors carry a reason and a next step").
 * The step is the error's first [NextStep]; codes whose first step is RETRY read "Press OK to retry".
 */
object ErrorText {
    @StringRes fun headline(code: PlaybackErrorCode): Int = when (code) {
        PlaybackErrorCode.AUTH_REJECTED -> R.string.error_auth_rejected
        PlaybackErrorCode.ACCOUNT_EXPIRED -> R.string.error_account_expired
        PlaybackErrorCode.ACCOUNT_DISABLED -> R.string.error_account_disabled
        PlaybackErrorCode.NEEDS_REAUTH -> R.string.error_needs_reauth
        PlaybackErrorCode.CREDENTIALS_UNAVAILABLE -> R.string.error_credentials_unavailable
        PlaybackErrorCode.CONNECTION_LIMIT -> R.string.error_connection_limit
        PlaybackErrorCode.CONNECTION_LIMIT_LOCAL -> R.string.error_connection_limit_local
        PlaybackErrorCode.FORBIDDEN -> R.string.error_forbidden
        PlaybackErrorCode.CHANNEL_OFFLINE -> R.string.error_channel_offline
        PlaybackErrorCode.FORMAT_REFUSED -> R.string.error_format_refused
        PlaybackErrorCode.WEB_PAGE_INSTEAD -> R.string.error_web_page
        PlaybackErrorCode.EMPTY_RESPONSE -> R.string.error_empty_response
        PlaybackErrorCode.NO_DATA_TIMEOUT -> R.string.error_no_data
        PlaybackErrorCode.STREAM_INTERRUPTED -> R.string.error_interrupted
        PlaybackErrorCode.SERVER_ERROR -> R.string.error_server_error
        PlaybackErrorCode.DNS_FAILURE -> R.string.error_dns
        PlaybackErrorCode.CONNECT_FAILED -> R.string.error_connect
        PlaybackErrorCode.TLS_FAILURE -> R.string.error_tls
        PlaybackErrorCode.CODEC_UNSUPPORTED -> R.string.error_codec_unsupported
        PlaybackErrorCode.STREAM_UNDECODABLE -> R.string.error_undecodable
        PlaybackErrorCode.AUDIO_OUTPUT_FAILED -> R.string.error_audio_output
        PlaybackErrorCode.UNSUPPORTED_SOURCE -> R.string.error_unsupported_source
        PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE -> R.string.error_catchup_missing
        PlaybackErrorCode.CHANNEL_NOT_FOUND -> R.string.error_channel_missing
        PlaybackErrorCode.INTERNAL -> R.string.error_internal
    }

    /** The next step for the code's default steps. */
    @StringRes fun step(code: PlaybackErrorCode): Int = step(code.defaultSteps.firstOrNull() ?: NextStep.RETRY)

    /** The next step for a concrete error: its own first action (the ladder may narrow the defaults). */
    @StringRes fun step(error: PlaybackError): Int =
        step(error.actions.firstOrNull() ?: error.code.defaultSteps.firstOrNull() ?: NextStep.RETRY)

    @StringRes fun step(step: NextStep): Int = when (step) {
        NextStep.RETRY -> R.string.error_step_retry
        NextStep.ACCOUNT_DETAILS -> R.string.error_step_account_details
        NextStep.EDIT_LOGIN -> R.string.error_step_edit_login
        NextStep.STOP_AND_WAIT -> R.string.error_step_stop_and_wait
        NextStep.TRY_BACKUP -> R.string.error_step_try_backup
        NextStep.PREVIOUS_CHANNEL -> R.string.error_step_previous_channel
        NextStep.CHANGE_USER_AGENT -> R.string.error_step_change_user_agent
        NextStep.PLAYLIST_SETTINGS -> R.string.error_step_playlist_settings
        NextStep.USE_SECURE_DNS -> R.string.error_step_secure_dns
        NextStep.DIAGNOSTICS -> R.string.error_step_diagnostics
        NextStep.TRY_SOFTWARE_DECODER -> R.string.error_step_software_decoder
        NextStep.OPEN_EXTERNAL -> R.string.error_step_open_external
        NextStep.AUDIO_OPTIONS -> R.string.error_step_audio_options
        NextStep.WATCH_LIVE -> R.string.error_step_watch_live
        NextStep.TRY_OTHER_CATCHUP_MODE -> R.string.error_step_other_catchup_mode
        NextStep.REFRESH_PLAYLIST -> R.string.error_step_refresh_playlist
    }

    @StringRes fun catchupHeadline(failure: CatchupFailure): Int = when (failure) {
        CatchupFailure.CHANNEL_GONE -> R.string.catchup_channel_gone
        CatchupFailure.NO_ARCHIVE -> R.string.catchup_no_archive
        CatchupFailure.OUTSIDE_ARCHIVE -> R.string.catchup_outside_archive
        CatchupFailure.UNSUPPORTED_MODE -> R.string.catchup_unsupported_mode
        CatchupFailure.CREDENTIALS -> R.string.catchup_credentials
    }

    @StringRes fun catchupStep(failure: CatchupFailure): Int = when (failure) {
        CatchupFailure.CHANNEL_GONE -> R.string.catchup_channel_gone_step
        CatchupFailure.NO_ARCHIVE -> R.string.catchup_no_archive_step
        CatchupFailure.OUTSIDE_ARCHIVE -> R.string.catchup_outside_archive_step
        CatchupFailure.UNSUPPORTED_MODE -> R.string.catchup_unsupported_mode_step
        CatchupFailure.CREDENTIALS -> R.string.catchup_credentials_step
    }
}
