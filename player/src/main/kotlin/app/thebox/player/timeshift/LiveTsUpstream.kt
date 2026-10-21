// M6 time-shift, WP-B (docs/design/m6-timeshift.md §4.2, A-N1, A-C4).
@file:OptIn(UnstableApi::class)

package app.thebox.player.timeshift

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.thebox.core.playback.timeshift.TsUpstream
import app.thebox.player.datasource.Terminable
import java.io.IOException

/**
 * Adapts the tune's `LiveTsDataSource` (built with `WriterHooks`, `TransportFactory.writer`) to [TsUpstream] for the `box-ts-pump` thread (§4.2).
 *
 * - The source behaves as for the player: one brokered connection, in-source reconnects with the packet-boundary splice
 *   and CC gap, the post-splice PCR hold, and the `ByteWatchdog`. Because the pump is always inside [read], the watchdog
 *   now also covers silence while the player is paused.
 * - A `LiveStreamException` thrown by [read] reaches the writer, which reports it once through
 *   `RingWriterListener.onFailure` (A-C2); the controller classifies it with the writer's tune id (§7.4).
 * - [terminate] (any thread) cancels the call and releases the lease, so a blocked [read] returns at once (A-S1).
 * - [read] never returns 0 or −1: the live source reconnects inside, and an end of input would be a bug, reported as an
 *   `IOException`.
 */
internal class LiveTsUpstream(
    private val source: DataSource,
    private val terminable: Terminable,
    private val sessionUri: Uri,
) : TsUpstream {
    @Volatile private var opened = false

    override fun open() {
        source.open(DataSpec(sessionUri))
        opened = true
    }

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) throw IOException("time-shift upstream: empty read")
        while (true) {
            val n = source.read(dst, off, len)
            if (n > 0) return n
            if (n == C.RESULT_END_OF_INPUT) throw IOException("time-shift upstream: end of input")
        }
    }

    override fun close() {
        opened = false
        try {
            source.close()
        } catch (e: IOException) {
            // close() after a failure: the source already released its call and lease.
        }
    }

    override fun terminate() = terminable.terminate()

    override fun toString(): String = "LiveTsUpstream(opened=$opened)"
}
