package app.thebox.player.datasource

import app.thebox.core.playback.LeaseKind
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** §5.3 WP-B `BrokeredCallFactory` (plain JUnit + MockWebServer; no Android types involved). */
class BrokeredCallFactoryTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before fun setUp() { server = MockWebServer(); server.start() }
    private val bodies = java.util.concurrent.CopyOnWriteArrayList<LiveBody>()
    private fun stalled() = LiveBody(ByteArray(1_000), stall = true).also { bodies += it }

    @After fun tearDown() {
        bodies.forEach { it.release() }
        server.close()
    }

    private fun lease() = FakeLease(9, LeaseKind.PLAYBACK, ownerTuneId = 77)

    @Test
    fun everyCallIsAttachedTaggedAndDetachedWhenItEnds() {
        server.enqueue(MockResponse.Builder().code(200).body("a").build())
        server.enqueue(MockResponse.Builder().code(200).body("b").build())
        val lease = lease()
        val f = BrokeredCallFactory(client, lease)
        repeat(2) {
            val call = f.newCall(Request.Builder().url(server.url("/x")).build())
            assertEquals(BrokeredCallFactory.LeaseTag(77, 9), call.request().tag(BrokeredCallFactory.LeaseTag::class.java))
            call.execute().use { it.body.string() }
        }
        assertEquals(2, lease.attachCount.get())
        assertEquals(2, lease.detachCount.get())
        assertEquals(0, f.liveCalls())
        assertEquals(0, lease.releaseCount.get()) // the holder has not finished: a reconnect may follow
        f.finish()
        assertEquals(1, lease.releaseCount.get())
        f.finish()
        f.forceRelease()
        assertEquals(1, lease.releaseCount.get())
    }

    @Test
    fun statusRedirectsAndFinalHostAreRecorded() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/b").build())
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "/c?token=secret").build())
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        val telemetry = SessionTelemetry(REAL_CLOCK)
        val f = BrokeredCallFactory(client, lease(), telemetry)
        f.newCall(Request.Builder().url(server.url("/a")).build()).execute().use { it.body.string() }
        assertEquals(200, telemetry.httpStatus)
        assertEquals(2, telemetry.redirects)
        assertEquals("${server.hostName}:${server.port}", telemetry.finalHost)
        assertFalse(telemetry.finalHost!!.contains("token"))
    }

    @Test
    fun aCallCreatedAfterPreemptionIsCancelledAtOnceAndNeverReachesTheNetwork() {
        val lease = lease()
        val f = BrokeredCallFactory(client, lease)
        lease.preempt()
        val call = f.newCall(Request.Builder().url(server.url("/x")).build())
        assertTrue(call.isCanceled())
        try {
            call.execute().close()
            fail("a cancelled call must not execute")
        } catch (expected: IOException) {
            // "Canceled"
        }
        assertEquals(0, server.requestCount)
        assertEquals(0, lease.attachCount.get())
        // Pre-empted and idle: the call listener released the lease.
        assertEquals(1, lease.releaseCount.get())
    }

    @Test
    fun preemptionCancelsTheLiveCallAndReleasesOnceItIsDone() {
        server.enqueue(liveResponse(stalled()))
        val lease = lease()
        val f = BrokeredCallFactory(client, lease)
        val response = f.newCall(Request.Builder().url(server.url("/live")).build()).execute()
        val source = response.body.source()
        source.require(1_000)
        assertEquals(1, f.liveCalls())
        lease.preempt()
        try {
            source.readByteArray(2_000)
            fail("read after cancel")
        } catch (expected: IOException) {
            // socket closed by the cancel
        }
        response.close()
        assertEquals(0, f.liveCalls())
        assertEquals(1, lease.releaseCount.get())
    }

    @Test
    fun cancelAllThenFinishReleasesAfterTheBodyIsClosed() {
        server.enqueue(liveResponse(stalled()))
        val lease = lease()
        val f = BrokeredCallFactory(client, lease)
        val response = f.newCall(Request.Builder().url(server.url("/live")).build()).execute()
        response.body.source().require(1_000)
        f.finish()
        assertEquals("a live call keeps the lease", 0, lease.releaseCount.get())
        f.cancelAll()
        response.close()
        assertEquals(1, lease.releaseCount.get())
    }
}
