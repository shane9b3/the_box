// M6 time-shift, WP-B (docs/design/m6-timeshift.md §5.1, §5.2, A-S9, A-C2).
@file:OptIn(UnstableApi::class)

package app.thebox.player.timeshift

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import app.thebox.core.common.BoxLog
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RingEndException
import app.thebox.core.playback.timeshift.RingException
import app.thebox.core.playback.timeshift.RingIoException
import app.thebox.core.playback.timeshift.RingReader
import app.thebox.core.playback.timeshift.TimeshiftConstants
import app.thebox.player.datasource.SessionScope
import app.thebox.player.datasource.StandaloneScope
import app.thebox.player.datasource.Terminable
import app.thebox.player.engine.MediaSourceBuilder
import app.thebox.player.internal.RingOpen
import java.io.IOException
import java.io.InterruptedIOException

/**
 * The Media3 `DataSource` of a buffered live tune: the player reads the ring through a [RingReader], never the network
 * (§0 item 1, §5.1).
 *
 * - `open(dataSpec)`: opens a reader at [RingOpen.startOffset] of [RingOpen.generation] (a RAP's packet; the reader
 *   injects the segment's PSI first) and blocks until one packet is readable, the ring ends, or the session is cancelled.
 *   Returns `C.LENGTH_UNSET` (a live, unseekable item: no `Range`, no duration read, facts §2). `dataSpec.position` is
 *   ignored and counted ([RingReaderTelemetry.positionIgnoredCount]); Media3 asks only for 0 on an unseekable map.
 * - `read`: whole packets, `n > 0`, **never** `RESULT_END_OF_INPUT`. At the head the reader waits; a boundary, the end
 *   of saving, a position below the tail and a disk error surface as the `Ring*` exceptions, which are all fatal under
 *   `BoxLoadErrorHandlingPolicy` (a Media3 deferred retry would re-anchor the `TimestampAdjuster`, A-S9). Any other
 *   `IOException` from the reader is wrapped in [RingIoException], so every failure is a `RingException` (or the
 *   `InterruptedIOException` of a cancel).
 * - It never rethrows a writer failure (A-C2): before the first byte the writer reports through
 *   `RingWriterListener.onFailure`, and the controller's restart ends this session, whose blocked read then ends.
 * - Cancel: `Loader.cancelLoading` interrupts the loader thread (the reader's wait throws `InterruptedIOException`);
 *   [terminate] (`TuneSession.end()` through the transport scope, A-S9) and [close] cancel the reader first.
 * - [getUri] is the session placeholder; [getResponseHeaders] is empty (S9).
 */
internal class RingDataSource(
    private val tuneId: Long,
    private val ringOpen: RingOpen,
    private val telemetry: RingReaderTelemetry?,
    private val scope: SessionScope = StandaloneScope(),
) : BaseDataSource(/* isNetwork = */ false), Terminable {
    private val sessionUri: Uri = MediaSourceBuilder.sessionUri(tuneId)
    @Volatile private var reader: RingReader? = null
    @Volatile private var cancelled = false
    private var opened = false

    /** The packet read inside [open] (the "blocks until one packet is readable" rule), handed out by the first [read]. */
    private val first = ByteArray(TimeshiftConstants.TS_PACKET)
    private var firstLen = 0
    private var firstPos = 0

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.position != 0L && (telemetry?.positionIgnoredCount?.getAndIncrement() ?: 1) == 0) {
            BoxLog.w(TAG, "tune $tuneId: ring open at position ${dataSpec.position} ignored")
        }
        if (cancelled || scope.isEnded) throw RingEndException(RecordStop.SESSION_END)
        transferInitializing(dataSpec)
        scope.add(this)
        val start = if (ringOpen.firstRap) awaitFirstRap() else ringOpen.startOffset
        val r = try {
            ringOpen.ring.openReader(start, ringOpen.generation)
        } catch (e: RingException) {
            throw e
        } catch (e: IOException) {
            throw RingIoException(e)
        }
        reader = r
        telemetry?.attach(r)
        // A cancel that raced the open: the reader exists now, so cancel it too (its next wait throws at once).
        if (cancelled || scope.isEnded) r.cancel()
        firstLen = readReader(r, first, 0, first.size)
        firstPos = 0
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (firstPos < firstLen) {
            val n = minOf(length, firstLen - firstPos)
            System.arraycopy(first, firstPos, buffer, offset, n)
            firstPos += n
            bytesTransferred(n)
            return n
        }
        val r = reader ?: throw RingEndException(RecordStop.SESSION_END)
        val n = readReader(r, buffer, offset, length)
        bytesTransferred(n)
        return n
    }

    /**
     * (Fix pass — playback, S7) The cycle's first reader opens at the generation's first RAP, so the extractor's
     * `TimestampAdjuster(0)` anchors on that RAP's PTS, which is the controller's origin. Waits in short slices (cancel
     * and interrupt end it) for at most `FIRST_RAP_WAIT_MS`, the ring's end or a generation change; then the start offset.
     */
    private fun awaitFirstRap(): Long {
        val ring = ringOpen.ring
        val e = LongArray(2)
        val deadline = System.nanoTime() + TimeshiftConstants.FIRST_RAP_WAIT_MS * 1_000_000L
        while (true) {
            if (cancelled || scope.isEnded) throw InterruptedIOException("ring open cancelled")
            if (ring.generation != ringOpen.generation) return ringOpen.startOffset // the reader fences itself
            if (ring.index.oldestRapEntry(e) >= 0 && e[0] >= ringOpen.startOffset) return e[0]
            val stop = ring.stop
            if (stop != null && !ring.saving && stop !in TimeshiftSessionImpl.STORAGE_STOPS) return ringOpen.startOffset
            if (System.nanoTime() >= deadline) {
                BoxLog.i(TAG) { "tune $tuneId: no RAP within ${TimeshiftConstants.FIRST_RAP_WAIT_MS} ms; opening at the tail" }
                return ringOpen.startOffset
            }
            try {
                Thread.sleep(FIRST_RAP_POLL_MS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("ring open interrupted")
            }
        }
    }

    private fun readReader(r: RingReader, buffer: ByteArray, offset: Int, length: Int): Int {
        if (cancelled) throw InterruptedIOException("ring read cancelled")
        telemetry?.onReadStarted()
        val n = try {
            r.read(buffer, offset, length)
        } catch (e: RingException) {
            throw e
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            throw RingIoException(e)
        } finally {
            telemetry?.onReadEnded()
        }
        // The reader contract: n > 0 whole packets. Anything else is a reader bug; never end the input.
        if (n <= 0) throw RingIoException(IOException("ring reader returned $n"))
        telemetry?.onBytes(n)
        return n
    }

    override fun getUri(): Uri = sessionUri

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    /** Unpins and closes the reader; idempotent; never touches the writer. */
    override fun close() {
        val r = reader
        reader = null
        telemetry?.attach(null)
        scope.remove(this)
        firstLen = 0
        firstPos = 0
        if (r != null) {
            try {
                r.cancel()
                r.close()
            } catch (t: Throwable) {
                BoxLog.w(TAG, "ring reader close failed", t)
            }
        }
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /** Session end (any thread): a blocked read throws `InterruptedIOException` at once. */
    override fun terminate() {
        cancelled = true
        reader?.cancel()
    }

    private companion object {
        const val TAG = "RingDataSource"
        const val FIRST_RAP_POLL_MS = 10L
    }
}
