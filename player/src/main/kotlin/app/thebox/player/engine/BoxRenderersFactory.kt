package app.thebox.player.engine

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-C. Replace the TODO bodies; keep the names.
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory

/** Extension mode, decoder fallback, [BoxMediaCodecSelector], gated audio sink with the downmix processor (§3.8.1). */
@OptIn(UnstableApi::class)
internal class BoxRenderersFactory(context: Context) : DefaultRenderersFactory(context)
