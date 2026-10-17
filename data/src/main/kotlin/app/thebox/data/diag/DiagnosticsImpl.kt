package app.thebox.data.diag

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.view.Display
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.diag.DecoderFact
import app.thebox.core.diag.DeviceFacts
import app.thebox.core.diag.Diagnostics
import app.thebox.core.diag.ExportBundleBuilder
import app.thebox.core.diag.ExportInputs
import app.thebox.core.diag.ExportPlaylist
import app.thebox.core.diag.NetworkTestEvent
import app.thebox.core.diag.NetworkTestRunner
import app.thebox.core.diag.NetworkTests
import app.thebox.core.diag.StepResult
import app.thebox.core.diag.TestTarget
import app.thebox.core.guide.GuideSettingsSource
import app.thebox.core.playback.PlaybackEventLog
import app.thebox.core.settings.SettingsStore
import app.thebox.core.source.PlaylistType
import app.thebox.data.db.dao.ChannelDao
import app.thebox.data.db.dao.RefreshLogDao
import app.thebox.data.db.entity.RefreshLogEntity
import app.thebox.data.di.IoDispatcher
import app.thebox.data.repo.PlaylistRepository
import app.thebox.data.repo.SyncStatusRepository
import app.thebox.data.security.TinkCredentialStore
import app.thebox.core.common.getOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Diagnostics (docs/design/m5-setup-settings.md §2.9, §3.11): the device panel's facts, the connection test (steps by
 * [NetworkTestRunner] over [NetworkProbesImpl]) and the redacted log export ([ExportBundleBuilder]). Never sends
 * anything anywhere (plan §3, no telemetry): the export is written to the sink the caller chose.
 */
