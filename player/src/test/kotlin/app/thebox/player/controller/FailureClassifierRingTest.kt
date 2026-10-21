package app.thebox.player.controller

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlaybackException
import app.thebox.core.playback.FailureSignal
import app.thebox.core.playback.LoadOrigin
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RingBoundaryException
import app.thebox.core.playback.timeshift.RingEndException
import app.thebox.core.playback.timeshift.RingIoException
import app.thebox.core.playback.timeshift.SegmentReason
import app.thebox.player.datasource.FailureInfo
import app.thebox.player.datasource.LiveFailureKind
import app.thebox.player.datasource.LiveStreamException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** §17.2 `FailureClassifierTest` additions (M6): the writer's tune id, and the ring rows ahead of the live rows. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FailureClassifierRingTest {
    private val classifier = FailureClassifier()
    private val facts = FailureClassifier.Facts(currentTuneId = 7, sessionStartMs = SystemClock.elapsedRealtime(), writerTuneId = 9)

    private fun live(kind: LiveFailureKind, tuneId: Long, status: Int? = null) = LiveStreamException(
        kind, FailureInfo(status, "h", 10, 1, FailureInfo.Phase.OPEN, null, null, 0), DataSpec(Uri.parse("thebox-session://$tuneId")), null,
    )

    private fun source(cause: IOException) = ExoPlaybackException.createForSource(cause, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

    @Test
    fun theWritersTuneIdIsNotStale() {
        assertEquals(FailureSignal.Http(403, LoadOrigin.PROGRESSIVE), classifier.classifyLive(live(LiveFailureKind.HTTP_STATUS, 9, 403), facts))
        assertEquals(FailureSignal.Http(403, LoadOrigin.PROGRESSIVE), classifier.classifyLive(live(LiveFailureKind.HTTP_STATUS, 7, 403), facts))
        assertNull("another tune's error is stale", classifier.classifyLive(live(LiveFailureKind.HTTP_STATUS, 8, 403), facts))
        assertNull("without a writer, the writer's id is stale", classifier.classifyLive(live(LiveFailureKind.HTTP_STATUS, 9, 403), facts.copy(writerTuneId = null)))
        assertNull(classifier.classifyLive(live(LiveFailureKind.PREEMPTED, 9), facts))
    }

    @Test
    fun ringRowsComeBeforeTheLiveRows() {
        // A RingEndException carries the writer's LiveStreamException: never read as this reader's 403.
        val end = RingEndException(RecordStop.UPSTREAM_FAILED, live(LiveFailureKind.HTTP_STATUS, 9, 403))
        val signal = classifier.classify(source(end), facts)
        assertTrue("got $signal", signal is FailureSignal.Unknown)
        assertEquals(end, FailureClassifier.ringException(source(end)))

        assertEquals(
            FailureSignal.RingBoundary(4_000, 1_500),
            classifier.classify(source(RingBoundaryException(4_000, SegmentReason.PCR_JUMP, 1_500, 0)), facts),
        )
        assertNull("SESSION_END: the reader's tune ended", classifier.classify(source(RingEndException(RecordStop.SESSION_END)), facts))
        assertTrue(classifier.classify(source(RingIoException(IOException("EIO"))), facts) is FailureSignal.Unknown)
    }

    /** Lead reconciliation R2 (§3.5, §7.4): a segment storm is the writer's PcrDiscontinuity; other writer errors are not. */
    @Test
    fun aSegmentStormIsAWriterPcrDiscontinuity() {
        val storm = app.thebox.core.playback.timeshift.SegmentStormException(6, 60_000)
        assertEquals(FailureSignal.PcrDiscontinuity(0L), classifier.classifyWriter(storm))
        assertEquals(FailureSignal.PcrDiscontinuity(0L), classifier.classifyWriter(IOException("wrapped", storm)))
        assertNull(classifier.classifyWriter(IOException("reset")))
        assertNull(classifier.classifyWriter(live(LiveFailureKind.NETWORK, tuneId = 9)))
    }
}
