package app.thebox.data.setup

import android.content.Context
import app.thebox.core.settings.PhonePurpose
import app.thebox.core.setup.lan.LanAddressFinder
import app.thebox.core.setup.lan.LanPhoneLink
import app.thebox.core.setup.lan.LanServerState
import app.thebox.core.setup.lan.PhoneLink
import app.thebox.core.setup.lan.PhoneSubmission
import app.thebox.core.setup.lan.SocketLanTransport
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.IoDispatcher
import app.thebox.data.di.WallClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone page's server session (docs/design/m5-setup-settings.md §2.5, §3.5): `:core`'s [LanPhoneLink] over a
 * [SocketLanTransport] bound to the LAN address [LanAddressFinder] picks, the page assets from
 * `data/src/main/assets/phone/`, uploads spooled to `cacheDir/phone/` (deleted on stop). Socket work runs on the
 * server's own threads (`box-lan`, never main). A consumer of `BackupUploaded`/`M3uFileUploaded` must move the file
 * out of `cacheDir/phone/` before it calls [stop].
 */
@Singleton
class PhoneLinkImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    finder: LanAddressFinder,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher io: CoroutineDispatcher,
    clock: WallClock,
) : PhoneLink {
    private val lane = io.limitedParallelism(1)
    private val assetCache = ConcurrentHashMap<String, ByteArray>()

    private val link = LanPhoneLink(
        finder = finder,
        transport = SocketLanTransport(),
        assets = ::asset,
        spoolDir = File(context.cacheDir, "phone"),
        scope = scope,
        // The session's idle/absolute lifetime runs on the debug harness's offset clock (DebugOverrides, §6.5; 0 in release).
        clock = { clock.now() + app.thebox.core.diag.DebugOverrides.clockOffsetMs },
    )

    /** Fixed port for the debug route `thebox-debug://phone?port=N` (WP-E); null = 47100 with the random fallback. */
    var portOverride: Int?
        get() = link.portOverride
        set(value) { link.portOverride = value }

    override val state: StateFlow<LanServerState> get() = link.state
    override val submissions: Flow<PhoneSubmission> get() = link.submissions
    /** Address lookup and bind happen off main, in call order (start/stop on one serial lane). */
    override fun start(purpose: PhonePurpose) {
        scope.launch(lane) { link.start(purpose) }
    }
    override fun stop() {
        scope.launch(lane) { link.stop() }
    }
    override fun offerDownload(name: String, path: String, contentType: String) = link.offerDownload(name, path, contentType)
    override fun reportStatus(text: String, done: Boolean) = link.reportStatus(text, done)

    private fun asset(name: String): ByteArray? = assetCache[name] ?: try {
        context.assets.open("phone/$name").use { it.readBytes() }.also { assetCache[name] = it }
    } catch (e: IOException) {
        null
    }
}
