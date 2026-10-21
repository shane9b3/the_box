package app.thebox.player.controller.fakes

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.playback.MonoClock
import app.thebox.player.api.InMemoryPlaybackSettings
import app.thebox.player.api.PlaybackNotice
import app.thebox.player.api.PlaybackState
import app.thebox.player.controller.MediaSessionFactory
import app.thebox.player.controller.PlaybackControllerImpl
import app.thebox.player.controller.ZapImportGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * A [PlaybackControllerImpl] wired to the fakes, on Robolectric's paused main looper. Time is
 * `SystemClock.elapsedRealtime()`, which [advance] moves together with the looper (coroutine delays, Handler posts).
 * Records every state and notice.
 */
internal class ControllerFixture(
    /**
     * (M6 time-shift) The session factory; the default keeps every cycle unbuffered (the Off path). The settings are
     * [settings]' `timeshift*` fields (lead reconciliation R10).
     */
    timeshift: app.thebox.player.internal.TimeshiftSessionFactory = app.thebox.player.timeshift.NoTimeshiftFactory,
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val journal = Journal()
    val resolver = FakeResolver(journal)
    val brokers = FakeBrokerRegistry(journal)
    val transport = FakeTransport(journal)
    val playerFactory = FakePlayerFactory(context, journal)
    val sources = FakeMediaSourceBuilder(journal)
    val events = FakeEventLog()
    val ladder = FakeLadder()
    val limiter = FakeLimiter()
    val debouncer = FakeDebouncer()
    val bufferFsm = FakeBufferFsm()
    val engine = FakeEngine()
    val provider = FakeProvider()
    val memory = FakeMemory()
    val settings = InMemoryPlaybackSettings()
    val device = FakeDeviceState()
    val clock = MonoClock { SystemClock.elapsedRealtime() }
    val gate = ZapImportGate({ SystemClock.elapsedRealtime() }, 200)
    /** (M5, B1) Per-identity guard verdicts; absent = ALLOWED. Every check is recorded in [guardChecks]. */
    val verdicts = HashMap<app.thebox.core.refresh.ChannelIdentity, app.thebox.core.playback.TuneVerdict>()
    val guardChecks = mutableListOf<app.thebox.core.refresh.ChannelIdentity>()
    var guardThrows = false
    val guard = app.thebox.core.playback.TuneGuard { id -> guardChecks += id; if (guardThrows) error("guard down"); verdicts[id] ?: app.thebox.core.playback.TuneVerdict.ALLOWED }

    val controller = PlaybackControllerImpl(
        context, provider, memory, settings, resolver, brokers, transport, playerFactory, sources, events, clock,
        ladder, limiter, debouncer, bufferFsm, engine, gate, MediaSessionFactory.None, device, guard, timeshift,
    ).also { it.ioDispatcher = Dispatchers.Main.immediate } // O-3 hop runs inline on the paused looper

    val exo get() = playerFactory.exo
    val states = mutableListOf<PlaybackState>()
    val notices = mutableListOf<PlaybackNotice>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch { controller.state.collect { states += it } }
        scope.launch { controller.notices.collect { notices += it } }
        idle()
    }

    val state: PlaybackState get() = controller.state.value

    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Latest session's telemetry. */
    fun telemetry(): FakeTelemetry = sources.telemetry.getValue(sources.last.ctx.tuneId)

    /** READY + tracks + first frame for the current session. */
    fun play(tracks: androidx.media3.common.Tracks = FakeExoPlayer.avTracks()) {
        exo.emitTracks(tracks)
        exo.emitState(androidx.media3.common.Player.STATE_READY)
        if (tracks.isTypeSelected(androidx.media3.common.C.TRACK_TYPE_VIDEO)) exo.emitFirstFrame() else exo.emitAudioAdvancing()
        idle()
    }

    fun close() = scope.cancel()
}
