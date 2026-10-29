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
import app.thebox.core.playback.timeshift.RecordStop
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
        PlayerToast.CHANNEL_LOCKED -> toastText(res, GuideToast.CHANNEL_LOCKED, arg) // U-S10
        PlayerToast.LOCKED_NOT_PROTECTED -> toastText(res, GuideToast.LOCKED_NOT_PROTECTED, arg) // U-S10
        PlayerToast.CATCHUP_FAILED -> {
            val f = ArchiveFailure.entries.firstOrNull { it.name == arg } ?: ArchiveFailure.NO_ARCHIVE
            res.getString(R.string.toast_reason_step, res.getString(ErrorText.archiveHeadline(f)), res.getString(ErrorText.archiveStep(f)))
        }
        // M6 time-shift commit 0 (docs/design/m6-timeshift.md §13). WP-C owns these bodies and the args the effects pass.
        /** arg = the playlist name (Xtream: "TS can fix it" path); null = the M3U variant. */
        PlayerToast.PAUSE_NO_REWIND_HLS, PlayerToast.PAUSE_NO_REWIND_OFF, PlayerToast.PAUSE_NO_REWIND_SPACE,
        PlayerToast.PAUSE_NO_REWIND_FORMAT, PlayerToast.PAUSE_NO_REWIND_STORAGE ->
            res.getString(R.string.player_toast_two_parts, noRewindReason(res, kind, arg), noRewindStep(res, kind, arg)!!)
        /** arg = the next step of the unbuffered pause's reason (once per tune, fix pass S6); null = the reason alone. */
        PlayerToast.BACK_TO_LIVE_UNBUFFERED -> res.getString(R.string.player_toast_back_to_live_unbuffered).let { if (arg.isNullOrEmpty()) it else res.getString(R.string.player_toast_two_parts, it, arg) }
        PlayerToast.REWIND_NOTHING_YET -> res.getString(R.string.player_toast_rewind_nothing_yet, args(arg, 0)) // "30 min"
        // (Fix pass S8) "21:02", then the window ("30 min") when 21:02 is not when the viewer tuned in.
        PlayerToast.REWIND_START -> if (args(arg, 1).isEmpty()) res.getString(R.string.player_toast_rewind_start, args(arg, 0))
            else res.getString(R.string.player_toast_rewind_start_window, args(arg, 0), args(arg, 1))
        PlayerToast.GAP_SKIPPED_PAUSED -> res.getString(R.string.player_toast_gap_skipped_paused, args(arg, 0), args(arg, 1)) // "12 min", "21:26"
        PlayerToast.GAP_SKIPPED_STREAM -> res.getString(R.string.player_toast_gap_skipped_stream, args(arg, 0), args(arg, 1)) // "40 s", "21:26"
        PlayerToast.REWIND_STOPPED_STORAGE -> storageStopped(res, arg) ?: res.getString(R.string.player_toast_rewind_stopped_storage)
        PlayerToast.REWIND_STOPPED_SLOW -> storageSlow(res, arg) ?: res.getString(R.string.player_toast_rewind_stopped_slow)
        PlayerToast.REWIND_LIMITED_TODAY -> res.getString(R.string.player_toast_rewind_limited_today)
        PlayerToast.LEFT_APP_CLEARED -> res.getString(R.string.player_toast_left_app_cleared)
        PlayerToast.ZAP_CONFIRM -> res.getString(R.string.player_toast_zap_confirm, args(arg, 0)) // "20 min"
        PlayerToast.ZAP_CONFIRM_NUMBER -> res.getString(R.string.player_toast_zap_confirm_number, args(arg, 0))
        PlayerToast.SAVING_STOPPED_CONNECTION -> res.getString(R.string.player_toast_saving_stopped_connection)
        PlayerToast.JUMPED_TO_LIVE_RESTART -> res.getString(R.string.player_toast_jumped_to_live_restart)
        PlayerToast.PAUSE_TIP -> res.getString(R.string.player_toast_pause_tip)
        // (Storage locations §10, WP-S4) The texts are strings_storage.xml's; [arg] is "<StorageKind>␟<second>␟<name>␟<jumped 1|>".
        PlayerToast.SAVE_LOCATION_FALLBACK -> storageFallback(res, arg)
        PlayerToast.PAUSE_RESUME_LOST_STORAGE -> storageArg(arg)?.let { a -> res.getString(R.string.storage_toast_resume_lost, capital(place(res, a))) }
            ?: res.getString(R.string.player_toast_rewind_stopped_storage)
    }

    // ---- (Storage locations §10, WP-S4) the storage toasts of a USB drive or a network folder

    /** `"<StorageKind>␟<second>␟<name>␟<jumped 1|>"`; null for a null arg, a blank one, or this device (today's text). */
    fun storageArg(arg: String?): app.thebox.core.full.StorageToastArg? =
        app.thebox.core.full.StorageToastArg.decode(arg)?.takeIf { it.kind != app.thebox.core.storage.StorageKind.INTERNAL }

    /** "NAS" (middle-ellipsised to `LABEL_MAX_CHARS`), or "the USB drive", or "the network folder" when unnamed. */
    fun place(res: Resources, a: app.thebox.core.full.StorageToastArg): String = when {
        a.kind == app.thebox.core.storage.StorageKind.VOLUME -> res.getString(R.string.storage_place_usb_drive)
        a.name.isBlank() -> res.getString(R.string.storage_place_network_folder)
        else -> ellipsize(a.name, app.thebox.core.storage.StorageConstants.LABEL_MAX_CHARS)
    }

    private fun capital(s: String): String = s.replaceFirstChar { it.uppercase() }

    /** Middle ellipsis of a long location name. */
    fun ellipsize(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max / 2 - 1) + "\u2026" + s.takeLast(max - max / 2)

    private fun storageFallback(res: Resources, arg: String?): String {
        val a = storageArg(arg) ?: return res.getString(R.string.player_toast_rewind_stopped_storage)
        val usb = a.kind == app.thebox.core.storage.StorageKind.VOLUME
        val p = place(res, a)
        return when (app.thebox.core.storage.LocationFallback.entries.firstOrNull { it.name == a.second }) {
            app.thebox.core.storage.LocationFallback.SIGN_IN_NEEDED -> res.getString(R.string.storage_toast_fb_sign_in, p)
            app.thebox.core.storage.LocationFallback.UNSTABLE -> res.getString(R.string.storage_toast_fb_unstable, p)
            app.thebox.core.storage.LocationFallback.NOT_CONNECTED -> res.getString(R.string.storage_toast_fb_not_connected)
            app.thebox.core.storage.LocationFallback.NO_SPACE_THERE ->
                res.getString(if (usb) R.string.storage_toast_fb_no_space_usb else R.string.storage_toast_fb_no_space, p)
            // UNREACHABLE; NOT_READY_YET is never toasted (§5.1) and reads as the unreachable text if it ever is.
            else -> res.getString(R.string.storage_toast_fb_unreachable, p)
        }
    }

    /** REWIND_STOPPED_STORAGE of a remote/USB cycle (§10); null = this device's text. */
    private fun storageStopped(res: Resources, arg: String?): String? {
        val a = storageArg(arg) ?: return null
        val p = place(res, a)
        return when (app.thebox.core.storage.StorageFailure.entries.firstOrNull { it.name == a.second }) {
            app.thebox.core.storage.StorageFailure.FULL -> res.getString(R.string.storage_toast_stopped_full, p)
            app.thebox.core.storage.StorageFailure.ACCESS -> res.getString(R.string.storage_toast_stopped_access, p)
            app.thebox.core.storage.StorageFailure.REMOVED ->
                if (a.kind == app.thebox.core.storage.StorageKind.VOLUME) res.getString(R.string.storage_toast_stopped_usb_removed)
                else res.getString(R.string.storage_toast_stopped_removed, p)
            app.thebox.core.storage.StorageFailure.SLOW -> storageSlow(res, arg)
            // UNREACHABLE, or no cause given: "back at live" when the viewer was behind live.
            else -> if (a.jumped) res.getString(R.string.storage_toast_stopped_unreachable_jumped, capital(p))
            else res.getString(R.string.storage_toast_stopped_unreachable, p)
        }
    }

    /** REWIND_STOPPED_SLOW of a remote/USB cycle (§10); null = this device's text. */
    private fun storageSlow(res: Resources, arg: String?): String? {
        val a = storageArg(arg) ?: return null
        return if (a.kind == app.thebox.core.storage.StorageKind.VOLUME) res.getString(R.string.storage_toast_slow_usb)
        else res.getString(R.string.storage_toast_slow, place(res, a))
    }

    /**
     * (§10 stats line, U-N4) The place suffix of "Rewind: 18:20 saved of 30:00 · 1.1 GB": "NAS 3.4 MB/s" while saving
     * there, "this device (NAS offline)" or "(NAS connecting)" while the cycle falls back; null = this device (no suffix).
     */
    fun rewindPlaceStat(
        res: Resources, kind: app.thebox.core.storage.StorageKind, name: String?, writeBps: Long?,
        fallback: app.thebox.core.storage.LocationFallback?,
    ): String? {
        if (kind == app.thebox.core.storage.StorageKind.INTERNAL && fallback == null) return null
        val shown = ellipsize(name?.takeIf { it.isNotBlank() } ?: res.getString(R.string.storage_place_usb), app.thebox.core.storage.StorageConstants.LABEL_MAX_CHARS)
        return when {
            fallback == app.thebox.core.storage.LocationFallback.NOT_READY_YET -> res.getString(R.string.storage_stat_place_connecting, shown)
            fallback != null -> res.getString(R.string.storage_stat_place_fallback, shown)
            writeBps != null && writeBps > 0 -> res.getString(R.string.storage_stat_place_rate, shown, String.format(Locale.getDefault(), "%.1f", writeBps / 1_000_000.0))
            else -> shown
        }
    }

    /** (M6 commit 0) Separates the values of a toast that has several ("12 min" + ARG_SEP + "21:26"). */
    const val ARG_SEP = app.thebox.core.full.PLAYER_TOAST_ARG_SEP

    /**
     * (Fix pass, product, 2026-10-19: UX review S6, S7) The reason half of a PAUSE_NO_REWIND_* toast ([arg] as for the
     * toast: the free size for SPACE).
     */
    fun noRewindReason(res: Resources, kind: PlayerToast, arg: String?): String = when (kind) {
        PlayerToast.PAUSE_NO_REWIND_OFF -> res.getString(R.string.player_reason_rewind_off)
        PlayerToast.PAUSE_NO_REWIND_SPACE -> res.getString(R.string.player_reason_no_space, args(arg, 0))
        // (WP-S4, §10) A USB drive or a network folder names itself: "Saving for rewind failed on NAS, so Play returns to live."
        PlayerToast.PAUSE_NO_REWIND_STORAGE -> storageArg(arg)?.let { a -> res.getString(R.string.storage_toast_pause_failed, place(res, a)) }
            ?: res.getString(R.string.player_reason_storage)
        else -> res.getString(R.string.player_reason_cant_rewind)
    }

    /**
     * The next-step half of a PAUSE_NO_REWIND_* toast, which BACK_TO_LIVE_UNBUFFERED repeats once per tune (§13, S6);
     * null for other kinds. HLS: [arg] is the playlist name (Xtream), null for M3U.
     */
    fun noRewindStep(res: Resources, kind: PlayerToast, arg: String?): String? = when (kind) {
        PlayerToast.PAUSE_NO_REWIND_HLS -> if (arg != null) res.getString(R.string.player_step_hls_xtream, arg) else res.getString(R.string.player_step_hls_m3u)
        PlayerToast.PAUSE_NO_REWIND_OFF -> res.getString(R.string.player_step_rewind_off)
        PlayerToast.PAUSE_NO_REWIND_SPACE -> res.getString(R.string.player_step_no_space)
        PlayerToast.PAUSE_NO_REWIND_FORMAT -> res.getString(R.string.player_step_format)
        PlayerToast.PAUSE_NO_REWIND_STORAGE -> res.getString(R.string.player_step_storage)
        else -> null
    }

    /** The [index]th value of a toast arg ("" when missing). */
    fun args(arg: String?, index: Int): String = arg?.split(ARG_SEP)?.getOrNull(index).orEmpty()

    // ---- (M6 time-shift, §13) the values inside the time-shift toasts and lines

    /** "40 s" below a minute, else whole minutes rounded ("12 min"). */
    fun durationArg(res: Resources, ms: Long): String {
        val sec = (ms.coerceAtLeast(0L) + 500) / 1000
        return if (sec < 60) res.getString(R.string.player_arg_seconds, sec.toInt())
        else res.getString(R.string.player_arg_minutes, ((sec + 30) / 60).toInt())
    }

    /** Whole minutes rounded down ("17 more min": never more than is left). */
    fun minutesDown(ms: Long): Int = (ms.coerceAtLeast(0L) / 60_000L).toInt()

    /** "21:02" / "9:02" of a display-frame epoch ms in the device zone. */
    fun clockArg(ms: Long, clock24: Boolean): String = clockOfMs(ms, clock24)

    /** "0.6 GB" (one decimal, GiB like the settings row). */
    fun gbArg(res: Resources, bytes: Long): String =
        res.getString(R.string.player_arg_gb, String.format(Locale.getDefault(), "%.1f", bytes / (1024.0 * 1024 * 1024)))

    /**
     * (M6 §11.4, U-S7) The full-screen pill text: "PAUSED · 4:12 behind" (grows), "4:12 BEHIND", no leading zero
     * ("1:04:12"); null for the other kinds and for a plain pause without a time-shift ("PAUSED").
     */
    fun pillTime(res: Resources, kind: PillKind, behindMs: Long): String? {
        val t = behindText(behindMs)
        return when (kind) {
            PillKind.PAUSED -> if (behindMs >= 1_000) res.getString(R.string.player_pill_paused_behind, t) else null
            PillKind.BEHIND -> res.getString(R.string.player_pill_behind, t)
            else -> null
        }
    }

    /**
     * (M6 §11.5) The paused header line: "Can stay paused 17 more min", or why saving stopped and "Press OK" /
     * "Press OK or ▶" (by whether the remote has sent a media key).
     */
    fun pausedHeader(res: Resources, header: app.thebox.core.full.PausedHeader, facts: PausedFacts): String {
        val press = res.getString(if (facts.mediaKeys) R.string.player_paused_press_ok_play else R.string.player_paused_press_ok)
        return when (header) {
            app.thebox.core.full.PausedHeader.SAVING -> res.getString(R.string.player_paused_header_saving, minutesDown(facts.pauseRemainingMs ?: 0L))
            app.thebox.core.full.PausedHeader.FULL ->
                res.getString(R.string.player_paused_header_full, durationArg(res, facts.windowMs ?: app.thebox.core.playback.timeshift.TimeshiftConstants.AUTO_LENGTH_SMALL_MS), press)
            app.thebox.core.full.PausedHeader.HOLD_LIMIT ->
                res.getString(R.string.player_paused_header_hold_limit, durationArg(res, facts.holdMs ?: 0L), press)
            app.thebox.core.full.PausedHeader.SLOT_LOST -> res.getString(R.string.player_paused_header_slot_lost, press)
            app.thebox.core.full.PausedHeader.CATCHUP_COVERS ->
                res.getString(R.string.player_paused_header_catchup_covers, durationArg(res, app.thebox.core.playback.timeshift.TimeshiftConstants.PAUSE_KEEP_AWAKE_MAX_MS).let { hoursArg(res, it) })
        }
    }

    /** "3 h" for whole hours (the catch-up line), else the duration as is. */
    private fun hoursArg(res: Resources, fallback: String): String {
        val h = app.thebox.core.playback.timeshift.TimeshiftConstants.PAUSE_KEEP_AWAKE_MAX_MS / 3_600_000L
        return if (h * 3_600_000L == app.thebox.core.playback.timeshift.TimeshiftConstants.PAUSE_KEEP_AWAKE_MAX_MS) res.getString(R.string.player_arg_hours, h.toInt()) else fallback
    }

    /** "4:12", "1:04:12", "0:05" (§11.4: no leading zero on the first field). */
    fun behindText(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
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
        is PlaybackNotice.PassthroughDisabled -> passthrough(res, notice.mimeType).let { if (notice.forced) it + res.getString(R.string.player_notice_passthrough_forced) else it }
        PlaybackNotice.VideoDecoderSwitchedToSoftware -> res.getString(R.string.player_notice_video_software)
        PlaybackNotice.TunnelingDisabled -> res.getString(R.string.player_notice_tunneling_off)
        is PlaybackNotice.UserAgentSwitched -> res.getString(R.string.player_notice_user_agent, notice.preset)
        is PlaybackNotice.BufferIncreased -> bufferSecondsOf(notice.level)?.let { res.getString(R.string.player_notice_buffer, it) }
            ?: res.getString(R.string.player_notice_buffer_generic)
        PlaybackNotice.LiveResumed -> res.getString(R.string.player_notice_live_resumed)
        // M6 time-shift commit 0 (docs/design/m6-timeshift.md §13, §16.2); WP-C owns the bodies.
        /** No toast: `LocalTimelineBackend` hands the gap to the provider (§9.1). */
        is PlaybackNotice.LocalGap -> null
        /** The VM toasts GAP_SKIPPED_* with the landing time ("Now at 21:26"), which only the timeline knows. */
        is PlaybackNotice.GapSkipped -> null
        is PlaybackNotice.SavingStopped -> when (notice.stop) {
            RecordStop.SLOT_LOST, RecordStop.PREEMPTED -> res.getString(R.string.player_toast_saving_stopped_connection)
            RecordStop.DISK_ERROR, RecordStop.STORAGE_EVICTED -> res.getString(R.string.player_toast_rewind_stopped_storage)
            RecordStop.DISK_TOO_SLOW -> res.getString(R.string.player_toast_rewind_stopped_slow)
            // Paused header lines or the stashed card say these (§4.4, §11.5); SESSION_END is silent.
            RecordStop.FULL_WHILE_PAUSED, RecordStop.HOLD_LIMIT, RecordStop.UPSTREAM_FAILED, RecordStop.SESSION_END -> null
        }
        PlaybackNotice.LimitedToday -> res.getString(R.string.player_toast_rewind_limited_today)
        PlaybackNotice.JumpedToLiveRestart -> res.getString(R.string.player_toast_jumped_to_live_restart)
        PlaybackNotice.LeftAppCleared -> res.getString(R.string.player_toast_left_app_cleared)
        is PlaybackNotice.Warning -> when (notice.warning) {
            PlaybackWarning.NO_AUDIO, PlaybackWarning.AUDIO_UNSUPPORTED -> res.getString(R.string.player_notice_no_audio)
            PlaybackWarning.AUDIO_TIMESTAMPS -> res.getString(R.string.player_notice_audio_timestamps)
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
        QuickItem.TIME_SHIFT -> R.string.player_quick_pause // M6 §11.3: labelled Pause
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
        is PickerChoice.Server -> res.getString(R.string.player_picker_server_row, c.index + 1, c.label) // M5 integration
        PickerChoice.AudioOutputAuto -> res.getString(R.string.error_button_back_to_auto) // U-S12 (row label; phase 2 styles it)
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
