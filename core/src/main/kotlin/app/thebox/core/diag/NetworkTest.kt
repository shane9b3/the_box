package app.thebox.core.diag

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/*
 * The connection test of docs/design/m5-setup-settings.md §3.11.2 as pure logic: the steps call [NetworkProbes] (the
 * platform side lives in :data), each StepResult streams out as it finishes, and the verdict is the first rule of
 * [NetworkVerdict] that matches. StepResult.detail is a machine-readable `key=value` list (never user text, never a
 * host with credentials); :ui formats it with strings_diag.xml.
 */

/** What the test talks to. Never logged: [username]/[password] are only handed to the Xtream probe. */
class TestTarget(
    val playlistId: Long,
    val xtream: Boolean,
    /** `host` of the login URL (no userinfo). */
    val host: String,
    val port: Int,
    val https: Boolean,
    /** M3U: the playlist URL; Xtream: the base URL. */
    val url: String,
    val username: String? = null,
    val password: String? = null,
    /** (D-L9, code review 2026-10-16; additive) The playlist's own User-Agent (a VLC-only panel refuses others); null = default. */
    val userAgent: String? = null,
) {
    override fun toString(): String = "TestTarget(playlist=$playlistId, ${if (xtream) "xtream" else "m3u"}, $host:$port)"
}

enum class DnsOutcome { OK, FAILED, BLOCKED }

data class LookupResult(val addresses: List<String>, val ms: Long, val failed: Boolean = addresses.isEmpty()) {
    val v4: List<String> get() = addresses.filter { !it.contains(':') }
    val v6: List<String> get() = addresses.filter { it.contains(':') }
}

data class ConnectResult(val ok: Boolean, val ms: Long)

enum class HttpOutcome { OK, AUTH_REFUSED, TLS_FAILED, HTTP_ERROR, NETWORK_ERROR, NOT_A_SOURCE }

/** One request of the Server or Response-time step. [status] null when no HTTP response arrived. */
data class HttpProbe(val outcome: HttpOutcome, val status: Int?, val ms: Long, val accountActive: Boolean? = null)

/** 10 s of the playing channel's delivery (§3.11.2 "Stream speed"); null when nothing of this playlist plays. */
data class StreamSample(
    val deliveredBps: Long,
    /** Media consumed per second of wall time while sampling, in bits; ≥ delivered when the buffer drained. */
    val neededBps: Long,
    val bufferDrops: Int,
    val wifiLinkMbps: Int? = null,
)

/** Platform seams of the connection test (`:data` implements them with InetAddress, DoH, sockets and OkHttp). */
interface NetworkProbes {
    suspend fun systemLookup(host: String): LookupResult
    /** Null: skipped (DNS is already secure, or [host] is an IP literal). */
    suspend fun secureLookup(host: String): LookupResult?
    suspend fun connect(address: String, port: Int, timeoutMs: Long): ConnectResult
    /** Xtream: `player_api.php` login; M3U: GET of the playlist, first 64 KB, then cancel. */
    suspend fun server(target: TestTarget): HttpProbe
    /** One `player_api.php` call for the Response-time step (Xtream only). */
    suspend fun apiCall(target: TestTarget): HttpProbe
    suspend fun streamSample(playlistId: Long): StreamSample?
}

/** What the steps found; the input of [NetworkVerdict]. */
data class NetworkFindings(
    val systemDns: DnsOutcome,
    /** Null: the secure lookup was skipped. */
    val secureDns: DnsOutcome? = null,
    /** Null: no address of that family to try. */
    val ipv4Connects: Boolean? = null,
    val ipv6Connects: Boolean? = null,
    val port: Int,
    val server: HttpOutcome? = null,
    val stream: StreamSample? = null,
)

sealed interface NetworkTestEvent {
    data class Step(val result: StepResult) : NetworkTestEvent
    data class Done(val result: NetworkTestResult) : NetworkTestEvent
}

