package app.thebox.core.setup.lan

import app.thebox.core.settings.PhonePurpose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server rules of docs/design/m5-setup-settings.md §3.5.2–§3.5.3 end to end over in-memory connections
 * (§6.1 WP-D `LanHttpServerImplTest`): [LanPhoneLink] → [LanHttpServerImpl] → [PairingSession] + [PhoneApi], with a
 * fake clock for the lifetimes.
 */
class LanHttpServerImplTest {
    private val transport = FakeTransportAdapter()
    private val now = AtomicLong(1_000_000L)
    private lateinit var scope: CoroutineScope
    private lateinit var spool: File
    private lateinit var link: LanPhoneLink
    private val submissions: MutableList<PhoneSubmission> = Collections.synchronizedList(ArrayList())
    private val tv = "192.168.1.20"
    private val host get() = "$tv:${LanLimits.PHONE_PORT_PREFERRED}"
    private var counter = 0

    private val assets = mapOf(
        "pair.html" to "<html>pair</html>", "pair.js" to "js", "app.html" to "<html>app</html>", "app.js" to "appjs", "app.css" to "css",
    )

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        spool = Files.createTempDirectory("phone").toFile()
        link = newLink()
        scope.launch { link.submissions.collect { submissions += it } }
    }

    @AfterTest
    fun tearDown() {
        link.stop()
        scope.cancel()
        spool.deleteRecursively()
    }

    private fun newLink(finder: LanAddressFinder = fixedFinder(tv), config: LanHttpServerImpl.Config = LanHttpServerImpl.Config()) =
        LanPhoneLink(
            finder, transport, { assets[it]?.toByteArray() }, spool, scope, clock = { now.get() }, serverConfig = config,
            randomBytes = { b -> for (i in b.indices) b[i] = (counter++ * 37 + i).toByte() }, tickMs = 5,
        )

    private fun fixedFinder(ip: String?) = object : LanAddressFinder {
        override fun find(): String? = ip
        override fun vpnActive() = false
    }

    private fun started(purpose: PhonePurpose = PhonePurpose.SETUP): LanServerState {
        link.start(purpose)
        return link.state.value.also { assertEquals(ServerPhase.WAITING, it.phase) }
    }

    private fun pairByToken(): String {
        val token = started().qrUrl!!.substringAfter("/s/")
        val r = transport.exchange(get("/s/$token", host))
        assertEquals(303, r.status)
        assertEquals("/app", r.headers["location"])
        return assertNotNull(r.cookie())
    }

    @Test
    fun bindsThePreferredPortAndPublishesTheUrls() {
        val s = started()
        assertEquals("http://$host", s.baseUrl)
        assertTrue(s.qrUrl!!.startsWith("http://$host/s/"))
        assertEquals(6, s.code!!.length)
        assertEquals(listOf(LanLimits.PHONE_PORT_PREFERRED), transport.boundPorts)
    }

    @Test
    fun takenPreferredPortFallsBackToARandomHighPort() {
        transport.takenPorts = setOf(LanLimits.PHONE_PORT_PREFERRED)
        val s = started()
        val port = s.baseUrl!!.substringAfterLast(':').toInt()
        assertTrue(port in LanLimits.PORT_RANDOM_FROM..LanLimits.PORT_RANDOM_TO)
    }

    @Test
    fun noLanAddressFailsWithAReason() {
        link = newLink(fixedFinder(null))
        link.start(PhonePurpose.SETUP)
        assertEquals(LanFailure.NO_LAN_ADDRESS, link.state.value.failure)
        link = newLink(fixedFinder("100.64.1.2")) // CGNAT is refused
        link.start(PhonePurpose.SETUP)
        assertEquals(LanFailure.NO_LAN_ADDRESS, link.state.value.failure)
    }

    @Test
    fun hostHeaderMustMatchExactly() {
        started()
        assertEquals(200, transport.exchange(get("/", host)).status)
        assertEquals(421, transport.exchange(get("/", "evil.example.com")).status)
        assertEquals(421, transport.exchange(get("/", tv)).status) // port missing
        assertEquals(421, transport.exchange("GET / HTTP/1.1\r\n\r\n").status)
    }

    @Test
    fun responsesCarryTheSecurityHeaders() {
        started()
        val r = transport.exchange(get("/", host))
        assertEquals("no-store", r.headers["cache-control"])
        assertEquals("nosniff", r.headers["x-content-type-options"])
        assertEquals("DENY", r.headers["x-frame-options"])
        assertEquals("no-referrer", r.headers["referrer-policy"])
        assertEquals(PhoneApi.CSP, r.headers["content-security-policy"])
        assertEquals("close", r.headers["connection"])
        assertEquals("<html>pair</html>", r.body)
    }

    @Test
    fun postsNeedTheCustomHeaderAndASameOrigin() {
        val cookie = pairByToken()
        val body = """{"url":"http://x/y.m3u"}"""
        assertEquals(403, transport.exchange(post("/api/link", host, body, cookie, xTheBox = false)).status)
        assertEquals(403, transport.exchange(post("/api/link", host, body, cookie, origin = "http://evil.example")).status)
        assertEquals(202, transport.exchange(post("/api/link", host, body, cookie, origin = "http://$host")).status)
        assertEquals(PhoneSubmission.Link("http://x/y.m3u", null), awaitValue { submissions.firstOrNull() })
    }

    @Test
    fun withoutACookieTheApiIs401AndTheAppPageRedirectsToPairing() {
        started()
        assertEquals(401, transport.exchange(get("/api/state", host)).status)
        val r = transport.exchange(get("/app", host))
        assertEquals(303, r.status)
        assertEquals("/", r.headers["location"])
    }

    @Test
    fun tokenIsSingleUseAndSetsAStrictCookie() {
        val token = started().qrUrl!!.substringAfter("/s/")
        val first = transport.exchange(get("/s/$token", host))
        assertEquals(303, first.status)
        val setCookie = first.headers["set-cookie"]!!
        assertTrue("HttpOnly" in setCookie && "SameSite=Strict" in setCookie && "Path=/" in setCookie)
        assertNotEquals(token, first.cookie())
        // The token is dead: another phone using it is refused, and so is the code.
        assertEquals(403, transport.exchange(get("/s/$token", host), ip = "192.168.1.99").status)
        val code = link.state.value.code!!
        assertEquals(403, transport.exchange(post("/pair", host, """{"code":"$code"}"""), ip = "192.168.1.99").status)
        assertEquals(ServerPhase.PAIRED, link.state.value.phase)
        assertEquals(FakeTransportAdapter.PHONE_IP, link.state.value.pairedFrom)
        // A wrong token never pairs.
        link.start(PhonePurpose.SETUP)
        assertEquals(404, transport.exchange(get("/s/AAAAAAAAAAAAAAAAAAAAAA", host)).status)
        assertNull(link.state.value.pairedFrom)
    }

    @Test
    fun codePairingWorksWithASpaceAndAnswers204WithACookie() {
        val code = started().code!!
        val r = transport.exchange(post("/pair", host, """{"code":"${PairingSession.displayCode(code)}"}"""))
        assertEquals(204, r.status)
        val cookie = assertNotNull(r.cookie())
        val state = transport.exchange(get("/api/state", host, cookie))
        assertEquals(200, state.status)
        assertTrue(""""purpose":"SETUP"""" in state.body)
        assertTrue(""""tv":"Waiting"""" in state.body)
    }

    @Test
    fun fiveWrongCodesRotateTheCodeOnceAndTheSecondExhaustionStops() {
        val first = started().code!!
        val wrong = if (first == "000000") "111111" else "000000"
        for (left in 4 downTo 1) {
            val r = transport.exchange(post("/pair", host, """{"code":"$wrong"}"""))
            assertEquals(403, r.status)
            assertEquals("""{"left":$left}""", r.body)
        }
        val fifth = transport.exchange(post("/pair", host, """{"code":"$wrong"}"""))
        assertEquals("""{"left":0}""", fifth.body)
        val second = link.state.value.code!!
        assertNotEquals(first, second, "the code rotates after $PAIR_MAX wrong codes")
        assertEquals(LanLimits.PAIR_MAX_WRONG, link.state.value.wrongCodes)
        // The old code no longer works.
        assertEquals(403, transport.exchange(post("/pair", host, """{"code":"$first"}""")).status)
        val wrong2 = if (second == "000000") "111111" else "000000"
        repeat(LanLimits.PAIR_MAX_WRONG - 2) { transport.exchange(post("/pair", host, """{"code":"$wrong2"}""")) }
        assertEquals(410, transport.exchange(post("/pair", host, """{"code":"$wrong2"}""")).status)
        awaitValue { link.state.value.takeIf { it.phase == ServerPhase.FAILED } }
        assertEquals(LanFailure.TOO_MANY_WRONG_CODES, link.state.value.failure)
    }

    @Test
    fun onePhonePerSession() {
        val cookie = pairByToken()
        assertEquals(200, transport.exchange(get("/api/state", host, cookie)).status)
        // The cookie from another IP, or another IP without one, is "another phone".
        assertEquals(403, transport.exchange(get("/api/state", host, cookie), ip = "192.168.1.77").status)
        assertEquals(403, transport.exchange(get("/api/state", host), ip = "192.168.1.77").status)
        assertEquals(403, transport.exchange(post("/api/link", host, """{"url":"http://a"}""", cookie), ip = "192.168.1.77").status)
        assertTrue(submissions.isEmpty())
    }

    @Test
    fun idleExpiryEndsTheSession() {
        val cookie = pairByToken()
        now.addAndGet(LanLimits.PAIR_IDLE_MS - 1)
        assertEquals(200, transport.exchange(get("/api/state", host, cookie)).status) // a request resets the idle clock
        now.addAndGet(LanLimits.PAIR_IDLE_MS - 1)
        Thread.sleep(50)
        assertEquals(ServerPhase.PAIRED, link.state.value.phase)
        now.addAndGet(2)
        awaitValue { link.state.value.takeIf { it.phase == ServerPhase.FAILED } }
        assertEquals(LanFailure.EXPIRED, link.state.value.failure)
    }

    @Test
    fun absoluteExpiryEndsTheSessionEvenWhileActive() {
        val cookie = pairByToken()
        var t = 0L
        while (t < LanLimits.PAIR_MAX_MS - LanLimits.PAIR_IDLE_MS / 2) {
            now.addAndGet(LanLimits.PAIR_IDLE_MS / 2)
            t += LanLimits.PAIR_IDLE_MS / 2
            transport.exchange(get("/api/state", host, cookie))
        }
        now.addAndGet(LanLimits.PAIR_IDLE_MS / 2)
        awaitValue { link.state.value.takeIf { it.phase == ServerPhase.FAILED } }
        assertEquals(LanFailure.EXPIRED, link.state.value.failure)
    }

    @Test
    fun downloadsOnlyWhenOfferedAndOnce() {
        val cookie = started(PhonePurpose.BACKUP_DOWNLOAD).let { pairToken(it) }
        assertEquals(404, transport.exchange(get("/api/download/TheBox-backup.thebox", host, cookie)).status)
        val file = File(spool.parentFile, "offer-${System.nanoTime()}.thebox").apply { writeText("{\"format\":\"thebox-backup\"}\n") }
        try {
            link.offerDownload("TheBox-backup.thebox", file.path, "application/octet-stream")
            assertTrue("TheBox-backup.thebox" in transport.exchange(get("/api/state", host, cookie)).body)
            // Only the paired phone.
            assertEquals(403, transport.exchange(get("/api/download/TheBox-backup.thebox", host), ip = "192.168.1.77").status)
            val r = transport.exchange(get("/api/download/TheBox-backup.thebox", host, cookie))
            assertEquals(200, r.status)
            assertEquals("attachment; filename=\"TheBox-backup.thebox\"", r.headers["content-disposition"])
            assertEquals(file.readText(), r.body)
            assertEquals(404, transport.exchange(get("/api/download/TheBox-backup.thebox", host, cookie)).status)
        } finally {
            file.delete()
        }
    }

    private fun pairToken(s: LanServerState): String {
        val r = transport.exchange(get("/s/" + s.qrUrl!!.substringAfter("/s/"), host))
        return assertNotNull(r.cookie())
    }

    @Test
    fun submissionsAreValidatedAndGatedByPurpose() {
        val cookie = pairByToken()
        assertEquals(400, transport.exchange(post("/api/xtream", host, """{"server":"http://p","username":"u"}""", cookie)).status)
        assertEquals(400, transport.exchange(post("/api/xtream", host, "not json", cookie)).status)
        assertEquals(
            202,
            transport.exchange(post("/api/xtream", host, """{"name":"","server":" http://p:8080 ","username":"u","password":"p+w","output":"hls"}""", cookie)).status,
        )
        val x = awaitValue { submissions.firstOrNull() } as PhoneSubmission.Xtream
        assertEquals("http://p:8080", x.form.server)
        assertEquals("p+w", x.form.password)
        assertEquals("HLS", x.form.output)
        assertNull(x.form.name)
        assertEquals(ServerPhase.RECEIVED, link.state.value.phase)

        link.start(PhonePurpose.RESTORE)
        val c2 = pairToken(link.state.value)
        assertEquals(409, transport.exchange(post("/api/xtream", host, """{"server":"a","username":"u","password":"p"}""", c2)).status)
        assertEquals(409, transport.exchange(post("/api/upload?kind=m3u", host, "x", c2)).status)
    }

    @Test
    fun jsonBodiesOverTheLimitAre413() {
        val cookie = pairByToken()
        val big = "{\"url\":\"" + "a".repeat(LanLimits.HTTP_MAX_JSON_BYTES) + "\"}"
        assertEquals(413, transport.exchange(post("/api/link", host, big, cookie)).status)
    }

    @Test
    fun uploadsStreamToTheSpoolDirectory() {
        link.start(PhonePurpose.RESTORE)
        val cookie = pairToken(link.state.value)
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val raw = "POST /api/upload?kind=backup&name=My%20backup.thebox HTTP/1.1\r\nHost: $host\r\nX-TheBox: 1\r\nCookie: tb=$cookie\r\n" +
            "Content-Type: application/octet-stream\r\nContent-Length: ${bytes.size}\r\n\r\n"
        assertEquals(202, transport.exchange(raw, bytes).status)
        val up = awaitValue { submissions.firstOrNull() } as PhoneSubmission.BackupUploaded
        assertEquals(bytes.size.toLong(), up.bytes)
        assertContentEquals(bytes, File(up.path).readBytes())
        assertTrue(File(up.path).parentFile == spool)
        // Uploads are deleted when the server stops.
        link.stop()
        assertTrue(!File(up.path).exists())
    }

    @Test
    fun uploadOverTheLimitIs413BeforeAnyBodyIsRead() {
        link.start(PhonePurpose.RESTORE)
        val cookie = pairToken(link.state.value)
        val raw = "POST /api/upload?kind=backup HTTP/1.1\r\nHost: $host\r\nX-TheBox: 1\r\nCookie: tb=$cookie\r\n" +
            "Content-Length: ${LanLimits.PHONE_UPLOAD_MAX_BYTES + 1}\r\n\r\n"
        assertEquals(413, transport.exchange(raw).status)
        assertTrue(submissions.isEmpty())
    }

    @Test
    fun postWithoutLengthIs411() {
        val cookie = pairByToken()
        val raw = "POST /api/link HTTP/1.1\r\nHost: $host\r\nX-TheBox: 1\r\nCookie: tb=$cookie\r\nTransfer-Encoding: chunked\r\n\r\n"
        assertEquals(411, transport.exchange(raw).status)
    }

    @Test
    fun slowSenderGets408() {
        link.stop()
        link = newLink(config = LanHttpServerImpl.Config(socketTimeoutMs = 150))
        started()
        val c = transport.connect()
        c.output.write("GET / HTTP/1.1\r\nHost: ".toByteArray()) // and nothing more: this thread stays alive
        c.output.flush()
        val bytes = c.input.readBytes()
        assertEquals(408, parseResponse(bytes).status)
    }

    @Test
    fun moreThanFourSocketsAreRefusedWith503() {
        link.stop()
        link = newLink(config = LanHttpServerImpl.Config(socketTimeoutMs = 2_000))
        started()
        val holders = (1..LanLimits.HTTP_MAX_SOCKETS).map { transport.connect().also { it.output.write("GET".toByteArray()); it.output.flush() } }
        Thread.sleep(50)
        assertEquals(503, transport.exchange(get("/", host)).status)
        holders.forEach { it.close() }
    }

    @Test
    fun stopClosesAndResetsTheState() {
        started()
        link.stop()
        assertEquals(ServerPhase.CLOSED, link.state.value.phase)
        assertNull(link.state.value.qrUrl)
    }

    private companion object {
        const val PAIR_MAX = LanLimits.PAIR_MAX_WRONG
    }
}
