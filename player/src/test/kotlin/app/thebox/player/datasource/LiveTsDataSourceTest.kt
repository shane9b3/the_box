package app.thebox.player.datasource

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.HttpDataSource
import app.thebox.core.playback.ConnectionLimitException
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.NetworkFailureKind
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.StreamFormat
import app.thebox.player.internal.StreamSessionContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.OkHttpClient
import okio.Buffer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * §5.3 WP-B `LiveTsDataSource` against `mockwebserver3`: no `Range` ever, packet-aligned reconnects with fresh
 * redirects, typed failures, the byte watchdog, the PCR hold, close/pre-emption order and lease release.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveTsDataSourceTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private val executor = Executors.newCachedThreadPool()
    private val toRelease = CopyOnWriteArrayList<LiveBody>()

    @Before fun setUp() { server = MockWebServer(); server.start() }

    @After fun tearDown() {
        toRelease.forEach { it.release() }
        executor.shutdownNow()
        server.close()
    }

    private val liveUrl get() = server.url("/live/user/pass/1.ts").toString()

    private fun source(
        ctx: StreamSessionContext,
        clock: MonoClock = REAL_CLOCK,
        timing: LiveTsDataSource.Timing = fastTiming(),
        watchdog: ByteWatchdog? = null,
        telemetry: SessionTelemetry = SessionTelemetry(clock),
        callFactory: Call.Factory = client,
        scope: SessionScope = StandaloneScope(),
    ) = LiveTsDataSource(ctx, callFactory, telemetry, watchdog, clock, timing, scope)

    private fun requests(): List<RecordedRequest> = generateSequence { server.takeRequest(10, TimeUnit.MILLISECONDS) }.toList()

    private fun assertNoRange(reqs: List<RecordedRequest>) {
        assertTrue("no requests recorded", reqs.isNotEmpty())
        for (r in reqs) assertNull("Range on ${r.target}", r.headers["Range"])
    }

    private fun assertNoUrl(e: LiveStreamException) {
        val text = e.toString() + " " + e.dataSpec.uri
        for (secret in listOf("user", "pass", "/live", "token", server.hostName, "http://")) {
            assertFalse("'$secret' leaked in: $text", text.contains(secret))
        }
        assertEquals(LiveTsDataSource.SESSION_SCHEME, e.dataSpec.uri.scheme)
    }

    private inline fun expectLive(block: () -> Unit): LiveStreamException {
        try {
            block()
        } catch (e: LiveStreamException) {
            return e
        }
        fail("expected a LiveStreamException")
        throw AssertionError()
    }

    /** Reads until the source throws; returns the bytes read and the failure. */
    private fun readUntilFailure(ds: LiveTsDataSource): Pair<ByteArray, LiveStreamException> {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = try {
                ds.read(buf, 0, buf.size)
            } catch (e: LiveStreamException) {
                return out.toByteArray() to e
            }
            assertTrue("live source returned $n", n > 0)
            out.write(buf, 0, n)
            if (out.size() > 10_000_000) fail("no failure")
        }
    }

    // ------------------------------------------------------------------ Range, length, URI

    @Test
    fun neverSendsRangeEvenWhenOpenedAtAPositionAndReturnsLengthUnsetDespiteContentLength() {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(FIXTURE)).build()) // with Content-Length
        val telemetry = SessionTelemetry(REAL_CLOCK)
        val ds = source(sessionCtx(liveUrl), telemetry = telemetry)
        assertEquals(C.LENGTH_UNSET.toLong(), ds.open(sessionSpec(position = 5_000)))
        assertEquals(1, telemetry.positionIgnoredCount.get())
        assertArrayEquals(FIXTURE, readExactly(ds, FIXTURE.size))
        ds.close()
        val reqs = requests()
        assertNoRange(reqs)
        assertEquals("/live/user/pass/1.ts", reqs.single().target)
        assertEquals(listOf("TestUA/1.0"), reqs.single().headers.values("User-Agent"))
        assertEquals(StreamFormat.TS, telemetry.sniffed)
        assertEquals(FIXTURE.size.toLong(), telemetry.bytesTotal)
    }

    @Test
    fun aRangeHeaderInTheResolvedHeadersIsDropped() {
        server.enqueue(liveResponse(FIXTURE))
        val ds = source(sessionCtx(liveUrl, headers = mapOf("User-Agent" to "UA", "range" to "bytes=5-")))
        ds.open(sessionSpec())
        readExactly(ds, PKT)
        ds.close()
        assertNoRange(requests())
    }

    @Test
    fun uriIsTheSessionPlaceholderAndResponseHeadersAreEmpty() {
        server.enqueue(liveResponse(FIXTURE))
        val ds = source(sessionCtx(liveUrl, tuneId = 42))
        ds.open(sessionSpec(42))
        assertEquals("thebox-session://42", ds.uri.toString())
        assertTrue(ds.responseHeaders.isEmpty())
        ds.close()
    }

    // ------------------------------------------------------------------ reconnect continuity

    @Test
    fun serverCloseMidStreamReconnectsOnceWithAPacketAlignedSplice() {
        val cut = 50_000 // 265 whole packets + 180 bytes
        val resume = (cut / PKT + 1) * PKT
        server.enqueue(liveResponse(FIXTURE.copyOf(cut)))
        server.enqueue(liveResponse(FIXTURE.copyOfRange(resume, FIXTURE.size)))
        val events = EventRecorder()
        val telemetry = SessionTelemetry(REAL_CLOCK)
        val ds = source(sessionCtx(liveUrl, events = events), telemetry = telemetry)
        ds.open(sessionSpec())
        val expected = FIXTURE.copyOf(cut / PKT * PKT) + FIXTURE.copyOfRange(resume, FIXTURE.size)
        val got = readExactly(ds, expected.size, readLen = 7_000)
        ds.close()
        assertArrayEquals(maskCc(expected), maskCc(got))
        val reqs = requests()
        assertEquals(2, reqs.size)
        assertNoRange(reqs)
        reqs.forEach { assertEquals("/live/user/pass/1.ts", it.target) }
        assertEquals(1, telemetry.reconnects)
        assertFalse(telemetry.inReconnect)
        assertEquals(1, events.count(PlaybackEventType.RECONNECT))
        assertEquals(1, events.count(PlaybackEventType.RECONNECT_OK))
        assertEquals(0, events.count(PlaybackEventType.PCR_JUMP))
    }

    /** A body cut short of its Content-Length surfaces as an IOException mid-read (not EOF); same splice, no Range. */
    @Test
    fun abruptDisconnectMidBodyReconnectsWithoutRange() {
        val cut = 30_001
        val resume = (cut / PKT + 1) * PKT
        server.enqueue(liveResponse(TruncatedBody(FIXTURE.copyOf(cut), declared = FIXTURE.size.toLong())).newBuilder()
            .setHeader("Content-Length", FIXTURE.size).build())
        server.enqueue(liveResponse(FIXTURE.copyOfRange(resume, FIXTURE.size)))
        val ds = source(sessionCtx(liveUrl))
        ds.open(sessionSpec())
        val expected = FIXTURE.copyOf(cut / PKT * PKT) + FIXTURE.copyOfRange(resume, FIXTURE.size)
        assertArrayEquals(maskCc(expected), maskCc(readExactly(ds, expected.size)))
        ds.close()
        val reqs = requests()
        assertEquals(2, reqs.size)
        assertNoRange(reqs)
    }

    @Test
    fun theTeeSinkReceivesTheSameAlignedBytesAsTheReader() {
        val cut = 40_000
        val resume = (cut / PKT + 2) * PKT
        server.enqueue(liveResponse(FIXTURE.copyOf(cut)))
        server.enqueue(liveResponse(FIXTURE.copyOfRange(resume, FIXTURE.size)))
        val sink = RecordingSink()
        val ctx = sessionCtx(liveUrl, sink = DataSink.Factory { sink })
        val ds = LiveTsDataSource.Factory(ctx, client, SessionTelemetry(REAL_CLOCK), null, REAL_CLOCK, fastTiming()).createDataSource()
        assertEquals(C.LENGTH_UNSET.toLong(), ds.open(sessionSpec()))
        val total = cut / PKT * PKT + FIXTURE.size - resume
        val read = readExactly(ds, total, readLen = 1_000)
        ds.close()
        assertEquals(1, sink.opened)
        assertEquals(1, sink.closed)
        assertArrayEquals(read, sink.bytes.toByteArray())
        assertEquals("thebox-session://7", ds.uri.toString())
    }

    @Test
    fun everyReconnectFollowsTheRedirectAgainAndGetsAFreshToken() {
        val cut = 20_000
        val resume = (cut / PKT + 1) * PKT
        val tokens = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/live/user/pass/1.ts" -> MockResponse.Builder().code(302)
                    .addHeader("Location", "/tok?token=" + (if (tokens.getAndIncrement() == 0) "a" else "b")).build()
                "/tok?token=a" -> liveResponse(FIXTURE.copyOf(cut))
                "/tok?token=b" -> liveResponse(FIXTURE.copyOfRange(resume, FIXTURE.size))
                else -> MockResponse.Builder().code(404).build()
            }
        }
        val telemetry = SessionTelemetry(REAL_CLOCK)
        val ds = source(sessionCtx(liveUrl), telemetry = telemetry)
        ds.open(sessionSpec())
        readExactly(ds, cut / PKT * PKT + FIXTURE.size - resume)
        ds.close()
        val targets = requests().map { it.target }
        assertEquals(listOf("/live/user/pass/1.ts", "/tok?token=a", "/live/user/pass/1.ts", "/tok?token=b"), targets)
        assertEquals(2, telemetry.redirects)
        assertEquals("${server.hostName}:${server.port}", telemetry.finalHost)
        assertEquals(200, telemetry.httpStatus)
    }

    @Test
    fun anExpiredTokenOnEveryReconnectExhaustsTheBudgetWithLastStatus403() {
        val token = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.target.startsWith("/live/") ->
                    MockResponse.Builder().code(302).addHeader("Location", "/tok?token=t${token.incrementAndGet()}").build()
                request.target == "/tok?token=t1" -> liveResponse(FIXTURE.copyOf(10_000))
                else -> MockResponse.Builder().code(403).body("expired").build()
            }
        }
        val clock = FakeClock()
        val sleeper = RecordingSleeper(clock)
        val ds = source(sessionCtx(liveUrl), clock = clock, timing = fastTiming(sleeper))
        ds.open(sessionSpec())
        val (bytes, e) = readUntilFailure(ds)
        ds.close()
        assertEquals(10_000 / PKT * PKT, bytes.size)
        assertEquals(LiveFailureKind.RECONNECT_EXHAUSTED, e.kind)
        assertEquals(403, e.info.status)
        assertEquals(LiveTsDataSource.RECONNECT_MAX_ATTEMPTS, e.info.attempt)
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, e.reason)
        assertEquals("live reconnect_exhausted 403", e.message)
        assertNoUrl(e)
        // EOF → immediate first attempt; each 403 waits RECONNECT_LIMIT_STATUS_DELAY_MS.
        assertEquals(listOf(1_000L, 1_000L), sleeper.delays.toList())
        val targets = requests().map { it.target }
        assertEquals(4, targets.count { it.startsWith("/live/") })
        assertEquals(targets.filter { it.startsWith("/tok") }.distinct().size, targets.count { it.startsWith("/tok") })
    }

    // ------------------------------------------------------------------ first-open failures

    @Test
    fun status403OnTheFirstOpenFailsAtOnceWithoutRetry() {
        server.enqueue(MockResponse.Builder().code(403).body("<html>no</html>").build())
        val broker = FakeBroker()
        val ds = source(sessionCtx(liveUrl, broker = broker))
        val e = expectLive { ds.open(sessionSpec()) }
        ds.close()
        assertEquals(LiveFailureKind.HTTP_STATUS, e.kind)
        assertEquals(403, e.info.status)
        assertEquals(FailureInfo.Phase.OPEN, e.info.phase)
        assertEquals(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, e.reason)
        assertEquals(HttpDataSource.HttpDataSourceException.TYPE_OPEN, e.type)
        assertEquals("live http_status 403", e.message)
        assertNoUrl(e)
        assertEquals(1, server.requestCount)
        assertEquals(1, broker.lease.releaseCount.get())
        assertEquals(broker.lease.attachCount.get(), broker.lease.detachCount.get())
    }

    @Test
    fun nonTsBodiesBecomeTypedFailuresWithoutRetry() {
        val cases = listOf(
            "http/splash.html" to LiveFailureKind.HTML_PAGE,
            "http/tiny.bin" to LiveFailureKind.TINY_BODY,
            "http/hls_live.m3u8" to LiveFailureKind.FORMAT_IS_HLS,
            "http/mp4_head.bin" to LiveFailureKind.FORMAT_UNRECOGNIZED,
        )
        for ((fixture, kind) in cases) {
            server.enqueue(liveResponse(res(fixture)))
            val events = EventRecorder()
            val telemetry = SessionTelemetry(REAL_CLOCK)
            val ds = source(sessionCtx(liveUrl, events = events), telemetry = telemetry)
            val e = expectLive { ds.open(sessionSpec()) }
            ds.close()
            assertEquals(fixture, kind, e.kind)
            assertEquals(fixture, FailureInfo.Phase.SNIFF, e.info.phase)
            assertEquals(fixture, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, e.reason)
            assertNull(fixture, e.cause)
            assertNoUrl(e)
            assertEquals(fixture, 1, events.count(PlaybackEventType.SNIFF))
            if (kind == LiveFailureKind.TINY_BODY) assertEquals(200L, e.info.bytesRead)
            if (kind == LiveFailureKind.FORMAT_IS_HLS) assertEquals(StreamFormat.HLS, telemetry.sniffed)
        }
        assertEquals(cases.size, server.requestCount)
    }

    @Test
    fun unknownHostAtOpenIsNetworkDnsFromTheRootCause() {
        val noDns = client.newBuilder().dns { throw UnknownHostException("test: no such host") }.build()
        val ds = source(sessionCtx("http://panel.invalid/live/u/p/1.ts"), callFactory = noDns)
        val e = expectLive { ds.open(sessionSpec()) }
        ds.close()
        assertEquals(LiveFailureKind.NETWORK, e.kind)
        assertEquals(NetworkFailureKind.DNS, e.info.networkKind)
        assertTrue(e.cause is UnknownHostException)
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, e.reason)
        assertNoUrl(e)
    }

    @Test
    fun aRefusedLeaseIsLocalLimitWithTheBrokerFacts() {
        val broker = FakeBroker().apply { refuse = ConnectionLimitException(1, 1) }
        val telemetry = SessionTelemetry(REAL_CLOCK)
        val ds = source(sessionCtx(liveUrl, broker = broker), telemetry = telemetry)
        val e = expectLive { ds.open(sessionSpec()) }
        ds.close()
        assertEquals(LiveFailureKind.LOCAL_LIMIT, e.kind)
        val limit = e.suppressed.single() as ConnectionLimitException
        assertEquals(1, limit.max)
        assertFalse(telemetry.waitingForLease)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun reopenAfterCloseIsAFreshConnectWithANewLease() {
        server.enqueue(liveResponse(FIXTURE))
        server.enqueue(liveResponse(FIXTURE))
        val broker = FakeBroker()
        val ds = source(sessionCtx(liveUrl, broker = broker))
        ds.open(sessionSpec())
        readExactly(ds, 10 * PKT)
        ds.close()
        ds.open(sessionSpec())
        readExactly(ds, 10 * PKT)
        ds.close()
        assertEquals(2, broker.leases.size)
        broker.leases.forEach { assertEquals(1, it.releaseCount.get()) }
    }

    // ------------------------------------------------------------------ byte watchdog

    @Test
    fun aSilentServerIsCancelledAfterTheNoBytesTimeoutAndReconnected() {
        val clock = FakeClock()
        val watchdog = ByteWatchdog(clock).apply { autoStart = false }
        val headerPackets = FIXTURE.copyOf(3 * PKT) // PAT, PMT, SDT: no PCR yet, so no hold after the splice
        val stalled = LiveBody(headerPackets, stall = true).also { toRelease += it }
        server.enqueue(liveResponse(stalled))
        server.enqueue(liveResponse(FIXTURE))
        val events = EventRecorder()
        val telemetry = SessionTelemetry(clock)
        val ds = source(sessionCtx(liveUrl, events = events), clock = clock, watchdog = watchdog, telemetry = telemetry)
        ds.open(sessionSpec())
        assertEquals(1, watchdog.registeredCount())
        assertArrayEquals(headerPackets, readExactly(ds, headerPackets.size))
        val next = executor.submit<ByteArray> { readExactly(ds, 5 * PKT) }
        Thread.sleep(300) // the loader thread is now blocked in the socket read
        watchdog.tick()
        clock.advance(LiveTsDataSource.NO_BYTES_TIMEOUT_MS) // exactly the timeout: not yet
        watchdog.tick()
        Thread.sleep(100)
        assertFalse(next.isDone)
        clock.advance(1)
        watchdog.tick()
        val got = next.get(5, TimeUnit.SECONDS)
        assertArrayEquals(maskCc(FIXTURE.copyOf(5 * PKT)), maskCc(got))
        ds.close()
        assertEquals(0, watchdog.registeredCount())
        assertEquals(1, telemetry.noBytesCount.get())
        assertEquals(1, events.count(PlaybackEventType.NO_BYTES))
        assertEquals(1, telemetry.reconnects)
        assertEquals(2, server.requestCount)
        assertNoRange(requests())
    }

    @Test
    fun noFirstByteWithinTheFirstByteTimeoutReconnects() {
        val clock = FakeClock()
        val watchdog = ByteWatchdog(clock).apply { autoStart = false }
        val silent = LiveBody(ByteArray(0), stall = true).also { toRelease += it }
        server.enqueue(liveResponse(silent))
        server.enqueue(liveResponse(FIXTURE))
        val ds = source(sessionCtx(liveUrl), clock = clock, watchdog = watchdog)
        val opened = executor.submit<Long> { ds.open(sessionSpec()) }
        Thread.sleep(300)
        clock.advance(LiveTsDataSource.FIRST_BYTE_TIMEOUT_MS - 1)
        watchdog.tick()
        Thread.sleep(100)
        assertFalse(opened.isDone)
        clock.advance(2)
        watchdog.tick()
        assertEquals(C.LENGTH_UNSET.toLong(), opened.get(5, TimeUnit.SECONDS))
        assertArrayEquals(FIXTURE.copyOf(PKT), readExactly(ds, PKT))
        ds.close()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun theWatchdogNeverFiresWhileNoReadIsInProgress() {
        val clock = FakeClock()
        val watchdog = ByteWatchdog(clock).apply { autoStart = false }
        val stalled = LiveBody(FIXTURE.copyOf(3 * PKT), stall = true).also { toRelease += it }
        server.enqueue(liveResponse(stalled))
        val telemetry = SessionTelemetry(clock)
        val ds = source(sessionCtx(liveUrl), clock = clock, watchdog = watchdog, telemetry = telemetry)
        ds.open(sessionSpec())
        readExactly(ds, 3 * PKT)
        // Buffer full: ProgressiveMediaPeriod stops calling read(). A minute of silence is not a stall.
        clock.advance(60_000)
        watchdog.tick()
        assertEquals(0, telemetry.noBytesCount.get())
        ds.close()
    }

    // ------------------------------------------------------------------ reconnect budget

    @Test
    fun threeFailedReconnectsInsideTheWindowExhaustTheBudget() {
        server.enqueue(liveResponse(FIXTURE.copyOf(3 * PKT)))
        repeat(4) { server.enqueue(MockResponse.Builder().code(503).build()) }
        val clock = FakeClock()
        val ds = source(sessionCtx(liveUrl), clock = clock, timing = fastTiming(RecordingSleeper(clock)))
        ds.open(sessionSpec())
        val (bytes, e) = readUntilFailure(ds)
        ds.close()
        assertEquals(3 * PKT, bytes.size)
        assertEquals(LiveFailureKind.RECONNECT_EXHAUSTED, e.kind)
        assertEquals(503, e.info.status)
        assertEquals(1 + LiveTsDataSource.RECONNECT_MAX_ATTEMPTS, server.requestCount)
    }

    @Test
    fun theBudgetStartsAgainAfterTheWindowOnceBytesFlowed() {
        val ok = FIXTURE.copyOf(3 * PKT)
        server.enqueue(liveResponse(ok))
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(liveResponse(ok)) // attempt 3 succeeds
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(liveResponse(ok)) // new burst: attempt 3 succeeds again
        val clock = FakeClock()
        val telemetry = SessionTelemetry(clock)
        val ds = source(sessionCtx(liveUrl), clock = clock, timing = fastTiming(RecordingSleeper(clock)), telemetry = telemetry)
        ds.open(sessionSpec())
        readExactly(ds, 3 * PKT)
        readExactly(ds, 3 * PKT) // across the first burst
        clock.advance(LiveTsDataSource.RECONNECT_WINDOW_MS + 1) // bytes flowed past the burst window
        readExactly(ds, 3 * PKT) // across the second burst
        ds.close()
        assertEquals(2, telemetry.reconnects)
        assertEquals(7, server.requestCount)
    }

    @Test
    fun aSecondLossInsideTheSameWindowContinuesTheBurst() {
        val ok = FIXTURE.copyOf(3 * PKT)
        server.enqueue(liveResponse(ok))
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(liveResponse(ok)) // attempt 3 succeeds
        server.enqueue(MockResponse.Builder().code(503).build())
        val clock = FakeClock()
        val ds = source(sessionCtx(liveUrl), clock = clock, timing = fastTiming(RecordingSleeper(clock)))
        ds.open(sessionSpec())
        readExactly(ds, 6 * PKT)
        val (_, e) = readUntilFailure(ds) // the next close comes within the window: budget spent
        ds.close()
        assertEquals(LiveFailureKind.RECONNECT_EXHAUSTED, e.kind)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun aNotReconnectableStatusOnAReconnectSurfacesAtOnce() {
        server.enqueue(liveResponse(FIXTURE.copyOf(3 * PKT)))
        server.enqueue(MockResponse.Builder().code(404).build())
        val ds = source(sessionCtx(liveUrl))
        ds.open(sessionSpec())
        val (_, e) = readUntilFailure(ds)
        ds.close()
        assertEquals(LiveFailureKind.HTTP_STATUS, e.kind)
        assertEquals(404, e.info.status)
        assertEquals(FailureInfo.Phase.RECONNECT, e.info.phase)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aZeroByteBodyOnAReconnectIsTreatedAsTheConnectionLimitLinger() {
        server.enqueue(liveResponse(FIXTURE.copyOf(3 * PKT)))
        server.enqueue(liveResponse(ByteArray(0))) // "200 that closes at once" (S7)
        server.enqueue(liveResponse(FIXTURE.copyOf(3 * PKT)))
        val sleeper = RecordingSleeper()
        val ds = source(sessionCtx(liveUrl), timing = fastTiming(sleeper))
        ds.open(sessionSpec())
        readExactly(ds, 6 * PKT)
        ds.close()
        assertEquals(listOf(LiveTsDataSource.RECONNECT_LIMIT_STATUS_DELAY_MS), sleeper.delays.toList())
    }

    // ------------------------------------------------------------------ PCR hold

    @Test
    fun aPcrJumpOnReconnectThrowsAfterDroppingTheHeldPacketsAndNothingPostSpliceIsReadable() {
        val bin = res("ts/pcr_jump.bin")
        server.enqueue(liveResponse(FIXTURE))
        server.enqueue(liveResponse(bin.copyOfRange(FIXTURE.size, bin.size)))
        val events = EventRecorder()
        val ds = source(sessionCtx(liveUrl, events = events))
        ds.open(sessionSpec())
        val (bytes, e) = readUntilFailure(ds)
        assertArrayEquals(FIXTURE, bytes) // not one post-splice byte
        assertEquals(LiveFailureKind.PCR_DISCONTINUITY, e.kind)
        val delta = e.info.deltaMs ?: error("no deltaMs")
        assertTrue("delta $delta", delta in 7_000..9_000)
        assertEquals(FailureInfo.Phase.READ, e.info.phase)
        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, e.reason)
        assertEquals(1, events.count(PlaybackEventType.PCR_JUMP))
        // Media3 must never get the new timestamps: a later read rethrows.
        assertEquals(e, expectLive { ds.read(ByteArray(PKT), 0, PKT) })
        ds.close()
    }

    @Test
    fun aContinuousStreamAfterTheReconnectIsReleasedFromTheHold() {
        val cut = 60 * PKT
        server.enqueue(liveResponse(FIXTURE.copyOf(cut)))
        server.enqueue(liveResponse(FIXTURE.copyOfRange(cut, FIXTURE.size)))
        val ds = source(sessionCtx(liveUrl))
        ds.open(sessionSpec())
        val got = readExactly(ds, FIXTURE.size)
        ds.close()
        assertArrayEquals(maskCc(FIXTURE), maskCc(got))
    }

    // ------------------------------------------------------------------ close, cancel, pre-emption, lease

    @Test
    fun closeCancelsTheCallAndReleasesTheLeaseOnlyAfterTheSocketClosed() {
        val flood = FloodBody(FIXTURE.copyOf(64 * PKT))
        server.enqueue(liveResponse(flood))
        val broker = FakeBroker()
        val ds = source(sessionCtx(liveUrl, broker = broker))
        ds.open(sessionSpec())
        readExactly(ds, 100_000)
        val lease = broker.lease
        var serverSawDisconnectBeforeRelease = false
        lease.onRelease = { serverSawDisconnectBeforeRelease = flood.disconnected.await(2, TimeUnit.SECONDS) }
        ds.close()
        assertTrue("MockWebServer saw the disconnect before release() returned", serverSawDisconnectBeforeRelease)
        assertEquals(1, lease.releaseCount.get())
        assertEquals(lease.attachCount.get(), lease.detachCount.get())
        assertTrue(lease.handles.isEmpty())
    }

    @Test
    fun closeOnAFlowingChunkedBodyIsFastBecauseTheCallIsCancelledFirst() {
        val times = (1..3).map {
            val flood = FloodBody(FIXTURE.copyOf(64 * PKT), chunked = true)
            server.enqueue(
                MockResponse.Builder().code(200).body(flood).removeHeader("Content-Length").addHeader("Transfer-Encoding", "chunked").build(),
            )
            val ds = source(sessionCtx(liveUrl))
            ds.open(sessionSpec())
            readExactly(ds, 200_000)
            val t0 = System.nanoTime()
            ds.close()
            (System.nanoTime() - t0) / 1_000_000.0
        }
        println("LiveTsDataSource.close() on a flowing chunked body: ${times.joinToString { "%.1f ms".format(it) }}")
        times.forEach { assertTrue("close took $it ms (a drain is 100 ms)", it < 100.0) }
        assertTrue("best close ${times.min()} ms", times.min() < 20.0)
    }

    @Test
    fun preemptionDuringABlockedReadUnblocksAtOnceAndDoesNotReconnect() {
        val stalled = LiveBody(FIXTURE.copyOf(3 * PKT), stall = true).also { toRelease += it }
        server.enqueue(liveResponse(stalled))
        val broker = FakeBroker()
        val ds = source(sessionCtx(liveUrl, broker = broker))
        ds.open(sessionSpec())
        readExactly(ds, 3 * PKT)
        val blocked = executor.submit<Int> { ds.read(ByteArray(PKT), 0, PKT) }
        Thread.sleep(300)
        assertFalse(blocked.isDone)
        val t0 = System.nanoTime()
        broker.lease.preempt()
        val e = try {
            blocked.get(2, TimeUnit.SECONDS)
            null
        } catch (x: ExecutionException) {
            x.cause
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("pre-emption unblocked the read in $ms ms")
        assertTrue("unblocked in $ms ms", ms < 200)
        assertTrue("got $e", e is LiveStreamException && e.kind == LiveFailureKind.PREEMPTED)
        assertEquals(1, server.requestCount)
        // Released by the call listener (callFailed) once the cancelled call is done; close() does not double-release.
        assertEquals(1, broker.lease.releaseCount.get())
        ds.close()
        assertEquals(1, broker.lease.releaseCount.get())
    }

    @Test
    fun aSessionEndDuringABlockedReadUnblocksItAndReleasesTheLease() {
        val stalled = LiveBody(FIXTURE.copyOf(3 * PKT), stall = true).also { toRelease += it }
        server.enqueue(liveResponse(stalled))
        val broker = FakeBroker()
        val scope = StandaloneScope()
        val ds = source(sessionCtx(liveUrl, broker = broker), scope = scope)
        ds.open(sessionSpec())
        readExactly(ds, 3 * PKT)
        val blocked = executor.submit<Int> { ds.read(ByteArray(PKT), 0, PKT) }
        Thread.sleep(300)
        val t0 = System.nanoTime()
        scope.end()
        val e = try { blocked.get(2, TimeUnit.SECONDS); null } catch (x: ExecutionException) { x.cause }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("endSession unblocked the read in $ms ms")
        assertTrue("unblocked in $ms ms", ms < 1_000)
        assertTrue("got $e", e is LiveStreamException && e.kind == LiveFailureKind.PREEMPTED)
        assertEquals(1, broker.lease.releaseCount.get())
        ds.close()
        assertEquals(1, broker.lease.releaseCount.get())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun theLeaseIsReleasedByTheCallListenerAfterANormalClose() {
        server.enqueue(liveResponse(FIXTURE))
        val broker = FakeBroker()
        val ds = source(sessionCtx(liveUrl, broker = broker))
        ds.open(sessionSpec())
        readExactly(ds, 10 * PKT)
        assertEquals(1, broker.lease.handles.size)
        assertEquals(0, broker.lease.releaseCount.get())
        ds.close()
        assertEquals(1, broker.lease.attachCount.get())
        assertEquals(1, broker.lease.detachCount.get())
        assertEquals(1, broker.lease.releaseCount.get())
    }

    // ------------------------------------------------------------------ code review 2026-10-14

    @Test
    fun aBadHeaderValueIsATypedFailureThatReleasesTheLeaseAndHidesTheValue() {
        // N-S1: OkHttp's IllegalArgumentException("Unexpected char 0xe9 at 4 in X-Token value: s3crét") used to escape
        // open() as a RuntimeException with the lease held.
        val broker = FakeBroker()
        val ds = source(sessionCtx(liveUrl, broker = broker, headers = mapOf("User-Agent" to "Box", "X-Token" to "s3crét")))
        val e = expectLive { ds.open(sessionSpec()) }
        assertEquals(LiveFailureKind.NETWORK, e.kind)
        assertEquals(NetworkFailureKind.OTHER, e.info.networkKind)
        assertNoUrl(e)
        var t: Throwable? = e
        while (t != null) {
            assertFalse("value in $t", t.toString().contains("s3cr"))
            t = t.cause
        }
        assertEquals("the lease is released by the failed open", 1, broker.lease.releaseCount.get())
        assertEquals(0, server.requestCount)
        ds.close()
        assertEquals(1, broker.lease.releaseCount.get())
    }

    @Test
    fun noLiveStreamExceptionCarriesARealDataSpecAsItsCause() {
        // N-S3: OkHttpDataSource's "Malformed URL" HttpDataSourceException holds the real DataSpec; it must not become
        // the cause (the builder rejects such schemes first; this is the transport's own guarantee).
        val ds = source(sessionCtx("rtmp://example.com/live/user/pass"))
        val e = expectLive { ds.open(sessionSpec()) }
        assertNoUrl(e)
        var t: Throwable? = e.cause
        while (t != null) {
            if (t is androidx.media3.datasource.DataSourceException && t is HttpDataSource.HttpDataSourceException) {
                assertEquals(LiveTsDataSource.SESSION_SCHEME, t.dataSpec.uri.scheme)
            }
            assertFalse("URL in $t", t.toString().contains("example.com"))
            t = t.cause
        }
        ds.close()
    }

    @Test
    fun readPendingIsReportedOnlyWhileAReadWaitsOnTheSocket() {
        // E-B1/N-S2: the watchdog's no-bytes age runs only while this is non-zero.
        val stalled = LiveBody(FIXTURE.copyOf(3 * PKT), stall = true).also { toRelease += it }
        server.enqueue(liveResponse(stalled))
        val telemetry = SessionTelemetry(REAL_CLOCK)
        val ds = source(sessionCtx(liveUrl), telemetry = telemetry)
        ds.open(sessionSpec())
        readExactly(ds, 3 * PKT)
        assertEquals("no read in progress", 0L, telemetry.readPendingSinceMs)
        val blocked = executor.submit<Int> { ds.read(ByteArray(PKT), 0, PKT) }
        // Poll rather than a fixed sleep: a slow CI runner may not have reached the socket read in 300 ms.
        val until = System.nanoTime() + 10_000_000_000L
        while (telemetry.readPendingSinceMs == 0L && System.nanoTime() < until) Thread.sleep(20)
        assertTrue("a read waits on the socket", telemetry.readPendingSinceMs > 0)
        ds.close()
        try { blocked.get(10, TimeUnit.SECONDS) } catch (_: ExecutionException) { }
        assertEquals(0L, telemetry.readPendingSinceMs)
    }

    /** A body that declares [declared] bytes but sends [data] and then closes the socket. */
    private class TruncatedBody(private val data: ByteArray, private val declared: Long) : MockResponseBody {
        override val contentLength: Long = declared
        override fun writeTo(sink: BufferedSink) {
            sink.write(data)
            sink.flush()
        }
    }
}
