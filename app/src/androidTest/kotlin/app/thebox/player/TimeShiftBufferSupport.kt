package app.thebox.player

import android.net.Uri
import android.os.Process
import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.catchup.archive.TimelineSource
import app.thebox.core.full.LocalBufferInfo
import app.thebox.core.full.PausedUi
import app.thebox.core.guide.GuideChannel
import app.thebox.core.playback.PlaybackEvent
import app.thebox.core.playback.PlaybackEventType
import app.thebox.debug.GuideDebugReceiver
import app.thebox.debug.SettingsDebugEntryPoint
import app.thebox.player.api.LocalBufferState
import app.thebox.player.api.PlaybackNotice
import app.thebox.player.api.PlaybackState
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The M6 additions to the fake-panel admin API used by the time-shift suite (docs/design/m6-timeshift.md §17.5,
 * `tools/fakepanel/README.md` "M5 additions"): the per-connection log (`/admin/stats.connection_log`) and another device on
 * the account (`/admin/occupy`). Blocking; call it from the test thread.
 */
class TimeShiftPanel(private val pp: PlayerPanel) {
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    /** One row of `connection_log` (panel clock, unix seconds). [kind] is `stream` (a client) or `occupier`. */
    data class Conn(
        val id: Int, val kind: String, val stream: String, val openUnix: Double, val closeUnix: Double?,
        val closeReason: String?, val rejected: Boolean, val bytes: Long,
    ) {
        val open: Boolean get() = closeUnix == null && !rejected
        override fun toString() = "#$id $kind s$stream open@${openUnix.toLong()} close@${closeUnix?.toLong()} $closeReason${if (rejected) " REJECTED" else ""} ${bytes}B"
    }

    fun connections(): List<Conn> {
        val a = pp.guide.stats().json.optJSONArray("connection_log") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Conn(
                id = o.optInt("id"),
                kind = o.optString("kind"),
                stream = o.optString("stream"),
                openUnix = o.optDouble("open_unix"),
                closeUnix = if (o.isNull("close_unix")) null else o.optDouble("close_unix"),
                closeReason = if (o.isNull("close_reason")) null else o.optString("close_reason"),
                rejected = o.optBoolean("rejected"),
                bytes = o.optLong("bytes"),
            )
        }
    }

    /** Client (not occupier) stream connections open now. */
    fun openClientStreams(): Int = connections().count { it.kind == "stream" && it.open }

    /** Accepted client stream opens at or after panel time [sinceUnix]. */
    fun clientOpensSince(sinceUnix: Double): List<Conn> = connections().filter { it.kind == "stream" && !it.rejected && it.openUnix >= sinceUnix }

    /** Occupier slots open now. */
    fun openOccupiers(): Int = connections().count { it.kind == "occupier" && it.open }

    /** `/admin/occupy?mode=…`: returns the HTTP status (200 occupied, 409 no free slot) and the body. */
    fun occupy(mode: String = "hold", sid: String = "999"): Pair<Int, String> =
        http.newCall(Request.Builder().url("${pp.base}/admin/occupy?mode=$mode&sid=$sid").build()).execute().use { it.code to it.body.string() }

    fun releaseOccupy() {
        runCatching {
            http.newCall(Request.Builder().url("${pp.base}/admin/release_occupy").post("{}".toRequestBody()).build()).execute().close()
        }
    }

    fun config(json: JSONObject) = pp.patch(json)
}

