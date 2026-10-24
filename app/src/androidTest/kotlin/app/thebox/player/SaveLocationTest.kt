package app.thebox.player

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.storage.LocationFallback
import app.thebox.core.storage.SmbAddress
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.StorageKind
import app.thebox.core.storage.StoragePurpose
import app.thebox.data.storage.smb.KeeperState
import app.thebox.debug.SettingsDebugEntryPoint
import app.thebox.debug.StorageDebugEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The save-location device suite (docs/design/m6-storage-locations.md §16.4), integration pass 2026-10-24. WP-S5 left the
 * device half unwritten; these are the cases that need **no SMB server** plus the Samba cases as opt-in.
 *
 * Run (one class per call; the panel as for `TimeShiftBufferTest`):
 * ```
 * python3 tools/smbproxy/smbproxy.py --listen 0.0.0.0:4446 --upstream 127.0.0.1:4445 --control 0.0.0.0:4447 &
 * tools/fakepanel/run-instrumented.sh --synthetic 2000 --set player_fixtures=true --set timeshift_mode='"duration"' \
 *     --class app.thebox.player.SaveLocationTest
 * ```
 * - Nothing listens on the Mac's port 4445 unless Samba runs (`tools/smbtest/run-samba.sh`), so `10.0.2.2:4445` is the
 *   "unreachable folder" (connection refused).
 * - The proxy (`tools/smbproxy`) on 4446, controlled from the emulator over `10.0.2.2:4447` (`--control 0.0.0.0:4447`),
 *   gives the "blackholed folder": TCP accepted, no SMB answer. Cases that need it are skipped when it is not running.
 * - Samba cases (I-SL-1…) run only with `-Pandroid.testInstrumentationRunnerArguments.samba=1` and Samba on 4445.
 * - Each case stores its own location (a fresh id, so the once-per-process toast rules start clean) and chooses This
 *   device again afterwards.
 */
@RunWith(AndroidJUnit4::class)
class SaveLocationTest : TimeShiftBufferBase() {
    private val storage by lazy { EntryPointAccessors.fromApplication(context, StorageDebugEntryPoint::class.java) }
    private val settingsProbe by lazy { EntryPointAccessors.fromApplication(context, SettingsDebugEntryPoint::class.java).settingsProbe() }
    private val http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    private val made = ArrayList<String>()

