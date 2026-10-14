package app.thebox.player.datasource

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.Loader
import app.thebox.core.playback.LoadOrigin
import app.thebox.core.playback.LoadRetryRules
import app.thebox.core.playback.errors.LoadRetryRulesImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToLong

/**
 * §5.3 WP-B `BoxLoadErrorHandlingPolicy` with real `LoadErrorInfo` objects (§4.2). The numbers come from WP-A's
 * `LoadRetryRulesImpl` once it lands; until then a reference implementation of the §4.2 table stands in, so the same
 * assertions run against the real rules after the merge.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoxLoadErrorHandlingPolicyTest {

    /** §4.2 as written: non-retryable statuses, HLS segment 404 ×2 at 500 ms, 500/1000/2000 ms ±20 % ×3. */
    private object ReferenceRules : LoadRetryRules {
        private val base = longArrayOf(500, 1_000, 2_000)
        override fun retryDelayMs(status: Int?, origin: LoadOrigin, transient: Boolean, errorCount: Int, random: Double): Long? {
            if (status != null) {
                return when {
                    status in setOf(401, 403, 405, 406, 410, 416, 451, 458, 509) -> null
                    status == 404 -> if (origin == LoadOrigin.HLS_SEGMENT && errorCount <= 2) 500L else null
                    status == 408 || status == 429 || status in 500..599 -> backoff(errorCount, random)
                    else -> null
                }
            }
            return if (transient) backoff(errorCount, random) else null
        }
        private fun backoff(n: Int, r: Double): Long? = if (n !in 1..3) null else (base[n - 1] * (1 + 0.2 * (2 * r - 1))).roundToLong()
    }

    private val rules: LoadRetryRules = try {
        LoadRetryRulesImpl().also { it.retryDelayMs(500, LoadOrigin.PROGRESSIVE, false, 1, 0.5) }
    } catch (e: NotImplementedError) {
        ReferenceRules
    }

    private class Recorder(val delegate: LoadRetryRules) : LoadRetryRules {
        val calls = CopyOnWriteArrayList<List<Any?>>()
        override fun retryDelayMs(status: Int?, origin: LoadOrigin, transient: Boolean, errorCount: Int, random: Double): Long? {
            calls += listOf(status, origin, transient, errorCount)
            return delegate.retryDelayMs(status, origin, transient, errorCount, random)
        }
    }

    private val spec = DataSpec.Builder().setUri("https://panel.example/hls/1/seg.ts").build()

    private fun info(e: IOException, dataType: Int = C.DATA_TYPE_MEDIA, errorCount: Int = 1) =
        LoadErrorHandlingPolicy.LoadErrorInfo(LoadEventInfo(1, spec, 0), MediaLoadData(dataType), e, errorCount)

    private fun status(code: Int) = HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), spec, ByteArray(0))

    private fun policy(origin: LoadOrigin, random: Double = 0.5) = BoxLoadErrorHandlingPolicy(origin, rules) { random }

    private fun live(kind: LiveFailureKind) = LiveStreamException(
        kind, FailureInfo(null, null, 0, 0, FailureInfo.Phase.READ, null, null, 0), DataSpec.Builder().setUri("thebox-session://1").build(), null,
    )

    @Test
    fun everyLiveStreamExceptionIsFatalTheSourceAlreadyRetried() {
        val p = policy(LoadOrigin.PROGRESSIVE)
        for (kind in LiveFailureKind.entries) {
            assertEquals(kind.name, C.TIME_UNSET, p.getRetryDelayMsFor(info(live(kind))))
        }
        // Also when Media3 wraps it.
        assertEquals(C.TIME_UNSET, p.getRetryDelayMsFor(info(IOException(live(LiveFailureKind.NETWORK)))))
    }

    @Test
    fun clientErrorsAndLimitStatusesAreNotRetriedOnAnyOrigin() {
        for (origin in listOf(LoadOrigin.PROGRESSIVE, LoadOrigin.HLS_SEGMENT, LoadOrigin.ARCHIVE)) {
            for (code in listOf(401, 403, 404, 405, 406, 410, 451, 458, 509)) {
                for (dataType in listOf(C.DATA_TYPE_MEDIA, C.DATA_TYPE_MANIFEST)) {
                    if (code == 404 && origin == LoadOrigin.HLS_SEGMENT && dataType == C.DATA_TYPE_MEDIA) continue
                    assertEquals("$code $origin $dataType", C.TIME_UNSET, policy(origin).getRetryDelayMsFor(info(status(code), dataType)))
                }
            }
        }
    }

    @Test
    fun hlsSegment404IsRetriedTwiceAt500MsButAManifest404IsNot() {
        val p = policy(LoadOrigin.HLS_SEGMENT)
        assertEquals(500L, p.getRetryDelayMsFor(info(status(404), C.DATA_TYPE_MEDIA, errorCount = 1)))
        assertEquals(500L, p.getRetryDelayMsFor(info(status(404), C.DATA_TYPE_MEDIA, errorCount = 2)))
        assertEquals(C.TIME_UNSET, p.getRetryDelayMsFor(info(status(404), C.DATA_TYPE_MEDIA, errorCount = 3)))
        assertEquals(C.TIME_UNSET, p.getRetryDelayMsFor(info(status(404), C.DATA_TYPE_MANIFEST, errorCount = 1)))
        // An HLS policy built with the manifest origin behaves the same: the data type decides.
        assertEquals(500L, policy(LoadOrigin.HLS_MANIFEST).getRetryDelayMsFor(info(status(404), C.DATA_TYPE_MEDIA)))
        // A progressive or archive 404 is never retried.
        assertEquals(C.TIME_UNSET, policy(LoadOrigin.PROGRESSIVE).getRetryDelayMsFor(info(status(404))))
        assertEquals(C.TIME_UNSET, policy(LoadOrigin.ARCHIVE).getRetryDelayMsFor(info(status(404))))
    }

    @Test
    fun manifestVersusSegmentComesFromTheDataTypeInsideHls() {
        val recorder = Recorder(rules)
        val p = BoxLoadErrorHandlingPolicy(LoadOrigin.HLS_SEGMENT, recorder) { 0.5 }
        p.getRetryDelayMsFor(info(status(500), C.DATA_TYPE_MANIFEST))
        p.getRetryDelayMsFor(info(status(500), C.DATA_TYPE_MEDIA))
        p.getRetryDelayMsFor(info(status(500), C.DATA_TYPE_MEDIA_INITIALIZATION))
        p.getRetryDelayMsFor(info(status(500), C.DATA_TYPE_DRM))
        assertEquals(
            listOf(LoadOrigin.HLS_MANIFEST, LoadOrigin.HLS_SEGMENT, LoadOrigin.HLS_SEGMENT, LoadOrigin.HLS_SEGMENT),
            recorder.calls.map { it[1] },
        )
        // Progressive errors all arrive as DATA_TYPE_MEDIA: the session family decides (S3).
        assertEquals(LoadOrigin.PROGRESSIVE, policy(LoadOrigin.PROGRESSIVE).originFor(C.DATA_TYPE_MEDIA))
        assertEquals(LoadOrigin.ARCHIVE, policy(LoadOrigin.ARCHIVE).originFor(C.DATA_TYPE_MANIFEST))
    }

    @Test
    fun serverErrorsAndIoFailuresFollowTheJitteredSchedule() {
        val schedule = listOf(500L, 1_000L, 2_000L)
        val failures = listOf<IOException>(status(500), status(503), status(522), status(429), status(408), SocketTimeoutException("read"), IOException("reset"))
        for (e in failures) {
            for ((i, base) in schedule.withIndex()) {
                val low = policy(LoadOrigin.HLS_SEGMENT, random = 0.0).getRetryDelayMsFor(info(e, errorCount = i + 1))
                val high = policy(LoadOrigin.HLS_SEGMENT, random = 1.0).getRetryDelayMsFor(info(e, errorCount = i + 1))
                assertTrue("$e #${i + 1}: $low", low in (base * 0.8).toLong()..base)
                assertTrue("$e #${i + 1}: $high", high in base..(base * 1.2).toLong())
            }
            assertEquals("$e after 3", C.TIME_UNSET, policy(LoadOrigin.HLS_SEGMENT).getRetryDelayMsFor(info(e, errorCount = 4)))
        }
        // Archive transport errors back off the same way.
        assertTrue(policy(LoadOrigin.ARCHIVE).getRetryDelayMsFor(info(IOException("reset"))) in 400L..600L)
    }

    @Test
    fun whatThePolicyHandsTheRules() {
        val recorder = Recorder(rules)
        val p = BoxLoadErrorHandlingPolicy(LoadOrigin.ARCHIVE, recorder) { 0.25 }
        p.getRetryDelayMsFor(info(status(503), errorCount = 2))
        p.getRetryDelayMsFor(info(IOException(SocketTimeoutException("t")), errorCount = 1))
        assertEquals(listOf(503, LoadOrigin.ARCHIVE, false, 2), recorder.calls[0])
        assertEquals(listOf(null, LoadOrigin.ARCHIVE, true, 1), recorder.calls[1])
    }

    @Test
    fun mediaThreesNonRetriableCausesStayFatal() {
        val p = policy(LoadOrigin.HLS_SEGMENT)
        val cases = listOf(
            ParserException.createForMalformedContainer("bad", null),
            FileNotFoundException("x"),
            HttpDataSource.CleartextNotPermittedException(IOException("cleartext"), spec),
            Loader.UnexpectedLoaderException(IllegalStateException("bug")),
            HttpDataSource.InvalidResponseCodeException(416, null, DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE), emptyMap(), spec, ByteArray(0)),
        )
        for (e in cases) assertEquals(e.toString(), C.TIME_UNSET, p.getRetryDelayMsFor(info(e)))
    }

    @Test
    fun retryCountsAndFallback() {
        val p = policy(LoadOrigin.PROGRESSIVE)
        assertEquals(1, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE))
        assertEquals(3, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        assertEquals(3, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST))
        val options = LoadErrorHandlingPolicy.FallbackOptions(2, 0, 3, 0, false)
        assertNull(p.getFallbackSelectionFor(options, info(status(403))))
    }
}
