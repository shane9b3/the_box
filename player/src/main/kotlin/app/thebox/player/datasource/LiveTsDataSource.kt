package app.thebox.player.datasource

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-B. Replace the TODO bodies; keep the names.
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.thebox.player.internal.StreamSessionContext
import okhttp3.Call

/**
 * The reconnecting live TS source (§3.3). Ignores `DataSpec.position`/`length`, never sends `Range`, always returns
 * `C.LENGTH_UNSET`, and releases only whole 188-byte packets. Built as `TeeDataSource(this, sink)` by the factory.
 */
@OptIn(UnstableApi::class)
internal class LiveTsDataSource(
    private val ctx: StreamSessionContext,
    private val callFactory: Call.Factory,
) : BaseDataSource(/* isNetwork = */ true) {
    override fun open(dataSpec: DataSpec): Long = TODO("WP-B")
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = TODO("WP-B")
    override fun getUri(): Uri? = TODO("WP-B")
    override fun close(): Unit = TODO("WP-B")

    class Factory internal constructor(
        private val ctx: StreamSessionContext,
        private val callFactory: Call.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = TODO("WP-B")
    }
}
