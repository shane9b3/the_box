package app.thebox.player.engine

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-C. Replace the TODO bodies; keep the names.
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.upstream.Allocator
import app.thebox.core.playback.BufferProfile
import app.thebox.player.internal.AdaptiveLoadControlHandle

/** Delegates the allocator to a `DefaultLoadControl`; thresholds from a `@Volatile` [profile] (§3.6.3). */
@OptIn(UnstableApi::class)
internal class AdaptiveLoadControl : LoadControl, AdaptiveLoadControlHandle {
    override var profile: BufferProfile
        get() = TODO("WP-C")
        set(@Suppress("UNUSED_PARAMETER") value) = TODO("WP-C")

    override fun getAllocator(playerId: PlayerId): Allocator = TODO("WP-C")
}
