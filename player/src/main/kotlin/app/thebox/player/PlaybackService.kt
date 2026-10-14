package app.thebox.player

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Dead code in v1, kept for v1.1 (background audio, PiP) and v2 (DVR). It is **not declared in the manifest**
 * (docs/design/m2-playback-core.md §3.10.4, design review B5): a declared `MediaSessionService` without a real
 * `startForeground` path crashes on Fire TV when a media button restarts the process. In v1 the
 * `PlaybackController` owns a bare `MediaSession` and the player; this service never owns the player
 * (architecture review B6.2). When it returns, `onGetSession` must return `null` unless background playback is
 * active.
 */
class PlaybackService : MediaSessionService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = null
}
