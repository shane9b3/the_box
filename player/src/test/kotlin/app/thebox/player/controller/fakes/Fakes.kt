package app.thebox.player.controller.fakes

import android.content.Context
import android.os.Looper
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import app.thebox.core.catchup.ArchiveRequest
import app.thebox.core.catchup.CatchupTarget
import app.thebox.core.common.Outcome
import app.thebox.core.model.StableKey
import app.thebox.core.playback.BrokerSnapshot
import app.thebox.core.playback.BufferMode
import app.thebox.core.playback.BufferProfile
import app.thebox.core.playback.BufferProfileFsm
import app.thebox.core.playback.ChannelStreamInfo
import app.thebox.core.playback.ConnectionBrokerCore
import app.thebox.core.playback.CoreLease
import app.thebox.core.playback.ErrorLadder
import app.thebox.core.playback.FailureSignal
import app.thebox.core.playback.LadderContext
import app.thebox.core.playback.LadderCounters
import app.thebox.core.playback.LadderDecision
import app.thebox.core.playback.LeaseKind
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.PlaybackEvent
import app.thebox.core.playback.PlaybackEventLog
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.PlaylistPlaybackInfo
import app.thebox.core.playback.ReResolveLimiter
import app.thebox.core.playback.ResolveOutcome
import app.thebox.core.playback.ResolvedStream
import app.thebox.core.playback.StallVerdict
import app.thebox.core.playback.StreamCredentials
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.core.playback.Viewport
import app.thebox.core.playback.XtreamOutput
import app.thebox.core.playback.ZapDebouncer
import app.thebox.core.playback.ZapPlan
import app.thebox.core.playback.ZapRecord
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.AccountStatus
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.player.api.ChannelMemory
import app.thebox.player.api.ChannelPlaybackMemory
import app.thebox.player.api.PlaybackSourceProvider
import app.thebox.player.api.StreamStats
import app.thebox.player.api.TrackInfo
import app.thebox.player.controller.EngineEnvironment
import app.thebox.player.controller.EngineParts
import app.thebox.player.controller.LifecycleCoordinator
import app.thebox.player.internal.AdaptiveLoadControlHandle
import app.thebox.player.internal.AudioPolicyHandle
import app.thebox.player.internal.CodecPolicyHandle
import app.thebox.player.internal.DisplayModeControllerApi
import app.thebox.player.internal.MediaSourceBuilderApi
import app.thebox.player.internal.MediaSourceWithTelemetry
import app.thebox.player.internal.PlaybackWatchdogApi
import app.thebox.player.internal.PlayerBundle
import app.thebox.player.internal.PlayerFactoryApi
import app.thebox.player.internal.StreamBrokerRegistry
import app.thebox.player.internal.StreamSessionContext
import app.thebox.player.internal.StreamTelemetry
import app.thebox.player.internal.StreamUrlResolver
import app.thebox.player.internal.TransportFactory
import app.thebox.player.internal.TsCompat
import app.thebox.player.internal.TuneTargetKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.reflect.Proxy

// Fakes of the WP-A/B/C seams (§2.5) for the controller's Robolectric tests (§5.3 WP-D). The integration pass (§1.9
// step 2) keeps the pure fakes for the state-machine tests and adds runs with the real PlayerFactory/MediaSourceBuilder.

/** Ordered record of side effects across fakes, so tests can assert "end old session before opening the new one". */
class Journal {
    val entries = mutableListOf<String>()
    operator fun invoke(e: String) { entries += e }
    fun indexOf(e: String) = entries.indexOf(e)
    fun count(prefix: String) = entries.count { it.startsWith(prefix) }
    override fun toString() = entries.joinToString()
}

val CH1 = ChannelIdentity(1, StableKey("ch1"))
val CH2 = ChannelIdentity(1, StableKey("ch2"))
val CH3 = ChannelIdentity(1, StableKey("ch3"))

fun stream(
    identity: ChannelIdentity? = CH1,
    format: StreamFormat = StreamFormat.TS,
    baseIndex: Int = 0,
    baseCount: Int = 1,
    output: XtreamOutput? = null,
    brokerKey: String = "p:1",
    droppedHeaders: List<String> = emptyList(),
) = ResolvedStream(
    identity = identity,
    brokerKey = brokerKey,
    url = "http://panel.example:8080/live/u/p/1.ts",
    headers = mapOf("User-Agent" to "TheBox"),
    formatHint = format,
    xtream = null,
    output = output,
    baseIndex = baseIndex,
    baseCount = baseCount,
    host = "panel.example:8080",
    ignoreTlsErrors = false,
    liveUrlTemplate = "{{base}}/live/{{u}}/{{p}}/1.{{ext}}",
    headerTemplates = emptyMap(),
    droppedHeaders = droppedHeaders,
)

