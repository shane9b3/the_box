package app.thebox.playback

import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.BufferMode
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.data.db.entity.ChannelPrefsEntity
import app.thebox.data.repo.ChannelPrefsRepository
import app.thebox.player.api.ChannelMemory
import app.thebox.player.api.ChannelPlaybackMemory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ChannelMemory] over `channel_prefs` (§2.4.2): reads the row, writes only the changed columns through the
 * column-targeted `ChannelPrefsRepository` setters (never `edit`, review S10), serialised by a Mutex.
 *
 * Columns: `audio_lang`, `subtitle_lang` (`""` = off on this channel), `aspect_mode` / `video_decoder` /
 * `buffer_profile` (enum names), `playback_flags` (bits 0–1 TS compatibility level, bit 2 known HDR), `fps_milli`.
 * The Mutex only orders this class's own read-modify-writes; the column-targeted upserts are what keep a concurrent
 * `setHidden`/`setLocked` from being reverted.
 */
@Singleton
class RoomChannelMemory @Inject constructor(private val prefs: ChannelPrefsRepository) : ChannelMemory {
    private val mutex = Mutex()

    override suspend fun read(identity: ChannelIdentity): ChannelPlaybackMemory = toMemory(prefs.get(identity))

    override suspend fun update(identity: ChannelIdentity, change: (ChannelPlaybackMemory) -> ChannelPlaybackMemory) {
        mutex.withLock {
            val before = toMemory(prefs.get(identity))
            val after = change(before)
            if (after.audioLanguage != before.audioLanguage) prefs.setAudioLang(identity, after.audioLanguage)
            if (after.subtitleLanguage != before.subtitleLanguage) prefs.setSubtitleLang(identity, after.subtitleLanguage)
            if (after.aspect != before.aspect) prefs.setAspectMode(identity, after.aspect?.name)
            if (after.videoDecoder != before.videoDecoder) prefs.setVideoDecoder(identity, after.videoDecoder?.name)
            if (after.bufferMode != before.bufferMode) prefs.setBufferProfile(identity, after.bufferMode?.name)
            val flags = flagsOf(after)
            if (flags != flagsOf(before)) prefs.setPlaybackFlags(identity, flags)
            if (after.fpsMilli != before.fpsMilli) prefs.setFpsMilli(identity, after.fpsMilli)
        }
    }

    override suspend fun recordWatched(identity: ChannelIdentity) {
        prefs.recordWatched(identity)
    }

    companion object {
        const val TS_COMPAT_MASK = 0b011
        const val KNOWN_HDR_BIT = 0b100
        const val TUNNELING_OFF_BIT = 0b1000

        fun toMemory(e: ChannelPrefsEntity?): ChannelPlaybackMemory {
            if (e == null) return ChannelPlaybackMemory()
            return ChannelPlaybackMemory(
                audioLanguage = e.audioLang,
                subtitleLanguage = e.subtitleLang,
                aspect = e.aspectMode?.let { n -> AspectMode.entries.firstOrNull { it.name == n } },
                videoDecoder = e.videoDecoder?.let { n -> VideoDecoderMode.entries.firstOrNull { it.name == n } },
                bufferMode = e.bufferProfile?.let { n -> BufferMode.entries.firstOrNull { it.name == n } },
                tsCompatLevel = e.playbackFlags and TS_COMPAT_MASK,
                knownHdr = e.playbackFlags and KNOWN_HDR_BIT != 0,
                fpsMilli = e.fpsMilli,
                tunnelingOff = e.playbackFlags and TUNNELING_OFF_BIT != 0,
            )
        }

        fun flagsOf(m: ChannelPlaybackMemory): Int =
            (m.tsCompatLevel.coerceIn(0, TS_COMPAT_MASK) and TS_COMPAT_MASK) or (if (m.knownHdr) KNOWN_HDR_BIT else 0) or
                (if (m.tunnelingOff) TUNNELING_OFF_BIT else 0)
    }
}
