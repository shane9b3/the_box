package app.thebox.player.controller

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import java.lang.ref.WeakReference

/**
 * `ForwardingPlayer` given to `PlayerSurface`; exposes only the surface commands and captures the `SurfaceView` (§2.6,
 * §3.11). M3 must not be able to `prepare()`, `seekTo()` or `stop()` behind the controller's back, so
 * `isCommandAvailable`/`getAvailableCommands` report only [Player.COMMAND_SET_VIDEO_SURFACE] and every other mutating
 * method is a no-op. Listener registration is forwarded (`PlayerSurface` only listens for `onSurfaceSizeChanged`).
 *
 * (reviewed 2026-10-13, B3) The `ExoPlayer` lives for the process, and `PlayerSurface` does not clear its view when it
 * leaves composition. So every view given to [setVideoSurfaceView] gets a `View.OnAttachStateChangeListener`, and on
 * `onViewDetachedFromWindow` the view is cleared from the player if it is still the current one: a destroyed
 * Activity's `SurfaceView` is never kept by the player. A re-attached view (same instance) is given back.
 *
 * [onSurfaceView] reports the current view (or null) for AFR's `Surface.setFrameRate` (§3.9.2). The reference is weak.
 *
 * (code review 2026-10-14, E-S4) [onSurfaceValid] reports whether the current view has a valid surface: the view's
 * `SurfaceHolder` callbacks (the surface is destroyed when the Activity stops, e.g. during the standby grace, while the
 * view stays attached), plus view attach/detach. Without a surface ExoPlayer skips frames, so the controller does not
 * expect rendered video then (detector c).
 */
@OptIn(UnstableApi::class)
internal class SurfacePlayer(
    private val exo: Player,
    private val onSurfaceValid: (Boolean) -> Unit = {},
    private val onSurfaceView: (SurfaceView?) -> Unit = {},
) : ForwardingPlayer(exo) {

    private var current: WeakReference<SurfaceView>? = null
    private var detached: WeakReference<SurfaceView>? = null
    private var watched: WeakReference<SurfaceView>? = null

    /** The last validity reported through [onSurfaceValid]. */
    var surfaceValid: Boolean = false
        private set

    @VisibleForTesting
    internal val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = report(true)
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
        override fun surfaceDestroyed(holder: SurfaceHolder) = report(false)
    }

    /** The view currently given to the player (tests and diagnostics). */
    val currentView: SurfaceView? get() = current?.get()

    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            val view = v as? SurfaceView ?: return
            if (detached?.get() === view && current == null) {
                detached = null
                attach(view)
            }
        }

        override fun onViewDetachedFromWindow(v: View) {
            val view = v as? SurfaceView ?: return
            if (current?.get() === view) {
                exo.clearVideoSurfaceView(view)
                current = null
                detached = WeakReference(view)
                unwatch()
                onSurfaceView(null)
            }
        }
    }

    override fun isCommandAvailable(command: Int): Boolean = command == Player.COMMAND_SET_VIDEO_SURFACE

    override fun getAvailableCommands(): Player.Commands = SURFACE_ONLY

    override fun setVideoSurfaceView(surfaceView: SurfaceView?) {
        if (surfaceView == null) {
            current?.get()?.let { exo.clearVideoSurfaceView(it) }
            current = null
            unwatch()
            onSurfaceView(null)
            return
        }
        surfaceView.removeOnAttachStateChangeListener(attachListener)
        surfaceView.addOnAttachStateChangeListener(attachListener)
        detached = null
        attach(surfaceView)
    }

    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) {
        exo.clearVideoSurfaceView(surfaceView)
        if (surfaceView != null && current?.get() === surfaceView) {
            current = null
            unwatch()
            onSurfaceView(null)
        }
    }

    private fun attach(view: SurfaceView) {
        exo.setVideoSurfaceView(view)
        current = WeakReference(view)
        watch(view)
        onSurfaceView(view)
    }

    private fun watch(view: SurfaceView) {
        if (watched?.get() !== view) {
            unwatch()
            view.holder.addCallback(holderCallback)
            watched = WeakReference(view)
        }
        report(view.holder.surface?.isValid == true)
    }

    private fun unwatch() {
        watched?.get()?.holder?.removeCallback(holderCallback)
        watched = null
        report(false)
    }

    private fun report(valid: Boolean) {
        if (surfaceValid == valid) return
        surfaceValid = valid
        onSurfaceValid(valid)
    }

    // ------------------------------------------------------------- everything else is not M3's to call

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) = Unit
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) = Unit
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) = Unit
    override fun setMediaItem(mediaItem: MediaItem) = Unit
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = Unit
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = Unit
    override fun addMediaItem(mediaItem: MediaItem) = Unit
    override fun addMediaItem(index: Int, mediaItem: MediaItem) = Unit
    override fun addMediaItems(mediaItems: MutableList<MediaItem>) = Unit
    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) = Unit
    override fun moveMediaItem(currentIndex: Int, newIndex: Int) = Unit
    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) = Unit
    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) = Unit
    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>) = Unit
    override fun removeMediaItem(index: Int) = Unit
    override fun removeMediaItems(fromIndex: Int, toIndex: Int) = Unit
    override fun clearMediaItems() = Unit
    override fun prepare() = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun setPlayWhenReady(playWhenReady: Boolean) = Unit
    override fun setRepeatMode(repeatMode: Int) = Unit
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = Unit
    override fun seekToDefaultPosition() = Unit
    override fun seekToDefaultPosition(mediaItemIndex: Int) = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = Unit
    override fun seekBack() = Unit
    override fun seekForward() = Unit
    override fun seekToPreviousMediaItem() = Unit
    override fun seekToPrevious() = Unit
    override fun seekToNextMediaItem() = Unit
    override fun seekToNext() = Unit
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit
    override fun setPlaybackSpeed(speed: Float) = Unit
    override fun stop() = Unit
    override fun release() = Unit
    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) = Unit
    override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) = Unit
    override fun setVolume(volume: Float) = Unit
    override fun mute() = Unit
    override fun unmute() = Unit
    override fun clearVideoSurface() = Unit
    override fun clearVideoSurface(surface: Surface?) = Unit
    override fun setVideoSurface(surface: Surface?) = Unit
    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) = Unit
    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) = Unit
    override fun setVideoTextureView(textureView: TextureView?) = Unit
    override fun clearVideoTextureView(textureView: TextureView?) = Unit
    @Deprecated("Deprecated in Java") override fun setDeviceVolume(volume: Int) = Unit
    override fun setDeviceVolume(volume: Int, flags: Int) = Unit
    @Deprecated("Deprecated in Java") override fun increaseDeviceVolume() = Unit
    override fun increaseDeviceVolume(flags: Int) = Unit
    @Deprecated("Deprecated in Java") override fun decreaseDeviceVolume() = Unit
    override fun decreaseDeviceVolume(flags: Int) = Unit
    @Deprecated("Deprecated in Java") override fun setDeviceMuted(muted: Boolean) = Unit
    override fun setDeviceMuted(muted: Boolean, flags: Int) = Unit
    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) = Unit

    companion object {
        private val SURFACE_ONLY: Player.Commands = Player.Commands.Builder().add(Player.COMMAND_SET_VIDEO_SURFACE).build()
    }
}
