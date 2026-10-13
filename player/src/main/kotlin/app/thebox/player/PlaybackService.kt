package app.thebox.player

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * M0 stub. In M2 this binds to the `@Singleton PlaybackController` (which owns the one
 * ExoPlayer) and exposes its MediaSession; the service never owns the player itself
 * (architecture review B6.2). Until then no controller can connect.
 */
class PlaybackService : MediaSessionService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = null
}
