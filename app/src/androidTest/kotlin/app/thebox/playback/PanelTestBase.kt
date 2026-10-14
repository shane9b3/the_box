package app.thebox.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.thebox.core.common.Outcome
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.data.repo.Trigger
import app.thebox.debug.PlaybackDebugActivity
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PlaybackNotice
import app.thebox.player.api.PlaybackSettings
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.StopReason
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.util.Collections
import java.util.UUID

/**
 * Base of the M2 instrumented suite (§5.5) against the fake panel on the Mac (`tools/fakepanel/run-instrumented.sh`).
 *
 * - The panel URL comes from the `fakepanel` instrumentation argument (`http://10.0.2.2:18080` on the emulator); without
 *   it, or when the panel does not answer, every test is skipped (`assumeTrue`), so a plain `connectedDebugAndroidTest`
 *   passes.
 * - `@Before`: panel reset, a fresh Xtream playlist `it-<uuid>` for the panel, refreshed synchronously, lifecycle timers
 *   scaled by 0.1. Tests configure faults themselves through [panel] (typed, nested keys).
 * - The controller is the app's real singleton, reached through the debug harness's Hilt entry point; the harness
 *   (`PlaybackDebugActivity`) is launched with its deep link, exactly as M3 will host the video layer.
 * - Waits are explicit, bounded and on state flows. Measured timings are logged under the `PlaybackIT` tag, never
 *   asserted (plan §7): only correctness is asserted.
 * - `@After`: I-9 — the panel never saw a `Range` header on a live request.
 * - (code review 2026-10-14, E-S5) The event log and zap history are process-wide rings that outlive a test. `@Before`
 *   snapshots a cursor (the highest tune id seen and the elapsed-realtime clock); [awaitFirstMedia] only accepts zap
 *   records of tunes after it and [events] only returns events logged after it, so no wait or assertion can be
 *   satisfied by an earlier test's events.
 */
abstract class PanelTestBase {
    protected val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    protected val ep: PlaybackDebugActivity.DebugPlaybackEntryPoint =
        EntryPointAccessors.fromApplication(context, PlaybackDebugActivity.DebugPlaybackEntryPoint::class.java)
    protected val controller: PlaybackController get() = ep.controller()

    protected lateinit var panel: FakePanelAdmin
    protected lateinit var base: String
    protected var playlistId = 0L
    private val extraPlaylists = mutableListOf<Long>()
    private var channelsByStream: Map<Int, ChannelIdentity> = emptyMap()
    private var scenario: ActivityScenario<PlaybackDebugActivity>? = null

    protected val notices: MutableList<PlaybackNotice> = Collections.synchronizedList(mutableListOf())
    protected val states: MutableList<PlaybackState> = Collections.synchronizedList(mutableListOf())
    /** [states] with the `elapsedRealtime` at which each arrived (same clock as `PlaybackEvent.elapsedMs`). */
    protected val timedStates: MutableList<Pair<Long, PlaybackState>> = Collections.synchronizedList(mutableListOf())

    /** E-S5 cursor: the highest tune id in the process before this test, and the clock at its start. */
    protected var startTuneId = 0L
        private set
    protected var startElapsedMs = 0L
        private set
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUpPanel() {
        val arg = InstrumentationRegistry.getArguments().getString(ARG)
        assumeTrue("no -e $ARG argument: fake-panel suite skipped", !arg.isNullOrBlank())
        base = arg!!.trimEnd('/')
        panel = FakePanelAdmin(base)
        assumeTrue("fake panel at $base not reachable", panel.reachable())
        panel.reset()
        ep.settings().update { PlaybackSettings(defaultUserAgent = it.defaultUserAgent, lifecycleTimeScale = 0.1f) }
        playlistId = addXtreamPlaylist()
        channelsByStream = channelsOf(playlistId) { it.xtreamStreamId?.toInt() }
        startElapsedMs = SystemClock.elapsedRealtime()
        startTuneId = maxOf(
            controller.diagnostics.events().maxOfOrNull { it.tuneId } ?: 0L,
            controller.diagnostics.zaps().maxOfOrNull { it.tuneId } ?: 0L,
            controller.state.value.target?.tuneId ?: 0L,
        )
        log("cursor: tune > $startTuneId, events after ${startElapsedMs} ms")
        collectors.launch { controller.notices.collect { notices += it } }
        collectors.launch {
            controller.state.collect {
                states += it
                timedStates += SystemClock.elapsedRealtime() to it
            }
        }
    }

