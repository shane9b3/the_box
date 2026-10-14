package app.thebox.player.datasource

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-B. Replace the TODO bodies; keep the names.
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec

/** The v1 sink behind the `TeeDataSource` seam (the v2 recorder replaces it). */
@OptIn(UnstableApi::class)
internal class NoOpDataSink : DataSink {
    override fun open(dataSpec: DataSpec): Unit = TODO("WP-B")
    override fun write(buffer: ByteArray, offset: Int, length: Int): Unit = TODO("WP-B")
    override fun close(): Unit = TODO("WP-B")
}
