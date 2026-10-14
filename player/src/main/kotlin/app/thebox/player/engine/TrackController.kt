package app.thebox.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import app.thebox.core.common.BoxLog
import app.thebox.core.playback.HdrKind
import app.thebox.core.playback.HdrPreviewMode
import app.thebox.core.playback.Viewport
import app.thebox.player.api.AudioTrackOption
import app.thebox.player.api.TextTrackOption
import app.thebox.player.api.TrackInfo
import app.thebox.player.api.TrackRef
import app.thebox.player.internal.PlayerBundle

/**
 * Track lists, selection and the track-type switches of one player (docs/design/m2-playback-core.md §3.12, §3.8.4,
 * §3.8.6, §3.9.5, §3.11). Main thread only. The controller calls [update] from `onTracksChanged` and reads [current],
 * [video] and [videoMilestoneExpected]; persisting choices to `ChannelMemory` is the controller's job (the selection
 * methods return the chosen `Format` so it can remember the language).
 */
@OptIn(UnstableApi::class)
internal class TrackController(private val bundle: PlayerBundle) {

    /** Facts about the selected video track (HDR, interlace, declared fps). */
    data class VideoFacts(
        val mimeType: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val hdr: HdrKind = HdrKind.SDR,
        val interlacedHint: Boolean = false,
        /** `Format.frameRate` when the container declares it (HLS, MP4); null for live TS (§3.9.3). */
        val declaredFps: Float? = null,
    )

    private var tracks: Tracks = Tracks.EMPTY
    private var info = TrackInfo()
    private var videoFacts = VideoFacts()
    private var lastVideoFormat: Format? = null

    fun current(): TrackInfo = info

    val video: VideoFacts get() = videoFacts

