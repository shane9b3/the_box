// Contract (M5 commit 0): docs/design/m5-setup-settings.md §2.9. Verbatim; changes go through the lead.
package app.thebox.core.diag

data class DeviceFacts(
    val model: String, val manufacturer: String, val os: String, val sdk: Int, val fireOs: String?,
    val memTotalMb: Int, val memoryClassMb: Int, val lowRam: Boolean, val performanceMode: String,
    val abis: List<String>, val hdmiEncodings: List<String>, val hdrTypes: List<String>, val displayModes: List<String>,
    val decoders: List<DecoderFact>, val ffmpegAudio: Boolean, val keystoreBacked: Boolean,
    val freeStorageMb: Long, val network: String,
)
data class DecoderFact(val mime: String, val name: String, val hardware: Boolean, val maxResolution: String?)

enum class TestStep { DNS_SYSTEM, DNS_SECURE, TCP_CONNECT, HTTP, API_LATENCY, STREAM_THROUGHPUT }
data class StepResult(val step: TestStep, val ok: Boolean, val ms: Long?, val detail: String)
data class NetworkTestResult(val playlistId: Long, val steps: List<StepResult>, val verdict: Verdict)
/** Text keys resolved in :ui; [args] are numbers only (never hosts with credentials). */
data class Verdict(val key: VerdictKey, val args: List<String>)
/** (M5 integration 2026-10-16, WP-E amendment) NAME_NOT_FOUND appended: both resolvers failed (the name does not exist). */
enum class VerdictKey { ALL_GOOD, DNS_BLOCKED_TRY_SECURE, SERVER_UNREACHABLE, TLS_PROBLEM, AUTH_PROBLEM, SLOW_PROVIDER, SLOW_CONNECTION, IPV6_BROKEN, NAME_NOT_FOUND }

interface Diagnostics {
    suspend fun deviceFacts(): DeviceFacts
    fun networkTest(playlistId: Long): kotlinx.coroutines.flow.Flow<StepResult>
    /** Builds the redacted export (§3.11.4) into [sink]. */
    suspend fun exportLogs(sink: java.io.OutputStream): app.thebox.core.common.Outcome<Long>
}