internal class FakeResolver(private val journal: Journal) : StreamUrlResolver {
    data class Call(val target: TuneTargetKey, val baseIndex: Int?, val output: XtreamOutput?, val userAgent: String?)

    val calls = mutableListOf<Call>()
    val archiveCalls = mutableListOf<ArchiveRequest>()
    var gate: CompletableDeferred<Unit>? = null
    var next: (Call) -> ResolveOutcome = { c ->
        val id = (c.target as? TuneTargetKey.Channel)?.identity
        ResolveOutcome.Ok(stream(id, baseIndex = c.baseIndex ?: 0, baseCount = baseCount, output = c.output))
    }
    var baseCount = 1

    override suspend fun resolve(target: TuneTargetKey, baseIndex: Int?, output: XtreamOutput?, userAgentOverride: String?): ResolveOutcome {
        val call = Call(target, baseIndex, output, userAgentOverride)
        calls += call
        journal("resolve:${(target as? TuneTargetKey.Channel)?.identity?.stableKey?.value ?: "direct"}")
        gate?.await()
        return next(call)
    }

    override suspend fun catchupTarget(identity: ChannelIdentity): CatchupTarget? = null

    override suspend fun expandArchive(identity: ChannelIdentity, request: ArchiveRequest): ResolveOutcome {
        archiveCalls += request
        journal("expandArchive")
        return ResolveOutcome.Ok(stream(identity, format = StreamFormat.HLS))
    }
}

internal class FakeBroker(override val key: String, private val journal: Journal) : ConnectionBrokerCore {
    var max = 1
    override fun configure(maxConnections: Int) { max = maxConnections }
    override fun preemptPlayback(reason: String): Int { journal("preempt"); return 0 }
    override fun acquire(kind: LeaseKind, ownerTuneId: Long, waitMs: Long): CoreLease = error("not used by the controller")
    override fun snapshot() = BrokerSnapshot(key, 1, 0, max, 0, mapOf(LeaseKind.PLAYBACK to 1))
}

internal class FakeBrokerRegistry(private val journal: Journal) : StreamBrokerRegistry {
    val brokers = HashMap<String, FakeBroker>()
    val configured = mutableListOf<Pair<String, Int>>()
    override fun broker(key: String): ConnectionBrokerCore = brokers.getOrPut(key) { FakeBroker(key, journal) }
    override fun configure(key: String, maxConnections: Int) {
        configured += key to maxConnections
        (broker(key) as FakeBroker).configure(maxConnections)
    }
}

internal class FakeTelemetry : StreamTelemetry {
    override var bytesTotal = 0L
    override var lastByteAtMs = 0L
    override var firstByteAtMs = 0L
    override var bitrateBps = 0L
    override var httpStatus = 0
    override var finalHost: String? = null
    override var redirects = 0
    override var inReconnect = false
    override var reconnects = 0
    override var sniffed = StreamFormat.UNKNOWN
}

internal class FakeTransport(private val journal: Journal) : TransportFactory {
    val ended = mutableListOf<Long>()
    override fun liveTs(ctx: StreamSessionContext) = error("built by the fake MediaSourceBuilder")
    override fun http(ctx: StreamSessionContext) = error("built by the fake MediaSourceBuilder")
    override fun endSession(tuneId: Long) {
        ended += tuneId
        journal("endSession:$tuneId")
    }
}

@OptIn(UnstableApi::class)
internal class FakeMediaSourceBuilder(private val journal: Journal) : MediaSourceBuilderApi {
    data class Build(val ctx: StreamSessionContext, val format: StreamFormat, val compat: TsCompat, val archive: ArchiveRequest?)

    val builds = mutableListOf<Build>()
    val telemetry = HashMap<Long, FakeTelemetry>()
    val last: Build get() = builds.last()
    /** Thrown by the next [build] (then cleared), e.g. the real builder's UnsupportedStreamFormatException. */
    var failNext: Exception? = null

    override fun build(ctx: StreamSessionContext, format: StreamFormat, compat: TsCompat, transport: TransportFactory, archive: ArchiveRequest?): MediaSourceWithTelemetry {
        failNext?.let { failNext = null; throw it }
        builds += Build(ctx, format, compat, archive)
        journal("build:${ctx.tuneId}:$format")
        val t = FakeTelemetry().also { telemetry[ctx.tuneId] = it }
        val source = Proxy.newProxyInstance(MediaSource::class.java.classLoader, arrayOf(MediaSource::class.java)) { p, m, a ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a?.get(0)
                "toString" -> "FakeMediaSource(${ctx.tuneId})"
                else -> null
            }
        } as MediaSource
        return MediaSourceWithTelemetry(source, t)
    }
}

internal class FakeLoadControl : AdaptiveLoadControlHandle {
    override var profile: BufferProfile = profile(0)
}