object NetworkVerdict {
    /** Delivered below 1.2 × needed with buffer drops = the provider is too slow (§3.11.2). */
    const val SLOW_FACTOR = 1.2
    /** The TV's own link is the bottleneck only when it cannot carry the channel at all: the platform figure is an estimate (F27). */
    const val LINK_FACTOR = 1.0

    /**
     * The first rule that matches (`NetworkVerdictTest`, order of §3.11.2):
     * 1. system DNS failed or blocked and secure DNS works → DNS_BLOCKED_TRY_SECURE;
     * 2. both DNS failed → NAME_NOT_FOUND (M5 integration amendment; WP-E used SERVER_UNREACHABLE with no args);
     * 3. no address connects (or the server step failed below HTTP) → SERVER_UNREACHABLE(port);
     * 4. TLS failed → TLS_PROBLEM; 5. 401/403/`auth:0` → AUTH_PROBLEM;
     * 6. IPv6 fails and IPv4 works → IPV6_BROKEN;
     * 7. delivered < 1.2 × needed with buffer drops → SLOW_CONNECTION(link, needed) when the TV's Wi-Fi link is below
     *    what the channel needs, else SLOW_PROVIDER(delivered, needed) (Mbit/s, one decimal);
     * 8. ALL_GOOD.
     */
    fun decide(f: NetworkFindings): Verdict {
        val systemBad = f.systemDns != DnsOutcome.OK
        if (systemBad && f.secureDns == DnsOutcome.OK) return Verdict(VerdictKey.DNS_BLOCKED_TRY_SECURE, emptyList())
        if (systemBad) return Verdict(VerdictKey.NAME_NOT_FOUND, emptyList())
        val anyConnects = f.ipv4Connects == true || f.ipv6Connects == true
        if (!anyConnects || f.server == HttpOutcome.NETWORK_ERROR) return Verdict(VerdictKey.SERVER_UNREACHABLE, listOf(f.port.toString()))
        if (f.server == HttpOutcome.TLS_FAILED) return Verdict(VerdictKey.TLS_PROBLEM, emptyList())
        if (f.server == HttpOutcome.AUTH_REFUSED) return Verdict(VerdictKey.AUTH_PROBLEM, emptyList())
        if (f.ipv6Connects == false && f.ipv4Connects == true) return Verdict(VerdictKey.IPV6_BROKEN, emptyList())
        val s = f.stream
        if (s != null && s.neededBps > 0 && s.deliveredBps < SLOW_FACTOR * s.neededBps && s.bufferDrops > 0) {
            val link = s.wifiLinkMbps
            if (link != null && link * 1_000_000.0 < LINK_FACTOR * s.neededBps) {
                return Verdict(VerdictKey.SLOW_CONNECTION, listOf(link.toString(), mbit(s.neededBps)))
            }
            return Verdict(VerdictKey.SLOW_PROVIDER, listOf(mbit(s.deliveredBps), mbit(s.neededBps)))
        }
        if (f.server == HttpOutcome.HTTP_ERROR || f.server == HttpOutcome.NOT_A_SOURCE) return Verdict(VerdictKey.SERVER_UNREACHABLE, listOf(f.port.toString()))
        return Verdict(VerdictKey.ALL_GOOD, emptyList())
    }

    fun mbit(bps: Long): String = String.format(java.util.Locale.ROOT, "%.1f", bps / 1_000_000.0)

    /**
     * A system answer that looks like a DNS block (§3.11.2): `0.0.0.0`, loopback, or a private address for a public
     * name. A name in a private suffix (`.local`, `.lan`, `.home`, `.test`, `.internal`) may resolve privately.
     */
    fun looksBlocked(host: String, answers: List<String>): Boolean {
        if (answers.isEmpty() || isIpLiteral(host)) return false
        val privateName = PRIVATE_SUFFIXES.any { host.lowercase().endsWith(it) } || !host.contains('.')
        return answers.all { a -> a == "0.0.0.0" || a == "::" || a.startsWith("127.") || a == "::1" || (!privateName && isPrivate(a)) }
    }

