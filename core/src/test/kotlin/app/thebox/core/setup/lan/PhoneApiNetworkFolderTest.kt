package app.thebox.core.setup.lan

import app.thebox.core.common.BoxLog
import app.thebox.core.common.Redact
import app.thebox.core.settings.PhonePurpose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WP-S6 (docs/design/m6-storage-locations.md §7.4, §16.2): `POST /api/network-folder` only for NETWORK_FOLDER; the body
 * buffer zeroed; the password never in `/api/state`; `Redact` registration; one shot after a success; [LanLimits.PAIR_MAX_WRONG]
 * failed sign-ins end the session; nothing of the body reaches a log. Unit level on [PhoneApi], then end to end through
 * [LanPhoneLink] over in-memory connections.
 */
class PhoneApiNetworkFolderTest {
    private val tvIp = "192.168.1.20"
    private val phoneIp = "192.168.1.34"
    private val hostPort = "$tvIp:${LanLimits.PHONE_PORT_PREFERRED}"
    private val now = AtomicLong(1_000_000L)
    private var counter = 0
    private val submissions: MutableList<PhoneSubmission> = Collections.synchronizedList(ArrayList())
    private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
    private lateinit var spool: File
    private lateinit var savedSink: BoxLog.Sink

    private val password = "Tr0ub4dor&3-wp-s6"
    private val user = "shawnjohnson-wps6"

    @BeforeTest
    fun setUp() {
        spool = Files.createTempDirectory("phone-nf").toFile()
        savedSink = BoxLog.sink
        BoxLog.sink = BoxLog.Sink { _, tag, message, error -> logs += "$tag $message ${error?.message.orEmpty()}" }
    }

    @AfterTest
    fun tearDown() {
        BoxLog.sink = savedSink
        spool.deleteRecursively()
    }

    // ---- unit level: PhoneApi ----

    private class Api(val api: PhoneApi, val cookie: String)

    private fun api(
        purpose: PhonePurpose = PhonePurpose.NETWORK_FOLDER,
        context: PhoneContext? = PhoneContext("NAS", "TV", listOf("Media", "TV")),
        canAccept: () -> Boolean = { true },
    ): Api {
        val session = PairingSession({ now.get() }, { b -> for (i in b.indices) b[i] = (counter++ * 31 + i).toByte() })
        val api = PhoneApi(
            session, purpose, hostPort, { null }, spool, onSubmission = { submissions += it }, canAccept = canAccept,
            folderContext = context,
        )
        val paired = session.pairWithToken(session.token, phoneIp)
        return Api(api, assertIs<PairingSession.PairResult.Paired>(paired).cookie)
    }

    private class Body(val bytes: ByteArray) : BodySource {
        override val length: Long = bytes.size.toLong()
        override fun copyTo(sink: OutputStream, max: Long): Long {
            ByteArrayInputStream(bytes).copyTo(sink)
            return length
        }
    }

    private fun postFolder(a: Api, json: String, cookie: String? = a.cookie, xTheBox: Boolean = true): HttpResponse {
        val headers = buildMap {
            put("host", hostPort)
            if (xTheBox) put("x-thebox", "1")
            if (cookie != null) put("cookie", "tb=$cookie")
        }
        return a.api.handle(HttpRequest("POST", "/api/network-folder", emptyMap(), headers, phoneIp, Body(json.encodeToByteArray())))
    }

    private fun state(a: Api): JsonObject {
        val r = a.api.handle(HttpRequest("GET", "/api/state", emptyMap(), mapOf("host" to hostPort, "cookie" to "tb=${a.cookie}"), phoneIp, null))
        assertEquals(200, r.status)
        return Json.parseToJsonElement(r.body!!.decodeToString()).jsonObject
    }

    private fun signIn(host: String = "NAS", share: String? = "TV", path: String? = "Rewind", guest: Boolean = false, pw: String = password) =
        buildString {
            append("{\"host\":").append(Json.encodeToString(host))
            if (share != null) append(",\"share\":").append(Json.encodeToString(share))
            if (path != null) append(",\"path\":").append(Json.encodeToString(path))
            append(",\"user\":").append(Json.encodeToString(user))
            append(",\"domain\":\"WORKGROUP\"")
            append(",\"password\":").append(Json.encodeToString(pw))
            append(",\"guest\":").append(guest)
            append('}')
        }

