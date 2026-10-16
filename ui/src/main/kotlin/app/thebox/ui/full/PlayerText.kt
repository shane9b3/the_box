package app.thebox.ui.full

import android.content.res.Resources
import androidx.annotation.StringRes
import app.thebox.core.catchup.archive.ArchiveFailure
import app.thebox.core.full.AudioChoice
import app.thebox.core.full.ChannelMenuItem
import app.thebox.core.full.PickerChoice
import app.thebox.core.full.PickerKind
import app.thebox.core.full.PillKind
import app.thebox.core.full.PillState
import app.thebox.core.full.PlayerToast
import app.thebox.core.full.QuickBarState
import app.thebox.core.full.QuickItem
import app.thebox.core.full.StatLabel
import app.thebox.core.full.TextChoice
import app.thebox.core.guide.GuideToast
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.HdrKind
import app.thebox.core.playback.PlaybackWarning
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.player.api.PlaybackNotice
import app.thebox.ui.R
import app.thebox.ui.playback.ErrorText
import app.thebox.ui.root.toastText
import java.util.Locale

/**
 * Every player string (docs/design/m4-player-ux.md §1.5, §3.10.2, §6.3), from `strings_player.xml` /
 * `strings_errors.xml`: no hard-coded user text. Failure toasts read "reason. Next step." (CLAUDE.md). Track languages
 * use the platform's display names (`Locale.getDisplayLanguage`), which are localised by the system.
 */
object PlayerText {
    fun toast(res: Resources, kind: PlayerToast, arg: String?): String = when (kind) {
        PlayerToast.NO_CATCHUP -> res.getString(R.string.player_toast_no_catchup)
        PlayerToast.NO_PREVIOUS -> res.getString(R.string.player_toast_no_previous)
        PlayerToast.NO_FAVOURITES -> toastText(res, GuideToast.NO_FAVOURITES, null)
        PlayerToast.NO_AUDIO_CHOICE -> res.getString(R.string.player_toast_no_audio_choice)
        PlayerToast.NO_SUBTITLES -> res.getString(R.string.player_toast_no_subtitles)
        PlayerToast.ARCHIVE_NOT_READY -> res.getString(R.string.player_toast_archive_not_ready)
        PlayerToast.BACK_TO_LIVE -> res.getString(R.string.player_toast_back_to_live)
        PlayerToast.ADDED_FAVOURITE -> toastText(res, GuideToast.ADDED_FAVOURITE, arg)
        PlayerToast.REMOVED_FAVOURITE -> toastText(res, GuideToast.REMOVED_FAVOURITE, arg)
        PlayerToast.CHANNEL_HIDDEN -> toastText(res, GuideToast.CHANNEL_HIDDEN, arg)
        PlayerToast.CATCHUP_FAILED -> {
            val f = ArchiveFailure.entries.firstOrNull { it.name == arg } ?: ArchiveFailure.NO_ARCHIVE
            res.getString(R.string.toast_reason_step, res.getString(ErrorText.archiveHeadline(f)), res.getString(ErrorText.archiveStep(f)))
        }
    }

    fun statLabel(res: Resources, label: StatLabel): String = res.getString(statLabelRes(label))

    @StringRes fun statLabelRes(label: StatLabel): Int = when (label) {
        StatLabel.VIDEO -> R.string.player_stat_video
        StatLabel.RESOLUTION -> R.string.player_stat_resolution
        StatLabel.FRAME_RATE -> R.string.player_stat_frame_rate
        StatLabel.VIDEO_DECODER -> R.string.player_stat_video_decoder
        StatLabel.AUDIO -> R.string.player_stat_audio
        StatLabel.AUDIO_DECODER -> R.string.player_stat_audio_decoder
        StatLabel.BITRATE -> R.string.player_stat_bitrate
        StatLabel.BUFFER -> R.string.player_stat_buffer
        StatLabel.DROPPED -> R.string.player_stat_dropped
        StatLabel.FORMAT -> R.string.player_stat_format
        StatLabel.HTTP -> R.string.player_stat_http
        StatLabel.SERVER -> R.string.player_stat_server
        StatLabel.CONNECTIONS -> R.string.player_stat_connections
        StatLabel.BUFFER_PROFILE -> R.string.player_stat_buffer_profile
        StatLabel.DISPLAY -> R.string.player_stat_display
        StatLabel.TUNNELING -> R.string.player_stat_tunneling
    }

