package app.thebox.core.diag

import app.thebox.core.common.Redact
import app.thebox.core.playback.PlaybackEvent
import app.thebox.core.playback.ZapRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.OutputStream
import java.io.Writer

/** One playlist line of the export (§3.11.4): type, display host, counts, last status, `needs_reauth`. No URL, no login. */
data class ExportPlaylist(val id: Long, val type: String, val displayHost: String, val channels: Int, val lastStatus: String?, val needsReauth: Boolean, val enabled: Boolean)

/**
 * Everything the bundle is built from (§3.11.4). [extraSecrets]: secrets known at export time that the audit must find
 * even if nobody registered them with [Redact] (every stored username and password, read once by the caller).
 */
class ExportInputs(
    val header: List<Pair<String, String>>,
    val deviceFacts: DeviceFacts?,
    val settings: Map<String, JsonElement>,
    val playlists: List<ExportPlaylist>,
    val syncStatus: List<String>,
    val refreshReports: List<String>,
    val events: List<PlaybackEvent>,
    val zaps: List<ZapRecord>,
    /** Rotated log files, oldest first, then the ring (each oldest first). */
    val logs: List<List<String>>,
    /** Newest first; at most [ExportBundleBuilder.CRASH_REPORTS] are written. */
    val crashReports: List<String>,
    val anrNotes: List<String>,
    val extraSecrets: Collection<String> = emptyList(),
)

data class ExportSummary(val bytes: Long, val auditHits: Int, val droppedLogLines: Int)

/**
 * The redacted log export (docs/design/m5-setup-settings.md §3.11.4; `ExportRedactionTest`). One text file of at most
 * [maxBytes]: header · device facts · settings snapshot (enums, booleans and numbers only; every string `<set>` or
 * `<empty>`) · playlists · sync status and the last 10 refresh reports · playback events (200) and zaps (50) · logs
 * (oldest lines dropped first to fit) · the last 5 crash reports · ANR notes. Every line passes [Redact.text] again,
 * then [RedactionAudit] looks for anything that still looks like a secret, replaces it with `****` and counts it in the
 * footer.
 */
class ExportBundleBuilder(private val maxBytes: Int = LogLimits.LOG_EXPORT_MAX_BYTES) {

    fun write(inputs: ExportInputs, out: OutputStream): ExportSummary {
        val audit = RedactionAudit(inputs.extraSecrets)
        val head = ArrayList<String>(512)
        val tail = ArrayList<String>(256)

        section(head, "The Box log export")
        inputs.header.forEach { (k, v) -> head += "$k: $v" }

        section(head, "Device")
        inputs.deviceFacts?.let { head += deviceLines(it) } ?: run { head += "(not available)" }

        section(head, "Settings")
        if (inputs.settings.isEmpty()) head += "(defaults)"
        for ((domain, json) in inputs.settings.toSortedMap()) settingsLines(domain, json, head)

        section(head, "Playlists")
        if (inputs.playlists.isEmpty()) head += "(none)"
        inputs.playlists.forEach { p ->
            head += "#${p.id} ${p.type} ${p.displayHost} channels=${p.channels} enabled=${p.enabled} needs_reauth=${p.needsReauth} last=${p.lastStatus ?: "-"}"
        }

        section(head, "Sync status")
        head += inputs.syncStatus.ifEmpty { listOf("(idle)") }
        section(head, "Refresh reports (last $REFRESH_REPORTS)")
        head += inputs.refreshReports.takeLast(REFRESH_REPORTS).ifEmpty { listOf("(none)") }

        section(head, "Playback events (last $EVENTS)")
        inputs.events.takeLast(EVENTS).forEach { e -> head += "${e.wallMs} +${e.elapsedMs} tune=${e.tuneId} ${e.type} ${e.code} ${e.detail}" }
        section(head, "Zaps (last $ZAPS)")
        inputs.zaps.takeLast(ZAPS).forEach { z ->
            head += "tune=${z.tuneId} ${z.format} resolve=${z.resolveMs} lease=${z.leaseMs} firstByte=${z.firstByteMs} ready=${z.readyMs} firstFrame=${z.firstFrameMs} ${z.outcome}"
        }

        section(tail, "Crash reports (last $CRASH_REPORTS)")
        val crashes = inputs.crashReports.take(CRASH_REPORTS)
        if (crashes.isEmpty()) tail += "(none)"
        crashes.forEachIndexed { i, r -> tail += "--- report ${i + 1}"; tail += r.lines() }
        section(tail, "ANR watchdog")
        tail += inputs.anrNotes.ifEmpty { listOf("(none)") }

        val headClean = head.map(audit::clean)
        val tailClean = tail.map(audit::clean)
        val footerReserve = 256
        var budget = maxBytes.toLong() - bytesOf(headClean) - bytesOf(tailClean) - footerReserve - SECTION_OVERHEAD
        // Logs: newest lines kept, oldest dropped first.
        val all = inputs.logs.flatten()
        val kept = ArrayDeque<String>()
        var dropped = 0
        for (i in all.indices.reversed()) {
            val line = audit.clean(all[i])
            val cost = line.toByteArray(Charsets.UTF_8).size + 1L
            if (budget - cost < 0) {
                dropped = i + 1
                break
            }
            budget -= cost
            kept.addFirst(line)
        }

        val w = out.bufferedWriter(Charsets.UTF_8)
        var bytes = 0L
        fun line(s: String, wr: Writer = w) {
            wr.write(s)
            wr.write("\n")
            bytes += s.toByteArray(Charsets.UTF_8).size + 1
        }
        headClean.forEach { line(it) }
        line("")
        line("== Logs" + if (dropped > 0) " ($dropped oldest lines dropped to fit ${maxBytes / 1_048_576} MB)" else "")
        kept.forEach { line(it) }
        tailClean.forEach { line(it) }
        line("")
        line("== Redaction audit: ${audit.hits} value(s) replaced by ****")
        w.flush()
        return ExportSummary(bytes, audit.hits, dropped)
    }