    @Test
    fun theEndpointIsOnlyForNetworkFolderAndBehindTheM5Gate() {
        for (p in PhonePurpose.entries - PhonePurpose.NETWORK_FOLDER) {
            assertEquals(409, postFolder(api(p, context = null), signIn()).status, "purpose $p")
        }
        val a = api()
        assertEquals(401, postFolder(a, signIn(), cookie = null).status)
        assertEquals(403, postFolder(a, signIn(), xTheBox = false).status)
        assertTrue(submissions.isEmpty())
        assertEquals(202, postFolder(a, signIn()).status)
        val s = assertIs<PhoneSubmission.NetworkFolder>(submissions.single())
        assertEquals("NAS", s.host)
        assertEquals("TV", s.share)
        assertEquals("Rewind", s.path)
        assertEquals(user, s.user)
        assertEquals("WORKGROUP", s.domain)
        assertContentEquals(password.toCharArray(), s.password)
        assertFalse(s.guest)
        assertFalse(s.toString().contains(password))
        assertFalse(s.toString().contains(user))
    }

    @Test
    fun theBodyBufferIsZeroedAfterUse() {
        val a = api()
        val buffers = ArrayList<ByteArray>()
        a.api.bodyBufferProbe = { buffers += it }
        assertEquals(202, postFolder(a, signIn()).status)
        a.api.reportStatus("NAS didn't accept that user name or password.", done = false)
        assertEquals(400, postFolder(a, "{\"host\":\"NAS\",\"password\":\"$password\"").status) // broken JSON
        assertEquals(400, postFolder(a, signIn(host = "")).status) // refused after parsing
        assertEquals(3, buffers.size)
        for (b in buffers) {
            assertTrue(b.isNotEmpty())
            assertTrue(b.all { it == 0.toByte() }, "a body buffer still holds bytes")
        }
    }