/**
 * Base of the M6 time-shift device suite (docs/design/m6-timeshift.md §17.4; owner WP-D) on top of the M4
 * [PlayerTestBase] (fake panel `--synthetic 2000`, `player_fixtures`, `timeshift_mode: "duration"`).
 *
 * - Every case starts from the real defaults: the seed link carries `ts_clear=1&timeshift=auto&ts_hold=auto&ts_protect=auto`
 *   ([GuideDebugReceiver]'s M6 switches), and `@After` applies the same again, because `timeshift`/`ts_hold`/`ts_protect`
 *   are persisted settings.
 * - Mid-case switches ([applyTs]) call [GuideDebugReceiver.applyTimeshift] in-process: starting the receiver activity over
 *   a playing MainActivity would stop playback as Home. Overrides are read by the time-shift factory at the next tune,
 *   so a case that changes the ring re-tunes ([bufferedOn] stops and tunes again).
 * - Observables: `controller.localBuffer` (mono frame), `ArchivePlayback.status` (LOCAL source), the controller's event
 *   journal (`diagnostics.events()`: `seek_local`, `segment`, `ring_boundary`, `saving_stopped`, `timeshift_start/end`,
 *   `OPEN RING`, `STALL`, `REBUFFER`, `FIRST_FRAME`), `controller.notices`, the panel's `connection_log`, toasts on screen
 *   (UiAutomator `textContains` on the §13 strings), `/proc/self/io` and `/proc/self/task/<tid>/stat` (this process is the
 *   app's), and the ring file `cacheDir/timeshift/ring-<pid>.bin`.
 * - Panel faults of a case are POSTed by the case after the per-test `/admin/reset`, so the class runs as one; the KDoc of
 *   each case gives the equivalent `--set` line to reproduce it alone.
 * - Tolerances: the design's numbers on hardware; on the emulator ([isEmulator]) a documented extra margin, and the
 *   hardware-only gates (seek P50, zap regression, writer CPU) are logged instead of asserted.
 */
abstract class TimeShiftBufferBase : PlayerTestBase() {
    override val playerSwitches: String = RESET_SWITCHES

    protected val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    protected val tp: TimeShiftPanel by lazy { TimeShiftPanel(pp) }
    private val store by lazy { EntryPointAccessors.fromApplication(context, SettingsDebugEntryPoint::class.java).settingsProbe().store }

    protected val notices: MutableList<Pair<Long, PlaybackNotice>> = Collections.synchronizedList(ArrayList())
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Extra margin (ms) on emulator timing assertions. */
    protected val emuSlack: Long by lazy { if (isEmulator()) 2_000L else 0L }

    @Before
    fun startTimeShiftBase() {
        bg.launch { controller.notices.collect { notices += SystemClock.elapsedRealtime() to it } }
    }

    @After
    fun stopTimeShiftBase() {
        bg.cancel()
        runCatching { tp.releaseOccupy() }
        runCatching { applyTs(RESET_SWITCHES) }
    }

    // ------------------------------------------------------------------ switches

    /** The receiver's M6 switches, in-process (e.g. `ts_ring_bytes=15000000`, `timeshift=off`). */
    protected fun applyTs(query: String) {
        GuideDebugReceiver.applyTimeshift(Uri.parse("thebox-debug://guide?$query"), store)
        log("time-shift switches: $query")
    }

    // ------------------------------------------------------------------ buffer state

    protected fun lb(): LocalBufferState? = controller.localBuffer.value
    protected fun info(): LocalBufferInfo? = (probe.archive ?: ep.archivePlayback()).localBuffer.value

    protected fun awaitBuffer(what: String, timeoutMs: Long = 20_000, predicate: (LocalBufferState) -> Boolean): LocalBufferState {
        val t0 = SystemClock.elapsedRealtime()
        val st = runBlocking { withTimeoutOrNull(timeoutMs) { controller.localBuffer.first { it != null && predicate(it) } } }
            ?: run { fail("timed out after $timeoutMs ms waiting for $what; buffer=${lb()?.brief()} controller=${controller.state.value}"); error("unreachable") }
        log("$what after ${SystemClock.elapsedRealtime() - t0} ms: ${st.brief()}")
        return st
    }

