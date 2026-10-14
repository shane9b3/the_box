package app.thebox.player.datasource

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-B. Replace the TODO bodies; keep the names.
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import app.thebox.core.playback.MonoClock
import app.thebox.player.internal.StreamSessionContext
import app.thebox.player.internal.StreamTelemetry
import app.thebox.player.internal.TransportFactory
import javax.inject.Inject
import javax.inject.Singleton

/** WP-B's [TransportFactory] (§2.5, bound by `PlaybackModule`, §2.8). */
@OptIn(UnstableApi::class)
@Singleton
internal class TransportFactoryImpl @Inject constructor(
    private val clients: PlaybackHttpClients,
    private val watchdog: ByteWatchdog,
    private val clock: MonoClock,
) : TransportFactory {
    override fun liveTs(ctx: StreamSessionContext): Pair<DataSource.Factory, StreamTelemetry> = TODO("WP-B")
    override fun http(ctx: StreamSessionContext): Pair<DataSource.Factory, StreamTelemetry> = TODO("WP-B")
    override fun endSession(tuneId: Long): Unit = TODO("WP-B")
}
