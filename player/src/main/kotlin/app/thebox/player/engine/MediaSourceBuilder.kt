package app.thebox.player.engine

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-C. Replace the TODO bodies; keep the names.
import app.thebox.core.catchup.ArchiveRequest
import app.thebox.core.playback.StreamFormat
import app.thebox.player.internal.MediaSourceBuilderApi
import app.thebox.player.internal.MediaSourceWithTelemetry
import app.thebox.player.internal.StreamSessionContext
import app.thebox.player.internal.TransportFactory
import app.thebox.player.internal.TsCompat
import javax.inject.Inject
import javax.inject.Singleton

/** `TuneSession` → `MediaSource` (progressive TS, HLS, generic progressive, archive) (§3.1.3). */
@Singleton
internal class MediaSourceBuilder @Inject constructor() : MediaSourceBuilderApi {
    override fun build(
        ctx: StreamSessionContext,
        format: StreamFormat,
        compat: TsCompat,
        transport: TransportFactory,
        archive: ArchiveRequest?,
    ): MediaSourceWithTelemetry = TODO("WP-C")
}