    /** Polls [predicate] (for conditions spanning several flows); fails with [what]. */
    protected fun awaitTrue(what: String, timeoutMs: Long, periodMs: Long = 200, predicate: () -> Boolean) {
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            if (runCatching(predicate).getOrDefault(false)) {
                log("$what after ${SystemClock.elapsedRealtime() - t0} ms")
                return
            }
            Thread.sleep(periodMs)
        }
        fail("timed out after $timeoutMs ms waiting for $what; buffer=${lb()?.brief()} archive=${archive().brief()} controller=${controller.state.value} player=${pui().brief()}")
    }

    /**
     * FULL on synthetic channel [id], live, and (unless [expectBuffered] is false) the buffer available and the P-frame info
     * published (`ArchivePlayback.localBuffer`: the machine needs it before a pause goes LOCAL). Stops the previous cycle
     * first, so overrides applied before this call take effect.
     */
    protected fun bufferedOn(id: Int, expectBuffered: Boolean = true): GuideChannel {
        val ch = fullOn(id)
        awaitController("live playing on ${ch.displayName}", 20_000) { it is PlaybackState.Playing && !it.target.isArchive && it.target.identity == ch.identity }
        if (expectBuffered) {
            // `saving` is not required: a PAUSE_ONLY cycle saves nothing at live (§4.5).
            awaitBuffer("buffer on ${ch.displayName}", 20_000) { it.identity == ch.identity && it.available }
            awaitTrue("P-frame buffer info", 10_000) { info()?.available == true }
        } else {
            // An unbuffered cycle may publish its reason (`off`) or nothing; the cases that need the reason wait for it.
            Thread.sleep(2_000)
            log("unbuffered tune on ${ch.displayName}: buffer ${lb()?.brief()} info off=${info()?.off}")
        }
        return ch
    }

    /** Bytes per second the ring is saving, measured over at least [minSavedMs] of saved media. */
    protected fun measureRateBps(minSavedMs: Long = 15_000): Long {
        val s = awaitBuffer("≥ ${minSavedMs / 1000} s saved", minSavedMs + 30_000) { it.available && it.usedBytes > 0 && it.liveMonoMs - it.fromMonoMs >= minSavedMs }
        val rate = s.usedBytes * 1000 / (s.liveMonoMs - s.fromMonoMs).coerceAtLeast(1)
        log("ring rate ${rate / 1000} kB/s (${s.usedBytes} B over ${s.liveMonoMs - s.fromMonoMs} ms)")
        return rate
    }

    /** Measures the rate on [id], then re-tunes [id] with a ring of [seconds] at that rate. Returns the ring bytes. */
    protected fun retuneWithRingSeconds(id: Int, seconds: Int): Long {
        bufferedOn(id)
        val bytes = measureRateBps() * seconds
        applyTs("ts_ring_bytes=$bytes")
        bufferedOn(id)
        val cap = lb()?.capacityBytes ?: 0
        log("ring of $seconds s requested: $bytes B, capacity $cap B")
        return bytes
    }

    /** The playhead's distance behind the newest saved media (mono frame; snapshots are ≤ 1 Hz). */
    protected fun monoBehindMs(): Long? {
        val s = lb() ?: return null
        val at = main { controller.position().localMonoMs } ?: return null
        return s.liveMonoMs - at
    }

    // ------------------------------------------------------------------ keys and phases

    protected fun isLocal(st: ArchiveStatus, phase: ArchivePhase) = st.source == TimelineSource.LOCAL && st.phase == phase

    /** PLAY_PAUSE at live on a buffered channel: LOCAL PAUSED, no archive request. Returns the panel time of the press. */
    protected fun pauseByKey(what: String = "LOCAL PAUSED"): Double {
        val at = pp.now().unix
        press(K_PLAY_PAUSE, intervalMs = 0)
        awaitArchive(what, 5_000) { isLocal(it, ArchivePhase.PAUSED) } // WP-C behaviour
        return at
    }

    /**
     * PLAY_PAUSE while LOCAL PAUSED → LOCAL PLAYING. Integration 2026-10-19 (test fix): §11.5 dims the paused frame
     * PAUSED_DIM_AFTER_MS (2 min) after the pause or the last key, and the next key only restores the picture. The cases
     * that pause 2 min or longer (I-TS-6, 10, 24) pressed once and timed out. A dimmed picture is woken first (BACK,
     * consumed); a dim that lands between that check and the press (the pause age within 1 s of the dim) gets one more
     * press. Any other press that does not resume still fails.
     */
    protected fun resumeByKey(what: String = "LOCAL PLAYING") {
        wakeIfDimmed()
        val age = tsUi()?.pausedSinceMs?.let { System.currentTimeMillis() - it } ?: 0L
        press(K_PLAY_PAUSE, intervalMs = 0)
        if (age >= app.thebox.core.playback.timeshift.TimeshiftConstants.PAUSED_DIM_AFTER_MS - 1_000) {
            val resumed = runBlocking { withTimeoutOrNull(3_000) { archiveFlow.first { isLocal(it, ArchivePhase.PLAYING) } } } != null
            if (!resumed && isLocal(archive(), ArchivePhase.PAUSED)) {
                log("resume press at pause age $age ms only woke the picture (dim race): pressing again")
                press(K_PLAY_PAUSE, intervalMs = 0)
            }
        }
        awaitArchive(what, 10_000) { isLocal(it, ArchivePhase.PLAYING) } // WP-C behaviour
    }

    /**
     * [n] × MEDIA_REWIND (30 s each, M4) from live or behind, then waits past the scrub commit for the settled LOCAL
     * playback. Returns the key time (elapsedRealtime of the last press).
     */
    protected fun rewind(n: Int, expectPhase: ArchivePhase = ArchivePhase.PLAYING): Long {
        repeat(n) { press(K_RW, intervalMs = 150) }
        val last = SystemClock.elapsedRealtime()
        Thread.sleep(SCRUB_SETTLE_MS)
        awaitArchive("settled after $n × RW", 15_000) { isLocal(it, expectPhase) } // WP-C behaviour
        awaitPlayer("no scrub preview pending", 5_000) { it.scrub?.previewMs == null && it.scrub?.seeking != true }
        return last
    }

    /** The paused-state facts (`PlayerUiState.paused`: dim, media keys, pause start; fix pass product 2026-10-19). */
    protected fun tsUi(): PausedUi? = pui().paused

    /** The dim card consumes the first key (§11.5); wakes the picture with BACK when dimmed. */
    protected fun wakeIfDimmed() {
        if (tsUi()?.dimmed == true) {
            log("dimmed: BACK wakes the picture (consumed)")
            press(K_BACK, intervalMs = 300)
            awaitTrue("picture restored", 3_000) { tsUi()?.dimmed != true }
        }
    }

    // ------------------------------------------------------------------ journal

    protected fun events(): List<PlaybackEvent> = main { controller.diagnostics.events() }
    protected fun eventsSince(sinceElapsed: Long, type: PlaybackEventType? = null): List<PlaybackEvent> =
        events().filter { it.elapsedMs >= sinceElapsed && (type == null || it.type == type) }
    protected fun decisions(code: String, sinceElapsed: Long): List<PlaybackEvent> =
        eventsSince(sinceElapsed, PlaybackEventType.DECISION).filter { it.code == code }

    /** Commit → first frame of each local seek since [sinceElapsed] (`seek_local` → the next FIRST_FRAME of a newer tune). */
    protected fun seekLatencies(sinceElapsed: Long): List<Long> {
        val ev = eventsSince(sinceElapsed)
        return ev.filter { it.type == PlaybackEventType.DECISION && (it.code == "seek_local" || it.code == "jump_to_live_local") }.mapNotNull { s ->
            ev.firstOrNull { it.type == PlaybackEventType.FIRST_FRAME && it.elapsedMs >= s.elapsedMs && it.tuneId > s.tuneId }?.let { it.elapsedMs - s.elapsedMs }
        }
    }

    protected fun tuneId(): Long? = controller.state.value.target?.tuneId

    // ------------------------------------------------------------------ toasts

    /** Waits for a toast whose text contains [fragment] (§13 strings); false when none showed within [timeoutMs]. */
    protected fun awaitToast(fragment: String, timeoutMs: Long = 8_000): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        val ok = device.wait(Until.hasObject(By.textContains(fragment)), timeoutMs) == true
        log("toast \"$fragment\" ${if (ok) "shown after ${SystemClock.elapsedRealtime() - t0} ms" else "NOT shown within $timeoutMs ms"}")
        if (!ok) log("on screen now: ${screenTexts()}")
        return ok
    }

    /** The texts UiAutomator sees now (diagnostics for a missed toast; integration 2026-10-19). */
    protected fun screenTexts(): List<String> = runCatching {
        device.findObjects(By.text(java.util.regex.Pattern.compile("(?s).+"))).mapNotNull { runCatching { it.text }.getOrNull() }
    }.getOrDefault(emptyList())

    /** Background watch for toasts containing any of [fragments]: first-seen times (elapsedRealtime). */
    protected inner class ToastWatch(private vararg val fragments: String) : AutoCloseable {
        private val seen = ConcurrentHashMap<String, Long>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        init {
            scope.launch {
                while (isActive) {
                    for (f in fragments) if (!seen.containsKey(f) && runCatching { device.hasObject(By.textContains(f)) }.getOrDefault(false)) {
                        seen[f] = SystemClock.elapsedRealtime()
                        log("toast watch: \"$f\" seen")
                    }
                    delay(250)
                }
            }
        }
        fun seen(f: String): Boolean = seen.containsKey(f)
        override fun close() = scope.cancel()
    }

    protected fun noticesSince(sinceElapsed: Long): List<PlaybackNotice> = synchronized(notices) { notices.filter { it.first >= sinceElapsed }.map { it.second } }

    // ------------------------------------------------------------------ files and process

    protected fun ringDir(): File = File(context.cacheDir, "timeshift")
    protected fun ringFile(): File = File(ringDir(), "ring-${Process.myPid()}.bin")

    /** `/proc/self/io` `wchar`: bytes this process passed to write syscalls (pwrite included). */
    protected fun wchar(): Long = File("/proc/self/io").readLines().firstOrNull { it.startsWith("wchar:") }?.substringAfter(':')?.trim()?.toLongOrNull() ?: -1

    /** utime + stime clock ticks (USER_HZ = 100) of this process's threads whose name starts with [prefix]. */
    protected fun threadTicks(prefix: String): Long = (File("/proc/self/task").listFiles() ?: emptyArray()).sumOf { t ->
        runCatching {
            val comm = File(t, "comm").readText().trim()
            if (!comm.startsWith(prefix)) 0L else {
                val stat = File(t, "stat").readText()
                val f = stat.substringAfterLast(')').trim().split(' ')
                f[11].toLong() + f[12].toLong() // fields 14 and 15 (utime, stime), counted after the comm
            }
        }.getOrDefault(0L)
    }

    /** Open file descriptors of this process that point at a deleted ring file. */
    protected fun deletedRingFds(): List<String> = (File("/proc/self/fd").listFiles() ?: emptyArray()).mapNotNull { fd ->
        runCatching { java.nio.file.Files.readSymbolicLink(fd.toPath()).toString() }.getOrNull()?.takeIf { it.contains("ring-") && it.contains("(deleted)") }
    }

    protected fun heapUsedBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    // ------------------------------------------------------------------ misc

    protected fun LocalBufferState.brief(): String =
        "id=${identity?.stableKey?.value} avail=$available off=$off saving=$saving stop=$stop mode=$mode saved=${(liveMonoMs - fromMonoMs) / 1000}s " +
            "window=${windowMs / 1000}s used=${usedBytes shr 10}KiB cap=${capacityBytes shr 10}KiB pauseLeft=$pauseRemainingMs limited=$limitedToday hls=$hls"

    /** Logs the case's verdict in the I-TS-n form `KeysOnDeviceTest` uses for I-4. */
    protected fun pass(case: String, detail: String = "") = log("$case: ok${if (detail.isNotBlank()) " ($detail)" else ""}")

    companion object {
        /** The class baseline: no overrides, every time-shift setting at its default. */
        const val RESET_SWITCHES = "ts_clear=1&timeshift=auto&ts_hold=auto&ts_protect=auto"
        /** M4's 600 ms scrub commit plus margin. */
        const val SCRUB_SETTLE_MS = 1_200L
        const val K_LAST = 229 // KEYCODE_LAST_CHANNEL → PREVIOUS_CHANNEL (DefaultKeyMap)
        const val K_PAUSE = android.view.KeyEvent.KEYCODE_MEDIA_PAUSE

        /** Synthetic lineup (`--synthetic 2000 --set player_fixtures=true`): TS without catch-up, neighbours 12 and 14 too. */
        const val TS_PLAIN = 13
        /** TS without catch-up whose neighbours are also plain TS (the zap-confirm cases). */
        const val TS_PLAIN_2 = 12
        /** TS with a 7-day xc archive. */
        const val TS_ARCHIVE = 1
    }
}