    @Test
    fun thePasswordAndTheFullUserNameNeverReachTheState() {
        val a = api()
        assertEquals(202, postFolder(a, signIn()).status)
        val checking = state(a)
        assertEquals(PhoneApi.TV_CHECKING, checking["tv"]!!.jsonPrimitive.content)
        assertEquals("sh***", checking["received"]!!.jsonObject["user"]!!.jsonPrimitive.content)
        a.api.reportStatus("NAS didn't accept that user name or password. Check them and send again.", done = false)
        val retry = state(a)
        assertEquals(PhoneApi.TV_RETRY, retry["tv"]!!.jsonPrimitive.content)
        assertEquals(1, retry["failures"]!!.jsonPrimitive.content.toInt())
        assertEquals(LanLimits.PAIR_MAX_WRONG, retry["maxFailures"]!!.jsonPrimitive.content.toInt())
        for (s in listOf(checking, retry)) {
            val text = s.toString()
            assertFalse(text.contains(password), "password in /api/state")
            assertFalse(text.contains(user), "full user name in /api/state")
            assertFalse(text.contains("WORKGROUP"), "domain in /api/state")
        }
        // The context the TV knows is mirrored, never a credential.
        val folder = retry["folder"]!!.jsonObject
        assertEquals("NAS", folder["host"]!!.jsonPrimitive.content)
        assertEquals(listOf("Media", "TV"), folder["shares"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun otherPurposesCarryNoFolderState() {
        val st = state(api(PhonePurpose.SETUP, context = PhoneContext("NAS", null, emptyList())))
        assertNull(st["folder"])
        assertNull(st["received"])
    }

    @Test
    fun theUserNameAndPasswordAreRegisteredForRedaction() {
        val a = api()
        assertEquals(202, postFolder(a, signIn()).status)
        val line = Redact.text("signing in as $user with $password")
        assertFalse(line.contains(password))
        assertFalse(line.contains(user))
    }

    @Test
    fun aGuestSignInCarriesNoCredential() {
        val a = api()
        assertEquals(202, postFolder(a, signIn(guest = true)).status)
        val s = assertIs<PhoneSubmission.NetworkFolder>(submissions.single())
        assertTrue(s.guest)
        assertNull(s.user)
        assertNull(s.password)
        assertNull(s.domain)
        assertEquals("true", state(a)["received"]!!.jsonObject["guest"]!!.jsonPrimitive.content)
    }

    @Test
    fun aUserIsRequiredUnlessGuest() {
        val a = api()
        assertEquals(400, postFolder(a, "{\"host\":\"NAS\",\"share\":\"TV\",\"guest\":false,\"password\":\"x\"}").status)
        assertTrue(submissions.isEmpty())
        assertEquals(JsonNull, state(a)["received"])
    }

    @Test
    fun oneSubmissionIsCheckedAtATime() {
        val a = api()
        assertEquals(202, postFolder(a, signIn()).status)
        assertEquals(409, postFolder(a, signIn()).status)
        a.api.reportStatus("Can't reach NAS (192.168.1.20). Check it's on and on the same network.", done = false)
        assertEquals(202, postFolder(a, signIn()).status)
        assertEquals(2, submissions.size)
    }

    @Test
    fun aRefusedSubmissionZeroesThePasswordAndFreesTheSlot() {
        val a = api(canAccept = { false })
        assertEquals(503, postFolder(a, signIn()).status)
        assertTrue(submissions.isEmpty())
        assertEquals(503, postFolder(a, signIn()).status, "the slot was freed (no 409)")
        assertEquals(JsonNull, state(a)["received"])
    }

    @Test
    fun theFirstSuccessEndsTheEndpoint() {
        val a = api()
        assertEquals(202, postFolder(a, signIn()).status)
        a.api.reportStatus("Signed in to NAS. Choose the folder on the TV.", done = true)
        assertTrue(a.api.folderSignedIn)
        assertEquals(410, postFolder(a, signIn()).status)
        val st = state(a)
        assertEquals(PhoneApi.TV_DONE, st["tv"]!!.jsonPrimitive.content)
        assertEquals("signed_in", st["ended"]!!.jsonPrimitive.content)
        assertEquals("Signed in to NAS. Choose the folder on the TV.", st["message"]!!.jsonPrimitive.content)
        a.api.reportStatus("late", done = false) // nothing changes after the outcome
        assertEquals("Signed in to NAS. Choose the folder on the TV.", state(a)["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun pairMaxWrongFailedSignInsEndTheEndpoint() {
        val a = api()
        repeat(LanLimits.PAIR_MAX_WRONG) { i ->
            assertEquals(202, postFolder(a, signIn(pw = "wrong-$i")).status, "try ${i + 1}")
            a.api.reportStatus("NAS didn't accept that user name or password. Check them and send again.", done = false)
        }
        assertTrue(a.api.folderExhausted)
        assertEquals(410, postFolder(a, signIn()).status)
        val st = state(a)
        assertEquals(PhoneApi.TV_ENDED, st["tv"]!!.jsonPrimitive.content)
        assertEquals("too_many_sign_ins", st["ended"]!!.jsonPrimitive.content)
        // A status without a pending submission is no failure.
        val b = api()
        b.api.reportStatus("text only", done = false)
        assertEquals(0, b.api.folderFailures)
    }

    @Test
    fun nothingOfTheBodyIsLogged() {
        val a = api()
        postFolder(a, signIn())
        a.api.reportStatus("NAS didn't accept that user name or password.", done = false)
        postFolder(a, "{\"host\":\"NAS\",\"password\":\"$password\"")
        postFolder(a, signIn(host = "bad host"))
        val all = logs.joinToString("\n")
        assertFalse(all.contains(password))
        assertFalse(all.contains(user))
        assertFalse(all.contains("Rewind"))
    }

    // ---- the folder split (shared with app.js: scratch check runs the same vectors through node) ----

    /** C-N4: a control character in a share or folder name (a decoded `%00`, a newline) is refused server-side. */
    @Test
    fun aControlCharacterInAFolderIsRefused() {
        assertNull(PhoneApi.splitFolder("nas", "TV\u0000", null))
        assertNull(PhoneApi.splitFolder("nas", "TV", "Rewind\nx"))
        assertNull(PhoneApi.splitFolder("\\\\nas\\TV\\a\u007Fb", null, null))
        assertTrue(PhoneApi.splitFolder("nas", "TV", "Rewind") != null)
    }

    @Test
    fun splitFolderVectors() {
        val vectors = Json.parseToJsonElement(SPLIT_VECTORS).jsonArray
        assertTrue(vectors.size >= 20)
        for (v in vectors) {
            val o = v.jsonObject
            val input = o["in"]!!.jsonArray.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
            val got = PhoneApi.splitFolder(input[0], input[1], input[2])
            val want = o["out"]
            if (want == null || want is JsonNull) {
                assertNull(got, "$input")
            } else {
                val w = (want as JsonArray).map { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
                assertEquals(PhoneApi.Folder(w[0]!!, w[1], w[2]), got, "$input")
            }
        }
    }

    @Test
    fun aPastedPathIsSplitOnTheServerToo() {
        val a = api(context = null)
        assertEquals(202, postFolder(a, signIn(host = "", share = "\\\\NAS\\TV\\Rewind\\Box", path = null)).status)
        val s = assertIs<PhoneSubmission.NetworkFolder>(submissions.single())
        assertEquals(Triple("NAS", "TV", "Rewind/Box"), Triple(s.host, s.share, s.path))
    }

    @Test
    fun theUserNameIsMasked() {
        assertEquals("sh***", PhoneApi.maskUser("shawn"))
        assertEquals("***", PhoneApi.maskUser("ab"))
        assertEquals("***", PhoneApi.maskUser("a"))
    }

    // ---- end to end: LanPhoneLink ----

    private inner class Link(grace: Long = 1_000) {
        val transport = FakeTransportAdapter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val link = LanPhoneLink(
            object : LanAddressFinder {
                override fun find() = tvIp
                override fun vpnActive() = false
            },
            transport, { null }, spool, scope, clock = { now.get() },
            randomBytes = { b -> for (i in b.indices) b[i] = (counter++ * 37 + i).toByte() }, tickMs = 5, doneGraceMs = grace,
        )

        init {
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { link.submissions.collect { submissions += it } }
        }

        fun pair(context: PhoneContext? = PhoneContext("NAS", "TV", listOf("TV"))): String {
            link.start(PhonePurpose.NETWORK_FOLDER, context)
            val token = link.state.value.qrUrl!!.substringAfter("/s/")
            return assertNotNull(transport.exchange(get("/s/$token", hostPort)).cookie())
        }

        fun send(cookie: String, body: String = signIn()) = transport.exchange(post("/api/network-folder", hostPort, body, cookie)).status
        fun state(cookie: String) = transport.exchange(get("/api/state", hostPort, cookie))

        fun close() {
            link.stop()
            scope.cancel()
        }
    }

    @Test
    fun endToEndSuccessIsOneShotAndThePhoneCanStillReadIt() {
        val l = Link()
        try {
            val cookie = l.pair()
            assertEquals(202, l.send(cookie))
            assertEquals(ServerPhase.RECEIVED, l.link.state.value.phase)
            assertEquals(1, submissions.size)
            l.link.reportStatus("Signed in to NAS. Choose the folder on the TV.", done = true)
            // The TV replaces the QR page at once; the phone still reads the result during the grace.
            l.link.stop()
            val st = l.state(cookie)
            assertEquals(200, st.status)
            assertTrue(st.body.contains("signed_in"))
            assertEquals(410, l.send(cookie))
            // The scheduled stop of the grace then closes it (no request after that: the fake transport has no acceptor).
            awaitValue { l.link.state.value.phase.takeIf { it == ServerPhase.CLOSED } }
            assertEquals(1, submissions.size)
        } finally {
            l.close()
        }
    }

    @Test
    fun endToEndTooManyFailedSignInsStopTheSession() {
        val l = Link()
        try {
            val cookie = l.pair()
            repeat(LanLimits.PAIR_MAX_WRONG) { i ->
                assertEquals(202, l.send(cookie, signIn(pw = "nope-$i")), "try ${i + 1}")
                l.link.reportStatus("NAS didn't accept that user name or password. Check them and send again.", done = false)
                if (i < LanLimits.PAIR_MAX_WRONG - 1) {
                    assertEquals(ServerPhase.PAIRED, l.link.state.value.phase)
                    // U-N1 (fix pass): the TV's state carries the failed phone sign-in's text.
                    assertEquals("NAS didn't accept that user name or password. Check them and send again.", l.link.state.value.lastSignInStatus)
                    assertEquals(false, l.link.state.value.lastSignInOk)
                }
            }
            assertEquals(410, l.send(cookie))
            val failed = awaitValue { l.link.state.value.takeIf { it.phase == ServerPhase.FAILED } }
            assertEquals(LanFailure.TOO_MANY_WRONG_CODES, failed.failure)
            assertEquals(phoneIp, failed.pairedFrom)
            assertEquals(false, failed.lastSignInOk)
            assertTrue(PhoneScreenModel.tooManySignIns(failed, PhonePurpose.NETWORK_FOLDER))
            assertFalse(PhoneScreenModel.tooManySignIns(failed, PhonePurpose.SETUP))
            assertFalse(PhoneScreenModel.tooManySignIns(LanServerState(ServerPhase.FAILED, failure = LanFailure.TOO_MANY_WRONG_CODES), PhonePurpose.NETWORK_FOLDER))
            assertEquals(LanLimits.PAIR_MAX_WRONG, submissions.size)
        } finally {
            l.close()
        }
    }

    @Test
    fun endToEndTheContextIsBoundToItsSession() {
        val l = Link()
        try {
            val first = l.pair(PhoneContext("NAS", "TV", listOf("TV")))
            assertTrue(l.state(first).body.contains("\"host\":\"NAS\""))
            // Another browse is another start: the old phone session is gone, the new one shows the new context.
            val second = l.pair(PhoneContext("PC", null, emptyList()))
            assertEquals(401, l.state(first).status) // the old cookie belongs to no session
            val body = l.state(second).body
            assertTrue(body.contains("\"host\":\"PC\""))
            assertFalse(body.contains("NAS"))
        } finally {
            l.close()
        }
    }

    @Test
    fun endToEndStopBeforeAnyResultStopsAtOnce() {
        val l = Link()
        try {
            val cookie = l.pair()
            assertEquals(202, l.send(cookie))
            l.link.stop()
            assertEquals(ServerPhase.CLOSED, l.link.state.value.phase)
        } finally {
            l.close()
        }
    }

    private companion object {
        /** `[host, share, path]` → `[host, share, path]` or null. The same list runs through `app.js`'s splitFolder. */
        // SPLIT_VECTORS_BEGIN
        const val SPLIT_VECTORS = """[
{"in":["nas","TV","Rewind"],"out":["nas","TV","Rewind"]},
{"in":["  nas.local ","TV",null],"out":["nas.local","TV",null]},
{"in":["192.168.1.20","TV",""],"out":["192.168.1.20","TV",null]},
{"in":["192.168.1.20:4445","TV","a/b"],"out":["192.168.1.20:4445","TV","a/b"]},
{"in":["nas",null,null],"out":["nas",null,null]},
{"in":["nas","",""],"out":["nas",null,null]},
{"in":["","\\\\NAS\\TV\\Rewind",""],"out":["NAS","TV","Rewind"]},
{"in":["nas","\\\\NAS2\\TV\\Rewind","Box"],"out":["NAS2","TV","Rewind/Box"]},
{"in":["","smb://nas/TV/Rewind",null],"out":["nas","TV","Rewind"]},
{"in":["","SMB://nas:4445/TV",null],"out":["nas:4445","TV",null]},
{"in":["","//nas/TV/",null],"out":["nas","TV",null]},
{"in":["\\\\NAS\\TV\\Rewind",null,null],"out":["NAS","TV","Rewind"]},
{"in":["smb://nas",null,null],"out":["nas",null,null]},
{"in":["nas","TV\\Rewind","Box\\Sub"],"out":["nas","TV","Rewind/Box/Sub"]},
{"in":["nas","/TV/","/Rewind//./Box/"],"out":["nas","TV","Rewind/Box"]},
{"in":["[fe80::1]:445","TV",null],"out":["[fe80::1]:445","TV",null]},
{"in":["","TV",null],"out":null},
{"in":[null,null,null],"out":null},
{"in":["nas",null,"Rewind"],"out":null},
{"in":["nas","TV","../etc"],"out":null},
{"in":["nas","..",null],"out":null},
{"in":["bad host","TV",null],"out":null},
{"in":["user@nas","TV",null],"out":null},
{"in":["nas:0","TV",null],"out":null},
{"in":["nas:70000","TV",null],"out":null},
{"in":["http://nas","TV",null],"out":null},
{"in":["-nas","TV",null],"out":null},
{"in":["","\\\\",null],"out":null}
]"""
        // SPLIT_VECTORS_END
    }
}
