package app.thebox.player.controller.fakes

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaSource
import java.lang.reflect.Proxy

/**
 * A scriptable [ExoPlayer] built as a `java.lang.reflect.Proxy`: it records what the controller calls (media source,
 * prepare, stop, playWhenReady, track parameters, surface, seeks) and lets a test deliver player events on the main
 * thread ([emitState], [emitError], [emitFirstFrame], [emitTracks], [emitAudioAdvancing]). Everything else returns a
 * default value. Integration replaces it with WP-C's real `PlayerFactory` (§1.9 step 2).
 */
@OptIn(UnstableApi::class)
class FakeExoPlayer(private val journal: Journal) {
    val listeners = mutableListOf<Player.Listener>()
    val analytics = mutableListOf<AnalyticsListener>()
    var playWhenReady = false
    var playbackState = Player.STATE_IDLE
    var params: TrackSelectionParameters = TrackSelectionParameters.DEFAULT
    var tracks: Tracks = Tracks.EMPTY
    var mediaSource: MediaSource? = null
    var prepares = 0
    var stops = 0
    var surfaceView: Any? = null
    val seeks = mutableListOf<Long>()
    var seekToDefault = 0
    var position = 0L
    var playerError: PlaybackException? = null
    var videoScalingMode = C.VIDEO_SCALING_MODE_DEFAULT

    val player: ExoPlayer = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { proxy, m, a ->
        val args = a ?: emptyArray()
        when (m.name) {
            "addListener" -> { (args[0] as? Player.Listener)?.let { listeners += it }; Unit }
            "removeListener" -> { listeners.remove(args[0]); Unit }
            "addAnalyticsListener" -> { analytics += args[0] as AnalyticsListener; Unit }
            "removeAnalyticsListener" -> { analytics.remove(args[0]); Unit }
            "setMediaSource" -> { mediaSource = args[0] as MediaSource; journal("setMediaSource"); Unit }
            "prepare" -> { prepares++; playerError = null; playbackState = Player.STATE_BUFFERING; journal("prepare"); Unit }
            "stop" -> { stops++; playbackState = Player.STATE_IDLE; journal("player.stop"); Unit }
            "setPlayWhenReady" -> { playWhenReady = args[0] as Boolean; Unit }
            "getPlayWhenReady" -> playWhenReady
            "getPlaybackState" -> playbackState
            "getPlayerError" -> playerError
            "getTrackSelectionParameters" -> params
            "setTrackSelectionParameters" -> { params = args[0] as TrackSelectionParameters; Unit }
            "getCurrentTracks" -> tracks
            "setVideoSurfaceView" -> { surfaceView = args[0]; journal("setVideoSurfaceView"); Unit }
            "clearVideoSurfaceView" -> { if (surfaceView === args[0]) surfaceView = null; journal("clearVideoSurfaceView"); Unit }
            "seekTo" -> { seeks += args.last() as Long; Unit }
            "seekToDefaultPosition" -> { seekToDefault++; Unit }
            "getCurrentPosition" -> position
            "getTotalBufferedDuration" -> 0L
            "getDuration" -> C.TIME_UNSET
            "isCurrentMediaItemSeekable" -> true
            "getApplicationLooper" -> Looper.getMainLooper()
            "setVideoScalingMode" -> { videoScalingMode = args[0] as Int; Unit }
            "getVideoScalingMode" -> videoScalingMode
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args[0]
            "toString" -> "FakeExoPlayer"
            else -> defaultFor(m.returnType)
        }
    } as ExoPlayer

    fun emitState(state: Int) {
        playbackState = state
        listeners.toList().forEach { it.onPlaybackStateChanged(state) }
    }

    fun emitError(e: PlaybackException) {
        playerError = e
        playbackState = Player.STATE_IDLE
        listeners.toList().forEach { it.onPlayerError(e) }
    }

    fun emitFirstFrame() = listeners.toList().forEach { it.onRenderedFirstFrame() }

    fun emitTracks(t: Tracks) {
        tracks = t
        listeners.toList().forEach { it.onTracksChanged(t) }
    }

    fun emitAudioAdvancing() = analytics.toList().forEach { it.onAudioPositionAdvancing(eventTime(), 0) }

    companion object {
        fun eventTime() = AnalyticsListener.EventTime(0, Timeline.EMPTY, 0, null, 0, Timeline.EMPTY, 0, null, 0, 0)

        val VIDEO: Format = Format.Builder().setId("v").setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1280).setHeight(720).build()
        val AUDIO: Format = Format.Builder().setId("a").setSampleMimeType(MimeTypes.AUDIO_AAC).setChannelCount(2).setSampleRate(48_000).setLanguage("en").build()

        fun group(f: Format, id: String, selected: Boolean = true) =
            Tracks.Group(TrackGroup(id, f), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))

        /** Video + audio, both selected. */
        fun avTracks(video: Format = VIDEO) = Tracks(listOf(group(video, "0:v"), group(AUDIO, "0:a")))

        /** Audio only (radio, B2). */
        fun audioTracks() = Tracks(listOf(group(AUDIO, "0:a")))

        private fun defaultFor(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> 0.toChar()
            else -> null
        }
    }
}