internal class FakeCodecPolicy : CodecPolicyHandle {
    override var videoMode: VideoDecoderMode = VideoDecoderMode.AUTO
    override var softwareAudioMimes: Set<String> = emptySet()
}

internal class FakeAudioPolicy : AudioPolicyHandle {
    override var deniedPassthrough: Set<String> = emptySet()
    override var forceStereo: Boolean = false
}

internal class FakePlayerFactory(private val context: Context, journal: Journal) : PlayerFactoryApi {
    val exo = FakeExoPlayer(journal)
    var bundle: PlayerBundle? = null
    var creates = 0
    override fun create(looper: Looper, softwareAudioFallback: Boolean): PlayerBundle {
        creates++
        return PlayerBundle(exo.player, DefaultTrackSelector(context), FakeLoadControl(), FakeCodecPolicy(), FakeAudioPolicy()).also { bundle = it }
    }
}

/** Scripted ladder: [script] answers each decide call; records the contexts it saw. */
internal class FakeLadder : ErrorLadder {
    data class Seen(val signal: FailureSignal, val ctx: LadderContext)

    val seen = mutableListOf<Seen>()
    var script: (FailureSignal, LadderContext) -> LadderDecision = { _, _ -> LadderDecision.Fail(PlaybackError(PlaybackErrorCode.INTERNAL)) }
    var counters: (LadderCounters) -> LadderCounters = { it.copy(transientRetries = it.transientRetries + 1) }

    override fun decide(signal: FailureSignal, ctx: LadderContext): Pair<LadderDecision, LadderCounters> {
        seen += Seen(signal, ctx)
        return script(signal, ctx) to counters(ctx.counters)
    }
}

internal class FakeLimiter : ReResolveLimiter {
    var allow = true
    var calls = 0
    val keys = mutableListOf<ChannelIdentity?>()
    override fun tryAcquire(key: ChannelIdentity?, nowMs: Long): Boolean { calls++; keys += key; return allow }
    val resets = mutableListOf<ChannelIdentity?>()
    override fun reset(key: ChannelIdentity?) { resets += key }
}

/** §3.5.4 as written (leading edge, 350 ms trailing). */
internal class FakeDebouncer : ZapDebouncer {
    private var lastZapAt = Long.MIN_VALUE / 2
    private var trailing = false
    override fun onZap(nowMs: Long, isRepeat: Boolean): ZapPlan {
        val immediate = !isRepeat && nowMs - lastZapAt > 350 && !trailing
        lastZapAt = nowMs
        return if (immediate) ZapPlan(true, nowMs) else { trailing = true; ZapPlan(false, nowMs + 350) }
    }
    override fun onFired() { trailing = false }
    override fun reset() { trailing = false; lastZapAt = Long.MIN_VALUE / 2 }
}

fun profile(level: Int) = BufferProfile(1000 + level * 500, 3000 * (level + 1), 10_000, 20_000, 48 shl 20, level)

internal class FakeBufferFsm : BufferProfileFsm {
    var level = 0
    val modes = mutableListOf<BufferMode>()
    override fun profileFor(playlistKey: String, mode: BufferMode, lowRam: Boolean): BufferProfile { modes += mode; return profile(level) }
    override fun onRebuffer(playlistKey: String, nowMs: Long, mode: BufferMode, lowRam: Boolean): BufferProfile? { level++; return profile(level) }
    override fun onStableTick(playlistKey: String, nowMs: Long, mode: BufferMode, lowRam: Boolean): BufferProfile? = null
}

internal class FakeWatchdog : PlaybackWatchdogApi {
    var onStall: ((StallVerdict) -> Unit)? = null
    var resets = 0
    var expectations: Pair<Boolean, Boolean>? = null
    var bound: StreamTelemetry? = null
    override fun start(onStall: (StallVerdict) -> Unit) { this.onStall = onStall }
    override fun stop() { onStall = null }
    override fun bind(telemetry: StreamTelemetry?) { bound = telemetry }
    override fun reset() { resets++ }
    override fun setExpectations(videoExpected: Boolean, audioExpected: Boolean) { expectations = videoExpected to audioExpected }
}

internal class FakeDisplayMode : DisplayModeControllerApi {
    override val request = MutableStateFlow(0)
    override val refreshHz = MutableStateFlow<Float?>(null)
    var view: SurfaceView? = null
    var tunes = mutableListOf<Int?>()
    var viewports = mutableListOf<Viewport>()
    var clears = 0
    override fun attachSurfaceView(view: SurfaceView?) { this.view = view }
    override fun onTune(knownFpsMilli: Int?) { tunes += knownFpsMilli }
    override fun onFps(fps: Float) = Unit
    override fun onViewport(viewport: Viewport) { viewports += viewport }
    override fun awaitSettled(onSettled: () -> Unit) = onSettled()
    override fun clear() { clears++ }
}