    /**
     * Toast text of a controller notice (§3.10.2), or null when it shows nothing. [bufferSecondsOf] maps a
     * `BufferIncreased.level` to its `afterRebufferMs` in seconds (null when unknown).
     */
    fun notice(res: Resources, notice: PlaybackNotice, bufferSecondsOf: (Int) -> String? = { null }): String? = when (notice) {
        is PlaybackNotice.Reconnecting ->
            if (notice.maxAttempts > 0) res.getString(R.string.player_notice_reconnecting, notice.attempt, notice.maxAttempts)
            else res.getString(R.string.player_notice_reconnecting_unlimited, notice.attempt)
        PlaybackNotice.Reconnected -> res.getString(R.string.player_notice_reconnected)
        is PlaybackNotice.SwitchedToBackupServer -> res.getString(R.string.player_notice_backup_server, notice.baseIndex + 1)
        is PlaybackNotice.SwitchedFormat -> when (notice.to) {
            StreamFormat.HLS -> res.getString(R.string.player_notice_format_to_hls)
            StreamFormat.TS -> res.getString(R.string.player_notice_format_to_ts)
            else -> res.getString(R.string.player_notice_format_other, notice.to.name)
        }
        is PlaybackNotice.AudioDecoderSwitched -> res.getString(
            if (notice.toSoftware) R.string.player_notice_audio_software else R.string.player_notice_audio_hardware,
            codecName(notice.mimeType),
        )
        is PlaybackNotice.PassthroughDisabled -> passthrough(res, notice.mimeType)
        PlaybackNotice.VideoDecoderSwitchedToSoftware -> res.getString(R.string.player_notice_video_software)
        PlaybackNotice.TunnelingDisabled -> res.getString(R.string.player_notice_tunneling_off)
        is PlaybackNotice.UserAgentSwitched -> res.getString(R.string.player_notice_user_agent, notice.preset)
        is PlaybackNotice.BufferIncreased -> bufferSecondsOf(notice.level)?.let { res.getString(R.string.player_notice_buffer, it) }
            ?: res.getString(R.string.player_notice_buffer_generic)
        PlaybackNotice.LiveResumed -> res.getString(R.string.player_notice_live_resumed)
        is PlaybackNotice.Warning -> when (notice.warning) {
            PlaybackWarning.NO_AUDIO, PlaybackWarning.AUDIO_UNSUPPORTED -> res.getString(R.string.player_notice_no_audio)
            PlaybackWarning.SOFTWARE_VIDEO -> res.getString(R.string.player_notice_video_software)
            PlaybackWarning.PASSTHROUGH_DISABLED -> passthrough(res, null)
        }
    }

    private fun passthrough(res: Resources, mime: String?): String =
        if (mime.isNullOrBlank()) res.getString(R.string.player_notice_passthrough_generic)
        else res.getString(R.string.player_notice_passthrough, codecName(mime))

    /** "audio/eac3" → "E-AC-3" (the names TrackController uses); unknown types print their subtype. */
    fun codecName(mime: String?): String {
        val m = mime?.lowercase(Locale.ROOT) ?: return ""
        return when {
            m.endsWith("eac3-joc") -> "E-AC-3 JOC"
            m.endsWith("eac3") -> "E-AC-3"
            m.endsWith("/ac3") -> "AC-3"
            m.endsWith("/ac4") -> "AC-4"
            m.contains("mp4a") || m.endsWith("aac") -> "AAC"
            m.endsWith("mpeg-l2") -> "MP2"
            m.endsWith("mpeg") -> "MP3"
            m.contains("dts") -> "DTS"
            m.endsWith("opus") -> "Opus"
            m.endsWith("true-hd") -> "TrueHD"
            else -> m.substringAfter('/').uppercase(Locale.ROOT)
        }
    }

