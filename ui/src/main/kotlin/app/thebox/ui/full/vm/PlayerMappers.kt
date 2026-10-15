package app.thebox.ui.full.vm

import app.thebox.core.full.AudioChoice
import app.thebox.core.full.StatsFacts
import app.thebox.core.full.TextChoice
import app.thebox.core.full.TrackKey
import app.thebox.core.full.TrackMenu
import app.thebox.core.full.VideoFacts
import app.thebox.player.api.StreamStats
import app.thebox.player.api.TextTrackOption
import app.thebox.player.api.TrackInfo
import app.thebox.player.api.TrackRef
import app.thebox.player.api.VideoInfo

/**
 * `:player` types → the `:core` mirrors the player machine reads (docs/design/m4-player-ux.md §3.10.1, §3.6.3). Pure.
 *
 * Speculative captions (§3.6.3, API notes §1.3): Media3 exposes a CEA-608 track for every H.264 TS without a caption
 * descriptor, so a CEA-608/708 track with neither label nor language is `speculative` ("Captions (if broadcast)") and
 * listed after every real text track (E14). The order among real tracks, and among speculative ones, is kept.
 */
object PlayerMappers {
    private val CEA = setOf("CEA-608", "CEA-708")

    fun trackMenu(info: TrackInfo): TrackMenu {
        val audio = info.audio.map {
            AudioChoice(key(it.ref), it.language, it.label, it.codec, it.channels, it.describesVideo, it.selected, it.supported)
        }
        val text = info.text.map(::textChoice).sortedBy { if (it.speculative) 1 else 0 }
        return TrackMenu(audio = audio, text = text, textEnabled = info.textEnabled)
    }

    fun textChoice(t: TextTrackOption): TextChoice =
        TextChoice(key(t.ref), t.language, t.label, t.kind, t.selected, speculative = isSpeculative(t))

    fun isSpeculative(t: TextTrackOption): Boolean = t.kind in CEA && t.label == null && t.language == null

    fun videoFacts(info: VideoInfo): VideoFacts = VideoFacts(
        width = info.width,
        height = info.height,
        fps = info.fps,
        interlaced = info.interlacedHint,
        hdr = info.hdr,
        aspect = info.aspectMode,
        decoderOverride = info.decoderOverride,
    )

    fun statsFacts(stats: StreamStats): StatsFacts = StatsFacts(
        videoMime = stats.videoMime,
        videoCodecs = stats.videoCodecs,
        videoDecoder = stats.videoDecoder,
        width = stats.width,
        height = stats.height,
        fps = stats.fps,
        interlaced = false,
        hdr = stats.hdr,
        audioMime = stats.audioMime,
        audioChannels = stats.audioChannels,
        audioDecoder = stats.audioDecoder,
        audioPassthrough = stats.audioPassthrough,
        streamBitrateBps = stats.streamBitrateBps,
        bufferMs = stats.bufferMs,
        droppedFrames = stats.droppedFrames,
        httpStatus = stats.httpStatus,
        host = stats.host,
        redirects = stats.redirects,
        format = stats.format,
        reconnects = stats.reconnects,
        broker = stats.broker,
        bufferProfile = stats.bufferProfile,
        afr = stats.afr,
        displayRefreshHz = stats.displayRefreshHz,
        tunneling = stats.tunneling,
        serverIndex = stats.serverIndex,
        serverCount = stats.serverCount,
    )

    /** With the interlace hint of the current [VideoInfo] (StreamStats has none). */
    fun statsFacts(stats: StreamStats, video: VideoInfo): StatsFacts = statsFacts(stats).copy(interlaced = video.interlacedHint)

    fun key(ref: TrackRef): TrackKey = TrackKey(ref.type, ref.groupId, ref.index)

    fun ref(key: TrackKey): TrackRef = TrackRef(key.type, key.groupId, key.index)
}
