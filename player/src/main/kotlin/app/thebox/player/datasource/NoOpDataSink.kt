package app.thebox.player.datasource

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec

/**
 * The v1 sink behind the `TeeDataSource` seam (the v2 recorder replaces it through `StreamSessionContext.sinkFactory`).
 * It receives the same continuous, packet-aligned TS the extractor gets and discards it.
 */
@OptIn(UnstableApi::class)
internal class NoOpDataSink : DataSink {
    override fun open(dataSpec: DataSpec) = Unit
    override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
    override fun close() = Unit
}