    // ---- pills (§3.2.3)

    fun pill(res: Resources, pill: PillState): String = when (pill.kind) {
        PillKind.LIVE -> res.getString(R.string.player_pill_live)
        PillKind.CATCH_UP -> res.getString(R.string.player_pill_catchup)
        PillKind.PAUSED -> res.getString(R.string.player_pill_paused)
        PillKind.CONNECTING -> res.getString(R.string.player_pill_connecting)
        PillKind.RECONNECTING ->
            if (pill.reconnectMax > 0) res.getString(R.string.player_pill_reconnecting, pill.reconnectAttempt, pill.reconnectMax)
            else res.getString(R.string.player_pill_reconnecting_unlimited, pill.reconnectAttempt)
        PillKind.BEHIND -> "" // drawn from TimeChars at 1 Hz (§4.1)
    }

    // ---- tracks, aspect, decoder

    /** "English", the label, or "Track n"; null language and label fall back to the codec/kind. */
    fun language(code: String?): String? {
        if (code.isNullOrBlank() || code == "und") return null
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
        return name.takeIf { it.isNotBlank() && it != code }?.replaceFirstChar { it.titlecase(Locale.getDefault()) } ?: code
    }

    /** "English 5.1" / "Deutsch · E-AC-3 5.1" (label, language, layout). */
    fun audio(res: Resources, a: AudioChoice, index: Int = 0): String {
        val name = a.label ?: language(a.language) ?: res.getString(R.string.player_track_unknown, index + 1)
        val sb = StringBuilder(name)
        layout(a.channels)?.let { sb.append(' ').append(it) }
        if (a.describesVideo) sb.append(" · ").append(res.getString(R.string.player_track_description))
        return sb.toString()
    }

    /** Picker row of an audio track: name, codec and layout ("English · AAC 2.0"). */
    fun audioRow(res: Resources, a: AudioChoice, index: Int): String {
        val name = a.label ?: language(a.language) ?: res.getString(R.string.player_track_unknown, index + 1)
        val sb = StringBuilder(name).append(" · ").append(a.codec)
        layout(a.channels)?.let { sb.append(' ').append(it) }
        if (a.describesVideo) sb.append(" · ").append(res.getString(R.string.player_track_description))
        return sb.toString()
    }

    /** Speculative CEA tracks read "Captions (if broadcast)" (§3.6.3, E14); CEA without a language reads "CC". */
    fun text(res: Resources, t: TextChoice, index: Int = 0): String = when {
        t.speculative -> res.getString(R.string.player_track_speculative)
        t.label != null -> t.label!!
        language(t.language) != null -> language(t.language)!!
        t.kind.startsWith("CEA") -> res.getString(R.string.player_value_cc)
        else -> res.getString(R.string.player_track_unknown, index + 1)
    }

    /** 1 → "1.0", 2 → "2.0", 6 → "5.1", 8 → "7.1" (§3.2.4). */
    fun layout(channels: Int): String? = when (channels) {
        1 -> "1.0"
        2 -> "2.0"
        3 -> "2.1"
        6 -> "5.1"
        8 -> "7.1"
        else -> null
    }

    @StringRes fun aspect(mode: AspectMode): Int = when (mode) {
        AspectMode.FIT -> R.string.player_aspect_fit
        AspectMode.FILL -> R.string.player_aspect_fill
        AspectMode.ZOOM -> R.string.player_aspect_zoom
        AspectMode.RATIO_16_9 -> R.string.player_aspect_16_9
        AspectMode.RATIO_4_3 -> R.string.player_aspect_4_3
    }

    @StringRes fun decoder(mode: VideoDecoderMode?): Int = when (mode) {
        null, VideoDecoderMode.AUTO -> R.string.player_decoder_auto
        VideoDecoderMode.HARDWARE_ONLY -> R.string.player_decoder_hardware
        VideoDecoderMode.SOFTWARE_PREFERRED -> R.string.player_decoder_software
    }

