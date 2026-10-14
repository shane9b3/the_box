package app.thebox.player.controller

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import app.thebox.core.common.BoxLog
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * `ForwardingSimpleBasePlayer` given to the `MediaSession` (§3.10.4; Media3's recommended way to hide commands).
 *
 * - Commands: `PLAY_PAUSE`, `STOP`, `SEEK_TO_NEXT`, `SEEK_TO_PREVIOUS`, `GET_CURRENT_MEDIA_ITEM`, `GET_METADATA`, plus
 *   `SEEK_IN_CURRENT_MEDIA_ITEM` for archive only. (reviewed 2026-10-13, B5) `PLAY_PAUSE` and `SEEK_TO_NEXT/PREVIOUS`
 *   are advertised only while the app is in the foreground ([Actions.isForeground]); [refresh] is called on
 *   `ON_START`/`ON_STOP`. In the background a session PLAY/NEXT/PREVIOUS is a no-op, so a CEC or Bluetooth key never
 *   re-tunes live TV behind the launcher. PAUSE and STOP still work.
 * - Every command goes through the controller ([Actions]), never straight to the `ExoPlayer`.
 * - The playlist is sanitised: `mediaId` and `MediaMetadata` only, never a URI (HLS `MediaItem`s carry the real URL).
 */
@OptIn(UnstableApi::class)
internal class SessionPlayer(player: Player, private val actions: Actions) : ForwardingSimpleBasePlayer(player) {

    /** The controller calls the session may make. Main thread. */
    interface Actions {
        fun isForeground(): Boolean
        fun isArchive(): Boolean
        fun title(): String?
        fun play()
        fun pause()
        fun stop()
        fun next()
        fun previous()
        fun seekTo(positionMs: Long)
    }

    /** Re-reads the command set (foreground change, archive/live change, new title). */
    fun refresh() = invalidateState()

    override fun getState(): State {
        val state = super.getState()
        val title = actions.title()
        val playlist = state.getPlaylist().map { item ->
            val clean = MediaItem.Builder()
                .setMediaId(item.mediaItem.mediaId)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build()
            item.buildUpon().setMediaItem(clean).build()
        }
        return state.buildUpon()
            .setAvailableCommands(commands(actions.isForeground(), actions.isArchive()))
            .setPlaylist(playlist)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            if (actions.isForeground()) actions.play() else BoxLog.i(TAG) { "session play ignored in background" }
        } else {
            actions.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        actions.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        onSeekCommand(seekCommand, positionMs)
        return Futures.immediateVoidFuture()
    }

    /** [handleSeek]'s mapping, visible for tests. */
    internal fun onSeekCommand(seekCommand: Int, positionMs: Long) {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                if (actions.isForeground()) actions.next() else BoxLog.i(TAG) { "session next ignored in background" }
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                if (actions.isForeground()) actions.previous() else BoxLog.i(TAG) { "session previous ignored in background" }
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> if (actions.isArchive()) actions.seekTo(positionMs)
            else -> Unit
        }
    }

    companion object {
        private const val TAG = "PlaybackController"

        fun commands(foreground: Boolean, archive: Boolean): Player.Commands {
            val b = Player.Commands.Builder().addAll(Player.COMMAND_STOP, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA)
            if (foreground) b.addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS)
            if (archive) b.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            return b.build()
        }
    }
}

/** Creates the controller's `MediaSession` (§3.10.4). Tests bind [None]: Robolectric needs no session. */
internal fun interface MediaSessionFactory {
    /** Returns a handle that releases the session, or null when no session was created. */
    fun create(context: Context, player: Player, actions: SessionPlayer.Actions): SessionHandle?

    companion object {
        val None = MediaSessionFactory { _, _, _ -> null }

        /** The v1 session: no `MediaSessionService` (B5), id "thebox", a [SessionPlayer] in front of the player. */
        val Default = MediaSessionFactory { context, player, actions ->
            val sessionPlayer = SessionPlayer(player, actions)
            val session = MediaSession.Builder(context, sessionPlayer).setId("thebox").build()
            SessionHandle(sessionPlayer) { session.release() }
        }
    }
}

/** The created session: [player] for [SessionPlayer.refresh], [release] at process end (never called in v1). */
internal class SessionHandle(val player: SessionPlayer?, val release: () -> Unit)