    private fun section(into: MutableList<String>, title: String) {
        if (into.isNotEmpty()) into += ""
        into += "== $title"
    }

    private fun bytesOf(lines: List<String>): Long = lines.sumOf { it.toByteArray(Charsets.UTF_8).size + 1L }

    private fun deviceLines(f: DeviceFacts): List<String> = listOf(
        "model: ${f.manufacturer} ${f.model}", "os: ${f.os} (SDK ${f.sdk})" + (f.fireOs?.let { ", Fire OS $it" } ?: ""),
        "memory: ${f.memTotalMb} MB, class ${f.memoryClassMb} MB, low-RAM ${f.lowRam}, performance ${f.performanceMode}",
        "abis: ${f.abis.joinToString()}", "hdmi encodings: ${f.hdmiEncodings.joinToString().ifEmpty { "-" }}",
        "hdr: ${f.hdrTypes.joinToString().ifEmpty { "-" }}", "display modes: ${f.displayModes.joinToString().ifEmpty { "-" }}",
        "ffmpeg audio: ${f.ffmpegAudio}", "keystore: ${f.keystoreBacked}", "free storage: ${f.freeStorageMb} MB", "network: ${f.network}",
    ) + f.decoders.map { d -> "decoder ${d.mime}: ${d.name} ${if (d.hardware) "hw" else "sw"} max ${d.maxResolution ?: "?"}" }

    /** Enums, booleans and numbers only; every other string becomes `<set>`/`<empty>` (§3.11.4). */
    private fun settingsLines(domain: String, json: JsonElement, into: MutableList<String>) {
        fun walk(path: String, e: JsonElement) {
            when (e) {
                is JsonObject -> if (e.isEmpty()) into += "$path = {}" else e.forEach { (k, v) -> walk("$path.$k", v) }
                is JsonArray -> into += "$path = [${e.size} item(s)]"
                is JsonNull -> into += "$path = null"
                is JsonPrimitive -> into += "$path = " + when {
                    !e.isString -> e.content
                    e.content.isEmpty() -> "<empty>"
                    ENUM_LIKE.matches(e.content) -> e.content
                    else -> "<set>"
                }
            }
        }
        walk(domain, json)
    }

    companion object {
        const val REFRESH_REPORTS = 10
        const val EVENTS = 200
        const val ZAPS = 50
        const val CRASH_REPORTS = 5
        private const val SECTION_OVERHEAD = 128L
        /** An enum constant as kotlinx.serialization writes it (`SYSTEM`, `BACK_TWICE`, `FORCE_PASSTHROUGH`). */
        private val ENUM_LIKE = Regex("""[A-Z][A-Z0-9]*(_[A-Z0-9]+)*""")
    }
}

/**
 * The export's second pass (§3.11.4): [Redact.text] again, then anything that still looks like a secret is replaced by
 * `****` and counted: `username=`/`password=` (and `user`/`pass`/`token`/`auth`) values outside a URL query, Xtream
 * path credentials (`/live/u/p/`) not already masked, and every [extraSecrets] value anywhere.
 */
class RedactionAudit(extraSecrets: Collection<String>) {
    private val secrets: List<String> = extraSecrets.filter { it.length >= 3 }.distinct().sortedByDescending { it.length }
    var hits: Int = 0
        private set

    fun clean(line: String): String {
        var r = Redact.text(line)
        for (s in secrets) {
            if (r.contains(s)) {
                hits += countOf(r, s)
                r = r.replace(s, MASK)
            }
        }
        r = KEY_VALUE.replace(r) { m ->
            val v = m.groupValues[2]
            if (v.all { it == '*' }) m.value else { hits++; m.groupValues[1] + MASK }
        }
        r = XC_PATH.replace(r) { m ->
            if (m.groupValues[2] == "***" && m.groupValues[3] == "***") m.value else { hits++; "/${m.groupValues[1]}/$MASK/$MASK/" }
        }
        return r
    }

    private fun countOf(s: String, sub: String): Int {
        var n = 0
        var i = s.indexOf(sub)
        while (i >= 0) { n++; i = s.indexOf(sub, i + sub.length) }
        return n
    }

    companion object {
        const val MASK = "****"
        private val KEY_VALUE = Regex("""(?i)\b((?:username|password|passwd|pass|user|token|auth|passphrase|pin)\s*[=:]\s*)([^\s&;,"']+)""")
        private val XC_PATH = Regex("""(?i)/(live|movie|series|timeshift)/([^/\s?#]+)/([^/\s?#]+)/""")
    }
}