    @StringRes fun hdr(kind: HdrKind): Int? = when (kind) {
        HdrKind.SDR -> null
        HdrKind.HDR10 -> R.string.player_hdr_hdr10
        HdrKind.HLG -> R.string.player_hdr_hlg
        HdrKind.DOLBY_VISION -> R.string.player_hdr_dv
        HdrKind.UNKNOWN_HDR -> R.string.player_hdr_generic
    }

    // ---- quick bar (§3.4.1)

    @StringRes fun quickLabel(item: QuickItem): Int = when (item) {
        QuickItem.AUDIO -> R.string.player_quick_audio
        QuickItem.ASPECT -> R.string.player_quick_aspect
        QuickItem.SUBTITLES -> R.string.player_quick_subtitles
        QuickItem.FAVOURITE_ADD -> R.string.player_quick_favourite_add
        QuickItem.FAVOURITE_REMOVE -> R.string.player_quick_favourite_remove
        QuickItem.START_OVER -> R.string.player_quick_start_over
        QuickItem.JUMP_TO_LIVE -> R.string.player_quick_jump_to_live
        QuickItem.TIME_SHIFT -> R.string.player_quick_time_shift
        QuickItem.PREVIOUS_CHANNEL -> R.string.player_quick_previous
        QuickItem.STREAM_INFO -> R.string.player_quick_stream_info
        QuickItem.VIDEO_DECODER -> R.string.player_quick_decoder
        QuickItem.SCREEN_OFF -> R.string.player_quick_screen_off
        QuickItem.SETTINGS -> R.string.player_quick_settings
        QuickItem.SOURCES -> R.string.player_quick_sources // M5
    }

    /** The current value shown after the label ("Audio · English 5.1"), or null. */
    fun quickValue(res: Resources, item: QuickItem, q: QuickBarState): String? = when (item) {
        QuickItem.AUDIO -> q.audio?.let { audio(res, it) }
        QuickItem.ASPECT -> res.getString(aspect(q.aspect))
        QuickItem.SUBTITLES -> q.subtitle?.let { text(res, it) } ?: res.getString(R.string.player_value_off)
        QuickItem.PREVIOUS_CHANNEL -> q.previous?.displayName
        QuickItem.STREAM_INFO -> if (q.statsOn) res.getString(R.string.player_value_on) else null
        QuickItem.VIDEO_DECODER -> res.getString(decoder(q.decoder))
        else -> null
    }

    // ---- pickers (§3.4.2)

    @StringRes fun pickerTitle(kind: PickerKind): Int = when (kind) {
        PickerKind.AUDIO -> R.string.player_picker_audio
        PickerKind.SUBTITLES -> R.string.player_picker_subtitles
        PickerKind.ASPECT -> R.string.player_picker_aspect
        PickerKind.VIDEO_DECODER -> R.string.player_picker_decoder
        PickerKind.SERVER -> R.string.player_picker_server // M5
    }

    fun choice(res: Resources, c: PickerChoice, index: Int): String = when (c) {
        is PickerChoice.Audio -> audioRow(res, c.track, index)
        is PickerChoice.Text -> text(res, c.track, index)
        PickerChoice.TextOff -> res.getString(R.string.player_value_off)
        is PickerChoice.Aspect -> res.getString(aspect(c.mode))
        is PickerChoice.Decoder -> res.getString(decoder(c.mode))
    }

    @StringRes fun menuItem(item: ChannelMenuItem): Int = when (item) {
        ChannelMenuItem.WATCH_FROM_START -> R.string.player_menu_watch_from_start
        ChannelMenuItem.ADD_FAVOURITE -> R.string.player_menu_add_favourite
        ChannelMenuItem.REMOVE_FAVOURITE -> R.string.player_menu_remove_favourite
        ChannelMenuItem.HIDE_CHANNEL -> R.string.player_menu_hide_channel
        ChannelMenuItem.LOCK_CHANNEL -> R.string.player_menu_lock_channel // M5
        ChannelMenuItem.CHANNEL_OPTIONS -> R.string.player_menu_channel_options
    }
}