    private val PRIVATE_SUFFIXES = listOf(".local", ".lan", ".home", ".test", ".internal", ".localdomain")

    fun isIpLiteral(host: String): Boolean = host.contains(':') || host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))

    fun isPrivate(a: String): Boolean {
        if (a.contains(':')) return a.lowercase().let { it.startsWith("fc") || it.startsWith("fd") || it.startsWith("fe80") }
        val p = a.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254) || (p[0] == 100 && p[1] in 64..127)
    }
}

/**
 * Runs the six steps in order (§3.11.2), emitting each [StepResult] as it finishes and the [NetworkTestResult] last.
 * Details (key=value, `;`-separated): DNS `n=2;v4=1;v6=1` or `error=nxdomain|blocked`, secure DNS adds `agree=1|0`;
 * Connect `v4=42|fail;v6=fail|none;port=8080`; Server `status=200;active=1` or `error=tls|auth|net|format`;
 * Response time `median=310`; Stream speed `delivered=6100000;needed=8000000;drops=2`, or `skipped=1`.
 */
class NetworkTestRunner(
    private val probes: NetworkProbes,
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
) {
    fun run(target: TestTarget): Flow<NetworkTestEvent> = flow {
        val steps = ArrayList<StepResult>(6)
        suspend fun emitStep(s: StepResult) {
            steps += s
            emit(NetworkTestEvent.Step(s))
        }

        // 1. DNS (system)
        val sys = guard { probes.systemLookup(target.host) } ?: LookupResult(emptyList(), 0)
        val blocked = !sys.failed && NetworkVerdict.looksBlocked(target.host, sys.addresses)
        val systemDns = when { sys.failed -> DnsOutcome.FAILED; blocked -> DnsOutcome.BLOCKED; else -> DnsOutcome.OK }
        emitStep(StepResult(TestStep.DNS_SYSTEM, systemDns == DnsOutcome.OK, sys.ms, dnsDetail(sys, blocked)))

        // 2. DNS (secure): skipped for IP literals and when DNS is already secure
        val sec = if (NetworkVerdict.isIpLiteral(target.host)) null else guard { probes.secureLookup(target.host) }
        val secureDns = sec?.let { if (it.failed) DnsOutcome.FAILED else DnsOutcome.OK }
        if (sec == null) {
            emitStep(StepResult(TestStep.DNS_SECURE, true, null, "skipped=1"))
        } else {
            val agree = !sec.failed && !sys.failed && sec.addresses.toSet().intersect(sys.addresses.toSet()).isNotEmpty()
            emitStep(StepResult(TestStep.DNS_SECURE, !sec.failed, sec.ms, dnsDetail(sec, false) + ";agree=${if (agree) 1 else 0}"))
        }

        // 3. Connect: the first address of each family the system (or, when it is blocked, the secure) answer has
        val answers = if (systemDns == DnsOutcome.OK) sys else sec?.takeIf { !it.failed } ?: sys
        val v4 = answers.v4.firstOrNull()?.let { guard { probes.connect(it, target.port, connectTimeoutMs) } ?: ConnectResult(false, connectTimeoutMs) }
        val v6 = answers.v6.firstOrNull()?.let { guard { probes.connect(it, target.port, connectTimeoutMs) } ?: ConnectResult(false, connectTimeoutMs) }
        val connectOk = v4?.ok == true || v6?.ok == true
        emitStep(StepResult(TestStep.TCP_CONNECT, connectOk, listOfNotNull(v4?.takeIf { it.ok }?.ms, v6?.takeIf { it.ok }?.ms).minOrNull(),
            "v4=${famDetail(v4)};v6=${famDetail(v6)};port=${target.port}"))

        // 4. Server
        val server = if (connectOk) guard { probes.server(target) } ?: HttpProbe(HttpOutcome.NETWORK_ERROR, null, 0) else null
        if (server == null) {
            emitStep(StepResult(TestStep.HTTP, false, null, "skipped=1"))
        } else {
            emitStep(StepResult(TestStep.HTTP, server.outcome == HttpOutcome.OK, server.ms, serverDetail(server)))
        }

        // 5. Response time: median of 3 player_api.php calls (Xtream only, after a good login)
        if (target.xtream && server?.outcome == HttpOutcome.OK) {
            val times = ArrayList<Long>(3)
            repeat(LATENCY_CALLS) { guard { probes.apiCall(target) }?.takeIf { it.outcome == HttpOutcome.OK }?.let { times += it.ms } }
            val median = times.sorted().getOrNull(times.size / 2)
            emitStep(StepResult(TestStep.API_LATENCY, median != null, median, if (median != null) "median=$median;calls=${times.size}" else "error=net"))
        } else {
            emitStep(StepResult(TestStep.API_LATENCY, true, null, "skipped=1"))
        }

        // 6. Stream speed: only when a channel of this playlist is playing
        val sample = guard { probes.streamSample(target.playlistId) }
        if (sample == null) {
            emitStep(StepResult(TestStep.STREAM_THROUGHPUT, true, null, "skipped=1"))
        } else {
            val ok = !(sample.deliveredBps < NetworkVerdict.SLOW_FACTOR * sample.neededBps && sample.bufferDrops > 0)
            emitStep(StepResult(TestStep.STREAM_THROUGHPUT, ok, null,
                "delivered=${sample.deliveredBps};needed=${sample.neededBps};drops=${sample.bufferDrops}" + (sample.wifiLinkMbps?.let { ";link=$it" } ?: "")))
        }

        val findings = NetworkFindings(
            systemDns = systemDns, secureDns = secureDns,
            ipv4Connects = v4?.ok, ipv6Connects = v6?.ok, port = target.port,
            server = server?.outcome, stream = sample,
        )
        emit(NetworkTestEvent.Done(NetworkTestResult(target.playlistId, steps, NetworkVerdict.decide(findings))))
    }

    /** A probe that throws counts as a failed step (never crashes the page); cancellation propagates. */
    private suspend fun <T> guard(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun dnsDetail(r: LookupResult, blocked: Boolean): String = when {
        r.failed -> "error=nxdomain"
        blocked -> "error=blocked;n=${r.addresses.size}"
        else -> "n=${r.addresses.size};v4=${r.v4.size};v6=${r.v6.size}"
    }

    private fun famDetail(c: ConnectResult?): String = when {
        c == null -> "none"
        c.ok -> c.ms.toString()
        else -> "fail"
    }

    private fun serverDetail(p: HttpProbe): String = when (p.outcome) {
        HttpOutcome.OK -> "status=${p.status ?: 0}" + (p.accountActive?.let { ";active=${if (it) 1 else 0}" } ?: "")
        HttpOutcome.AUTH_REFUSED -> "error=auth" + (p.status?.let { ";status=$it" } ?: "")
        HttpOutcome.TLS_FAILED -> "error=tls"
        HttpOutcome.HTTP_ERROR -> "error=http;status=${p.status ?: 0}"
        HttpOutcome.NETWORK_ERROR -> "error=net"
        HttpOutcome.NOT_A_SOURCE -> "error=format" + (p.status?.let { ";status=$it" } ?: "")
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val LATENCY_CALLS = 3

        /** Parses a step detail back into its keys (the UI's formatter). */
        fun parseDetail(detail: String): Map<String, String> = detail.split(';').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else kv.substring(0, i) to kv.substring(i + 1)
        }.toMap()
    }
}

/** The richer seam behind `Diagnostics.networkTest` (steps and verdict), for the NetworkTest page. `:data` implements it. */
fun interface NetworkTests {
    fun run(playlistId: Long): Flow<NetworkTestEvent>
}