internal class FakeEngine : EngineEnvironment, EngineParts {
    override val watchdog = FakeWatchdog()
    override val displayMode = FakeDisplayMode()
    override val fps = MutableStateFlow<Float?>(null)
    var statsValue = StreamStats()
    var ffmpegMimes = setOf("audio/mpeg-L2")
    override val isLowRam = false
    override fun capabilities() = mapOf("fake" to "1")
    override fun ffmpegSupports(mimeType: String) = mimeType in ffmpegMimes
    override fun softwareVideoDecoderAvailable(mimeType: String?) = true
    private var bundle: PlayerBundle? = null
    override fun createParts(bundle: PlayerBundle): EngineParts = this.also { it.bundle = bundle }
    override fun stats(): StreamStats = statsValue
    override fun tracks(tracks: androidx.media3.common.Tracks): TrackInfo = TrackInfo()
    var sessions = 0
    var statsOn = false
    var tuneDefaults: List<Any?>? = null
    var milestoneListener: ((Boolean) -> Unit)? = null
    override fun beginSession(telemetry: StreamTelemetry?, format: app.thebox.core.playback.StreamFormat, isLive: Boolean, isHls: Boolean) { sessions++ }
    override fun setFirstMediaListener(listener: ((video: Boolean) -> Unit)?) { milestoneListener = listener }
    override fun setStatsEnabled(enabled: Boolean) { statsOn = enabled }
    override fun applyTuneDefaults(audioLanguages: List<String>, textLanguages: List<String>, subtitles: String?, subtitlesEnabled: Boolean) {
        tuneDefaults = listOf(audioLanguages, textLanguages, subtitles, subtitlesEnabled)
        // The real WP-C behaviour on the fake player, so the parameter assertions keep testing the outcome.
        bundle?.let { app.thebox.player.engine.TrackController(it).applyTuneDefaults(audioLanguages, textLanguages, subtitles, subtitlesEnabled) }
    }
}

internal class FakeEventLog : PlaybackEventLog {
    val events = mutableListOf<PlaybackEvent>()
    val zaps = mutableListOf<ZapRecord>()
    override fun add(type: PlaybackEventType, tuneId: Long, code: String, detail: String) {
        events += PlaybackEvent(0, 0, tuneId, type, code, detail)
    }
    override fun addZap(record: ZapRecord) { zaps += record }
    override fun events(): List<PlaybackEvent> = events.toList()
    override fun zaps(): List<ZapRecord> = zaps.toList()
}

internal class FakeProvider : PlaybackSourceProvider {
    var playlistInfo = PlaylistPlaybackInfo(1, PlaylistType.XTREAM, userAgent = null)
    var account: XtreamAccountInfo? = account()
    var channelHeaders = emptyMap<String, String>()
    var refreshes = 0
    val savedBase = mutableListOf<Int>()
    val savedOutput = mutableListOf<XtreamOutput?>()

    override suspend fun channel(identity: ChannelIdentity) =
        ChannelStreamInfo(identity, "Channel ${identity.stableKey.value}", "{{base}}/live/{{u}}/{{p}}/1.{{ext}}", channelHeaders)
    override suspend fun playlist(playlistId: Long) = playlistInfo
    override suspend fun credentials(playlistId: Long): Outcome<StreamCredentials?> = Outcome.Ok(null)
    override suspend fun account(playlistId: Long) = account
    override suspend fun refreshAccount(playlistId: Long): Outcome<XtreamAccountInfo> {
        refreshes++
        return Outcome.Ok(account ?: account())
    }
    override suspend fun saveActiveBaseIndex(playlistId: Long, index: Int) { savedBase += index }
    override suspend fun saveLearnedOutput(playlistId: Long, output: XtreamOutput?) { savedOutput += output }
    override suspend fun backupSources(identity: ChannelIdentity): List<ChannelIdentity> = emptyList()

    companion object {
        fun account(status: AccountStatus = AccountStatus.ACTIVE) =
            XtreamAccountInfo(auth = true, status = status, activeConnections = 0, maxConnections = 1, measuredAtDeviceMs = 0)
    }
}

internal class FakeMemory : ChannelMemory {
    val rows = HashMap<ChannelIdentity, ChannelPlaybackMemory>()
    val watched = mutableListOf<ChannelIdentity>()
    override suspend fun read(identity: ChannelIdentity) = rows[identity] ?: ChannelPlaybackMemory()
    override suspend fun update(identity: ChannelIdentity, change: (ChannelPlaybackMemory) -> ChannelPlaybackMemory) {
        rows[identity] = change(read(identity))
    }
    override suspend fun recordWatched(identity: ChannelIdentity) { watched += identity }
}

internal class FakeDeviceState : LifecycleCoordinator.DeviceState {
    var interactive = true
    var displayOn = true
    override fun isInteractive() = interactive
    override fun isDisplayOn() = displayOn
}
