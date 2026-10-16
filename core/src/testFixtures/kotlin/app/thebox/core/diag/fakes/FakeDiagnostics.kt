package app.thebox.core.diag.fakes

import app.thebox.core.common.Outcome
import app.thebox.core.diag.DeviceFacts
import app.thebox.core.diag.Diagnostics
import app.thebox.core.diag.LogRing
import app.thebox.core.diag.StepResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import java.io.OutputStream

/** [Diagnostics] with fixed device facts and a scripted network test ([steps]). */
class FakeDiagnostics(
    var facts: DeviceFacts = DeviceFacts(
        model = "AFTKA", manufacturer = "Amazon", os = "Fire OS 7", sdk = 28, fireOs = "7.6", memTotalMb = 1_536, memoryClassMb = 192,
        lowRam = false, performanceMode = "AUTO", abis = listOf("armeabi-v7a"), hdmiEncodings = listOf("AC3", "E_AC3"), hdrTypes = listOf("HDR10"),
        displayModes = listOf("3840x2160@60"), decoders = emptyList(), ffmpegAudio = true, keystoreBacked = true, freeStorageMb = 2_048, network = "WIFI",
    ),
) : Diagnostics {
    var steps: List<StepResult> = emptyList()
    var exported = 0
    override suspend fun deviceFacts(): DeviceFacts = facts
    override fun networkTest(playlistId: Long): Flow<StepResult> = steps.asFlow()
    override suspend fun exportLogs(sink: OutputStream): Outcome<Long> {
        exported++
        val bytes = "log\n".encodeToByteArray()
        sink.write(bytes)
        return Outcome.Ok(bytes.size.toLong())
    }
}

/** [LogRing] that keeps lines appended by the test. */
class FakeLogRing : LogRing {
    val lines = ArrayList<String>()
    var installs = 0
    override fun install() { installs++ }
    override fun snapshot(): List<String> = lines.toList()
}