    @After
    fun tearDownPanel() {
        if (!::panel.isInitialized) return
        try {
            onMain { controller.stop(StopReason.USER) }
            scenario?.close()
            val stats = panel.stats()
            log("panel stats: requests=${stats.requests} opens=${stats.streamOpens} rejections=${stats.rejections} maxOpen=${stats.maxOpenSeen}")
            // I-9: no Range header on any live request (LiveTsDataSource never sends one).
            assertEquals("range requests seen by the panel", 0, stats.rangeRequestsSeen)
        } finally {
            collectors.cancel()
            runBlocking { (extraPlaylists + playlistId).forEach { ep.playlists().delete(it) } }
            ep.settings().update { PlaybackSettings(defaultUserAgent = it.defaultUserAgent) }
        }
    }

    // ------------------------------------------------------------------ setup helpers

    protected fun addXtreamPlaylist(): Long = runBlocking {
        val id = when (val r = ep.playlists().addXtream("it-${UUID.randomUUID()}", listOf(base), "test", "test")) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> error("addXtream failed: ${r.error.code}")
        }
        val refresh = ep.refresher().refresh(id, Trigger.MANUAL)
        check(refresh.ok) { "refresh failed: ${refresh.error?.code}" }
        id
    }

    /** The panel's `get.php` M3U as a second playlist (I-12's pipe-UA channel). Returns its id. */
    protected fun addM3uPlaylist(): Long = runBlocking {
        val url = "$base/get.php?username=test&password=test&type=m3u_plus&output=ts"
        val id = when (val r = ep.playlists().addM3uUrl("it-m3u-${UUID.randomUUID()}", url)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> error("addM3uUrl failed: ${r.error.code}")
        }
        extraPlaylists += id
        val refresh = ep.refresher().refresh(id, Trigger.MANUAL)
        check(refresh.ok) { "m3u refresh failed: ${refresh.error?.code}" }
        id
    }

    protected fun refreshPlaylist() = runBlocking { check(ep.refresher().refresh(playlistId, Trigger.MANUAL).ok) }

    private fun channelsOf(id: Long, key: (app.thebox.core.model.Channel) -> Int?): Map<Int, ChannelIdentity> = runBlocking {
        ep.channels().browseAll(id, unlocked = true).first()
            .mapNotNull { c -> key(c)?.let { it to ChannelIdentity(c.playlistId, c.stableKey) } }.toMap()
    }

    /** Identity of the panel's live stream [streamId] (catalog 1–13) in the Xtream playlist. */
    protected fun ch(streamId: Int): ChannelIdentity = channelsByStream[streamId] ?: error("stream $streamId not in the playlist")

    /** Identity of stream [streamId] in an M3U playlist (matched on the URL path). */
    protected fun m3uChannel(playlist: Long, streamId: Int): ChannelIdentity =
        channelsOf(playlist) { c -> Regex("/(\\d+)\\.(ts|m3u8)").find(c.streamUrl)?.groupValues?.get(1)?.toInt() }[streamId]
            ?: error("stream $streamId not in the M3U playlist")

    // ------------------------------------------------------------------ harness

    /** Launches the debug harness on [channel] (optionally with a zap list over [zap]). */
    protected fun launch(channel: ChannelIdentity, zap: List<ChannelIdentity> = emptyList(), stats: Boolean = true, mini: Boolean = false) {
        val q = StringBuilder("thebox-debug://play?playlist=${channel.playlistId}&key=${Uri.encode(channel.stableKey.value)}")
        if (zap.isNotEmpty()) q.append("&zap=").append(Uri.encode(zap.joinToString(",") { it.stableKey.value }))
        if (stats) q.append("&stats=1")
        if (mini) q.append("&viewport=mini")
        open(Uri.parse(q.toString()))
    }

    protected fun launchUrl(url: String, max: Int = 1) =
        open(Uri.parse("thebox-debug://play?url=${Uri.encode(url)}&max=$max&stats=1"))

    private fun open(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).setClass(context, PlaybackDebugActivity::class.java)
        val s = scenario
        if (s == null) scenario = ActivityScenario.launch(intent) else s.onActivity { it.startActivity(intent) }
    }

    protected fun relaunch() {
        val intent = Intent(context, PlaybackDebugActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    protected fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    protected fun <T> main(block: () -> T): T {
        var out: T? = null
        onMain { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    // ------------------------------------------------------------------ waits

    /** Waits up to [timeoutMs] for a state matching [predicate]; returns it with the elapsed ms, or fails. */
    protected fun awaitState(what: String, timeoutMs: Long, predicate: (PlaybackState) -> Boolean): Pair<PlaybackState, Long> {
        val t0 = System.nanoTime()
        val st = runBlocking { withTimeoutOrNull(timeoutMs) { controller.state.first(predicate) } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (st == null) fail("timed out after $timeoutMs ms waiting for $what; state=${controller.state.value}")
        log("$what after $ms ms")
        return st!! to ms
    }

    protected fun awaitPlaying(timeoutMs: Long = 15_000) = awaitState("Playing", timeoutMs) { it is PlaybackState.Playing && !it.paused }

    /** Playing on [channel] (a second deep link would otherwise match the previous channel's Playing state). */
    protected fun awaitPlayingOn(channel: ChannelIdentity, timeoutMs: Long = 15_000) =
        awaitState("Playing ${channel.stableKey.value}", timeoutMs) { it is PlaybackState.Playing && !it.paused && it.target?.identity == channel }

    protected fun awaitError(timeoutMs: Long) = awaitState("Error", timeoutMs) { it is PlaybackState.Error }.let { it.first as PlaybackState.Error to it.second }

    /**
     * Waits for the first-media ZapRecord of the cycle started by tune [tuneIdAtLeast] (never one of a tune before this
     * test's cursor, E-S5: `awaitFirstMedia(1)` means "this test's first tune").
     */
    protected fun awaitFirstMedia(tuneIdAtLeast: Long, timeoutMs: Long = 15_000): app.thebox.core.playback.ZapRecord {
        val from = maxOf(tuneIdAtLeast, startTuneId + 1)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val z = controller.diagnostics.zaps().lastOrNull { it.tuneId >= from && it.outcome == "ok" }
            if (z != null) return z
            Thread.sleep(100)
        }
        fail("no first media for tune ≥ $from within $timeoutMs ms; state=${controller.state.value}")
        error("unreachable")
    }

    /** Lets playback run for [ms] (test thread only) and returns the states seen meanwhile. */
    protected fun runFor(ms: Long): List<PlaybackState> {
        val from = states.size
        Thread.sleep(ms)
        return synchronized(states) { states.drop(from) }
    }

    protected fun currentTuneId(): Long = controller.state.value.target?.tuneId ?: 0

    /** This test's events of [type] (E-S5: nothing logged before the test's cursor). */
    protected fun events(type: PlaybackEventType) =
        controller.diagnostics.events().filter { it.type == type && it.elapsedMs >= startElapsedMs }

    /** Elapsed time of the first state after [afterElapsedMs] matching [predicate], or null. */
    protected fun firstStateAfter(afterElapsedMs: Long, predicate: (PlaybackState) -> Boolean): Long? =
        synchronized(timedStates) { timedStates.firstOrNull { it.first >= afterElapsedMs && predicate(it.second) }?.first }

    protected fun assertNoError(seen: List<PlaybackState>) {
        val err = seen.firstOrNull { it is PlaybackState.Error }
        if (err != null) fail("unexpected error: $err")
    }

    protected fun stats() = main { controller.stats.value }

    protected fun log(msg: String) {
        Log.i(TAG, "${javaClass.simpleName}: $msg")
    }

    protected fun percentile(values: List<Long>, p: Double): Long? =
        values.sorted().takeIf { it.isNotEmpty() }?.let { it[((it.size - 1) * p).toInt()] }

    protected suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val ARG = "fakepanel"
        const val TAG = "PlaybackIT"
    }
}
