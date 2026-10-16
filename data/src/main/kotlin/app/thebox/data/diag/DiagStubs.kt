package app.thebox.data.diag

import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.diag.DeviceFacts
import app.thebox.core.diag.Diagnostics
import app.thebox.core.diag.LogRing
import app.thebox.core.diag.StepResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Commit-0 stubs of WP-E's diagnostics classes (docs/design/m5-setup-settings.md §1.3a, §2.9, §3.11). WP-E replaces
 * them with its own files under data/.../diag/ (DiagnosticsImpl, LogRingImpl) and deletes this one.
 */

/** No device panel, no network test, no export yet. */
@Singleton
class DiagnosticsImpl @Inject constructor() : Diagnostics {
    override suspend fun deviceFacts(): DeviceFacts = DeviceFacts(
        model = "", manufacturer = "", os = "", sdk = 0, fireOs = null, memTotalMb = 0, memoryClassMb = 0, lowRam = false,
        performanceMode = "", abis = emptyList(), hdmiEncodings = emptyList(), hdrTypes = emptyList(), displayModes = emptyList(),
        decoders = emptyList(), ffmpegAudio = false, keystoreBacked = false, freeStorageMb = 0, network = "",
    )
    override fun networkTest(playlistId: Long): Flow<StepResult> = emptyFlow()
    override suspend fun exportLogs(sink: OutputStream): Outcome<Long> = Outcome.Err(SourceError.Unsupported("log export arrives with WP-E"))
}

/** No ring: [install] leaves `BoxLog.sink` as `TheBoxApplication` set it. */
@Singleton
class LogRingImpl @Inject constructor() : LogRing {
    override fun install() = Unit
    override fun snapshot(): List<String> = emptyList()
}
