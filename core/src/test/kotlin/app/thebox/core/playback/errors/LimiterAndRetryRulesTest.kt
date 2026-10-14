package app.thebox.core.playback.errors

import app.thebox.core.model.StableKey
import app.thebox.core.playback.LoadOrigin
import app.thebox.core.refresh.ChannelIdentity
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReResolveLimiterTest {
    private val a = ChannelIdentity(1, StableKey("xc:1"))
    private val b = ChannelIdentity(1, StableKey("xc:2"))

    @Test
    fun twoPerSlidingMinutePerChannel() {
        val l = ReResolveLimiterImpl()
        assertTrue(l.tryAcquire(a, 0))
        assertTrue(l.tryAcquire(a, 10_000))
        assertFalse(l.tryAcquire(a, 59_999))
        assertTrue(l.tryAcquire(b, 59_999), "other channel has its own window")
        assertTrue(l.tryAcquire(a, 60_000), "the first stamp slid out")
        assertFalse(l.tryAcquire(a, 69_999))
        assertTrue(l.tryAcquire(a, 70_000))
    }

    @Test
    fun nullKeyIsOneBucket() {
        val l = ReResolveLimiterImpl()
        assertTrue(l.tryAcquire(null, 0))
        assertTrue(l.tryAcquire(null, 1))
        assertFalse(l.tryAcquire(null, 2))
    }

    @Test
    fun wouldAllowPeeksAndResetClears() {
        val l = ReResolveLimiterImpl()
        assertTrue(l.wouldAllow(a, 0))
        l.tryAcquire(a, 0)
        l.tryAcquire(a, 1)
        assertFalse(l.wouldAllow(a, 2))
        assertFalse(l.wouldAllow(a, 2), "peeking does not consume")
        l.reset(a)
        assertTrue(l.wouldAllow(a, 3))
        assertTrue(l.tryAcquire(a, 3))
    }
}

class LoadRetryRulesTest {
    private val r = LoadRetryRulesImpl()
    private val mid = 0.5

    @ParameterizedTest
    @CsvSource("401", "403", "405", "406", "410", "451", "458", "509", "416", "400")
    fun nonRetryableStatuses(status: Int) {
        for (o in LoadOrigin.entries) assertNull(r.retryDelayMs(status, o, transient = false, errorCount = 1, random = mid), "$status $o")
    }

    @Test
    fun hlsSegment404RetriesTwiceAt500() {
        assertEquals(500L, r.retryDelayMs(404, LoadOrigin.HLS_SEGMENT, false, 1, 0.0))
        assertEquals(500L, r.retryDelayMs(404, LoadOrigin.HLS_SEGMENT, false, 2, 1.0))
        assertNull(r.retryDelayMs(404, LoadOrigin.HLS_SEGMENT, false, 3, mid))
        for (o in listOf(LoadOrigin.HLS_MANIFEST, LoadOrigin.PROGRESSIVE, LoadOrigin.ARCHIVE)) assertNull(r.retryDelayMs(404, o, false, 1, mid))
    }

    @ParameterizedTest
    @CsvSource("408", "429", "500", "502", "503", "504", "520", "524", "599")
    fun transientStatusesBackOff(status: Int) {
        assertEquals(500L, r.retryDelayMs(status, LoadOrigin.PROGRESSIVE, false, 1, mid))
        assertEquals(1_000L, r.retryDelayMs(status, LoadOrigin.HLS_MANIFEST, false, 2, mid))
        assertEquals(2_000L, r.retryDelayMs(status, LoadOrigin.HLS_SEGMENT, false, 3, mid))
        assertNull(r.retryDelayMs(status, LoadOrigin.ARCHIVE, false, 4, mid))
    }

    @Test
    fun jitterBoundsAtRandomZeroAndOne() {
        assertEquals(400L, r.retryDelayMs(500, LoadOrigin.ARCHIVE, false, 1, 0.0))
        assertEquals(600L, r.retryDelayMs(500, LoadOrigin.ARCHIVE, false, 1, 1.0))
        assertEquals(800L, r.retryDelayMs(500, LoadOrigin.ARCHIVE, false, 2, 0.0))
        assertEquals(1_200L, r.retryDelayMs(500, LoadOrigin.ARCHIVE, false, 2, 1.0))
        assertEquals(1_600L, r.retryDelayMs(null, LoadOrigin.HLS_SEGMENT, true, 3, 0.0))
        assertEquals(2_400L, r.retryDelayMs(null, LoadOrigin.HLS_SEGMENT, true, 3, 1.0))
    }

    @Test
    fun ioExceptionsByOrigin() {
        for (o in listOf(LoadOrigin.HLS_MANIFEST, LoadOrigin.HLS_SEGMENT, LoadOrigin.ARCHIVE)) {
            assertEquals(500L, r.retryDelayMs(null, o, transient = true, errorCount = 1, random = mid))
            assertNull(r.retryDelayMs(null, o, transient = true, errorCount = 4, random = mid))
            assertNull(r.retryDelayMs(null, o, transient = false, errorCount = 1, random = mid), "parser/cleartext/FNF/unexpected never retry")
        }
        assertNull(r.retryDelayMs(null, LoadOrigin.PROGRESSIVE, true, 1, mid), "live TS retries inside the data source")
    }

    @Test
    fun errorCountBelowOneIsTreatedAsFirst() {
        assertEquals(500L, r.retryDelayMs(500, LoadOrigin.ARCHIVE, false, 0, mid))
    }
}
