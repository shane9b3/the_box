package app.thebox.core.playback.errors

import app.thebox.core.playback.AccountGate
import app.thebox.core.playback.FailureSignal
import app.thebox.core.playback.LadderContext
import app.thebox.core.playback.LadderCounters
import app.thebox.core.playback.LoadOrigin
import app.thebox.core.playback.NetworkFailureKind
import app.thebox.core.playback.StallKind
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.core.playback.XtreamOutput
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Golden table: `core/src/test/resources/playback/ladder.csv`, one dynamic test per line (§5.3 WP-A). */
class ErrorLadderTableTest {
    data class Case(val line: Int, val rule: String, val signal: String, val context: String, val expected: String, val row: String)

    private val cases: List<Case> by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/playback/ladder.csv")) { "ladder.csv missing" }
        stream.bufferedReader().readLines().mapIndexedNotNull { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
            val c = line.split(';').map { it.trim() }
            require(c.size == 5) { "ladder.csv:${i + 1}: expected 5 columns, got ${c.size}" }
            Case(i + 1, c[0], c[1], c[2], c[3], c[4])
        }
    }

    @TestFactory
    fun goldenTable(): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest("ladder.csv:${case.line} ${case.rule} ${case.signal} [${case.context}]") {
            val ladder = ErrorLadderImpl(random = { 0.5 })
            val overrides = parseContext(case.context)
            val ctx = ctx(overrides)
            val signal = signal(case.signal)
            repeat(overrides["prior"]?.toInt() ?: 0) { ladder.trace(signal, ctx) }
            val t = ladder.trace(signal, ctx)
            assertEquals(case.expected, ErrorLadderImpl.describe(t.decision), "decision, ladder.csv:${case.line}")
            assertEquals(case.row, t.row, "row, ladder.csv:${case.line}")
        }
    }

    @Test
    fun everyDesignRowIsInTheTable() {
        val covered = cases.map { it.rule }.toSet()
        val missing = (1..44).map { "L$it" }.filter { it !in covered }
        assertTrue(missing.isEmpty(), "ladder.csv lacks rows for $missing")
        assertTrue("L22a" in covered && "G-gate" in covered && "G-timeout" in covered && "G-limiter" in covered)
    }

    companion object {
        val FFMPEG_MIMES = setOf("audio/ac3", "audio/eac3", "audio/mpeg-L2")

        fun parseContext(s: String): Map<String, String> =
            if (s.isBlank()) emptyMap() else s.split(',').associate { kv -> kv.substringBefore('=').trim() to kv.substringAfter('=').trim() }

        fun ctx(o: Map<String, String> = emptyMap()): LadderContext {
            fun b(k: String, d: Boolean) = o[k]?.toBooleanStrict() ?: d
            fun i(k: String, d: Int) = o[k]?.toInt() ?: d
            fun l(k: String, d: Long) = o[k]?.toLong() ?: d
            val counters = LadderCounters(
                limitSteps = i("limitSteps", 0), forbiddenSteps = i("forbiddenSteps", 0), formatFlips = i("formatFlips", 0),
                sourceTypeSwitches = i("sourceTypeSwitches", 0), transientRetries = i("transientRetries", 0),
                baseRotations = i("baseRotations", 0), pcrResets = i("pcrResets", 0), behindLiveWindow = i("behindLiveWindow", 0),
                parsingRetries = i("parsingRetries", 0), decoderSteps = i("decoderSteps", 0), audioSteps = i("audioSteps", 0),
                stallRecoveries = i("stallRecoveries", 0), unknownRetries = i("unknownRetries", 0), uaFallbackTried = b("uaTried", false),
            )
            val output = o["output"].let { if (it == null) XtreamOutput.TS else if (it == "null") null else XtreamOutput.valueOf(it) }
            return LadderContext(
                nowMs = l("now", 1_000_000), msSinceTuneStart = l("ms", 1_000), firstFrameRendered = b("firstFrame", false),
                isLive = b("live", true), isArchive = b("archive", false), format = StreamFormat.valueOf(o["format"] ?: "TS"),
                isXtream = b("xtream", true), baseCount = i("baseCount", 1), output = output, counters = counters,
                reResolveAllowed = b("reResolveAllowed", true), reconnectAttemptsSetting = i("attempts", 5),
                tunnelingActive = b("tunneling", false), passthroughActive = b("passthrough", false), passthroughMime = o["passthroughMime"],
                audioChannelCount = i("channels", 2), videoDecoderMode = VideoDecoderMode.valueOf(o["decoderMode"] ?: "AUTO"),
                softwareVideoAvailable = b("swVideo", true), ffmpegSupportsMime = { it in FFMPEG_MIMES },
                uaFallbackAvailable = b("uaFallback", false), backupAvailable = b("backup", false), tsCompatLevel = i("compat", 0),
                accountActive = o["active"]?.toInt(), accountMax = o["max"]?.toInt(), accountStatus = AccountGate.valueOf(o["account"] ?: "UNKNOWN"),
                lastHost = "h.example:8080",
            )
        }

        fun signal(s: String): FailureSignal {
            val t = s.split(' ').filter { it.isNotEmpty() }
            fun video() = t[1] == "video"
            return when (t[0]) {
                "Http" -> FailureSignal.Http(t[1].toInt(), LoadOrigin.valueOf(t[2]))
                "Network" -> FailureSignal.Network(NetworkFailureKind.valueOf(t[1]), t[2] == "before")
                "ReconnectExhausted" -> FailureSignal.ReconnectExhausted(t[1].toIntOrNull(), t[2] == "nobytes")
                "Html" -> FailureSignal.HtmlInsteadOfStream
                "TinyBody" -> FailureSignal.TinyBody(t[1].toInt())
                "FormatIsHls" -> FailureSignal.FormatIsHls
                "FormatUnrecognized" -> FailureSignal.FormatUnrecognized
                "Pcr" -> FailureSignal.PcrDiscontinuity(t[1].toLong())
                "BehindLive" -> FailureSignal.BehindLiveWindow
                "Parsing" -> FailureSignal.Parsing(t[1] == "manifest")
                "DecoderInit" -> FailureSignal.DecoderInit(video(), t[2], t.getOrNull(3))
                "DecodingFailed" -> FailureSignal.DecodingFailed(video(), t[2])
                "FormatUnsupported" -> FailureSignal.FormatUnsupported(video(), t[2])
                "AudioTrackInit" -> FailureSignal.AudioTrackInit(t[1] == "encoded", t[2].toInt(), t[3])
                "AudioWrite" -> FailureSignal.AudioWrite
                "ResourcesReclaimed" -> FailureSignal.ResourcesReclaimed
                "Stuck" -> FailureSignal.Stuck(t[1].toInt())
                "Stall" -> FailureSignal.Stall(StallKind.valueOf(t[1]))
                "LiveEnded" -> FailureSignal.LiveEnded
                "Credentials" -> FailureSignal.Credentials(t[1] == "permanent")
                "LocalLimit" -> FailureSignal.LocalConnectionLimit(t[1].toInt(), t[2].toInt())
                "ChannelMissing" -> FailureSignal.ChannelMissing
                "Unknown" -> FailureSignal.Unknown(t[1].toInt())
                else -> error("unknown signal '$s'")
            }
        }
    }
}
