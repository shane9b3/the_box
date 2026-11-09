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
import kotlin.test.assertFalse
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
        // UNDISPATCHED: the collector is subscribed before any request (D-L4 refuses submissions nobody collects).
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { link.submissions.collect { submissions += it } }
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
        assertTrue("HttpOnly" in setCookie && "SameSite=Lax" in setCookie && "Path=/" in setCookie)
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
    fun fiveWrongCodesRotateTheCodeAndTheSecondExhaustionBlocksOnlyThatAddress() {
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
        val blocked = transport.exchange(post("/pair", host, """{"code":"$wrong2"}"""))
        assertEquals(410, blocked.status)
        assertTrue("too_many_wrong_codes" in blocked.body, blocked.body)
        // F2 (2026-11-09): one LAN host no longer ends the session; it is blocked, and even the right code is refused from it.
        assertEquals(410, transport.exchange(post("/pair", host, """{"code":"$second"}""")).status)
        assertEquals(ServerPhase.WAITING, link.state.value.phase)
        assertNull(link.state.value.failure)
        // The real phone, on another address, still pairs with the code the TV shows.
        val phone = transport.exchange(post("/pair", host, """{"code":"$second"}"""), ip = "192.168.1.35")
        assertEquals(204, phone.status)
        awaitValue { link.state.value.takeIf { it.phase == ServerPhase.PAIRED } }
        assertEquals("192.168.1.35", link.state.value.pairedFrom)
    }

    @Test
    fun wrongCodesFromManyAddressesEndTheSessionAtTheGlobalCeiling() {
        started()
        var last = 0
        var n = 0
        while (n < LanLimits.PAIR_MAX_WRONG_TOTAL) {
            val ip = "192.168.1.${100 + n / (2 * LanLimits.PAIR_MAX_WRONG)}"
            val code = link.state.value.code!!
            val wrong = if (code == "000000") "111111" else "000000"
            last = transport.exchange(post("/pair", host, """{"code":"$wrong"}"""), ip = ip).status
            n++
            if (n < LanLimits.PAIR_MAX_WRONG_TOTAL) assertNotEquals(ServerPhase.FAILED, link.state.value.phase, "still open after $n")
        }
        assertEquals(410, last)
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

    /**
     * Owner finding 2026-11-01, the exact sequence: the QR link was fetched (the phone's scanner) before the browser,
     * so the browser reached /app without the cookie, and the right code from the same phone then said "another phone".
     */
    @Test
    fun samePhoneRePairsWithTheCodeAfterTheTokenWasUsedWithoutItsCookie() {
        val first = pairByToken()
        val app = transport.exchange(get("/app", host))
        assertEquals(303, app.status)
        assertEquals("/", app.headers["location"])
        val code = link.state.value.code!!
        val r = transport.exchange(post("/pair", host, """{"code":"${PairingSession.displayCode(code)}"}"""))
        assertEquals(204, r.status)
        val second = assertNotNull(r.cookie())
        assertNotEquals(first, second)
        assertEquals(401, transport.exchange(get("/api/state", host, first)).status)
        assertEquals("/", transport.exchange(get("/app", host, first)).headers["location"])
        assertEquals(200, transport.exchange(get("/api/state", host, second)).status)
        assertEquals(ServerPhase.PAIRED, link.state.value.phase)
        assertEquals(FakeTransportAdapter.PHONE_IP, link.state.value.pairedFrom)
    }

    @Test
    fun samePhoneOpeningTheQrLinkAgainWithoutTheCookieRePairs() {
        val token = started().qrUrl!!.substringAfter("/s/")
        val first = assertNotNull(transport.exchange(get("/s/$token", host)).cookie())
        // With the valid cookie: straight to /app, no new cookie.
        val again = transport.exchange(get("/s/$token", host, first))
        assertEquals(303, again.status)
        assertNull(again.headers["set-cookie"])
        // Without it (the scanner took the first one): a new cookie, the old one is refused.
        val re = transport.exchange(get("/s/$token", host))
        assertEquals(303, re.status)
        assertEquals("/app", re.headers["location"])
        val second = assertNotNull(re.cookie())
        assertNotEquals(first, second)
        assertEquals(401, transport.exchange(get("/api/state", host, first)).status)
        assertEquals(200, transport.exchange(get("/api/state", host, second)).status)
    }

    @Test
    fun anotherPhoneIsStillRefusedOnBothPathsAfterAPairing() {
        val token = started().qrUrl!!.substringAfter("/s/")
        val cookie = assertNotNull(transport.exchange(get("/s/$token", host)).cookie())
        val other = "192.168.1.99"
        val viaToken = transport.exchange(get("/s/$token", host), ip = other)
        assertEquals(403, viaToken.status)
        assertEquals("Another phone is already connected", viaToken.body)
        assertNull(viaToken.headers["set-cookie"])
        val viaCode = transport.exchange(post("/pair", host, """{"code":"${link.state.value.code!!}"}"""), ip = other)
        assertEquals(403, viaCode.status)
        assertEquals("""{"error":"busy"}""", viaCode.body)
        assertNull(viaCode.headers["set-cookie"])
        assertEquals(200, transport.exchange(get("/api/state", host, cookie)).status)
        assertEquals(FakeTransportAdapter.PHONE_IP, link.state.value.pairedFrom)
    }

    @Test
    fun headAndPrefetchesOfTheQrLinkNeverPair() {
        val token = started().qrUrl!!.substringAfter("/s/")
        val head = transport.exchange("HEAD /s/$token HTTP/1.1\r\nHost: $host\r\n\r\n")
        assertEquals(204, head.status)
        assertNull(head.headers["set-cookie"])
        for (h in listOf("Sec-Purpose: prefetch", "Sec-Purpose: prefetch;prerender", "Purpose: prefetch", "X-Purpose: preview", "X-Moz: prefetch")) {
            val r = transport.exchange("GET /s/$token HTTP/1.1\r\nHost: $host\r\n$h\r\n\r\n")
            assertEquals(204, r.status, h)
            assertNull(r.headers["set-cookie"], h)
        }
        assertNull(link.state.value.pairedFrom)
        assertEquals(ServerPhase.WAITING, link.state.value.phase)
        // The real navigation from the same phone then pairs normally.
        val r = transport.exchange(get("/s/$token", host))
        assertEquals(303, r.status)
        val cookie = assertNotNull(r.cookie())
        assertEquals(200, transport.exchange(get("/api/state", host, cookie)).status)
    }

    /** N8 (review 2026-11-01): prefetch header values match whatever their case. */
    @Test
    fun prefetchHeaderValuesMatchCaseInsensitively() {
        val token = started().qrUrl!!.substringAfter("/s/")
        for (h in listOf("Sec-Purpose: Prefetch;Prerender", "SEC-PURPOSE: PRERENDER", "Purpose: PREFETCH", "X-Purpose: Preview", "x-moz: Prefetch")) {
            val r = transport.exchange("GET /s/$token HTTP/1.1\r\nHost: $host\r\n$h\r\n\r\n")
            assertEquals(204, r.status, h)
            assertNull(r.headers["set-cookie"], h)
        }
        assertNull(link.state.value.pairedFrom)
        assertEquals(303, transport.exchange(get("/s/$token", host)).status)
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
            val expected = file.readText()
            val r = transport.exchange(get("/api/download/TheBox-backup.thebox", host, cookie))
            assertEquals(200, r.status)
            assertEquals("attachment; filename=\"TheBox-backup.thebox\"", r.headers["content-disposition"])
            assertEquals(expected, r.body)
            assertEquals(404, transport.exchange(get("/api/download/TheBox-backup.thebox", host, cookie)).status)
        } finally {
            file.delete()
        }
    }

    /**
     * D-B3 (adopted from the data reviewer's `M5ReviewDataLanTest`): "Export logs → To your phone" offers the bundle
     * before the phone screen starts the server; the offer survives the start and the phone sees it. The bundle lives
     * outside the spool directory (DiagSourcesImpl writes to `cacheDir/phone-out/`).
     */
    @Test
    fun logsOfferedBeforeTheServerStartsReachThePhone() {
        val out = Files.createTempDirectory("phone-out").toFile()
        try {
            val bundle = File(out, "TheBox-logs-2026-10-16-1402.txt").apply { writeText("log bundle") }
            link.offerDownload(bundle.name, bundle.path, "text/plain")
            link.start(PhonePurpose.LOGS_DOWNLOAD)
            val token = link.state.value.qrUrl!!.substringAfter("/s/")
            val cookie = transport.exchange(get("/s/$token", host)).cookie()!!
            val state = transport.exchange(get("/api/state", host, cookie)).body
            assertTrue(bundle.isFile, "the log bundle survives the server start")
            assertTrue(bundle.name in state, "the phone sees the offered log bundle; /api/state was: $state")
            assertEquals(200, transport.exchange(get("/api/download/${bundle.name}", host, cookie)).status)
        } finally {
            out.deleteRecursively()
        }
    }

    /** D-S2 (§2.5 "removed after download or stop"): an offered file is deleted after its one download, and on stop. */
    @Test
    fun offeredFileIsDeletedAfterItsDownloadAndOnStop() {
        val out = Files.createTempDirectory("backup-out").toFile()
        try {
            val cookie = started(PhonePurpose.BACKUP_DOWNLOAD).let { pairToken(it) }
            val served = File(out, "a.thebox").apply { writeText("a") }
            link.offerDownload(served.name, served.path, "application/octet-stream")
            assertEquals(200, transport.exchange(get("/api/download/a.thebox", host, cookie)).status)
            assertFalse(served.exists(), "served once, then deleted")
            val left = File(out, "b.thebox").apply { writeText("b") }
            link.offerDownload(left.name, left.path, "application/octet-stream")
            link.stop()
            assertFalse(left.exists(), "an offer nobody downloaded goes with the session")
            val pending = File(out, "c.thebox").apply { writeText("c") }
            link.offerDownload(pending.name, pending.path, "application/octet-stream") // no server: kept for the next start
            assertTrue(pending.exists())
            link.stop()
            assertFalse(pending.exists(), "an offer that never reached a session goes on stop")
        } finally {
            out.deleteRecursively()
        }
    }

    /** D-L4: with nothing on the TV collecting submissions the phone gets 503 (send again), never a 202 that is dropped. */
    @Test
    fun submissionWithNobodyListeningIsRefused() {
        val other = newLink()
        try {
            other.start(PhonePurpose.SETUP)
            val cookie = pairToken(other.state.value)
            val r = transport.exchange(post("/api/link", host, "{\"url\":\"http://x/y.m3u\"}", cookie))
            assertEquals(503, r.status)
            assertTrue(submissions.isEmpty())
        } finally {
            other.stop()
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
    fun moreThanFourSocketsAreClosedWithoutAResponse() {
        link.stop()
        link = newLink(config = LanHttpServerImpl.Config(socketTimeoutMs = 2_000))
        started()
        val holders = (1..LanLimits.HTTP_MAX_SOCKETS).map { transport.connect().also { it.output.write("GET".toByteArray()); it.output.flush() } }
        Thread.sleep(50)
        transport.connect().use { excess -> assertTrue(excess.input.readBytes().isEmpty()) }
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