    /** Rebuilds [current] and [video] from [newTracks] (the `onTracksChanged` argument). Returns the new [TrackInfo]. */
    fun update(newTracks: Tracks): TrackInfo {
        tracks = newTracks
        val audio = ArrayList<AudioTrackOption>()
        val text = ArrayList<TextTrackOption>()
        var anyTextSelected = false
        var selectedVideo: Format? = null
        for (group in newTracks.groups) {
            val tg = group.mediaTrackGroup
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                val ref = TrackRef(group.type, tg.id, i)
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> audio += AudioTrackOption(
                        ref = ref,
                        language = f.language,
                        label = f.label,
                        codec = codecName(f.sampleMimeType),
                        channels = f.channelCount.coerceAtLeast(0),
                        describesVideo = (f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0,
                        selected = group.isTrackSelected(i),
                        supported = group.isTrackSupported(i),
                    )
                    C.TRACK_TYPE_TEXT -> {
                        val selected = group.isTrackSelected(i)
                        anyTextSelected = anyTextSelected || selected
                        text += TextTrackOption(ref, f.language, f.label, textKind(f), selected)
                    }
                    C.TRACK_TYPE_VIDEO -> if (group.isTrackSelected(i) && selectedVideo == null) selectedVideo = f
                }
            }
        }
        val params = bundle.player.trackSelectionParameters
        info = TrackInfo(audio = audio, text = text, textEnabled = anyTextSelected && C.TRACK_TYPE_TEXT !in params.disabledTrackTypes)
        updateVideo(selectedVideo)
        return info
    }

    /**
     * Video facts from a newly seen video format (`onVideoInputFormatChanged` carries the decoder's format, which for TS
     * is the only place the SPS and the colour info are known before the tracks are re-reported).
     */
    fun onVideoFormat(format: Format) = updateVideo(format)

    private fun updateVideo(format: Format?) {
        if (format == null) {
            if (tracks.groups.none { it.type == C.TRACK_TYPE_VIDEO }) videoFacts = VideoFacts()
            return
        }
        if (format === lastVideoFormat) return
        lastVideoFormat = format
        videoFacts = VideoFacts(
            mimeType = format.sampleMimeType,
            width = format.width.coerceAtLeast(0),
            height = format.height.coerceAtLeast(0),
            hdr = hdrKindOf(format),
            interlacedHint = isInterlaced(format),
            declaredFps = format.frameRate.takeIf { it > 0f },
        )
    }

    /**
     * B2 first-media milestone input: true when a video group is selected **and** the video track type is not disabled
     * (poster, HIDDEN). Then the milestone is `onRenderedFirstFrame`, else the first `onAudioPositionAdvancing`.
     */
    fun videoMilestoneExpected(): Boolean =
        tracks.isTypeSelected(C.TRACK_TYPE_VIDEO) && C.TRACK_TYPE_VIDEO !in bundle.player.trackSelectionParameters.disabledTrackTypes

    /** User audio choice: an override for this tune, or null to go back to the language preferences. Returns its format. */
    fun selectAudioTrack(ref: TrackRef?): Format? {
        val p = bundle.player.trackSelectionParameters.buildUpon()
        if (ref == null) {
            bundle.player.trackSelectionParameters = p.clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
            return null
        }
        val (group, index) = find(ref, C.TRACK_TYPE_AUDIO) ?: return null
        bundle.player.trackSelectionParameters = p
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
        return group.getTrackFormat(index)
    }

    /** User subtitle choice; null turns text off. Returns the chosen format. */
    fun selectTextTrack(ref: TrackRef?): Format? {
        val p = bundle.player.trackSelectionParameters.buildUpon()
        if (ref == null) {
            bundle.player.trackSelectionParameters = p.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            return null
        }
        val (group, index) = find(ref, C.TRACK_TYPE_TEXT) ?: return null
        bundle.player.trackSelectionParameters = p
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        return group.getTrackFormat(index)
    }

    /**
     * Tune defaults (§3.8.6): clears the previous tune's overrides and sets the language preferences.
     * [audioLanguages]: the channel's remembered language first, then the settings (device locale when empty).
     * [subtitles]: `null` = follow [subtitlesEnabled], `""` = the user turned them off on this channel, else a language.
     */
    fun applyTuneDefaults(audioLanguages: List<String>, textLanguages: List<String>, subtitles: String?, subtitlesEnabled: Boolean) {
        val textOn = when (subtitles) {
            null -> subtitlesEnabled
            "" -> false
            else -> true
        }
        val textLangs = if (!subtitles.isNullOrEmpty()) listOf(subtitles) + textLanguages.filter { it != subtitles } else textLanguages
        bundle.player.trackSelectionParameters = bundle.player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setPreferredAudioLanguages(*audioLanguages.distinct().toTypedArray())
            .setPreferredAudioRoleFlags(C.ROLE_FLAG_MAIN)
            .setPreferredTextLanguages(*textLangs.toTypedArray())
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !textOn)
            .build()
    }

    /** Disables or re-enables video decoding (HDR poster, HIDDEN/NONE viewport). Audio and the connection continue. */
    fun setVideoDisabled(disabled: Boolean) {
        val p = bundle.player.trackSelectionParameters
        if ((C.TRACK_TYPE_VIDEO in p.disabledTrackTypes) == disabled) return
        bundle.player.trackSelectionParameters = p.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled).build()
    }

    val videoDisabled: Boolean get() = C.TRACK_TYPE_VIDEO in bundle.player.trackSelectionParameters.disabledTrackTypes

    /** Tunneling switch (§3.8.4): Media3 enables it only when both selected renderers support it. */
    fun setTunnelingEnabled(enabled: Boolean) {
        val selector = bundle.trackSelector
        if (selector.parameters.tunnelingEnabled == enabled) return
        selector.setParameters(selector.buildUponParameters().setTunnelingEnabled(enabled))
        BoxLog.i(TAG) { "tunneling ${if (enabled) "enabled" else "disabled"}" }
    }

    val tunnelingRequested: Boolean get() = bundle.trackSelector.parameters.tunnelingEnabled

    private fun find(ref: TrackRef, type: Int): Pair<Tracks.Group, Int>? {
        for (group in tracks.groups) {
            if (group.type == type && group.mediaTrackGroup.id == ref.groupId && ref.index in 0 until group.length) return group to ref.index
        }
        BoxLog.w(TAG, "track $ref not in the current tracks")
        return null
    }

    companion object {
        private const val TAG = "PlaybackController"

        /** Display name of an audio codec (§3.12). */
        fun codecName(mime: String?): String = when {
            mime == null -> "?"
            mime == MimeTypes.AUDIO_E_AC3 || mime == MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3"
            mime == MimeTypes.AUDIO_AC3 -> "AC-3"
            mime == MimeTypes.AUDIO_AC4 -> "AC-4"
            mime == MimeTypes.AUDIO_AAC -> "AAC"
            mime == MimeTypes.AUDIO_MPEG_L2 -> "MP2"
            mime == MimeTypes.AUDIO_MPEG_L1 -> "MP1"
            mime == MimeTypes.AUDIO_MPEG -> "MP3"
            mime.startsWith("audio/vnd.dts") -> "DTS"
            mime == MimeTypes.AUDIO_TRUEHD -> "TrueHD"
            mime == MimeTypes.AUDIO_OPUS -> "Opus"
            mime == MimeTypes.AUDIO_VORBIS -> "Vorbis"
            mime == MimeTypes.AUDIO_FLAC -> "FLAC"
            mime == MimeTypes.AUDIO_RAW -> "PCM"
            else -> mime.substringAfter('/').uppercase()
        }

        /** Subtitle kind for the track menu. TS subtitles are transcoded, so the original MIME is in `codecs`. */
        fun textKind(f: Format): String {
            val original = if (f.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) f.codecs ?: f.sampleMimeType else f.sampleMimeType
            return when (original) {
                MimeTypes.APPLICATION_DVBSUBS -> "DVB"
                MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_MP4CEA608 -> "CEA-608"
                MimeTypes.APPLICATION_CEA708 -> "CEA-708"
                MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_MP4VTT -> "WebVTT"
                MimeTypes.APPLICATION_TTML -> "TTML"
                MimeTypes.APPLICATION_SUBRIP -> "SubRip"
                MimeTypes.APPLICATION_PGS -> "PGS"
                null -> "?"
                else -> original.substringAfter('/')
            }
        }

        /** §3.9.5: Dolby Vision by MIME, else the colour transfer. */
        fun hdrKindOf(format: Format): HdrKind {
            if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) return HdrKind.DOLBY_VISION
            val color: ColorInfo = format.colorInfo ?: return HdrKind.SDR
            if (!ColorInfo.isTransferHdr(color)) return HdrKind.SDR
            return when (color.colorTransfer) {
                C.COLOR_TRANSFER_ST2084 -> HdrKind.HDR10
                C.COLOR_TRANSFER_HLG -> HdrKind.HLG
                else -> HdrKind.UNKNOWN_HDR
            }
        }

        /**
         * H.264 field/MBAFF coding from the SPS `frame_mbs_only_flag` (lead decision Q12). `H264Reader` stores the SPS
         * with its start code in `initializationData[0]`. HEVC and everything else: false.
         */
        fun isInterlaced(format: Format): Boolean {
            if (format.sampleMimeType != MimeTypes.VIDEO_H264) return false
            val sps = format.initializationData.firstOrNull() ?: return false
            val header = nalHeaderOffset(sps)
            if (header < 0 || header >= sps.size) return false
            return try {
                !NalUnitUtil.parseSpsNalUnit(sps, header, sps.size).frameMbsOnlyFlag
            } catch (e: RuntimeException) {
                BoxLog.w(TAG, "SPS parse failed: ${e.javaClass.simpleName}")
                false
            }
        }

        /** Offset of the NAL header after a 3- or 4-byte start code; 0 when there is none (raw NAL unit). */
        private fun nalHeaderOffset(b: ByteArray): Int = when {
            b.size > 4 && b[0].toInt() == 0 && b[1].toInt() == 0 && b[2].toInt() == 0 && b[3].toInt() == 1 -> 4
            b.size > 3 && b[0].toInt() == 0 && b[1].toInt() == 0 && b[2].toInt() == 1 -> 3
            else -> 0
        }

        /** §3.9.5 poster rule: HDR in the mini viewport with the POSTER setting disables the video track. */
        fun posterFor(hdr: HdrKind, viewport: Viewport, mode: HdrPreviewMode): Boolean =
            mode == HdrPreviewMode.POSTER && viewport == Viewport.MINI && hdr != HdrKind.SDR
    }
}
