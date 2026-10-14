package app.thebox.player.engine

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-C. Replace the TODO bodies; keep the names.
import android.content.Context
import android.os.Looper
import app.thebox.player.api.PlaybackSettingsSource
import app.thebox.player.internal.PlayerBundle
import app.thebox.player.internal.PlayerFactoryApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the [PlayerBundle] (ExoPlayer + track selector + load control + selector + audio gate) (§3.8, §6.3, App. A).
 * Needs an `internal` test seam for a `Clock` and `IdlingMediaCodecAdapterFactory` (§5.4, S14).
 */
@Singleton
internal class PlayerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: PlaybackSettingsSource,
) : PlayerFactoryApi {
    override fun create(looper: Looper, softwareAudioFallback: Boolean): PlayerBundle = TODO("WP-C")
}