@Singleton
class DiagnosticsImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlists: PlaylistRepository,
    private val channelDao: ChannelDao,
    private val refreshLog: RefreshLogDao,
    private val sync: SyncStatusRepository,
    private val eventLog: PlaybackEventLog,
    private val logRing: LogRingImpl,
    private val credentials: TinkCredentialStore,
    private val guideSettings: GuideSettingsSource,
    private val store: SettingsStore,
    private val probes: NetworkProbesImpl,
    @IoDispatcher private val io: CoroutineDispatcher,
) : Diagnostics, NetworkTests {

    override suspend fun deviceFacts(): DeviceFacts = withContext(io) { DeviceFactsReader(context).read(credentials.usingKeystore, guideSettings.settings.value.performance.name) }

    override fun networkTest(playlistId: Long): Flow<StepResult> = run(playlistId).mapNotNull { (it as? NetworkTestEvent.Step)?.result }

    override fun run(playlistId: Long): Flow<NetworkTestEvent> = flow {
        val target = target(playlistId)
        if (target == null) {
            emitAll(emptyFlow())
            return@flow
        }
        emitAll(NetworkTestRunner(probes).run(target))
    }.flowOn(io)

    /** Builds the test target from the stored login; null when the playlist or its secret is gone. */
    private suspend fun target(playlistId: Long): TestTarget? {
        val p = playlists.get(playlistId) ?: return null
        if (p.type == PlaylistType.M3U_FILE) return null
        val secret = playlists.secret(p).getOrNull() ?: return null
        val url = secret.urls.firstOrNull() ?: return null
        val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val host = parsed.host ?: return null
        val https = parsed.scheme.equals("https", ignoreCase = true)
        val port = if (parsed.port > 0) parsed.port else if (https) 443 else 80
        // D-L9: the test asks the panel as playback does, with the playlist's User-Agent.
        return TestTarget(playlistId, p.type == PlaylistType.XTREAM, host, port, https, url, secret.username, secret.password, p.userAgent?.takeIf { it.isNotBlank() })
    }

    override suspend fun exportLogs(sink: OutputStream): Outcome<Long> = withContext(io) {
        try {
            val summary = ExportBundleBuilder().write(inputs(System.currentTimeMillis()), sink)
            Outcome.Ok(summary.bytes)
        } catch (e: java.io.IOException) {
            Outcome.Err(SourceError.Internal("export: ${e.javaClass.simpleName}"))
        }
    }

    /** The export's inputs (§3.11.4). Public for [DiagSourcesImpl], which writes the same bundle to a target. */
    suspend fun inputs(nowMs: Long): ExportInputs = withContext(io) {
        val all = playlists.all()
        val secrets = LinkedHashSet<String>()
        val rows = all.map { p ->
            playlists.secret(p).getOrNull()?.let { s ->
                s.username?.let(secrets::add)
                s.password?.let(secrets::add)
                s.headers.values.forEach(secrets::add)
                s.urls.forEach(secrets::add)
            }
            val last = refreshLog.latest(TARGET_PLAYLIST, p.id, 1).firstOrNull()
            ExportPlaylist(p.id, p.type.name, p.displaySource, channelDao.activeCount(p.id), last?.let { "${it.outcome} ${it.errorCode ?: ""}".trim() }, p.needsReauth, p.enabled)
        }
        val reports = (all.flatMap { refreshLog.latest(TARGET_PLAYLIST, it.id, REPORTS) } + refreshLog.latest(TARGET_EPG, null, REPORTS))
            .sortedBy { it.startedAt }.takeLast(REPORTS).map(::reportLine)
        val s = sync.status.value
        val syncLines = buildList {
            if (s.playlistsRefreshing.isNotEmpty()) add("refreshing playlists ${s.playlistsRefreshing.sorted()}")
            if (s.epgImporting) add("guide import running")
            if (s.epgPatching) add("guide patch running")
            s.progress?.let { add("progress: $it") }
            s.failures.forEach { add("unacknowledged failure: ${it.targetType} ${it.targetId ?: ""} ${it.errorCode ?: ""}") }
        }
        val logs = logRing.fileLines().filter { it.isNotEmpty() }.ifEmpty { listOf(logRing.snapshot()) }
        ExportInputs(
            header = header(nowMs),
            deviceFacts = runCatching { DeviceFactsReader(context).read(credentials.usingKeystore, guideSettings.settings.value.performance.name) }.getOrNull(),
            settings = runCatching { store.export() }.getOrDefault(emptyMap()),
            playlists = rows,
            syncStatus = syncLines,
            refreshReports = reports,
            events = eventLog.events(),
            zaps = eventLog.zaps(),
            logs = logs,
            crashReports = crashReports(),
            anrNotes = emptyList(),
            extraSecrets = secrets,
        )
    }

    private fun header(nowMs: Long): List<Pair<String, String>> {
        val pm = context.packageManager
        val info = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return listOf(
            "version" to "${info?.versionName ?: "?"} ($code)",
            "build" to (if (debuggable) "debug" else "release") + " " + context.packageName,
            "installer" to (installer(pm) ?: "unknown"),
            "time" to TIME.format(Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())),
            "uptime" to "${SystemClock.elapsedRealtime() / 60_000} min since boot",
        )
    }

    private fun installer(pm: PackageManager): String? = try {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(context.packageName).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
    } catch (e: Exception) {
        null
    }

    /** ACRA's local reports (commit 0, `CrashConfig`: `filesDir/acra/<ms>.json`, already redacted), newest first. */
    private fun crashReports(): List<String> = File(context.filesDir, ACRA_DIR).listFiles { f -> f.name.endsWith(".json") }
        ?.sortedByDescending { it.name }?.take(ExportBundleBuilder.CRASH_REPORTS)
        ?.mapNotNull { f -> runCatching { f.readText().take(CRASH_MAX_CHARS) }.getOrNull() }
        .orEmpty()

    private fun reportLine(r: RefreshLogEntity): String =
        "${TIME.format(Instant.ofEpochMilli(r.startedAt).atZone(ZoneId.systemDefault()))} ${r.targetType} ${r.targetId ?: "-"} ${r.trigger} ${r.outcome}" +
            (r.errorCode?.let { " $it" } ?: "") + (r.bytes?.let { " ${it / 1024} KB" } ?: "") +
            (r.finishedAt?.let { " ${(it - r.startedAt)} ms" } ?: "")

    companion object {
        const val TARGET_PLAYLIST = "PLAYLIST"
        const val TARGET_EPG = "EPG_BUILD"
        const val REPORTS = 10
        const val ACRA_DIR = "acra"
        private const val CRASH_MAX_CHARS = 64_000
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

/** Reads [DeviceFacts] from the platform (§3.11.1). Every lookup is best effort; a failure leaves that fact empty. */
internal class DeviceFactsReader(private val context: Context) {
    fun read(keystoreBacked: Boolean, performanceMode: String): DeviceFacts {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val amazon = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        return DeviceFacts(
            model = Build.MODEL.orEmpty(), manufacturer = Build.MANUFACTURER.orEmpty(),
            os = "Android ${Build.VERSION.RELEASE}", sdk = Build.VERSION.SDK_INT,
            fireOs = if (amazon) fireOs(Build.VERSION.SDK_INT) else null,
            memTotalMb = (mem.totalMem / 1_048_576).toInt(), memoryClassMb = am?.memoryClass ?: 0,
            lowRam = am?.isLowRamDevice ?: false, performanceMode = performanceMode,
            abis = Build.SUPPORTED_ABIS.toList(),
            hdmiEncodings = hdmiEncodings(), hdrTypes = hdrTypes(display), displayModes = displayModes(display),
            decoders = decoders(), ffmpegAudio = ffmpegAvailable(), keystoreBacked = keystoreBacked,
            freeStorageMb = context.filesDir.usableSpace / 1_048_576, network = network(),
        )
    }

    /** Fire OS by API level (api notes §8.1, [memory]): 22 → 5, 25 → 6, 28 → 7, 30 → 8. */
    private fun fireOs(sdk: Int): String? = when {
        sdk >= 30 -> "8"
        sdk >= 28 -> "7"
        sdk >= 25 -> "6"
        else -> null
    }

    private fun hdmiEncodings(): List<String> = try {
        val am = context.getSystemService(AudioManager::class.java)
        am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            .filter { it.type == AudioDeviceInfo.TYPE_HDMI || it.type == AudioDeviceInfo.TYPE_HDMI_ARC || (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_HDMI_EARC) }
            .flatMap { it.encodings.toList() }.distinct().mapNotNull(::encodingName)
    } catch (e: RuntimeException) {
        emptyList()
    }

    private fun encodingName(e: Int): String? = when (e) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM"
        AudioFormat.ENCODING_AC3 -> "AC-3"
        AudioFormat.ENCODING_E_AC3 -> "E-AC-3"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS-HD"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "TrueHD"
        AudioFormat.ENCODING_PCM_FLOAT -> null
        else -> "encoding $e"
    }

    @Suppress("DEPRECATION")
    private fun hdrTypes(d: Display?): List<String> = try {
        d?.hdrCapabilities?.supportedHdrTypes?.map {
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                4 -> "HDR10+"
                else -> "type $it"
            }
        }.orEmpty()
    } catch (e: RuntimeException) {
        emptyList()
    }

    private fun displayModes(d: Display?): List<String> = try {
        d?.supportedModes?.map { "${it.physicalWidth}x${it.physicalHeight}@${"%.0f".format(it.refreshRate)}" }?.distinct().orEmpty()
    } catch (e: RuntimeException) {
        emptyList()
    }

    private fun decoders(): List<DecoderFact> = try {
        val all = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }
        VIDEO_MIMES.flatMap { mime ->
            all.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }.map { info ->
                val caps = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
                DecoderFact(mime, info.name, hardware(info), caps?.let { "${it.supportedWidths.upper}x${it.supportedHeights.upper}" })
            }
        }
    } catch (e: RuntimeException) {
        emptyList()
    }

    private fun hardware(info: MediaCodecInfo): Boolean = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else {
        val n = info.name.lowercase()
        !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.startsWith("omx.ffmpeg"))
    }

    /** The FFmpeg extension is an `:app` dependency; reflection keeps `:data` free of it. */
    private fun ffmpegAvailable(): Boolean = try {
        Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary").getMethod("isAvailable").invoke(null) as Boolean
    } catch (e: Throwable) {
        false
    }

    private fun network(): String = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "NONE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "OTHER"
        }
    } catch (e: SecurityException) {
        "UNKNOWN"
    }

    companion object {
        val VIDEO_MIMES = listOf("video/avc", "video/hevc", "video/mpeg2", "video/av01")
    }
}