    @After
    fun backToThisDevice() {
        // A blackholed connection stays silent after `pass` until `drop` (tools/smbproxy README).
        runCatching { proxy("mode=drop") }
        runCatching { proxy("mode=pass") }
        runBlocking {
            runCatching { storage.storageLocations().choose(StoragePurpose.TIMESHIFT, StorageConstants.INTERNAL_ID) }
            made.forEach { id -> runCatching { storage.storageLocations().remove(id) } }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Stores a guest folder `\\10.0.2.2\rewind` on [port] named [name] and chooses it for rewind. */
    private fun chooseFolder(port: Int, name: String, lastKnownGood: Boolean = false): String = runBlocking {
        val loc = storage.storageLocations().saveSmb(SmbAddress(host = HOST, port = port, share = "rewind", guest = true), name, null, null)
        made += loc.id
        if (lastKnownGood) markLastKnownGood(loc.id)
        storage.storageLocations().choose(StoragePurpose.TIMESHIFT, loc.id)
        log("chosen ${loc.id} ($name on $HOST:$port, last-known-good=$lastKnownGood)")
        loc.id
    }

    /**
     * §5.1 "last-known-good ≤ SMB_LAST_GOOD_MS ago (persisted across processes)": a previous process reached the folder.
     * The test writes it into the keeper's store in memory (the file is read once per process).
     */
    private fun markLastKnownGood(id: String) {
        val keeper = storage.keeper()
        val f = keeper.javaClass.getDeclaredField("lastGood").apply { isAccessible = true }
        val store = f.get(keeper) as app.thebox.data.storage.smb.LastGoodStore
        store.put(id, System.currentTimeMillis() - 60_000)
        assertNotNull("last-known-good recorded", keeper.lastKnownGoodWall(id))
    }

    /** Polls the keeper; on a timeout logs the `box-smb*` stacks (where the keeper's thread is) and fails. */
    private fun awaitKeeper(what: String, timeoutMs: Long, p: (app.thebox.data.storage.smb.KeeperSnapshot) -> Boolean) {
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            if (p(storage.keeper().snapshot)) { log("$what after ${SystemClock.elapsedRealtime() - t0} ms: ${keeperBrief()}"); return }
            Thread.sleep(200)
        }
        dumpSmbThreads()
        org.junit.Assert.fail("timed out after $timeoutMs ms waiting for $what; ${keeperBrief()}")
    }

    private fun dumpSmbThreads() {
        Thread.getAllStackTraces().filterKeys { it.name.startsWith("box-smb") || it.name.startsWith("box-ts-flush") }.forEach { (t, st) ->
            log("thread ${t.name} ${t.state}:\n  " + st.take(25).joinToString("\n  "))
        }
    }

    private fun keeperBrief(): String = storage.keeper().snapshot.let { "keeper ${it.locationId} ${it.state} failureSeen=${it.failureSeen} net=${it.networkUp}" }

    private fun proxy(query: String): String =
        http.newCall(Request.Builder().url("http://$HOST:$PROXY_CONTROL/control?$query").build()).execute().use { it.body.string() }

    private fun proxyUp(): Boolean = runCatching {
        http.newCall(Request.Builder().url("http://$HOST:$PROXY_CONTROL/status").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    /**
     * A pause of [seconds] at live and the resume: rewind works wherever this cycle saves. BEHIND after the resume is the
     * pause plus the live latency at the press (up to a few seconds after a §5.2 switch), so the bounds are
     * [pause − 1 s, pause + 6 s + emulator slack].
     */
    private fun pauseWorks(case: String, seconds: Long = 10) {
        Thread.sleep(5_000)
        val latency = monoBehindMs()
        pauseByKey()
        Thread.sleep(seconds * 1_000)
        resumeByKey()
        Thread.sleep(1_000)
        val p = pui().pill
        val b = behindNowMs(p)
        log("$case after a ${seconds} s pause: pill=$p behind=$b ms (latency before the pause $latency ms)")
        assertTrue("$case: pill ${p.kind} after the resume", p.kind == app.thebox.core.full.PillKind.BEHIND)
        assertTrue("$case: behind $b ms after a ${seconds} s pause", b >= seconds * 1_000 - 1_000 && b <= seconds * 1_000 + 6_000 + emuSlack)
    }

    // ------------------------------------------------------------------ I-SL-19 (reachability, no server)

    /**
     * I-SL-19 (part): Settings › Playback › Pause and rewind › Save to by keys; Save to lists This device and "Add a network
     * folder"; OK on it opens Find computers, whose search ends on the emulator (no multicast, a virtual /24) with the
     * "No computers found." reason and the manual-entry row. BACK leaves it.
     */
    @Test
    fun sl19_saveToIsReachableAndFindComputersEnds() {
        bufferedOn(TS_PLAIN)
        settingsProbe.open(SettingsRoute.Section(SectionId.PLAYBACK))
        awaitTrue("Settings on Playback", 10_000) { settingsProbe.settingsUi()?.route == SettingsRoute.Section(SectionId.PLAYBACK) }
        fun ui() = settingsProbe.settingsUi()!!
        fun cursorRow() = ui().page?.rows?.getOrNull(ui().cursor)?.id
        fun moveTo(id: RowId) {
            awaitTrue("row $id on the page", 10_000) { ui().page?.rows?.any { it.id == id } == true }
            if (ui().pane != app.thebox.core.settings.Pane.PAGE) press(K_RIGHT, intervalMs = 300)
            var guard = 0
            while (cursorRow() != id && guard++ < 30) {
                val target = ui().page!!.rows.indexOfFirst { it.id == id }
                press(if (target > ui().cursor) K_DOWN else K_UP, intervalMs = 150)
            }
            assertEquals("cursor on $id", id, cursorRow())
        }
        var presses = 0
        moveTo(RowId("playback.pause_rewind")); press(K_OK, intervalMs = 400); presses++
        awaitTrue("Pause and rewind", 5_000) { ui().route == SettingsRoute.PauseRewind }
        moveTo(RowId("playback.timeshift_location")); press(K_OK, intervalMs = 400); presses++
        awaitTrue("Save to", 5_000) { ui().route is SettingsRoute.SaveLocation }
        val ids = ui().page!!.rows.map { it.id.key }
        log("I-SL-19 Save to rows: $ids")
        assertTrue("This device row", "storage.loc.internal" in ids)
        assertTrue("Add a network folder row", "storage.loc.add" in ids)
        moveTo(RowId("storage.loc.add")); press(K_OK, intervalMs = 400)
        awaitTrue("Find computers", 5_000) { ui().route == SettingsRoute.FindComputers }
        awaitTrue("the search ended (no multicast on the emulator)", 20_000) { ui().page?.rows?.any { it.id.key == "storage.find.status" } == true }
        val find = ui().page!!.rows
        log("I-SL-19 Find computers: ${find.map { "${it.id.key}='${it.label}'" }}")
        assertTrue("manual entry offered", find.any { it.id.key == "storage.find.enter" })
        assertTrue("phone entry offered", find.any { it.id.key == "storage.find.phone" })
        press(K_BACK, intervalMs = 400)
        awaitTrue("BACK to Save to", 5_000) { ui().route is SettingsRoute.SaveLocation }
        pass("I-SL-19", "Save to after $presses OKs from Playback; find status '${find.firstOrNull { it.id.key == "storage.find.status" }?.label}'")
    }

    // ------------------------------------------------------------------ I-SL-4 / 4b (fallback, no server)

    /**
     * I-SL-4: the folder is unreachable (nothing listens on 10.0.2.2:4445) and the keeper has seen the failure → the tune
     * saves on this device (`fallback = UNREACHABLE`), one toast; a re-tune shows no second toast; rewind works.
     */
    @Test
    fun sl04_unreachableFolderSavesOnThisDeviceWithOneToast() {
        if (!runs("Samba is listening on 4445: this case needs nothing there", !sambaRequested())) return
        val id = chooseFolder(SMB_PORT, "NAS4")
        awaitKeeper("keeper saw the failure", 20_000) { it.locationId == id && it.failureSeen }
        val watch = ToastWatch("isn't answering")
        try {
            bufferedOn(TS_PLAIN)
            val st = awaitBuffer("fallback published", 10_000) { it.fallback != null }
            log("I-SL-4 buffer storage=${st.storage} fallback=${st.fallback} name=${st.storageName} location=${st.locationId}")
            assertEquals(StorageKind.INTERNAL, st.storage)
            assertEquals(LocationFallback.UNREACHABLE, st.fallback)
            assertEquals("NAS4", st.storageName)
            awaitTrue("I-SL-4 fallback toast", 8_000) { watch.seen("isn't answering") }
            pauseWorks("I-SL-4")
        } finally { watch.close() }
        // Once per process per (location, reason): a re-tune does not toast again.
        val again = ToastWatch("isn't answering")
        try {
            bufferedOn(TS_PLAIN_2)
            Thread.sleep(6_000)
            assertFalse("I-SL-4 no second toast on the next tune", again.seen("isn't answering"))
        } finally { again.close() }
        pass("I-SL-4")
    }

    /**
     * I-SL-4b: last-known-good from a previous process, the folder blackholed (the proxy accepts TCP, SMB never answers):
     * the tune chooses the folder without waiting, the first write misses `SMB_OPEN_DEADLINE_MS`, the cycle switches to
     * this device with one toast, and rewind works on this device.
     */
    @Test
    fun sl04b_lastKnownGoodBlackholedSwitchesAtTheDeadline() {
        if (!runs("smbproxy control on $HOST:$PROXY_CONTROL", proxyUp())) return
        proxy("mode=blackhole")
        val id = chooseFolder(PROXY_PORT, "NAS4b", lastKnownGood = true)
        val watch = ToastWatch("isn't answering")
        try {
            val t0 = SystemClock.elapsedRealtime()
            bufferedOn(TS_PLAIN)
            val first = lb()
            log("I-SL-4b at the tune: storage=${first?.storage} fallback=${first?.fallback} ${keeperBrief()}")
            val sw = awaitBuffer("switched to this device", 30_000) { it.fallback != null }
            log("I-SL-4b switched after ${SystemClock.elapsedRealtime() - t0} ms: storage=${sw.storage} fallback=${sw.fallback} name=${sw.storageName}")
            assertEquals(StorageKind.INTERNAL, sw.storage)
            assertEquals(LocationFallback.UNREACHABLE, sw.fallback)
            assertEquals(id, sw.locationId)
            awaitTrue("I-SL-4b fallback toast", 10_000) { watch.seen("isn't answering") }
            pauseWorks("I-SL-4b")
        } finally { watch.close() }
        pass("I-SL-4b")
    }

    // ------------------------------------------------------------------ I-SL-3 (zap, no I/O)

    /**
     * I-SL-3 (emulator form): 15 CH+ zaps with a blackholed folder chosen (last-known-good) against 15 with This device:
     * the tune never waits on the network (§5.1, A-B1). First-frame P50/P90 logged; on the emulator the blackholed P50
     * must stay within 25 % + 250 ms of This device's (the 5 % zap gate is a hardware gate).
     *
     * (Zap-1h investigation, 2026-10-24) The legs run with the panel's `reconnect_mode: reset`. With `continue` each
     * channel resumes where its previous connection stopped, so whether the first IDR falls inside the 1-s connect burst
     * (≈ 0.35 s to first frame) or after it (≈ 1.3 s: the RAP wait plus real-time bytes) was decided by the block's place
     * in the sequence, which read as "1 h is slow". `reset` starts every connection on the clip's first IDR, so the legs
     * compare the app, not the panel's phase; the 1-h leg is then held to the same bound as the folder.
     */
    @Test
    fun sl03_zapWithABlackholedFolderDoesNotWait() {
        if (!runs("smbproxy control on $HOST:$PROXY_CONTROL", proxyUp())) return
        fun zaps(label: String): List<Long> {
            bufferedOn(100)
            val out = ArrayList<Long>()
            repeat(15) { i ->
                val before = main { controller.diagnostics.zaps() }.lastOrNull()?.tuneId
                val from = controller.state.value.target?.identity
                press(K_CH_UP, intervalMs = 0)
                awaitTrue("I-SL-3 $label zap ${i + 1}", 20_000, periodMs = 100) {
                    controller.state.value.target?.identity != from && main { controller.diagnostics.zaps() }.lastOrNull().let { z -> z != null && z.tuneId != before && z.firstFrameMs != null }
                }
                main { controller.diagnostics.zaps() }.last().firstFrameMs?.let { out += it }
                Thread.sleep(1_000)
            }
            // The last zap's journal (where first media waits), relative to its first event.
            val last = main { controller.diagnostics.zaps() }.last().tuneId
            val ev = events().filter { it.tuneId >= last - 1 }
            ev.firstOrNull()?.let { t0 -> log("I-SL-3 $label last zap journal: " + ev.joinToString(" | ") { "+${it.elapsedMs - t0.elapsedMs} ${it.type} ${it.code} ${it.detail.take(60)}" }) }
            log("I-SL-3 $label: first frame P50 ${percentile(out, 0.5)} P90 ${percentile(out, 0.9)} ms over ${out.size} zaps; ${keeperBrief()} buffer ${lb()?.let { "${it.storage}/${it.fallback}" }}")
            return out
        }
        pp.faults(JSONObject().put("reconnect_mode", "reset"))
        try {
            val internal = zaps("this device")
            // The folder's Auto length is 1 h (§3.4) against 30 min here: the same length on this device separates the two.
            applyTs("timeshift=60")
            val internalHour = zaps("this device, 1 h")
            applyTs("timeshift=auto")
            log("I-SL-3 this device 30 min vs 1 h P50 ${percentile(internal, 0.5)} / ${percentile(internalHour, 0.5)} ms")
            proxy("mode=blackhole")
            chooseFolder(PROXY_PORT, "NAS3", lastKnownGood = true)
            val black = zaps("blackholed folder")
            dumpSmbThreads()
            val p50 = percentile(black, 0.5)!! to percentile(internal, 0.5)!!
            log("I-SL-3 P50 blackholed/this device ${p50.first}/${p50.second} ms")
            // Zap-1h investigation: with the panel phase fixed (reset), first media is no longer bimodal by block; the ring
            // length does not change the tune path (ZapLengthIndependenceTest), so 1 h is held to the same bound.
            val hour = percentile(internalHour, 0.5)!!
            assertTrue("I-SL-3 the tune does not wait on the folder: $p50 (this device 1 h $hour)", p50.first <= p50.second * 1.25 + 250)
            assertTrue("I-SL-3 a 1-h ring zaps as fast as a 30-min one: ${p50.second} / $hour ms", hour <= p50.second * 1.25 + 250)
            pass("I-SL-3", "P50 $p50, this device 1 h $hour")
        } finally {
            pp.faults(JSONObject().put("reconnect_mode", "continue"))
        }
    }

    /**
     * Keeper liveness (§5.3, §13): a connect hanging on a blackholed folder must not keep the keeper from the folder the
     * viewer chooses next. Folder A (blackholed) is chosen and its connect left hanging; folder B (nothing on 4445,
     * refused at once) is chosen: the keeper reports B's failure within the watchdog time plus a margin.
     */
    @Test
    fun sl05_switchingAwayFromAHangingConnectReachesTheNewFolder() {
        if (!runs("smbproxy control on $HOST:$PROXY_CONTROL", proxyUp())) return
        if (!runs("Samba is listening on 4445: this case needs nothing there", !sambaRequested())) return
        // The keeper connects only while The Box is in the foreground (§5.3): play something first.
        bufferedOn(TS_PLAIN)
        proxy("mode=blackhole")
        val a = chooseFolder(PROXY_PORT, "NAS5a")
        awaitKeeper("keeper connecting to A", 10_000) { it.locationId == a && it.state == KeeperState.CONNECTING }
        Thread.sleep(2_000)
        val b = chooseFolder(SMB_PORT, "NAS5b")
        awaitKeeper("keeper on B with its failure seen", StorageConstants.SMB_WATCHDOG_MS + 15_000) { it.locationId == b && it.failureSeen }
        pass("I-SL-keeper-switch")
    }

    // ------------------------------------------------------------------ I-SL-8 (demotion; simulated without a server)

    /**
     * I-SL-8 (no-server form): a disk stop outside the start-of-cycle grace (`onStorageFailure` with 30 s since the first
     * write, as the factory reports a mid-cycle drop) demotes the folder; the next tune saves on this device with
     * `UNSTABLE` and the "kept dropping out" toast; a stop inside the grace does not demote. The real drop through the
     * proxy needs Samba (I-SL-8 proper, below).
     */
    @Test
    fun sl08a_aDropOutsideTheGraceDemotesTheFolder() {
        if (!runs("smbproxy control on $HOST:$PROXY_CONTROL", proxyUp())) return
        proxy("mode=blackhole")
        val id = chooseFolder(PROXY_PORT, "NAS8", lastKnownGood = true)
        // Inside the grace: no demotion.
        storage.ringTargets().onStorageFailure(id, RecordStop.DISK_ERROR, StorageFailure.UNREACHABLE, 5_000)
        assertEquals("inside the grace: not demoted", Long.MIN_VALUE, storage.keeper().snapshot.demotedUntilMono)
        storage.ringTargets().onStorageFailure(id, RecordStop.DISK_ERROR, StorageFailure.UNREACHABLE, 30_000)
        assertTrue("demoted", storage.keeper().snapshot.demotedUntilMono != Long.MIN_VALUE)
        val choice = storage.ringTargets().current(StoragePurpose.TIMESHIFT)
        log("I-SL-8a choice after the drop: ${choice.target.kind} fallback=${choice.fallback}")
        assertEquals(LocationFallback.UNSTABLE, choice.fallback)
        val watch = ToastWatch("kept dropping out")
        try {
            bufferedOn(TS_PLAIN)
            val st = awaitBuffer("fallback published", 10_000) { it.fallback != null }
            assertEquals(StorageKind.INTERNAL, st.storage)
            assertEquals(LocationFallback.UNSTABLE, st.fallback)
            awaitTrue("I-SL-8a unstable toast", 8_000) { watch.seen("kept dropping out") }
        } finally { watch.close() }
        pass("I-SL-8a")
    }

    // ------------------------------------------------------------------ Samba cases (opt-in)

    /** I-SL-1 (Samba, opt-in): a guest share on 4445, READY, the tune saves to it; pause 60 s and resume in place. */
    @Test
    fun sl01_sambaSavesAndResumesWherePaused() {
        if (!runs("Samba cases: -e samba 1 and tools/smbtest/run-samba.sh", sambaRequested())) return
        val id = chooseFolder(SMB_PORT, "Samba")
        awaitKeeper("keeper READY", 30_000) { it.locationId == id && it.state == KeeperState.READY }
        bufferedOn(TS_PLAIN)
        val st = awaitBuffer("saving on the folder", 20_000) { it.storage == StorageKind.SMB && it.saving }
        assertEquals(null, st.fallback)
        Thread.sleep(10_000)
        pauseByKey()
        Thread.sleep(60_000)
        resumeByKey()
        Thread.sleep(1_000)
        assertBehind(60_000, 2_500 + emuSlack, "I-SL-1 after 60 s paused on SMB")
        assertEquals(ArchivePhase.PLAYING, archive().phase)
        pass("I-SL-1")
    }

    /** I-SL-8 (Samba + proxy, opt-in): saving through the proxy, `blackhole` 20 s → DISK_ERROR, live continues, demoted. */
    @Test
    fun sl08_sambaBlackholeMidCycleDemotes() {
        if (!runs("Samba cases: -e samba 1", sambaRequested())) return
        if (!runs("smbproxy control on $HOST:$PROXY_CONTROL", proxyUp())) return
        proxy("mode=pass")
        val id = chooseFolder(PROXY_PORT, "SambaP")
        awaitKeeper("keeper READY", 30_000) { it.locationId == id && it.state == KeeperState.READY }
        bufferedOn(TS_PLAIN)
        awaitBuffer("saving on the folder", 20_000) { it.storage == StorageKind.SMB && it.saving }
        Thread.sleep(SMB_GRACE_PAST_MS)
        proxy("mode=blackhole")
        awaitBuffer("disk stop", 40_000) { it.stop == RecordStop.DISK_ERROR || it.stop == RecordStop.DISK_TOO_SLOW }
        assertTrue("live continues", controller.state.value is app.thebox.player.api.PlaybackState.Playing)
        proxy("mode=pass")
        assertTrue("demoted", storage.keeper().snapshot.demotedUntilMono != Long.MIN_VALUE)
        pass("I-SL-8")
    }

    /**
     * AGP's connected runner reports an assumption failure as a failed test (integration 2026-10-19), so a case whose
     * precondition is missing logs "not run" and returns.
     */
    private fun runs(what: String, ok: Boolean): Boolean {
        if (!ok) log("${name.methodName}: not run ($what)")
        return ok
    }

    @get:org.junit.Rule val name = org.junit.rules.TestName()

    private fun sambaRequested(): Boolean = InstrumentationRegistry.getArguments().getString("samba") == "1"

    companion object {
        const val HOST = "10.0.2.2"
        /** Samba's port in tools/smbtest; nothing listens there without it. */
        const val SMB_PORT = 4445
        const val PROXY_PORT = 4446
        const val PROXY_CONTROL = 4447
        /** Past `SMB_FIRST_WRITE_GRACE_MS` (20 s) after the first write. */
        const val SMB_GRACE_PAST_MS = 25_000L
    }
}
