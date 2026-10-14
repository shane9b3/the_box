package app.thebox.player.datasource

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-B. Replace the TODO bodies; keep the names.
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import app.thebox.player.internal.StreamSessionContext
import okhttp3.Call

/** HLS and archive (finite) HTTP sources over the brokered call factory (§3.4). Range allowed, no reconnect. */
@OptIn(UnstableApi::class)
internal class StreamHttpDataSourceFactory(
    private val ctx: StreamSessionContext,
    private val callFactory: Call.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = TODO("WP-B")
}
