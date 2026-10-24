// WP-S1 (docs/design/m6-storage-locations.md §5.3, §5.4, §3.5, §13; A-S1, A-B4, A-N2, A-N6; lead ruling 6).
package app.thebox.data.storage.smb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.storage.LocationState
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageLocation
import app.thebox.core.storage.smb.FaultClass
import app.thebox.core.storage.smb.OpenMode
import app.thebox.core.storage.smb.RemoteShare
import app.thebox.data.storage.DeviceIdentity
import app.thebox.data.storage.StorageLocationsImpl
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** The keeper's states (§5.3). DEMOTED is a time window on top of them ([KeeperSnapshot.demotedUntilMono]). */
enum class KeeperState { IDLE, CONNECTING, READY, BACKOFF, AUTH_FAILED }

/**
 * What `StorageTargetsImpl.current()` reads, without I/O or a lock (§5.1). Times are MonoClock ms;
 * [lastGoodMonoMs] = [NEVER] until a good round trip in this process.
 */
data class KeeperSnapshot(
    val locationId: String?,
    val state: KeeperState,
    val lastGoodMonoMs: Long,
    /** A connect or keepalive failed for [locationId] in this process (§5.1 "no failure seen in this process"). */
    val failureSeen: Boolean,
    val networkUp: Boolean,
    val demotedUntilMono: Long = NEVER,
) { companion object { const val NEVER = Long.MIN_VALUE } }

/**
 * `noBackupFilesDir/smb-last-good`: `<locationId> <wallMs>` (§5.1), so a fresh process may choose the folder before its
 * first connect. Read once, lazily; written at most every [WRITE_EVERY_MS] per location (keepalives come every 20 s).
 */
class LastGoodStore(private val file: File) {
    @Volatile private var cache: Pair<String, Long>? = null
    @Volatile private var loaded = false
    private var lastWrite = Long.MIN_VALUE

    /** The wall time of the last good round trip with [locationId] in any process; null when none. */
    fun get(locationId: String): Long? {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                cache = try {
                    file.takeIf { it.isFile }?.readText()?.trim()?.split(' ')?.takeIf { it.size == 2 }?.let { it[0] to it[1].toLong() }
                } catch (_: Exception) { null }
                loaded = true
            }
        }
        return cache?.takeIf { it.first == locationId }?.second
    }

    @Synchronized fun put(locationId: String, wallMs: Long) {
        val prev = cache
        cache = locationId to wallMs; loaded = true
        if (prev?.first == locationId && lastWrite != Long.MIN_VALUE && wallMs - lastWrite < WRITE_EVERY_MS) return
        lastWrite = wallMs
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText("$locationId $wallMs")
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) { /* best effort: the in-memory value still counts for this process */ }
    }

    companion object { const val FILE_NAME = "smb-last-good"; const val WRITE_EVERY_MS = 600_000L }
}

/**
 * The sole owner of the ring's network link (§5.3; A-S1, lead ruling 6): one [SmbjRemoteLink] for the SMB location
 * chosen for TIMESHIFT, on its own `SMBClient`. Everything runs on [exec] (`box-smb`), never main.
 *
 * - **Triggers**: [start] (the first frame, or `SMB_FIRST_CONNECT_DELAY_MS` after launch, A-N6), a location or sign-in
 *   change, the network callback, ON_START, the guide's preview start ([wakeNow]), [warm] from `current()`.
 * - **Backoff** 5 s, 15 s, 60 s, then 300 s, reset by any trigger. A logon status or a missing password → AUTH_FAILED
 *   until the sign-in changes.
 * - **On every (re)connect**: write 4 KiB to `TheBox-rewind/.wake-<deviceId>` (A-B4; a sleeping NAS disk spins up), record
 *   last-known-good, and at the first READY of the process run the sweep (§3.5) after the wake write.
 * - **Keepalive** while READY in the foreground: a live free-space query every `SMB_KEEPALIVE_MS`. A transport loss goes
 *   through the link's own reconnect (one owner); a timeout keeps the connection (SLOW, A-B4).
 * - **Background**: disconnect after `SMB_BACKGROUND_DISCONNECT_MS`, unless a ring file is open.
 * - **Demotion** (§5.4) is a window set synchronously by [demote].
 * - **Watchdog** (§13): a connect stuck past `SMB_WATCHDOG_MS` has its connection `close(true)`d from another thread.
 */
class SmbSessionKeeper(
    private val locations: StorageLocationsImpl,
    private val deviceId: DeviceIdentity,
    private val lastGood: LastGoodStore,
    private val exec: SmbExecutor,
    private val clock: MonoClock,
    private val wallMs: () -> Long,
    private val pid: Int,
    /** `SMB_IO_TIMEOUT_MS` or `_SMALL_POOL_MS` by the device's pool size (§4.3, lead ruling 5). */
    val ioTimeoutMs: Long,
    private val openerFor: (StorageLocation) -> ShareOpener = { SmbjShareOpener(it.smb!!, ioTimeoutMs) },
    private val sweeper: SmbSweeper = SmbSweeper(),
    private val classifier: (Throwable, Boolean, Boolean?) -> FaultClass = SmbFaults::classify,
    private val isAuthFailure: (Throwable) -> Boolean = { it is SignInNeededException || SmbFaults.isLogonFailure(it) },
) {
    @Volatile var snapshot = KeeperSnapshot(null, KeeperState.IDLE, KeeperSnapshot.NEVER, failureSeen = false, networkUp = true)
        private set

    private class Kept(val key: Pair<StorageLocation, Long>, val location: StorageLocation, val link: SmbjRemoteLink, val opener: ShareOpener)
    @Volatile private var kept: Kept? = null
    private val keptLock = Any()

    // box-smb state
    @Volatile private var started = false
    @Volatile private var foreground = true
    @Volatile private var networkUp = true
    private var backoffStep = 0
    private var retry: ScheduledFuture<*>? = null
    private var keepalive: ScheduledFuture<*>? = null
    private var background: ScheduledFuture<*>? = null
    private val failures: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val swept: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** A new link was made and its [resetFor] is queued: [evaluate] waits for it. */
    @Volatile private var pendingReset = false
    @Volatile private var lastWarmMono = Long.MIN_VALUE
    // demotion (§5.4): written synchronously by demote(), read by current()
    @Volatile private var demotedUntil = KeeperSnapshot.NEVER
    @Volatile private var lastDemoteAt = KeeperSnapshot.NEVER
    @Volatile private var lastDemoteLen = 0L
    @Volatile private var demotedId: String? = null

    init { locations.addChangeListener { post { evaluate(reset = true) } } }

    // ================================================================ triggers (any thread, never block)

    /** Arms the first connect: [firstFrame] opening, or `SMB_FIRST_CONNECT_DELAY_MS`, whichever is first (A-N6). */
    fun start() {
        exec.schedule(StorageConstants.SMB_FIRST_CONNECT_DELAY_MS) { startNow() }
    }

    /** The first frame drew (or a test): connects now if a folder is chosen. Idempotent. */
    fun startNow() = post { if (!started) { started = true; evaluate(reset = true) } }

    /** `current()` saw the link not READY (§5.1). Rate-limited to one evaluate per `SMB_RECONNECT_MIN_GAP_MS`. */
    fun warm() {
        val now = clock.nowMs()
        if (lastWarmMono != Long.MIN_VALUE && now - lastWarmMono < StorageConstants.SMB_RECONNECT_MIN_GAP_MS) return
        lastWarmMono = now
        post { if (started && snapshot.state != KeeperState.CONNECTING) evaluate(reset = true) }
    }

    fun onForeground(fg: Boolean) = post {
        foreground = fg
        if (fg) {
            background?.cancel(false); background = null
            evaluate(reset = true)
            kept?.link?.currentShare()?.let { wake(it) } // ON_START: spin the disk up before the viewer pauses (A-B4)
        } else scheduleBackgroundCheck()
    }

    fun onNetwork(up: Boolean) = post {
        networkUp = up
        publishSnapshot()
        if (up) evaluate(reset = true)
    }

    /** The guide's preview start (§5.3): the wake write, so the disk is spinning before a pause. */
    fun wakeNow() = post { kept?.link?.currentShare()?.let { wake(it) } }

    /** A ring stopped with a disk failure: check the link now (the keepalive path). */
    fun onRingFailure() = post { keepaliveTick() }

    /**
     * §5.4, synchronous (A-N5): [locationId] is demoted for `SMB_DEMOTE_MS`, doubled for a repeat within
     * `SMB_DEMOTE_REPEAT_WINDOW_MS`, capped at `SMB_DEMOTE_MAX_MS`. Preferred again after a READY plus one keepalive.
     */
    fun demote(locationId: String) {
        val now = clock.nowMs()
        val repeat = demotedId == locationId && lastDemoteAt != KeeperSnapshot.NEVER && now - lastDemoteAt <= StorageConstants.SMB_DEMOTE_REPEAT_WINDOW_MS
        val len = if (repeat) minOf(lastDemoteLen * 2, StorageConstants.SMB_DEMOTE_MAX_MS) else StorageConstants.SMB_DEMOTE_MS
        demotedId = locationId; lastDemoteAt = now; lastDemoteLen = len; demotedUntil = now + len
        BoxLog.i(TAG) { "demoted $locationId for ${len / 1000} s" }
        publishSnapshot()
        post { publish() }
    }

    /** The one link for [location] (no I/O: smbj objects are made on box-smb at the first connect). */
    fun linkFor(location: StorageLocation): SmbjRemoteLink {
        val key = location to locations.signInVersion.value
        synchronized(keptLock) {
            kept?.let { if (it.key == key) return it.link }
            val old = kept
            val opener = openerFor(location)
            val link = SmbjRemoteLink(
                opener = opener, exec = exec, clock = clock, ioTimeoutMs = ioTimeoutMs,
                password = { runBlocking { locations.readPassword(location.id) } },
                onDemand = { warm() },
                onConnected = { share -> onLinkUp(location, share) },
                classifier = classifier,
            )
            kept = Kept(key, location, link, opener)
            pendingReset = true
            if (old != null) post {
                old.link.disconnect()
                if (old.location.id != location.id) locations.clearKeeper(old.location.id)
                resetFor(location)
            } else post { resetFor(location) }
            return link
        }
    }

    /** The current link when it serves [locationId]; null otherwise. */
    fun linkIfCurrent(locationId: String): SmbjRemoteLink? = kept?.takeIf { it.location.id == locationId }?.link

    /** Installs the watchdog thread (§13). Production only. */
    fun startWatchdog() {
        val w = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "box-smb-watch").also { it.isDaemon = true } }
        w.scheduleWithFixedDelay({
            val busy = exec.busySinceMono
            if (busy != Long.MIN_VALUE && clock.nowMs() - busy > StorageConstants.SMB_WATCHDOG_MS) {
                (kept?.opener as? SmbjShareOpener)?.abortInFlight()
            }
        }, StorageConstants.SMB_WATCHDOG_MS, StorageConstants.SMB_WATCHDOG_MS / 3, TimeUnit.MILLISECONDS)
    }

    // ================================================================ box-smb

    private fun post(work: () -> Unit) = exec.post(Runnable(work))

    private fun resetFor(location: StorageLocation) {
        if (kept?.location?.id != location.id) return // superseded by a newer link; its own reset follows
        pendingReset = false
        retry?.cancel(false); retry = null
        keepalive?.cancel(false); keepalive = null
        backoffStep = 0
        setState(KeeperState.IDLE, location.id)
        evaluate(reset = true)
    }

    private fun evaluate(reset: Boolean) {
        val loc = locations.keptLocation()
        if (loc == null) {
            val k = synchronized(keptLock) { kept.also { kept = null } }
            if (k != null) { k.link.disconnect(); locations.clearKeeper(k.location.id) }
            retry?.cancel(false); keepalive?.cancel(false)
            setState(KeeperState.IDLE, null)
            return
        }
        val link = linkFor(loc)
        if (!started || pendingReset) return // a queued resetFor(loc) evaluates again
        if (snapshot.locationId != loc.id) return
        if (reset) backoffStep = 0
        when (snapshot.state) {
            KeeperState.CONNECTING, KeeperState.AUTH_FAILED -> return
            KeeperState.READY -> if (link.isReady) { scheduleKeepalive(); return } else connect(loc, link)
            KeeperState.IDLE -> if (foreground || link.openFiles > 0) connect(loc, link)
            KeeperState.BACKOFF -> if (reset) connect(loc, link)
        }
    }

    private fun connect(loc: StorageLocation, link: SmbjRemoteLink) {
        retry?.cancel(false); retry = null
        setState(KeeperState.CONNECTING, loc.id)
        val err = link.connectBlocking() ?: return // onLinkUp ran on success
        onFailure(loc, err)
    }

    private fun onFailure(loc: StorageLocation, err: Throwable) {
        if (kept?.location?.id != loc.id) return
        failures += loc.id
        if (isAuthFailure(err)) {
            BoxLog.i(TAG) { "sign-in refused for ${loc.id}" }
            setState(KeeperState.AUTH_FAILED, loc.id)
            return
        }
        val steps = StorageConstants.SMB_BACKOFF_MS
        val delay = steps[minOf(backoffStep, steps.size - 1)]
        backoffStep++
        BoxLog.i(TAG) { "connect to ${loc.id} failed (${SmbFaults.faultName(err)}); retry in ${delay / 1000} s" }
        setState(KeeperState.BACKOFF, loc.id)
        retry = exec.schedule(delay) { if (snapshot.state == KeeperState.BACKOFF && snapshot.locationId == loc.id) kept?.let { connect(loc, it.link) } }
    }

    /** Box-smb, after every successful connect or reconnect of [location]'s link (the link's onConnected). */
    private fun onLinkUp(location: StorageLocation, share: RemoteShare) {
        if (kept?.location?.id != location.id) return
        backoffStep = 0
        markGood(location.id)
        setState(KeeperState.READY, location.id)
        wake(share)
        if (swept.add(location.id)) {
            try { sweeper.sweep(share, ringDir(location), deviceId.id, pid) } catch (e: Exception) { BoxLog.i(TAG) { "sweep failed: ${SmbFaults.faultName(e)}" } }
        }
        try { kept?.link?.probe() } catch (_: IOException) { }
        publish()
        scheduleKeepalive()
    }

    private fun keepaliveTick() {
        val k = kept ?: return
        if (snapshot.locationId != k.location.id) return
        if (snapshot.state != KeeperState.READY) { evaluate(reset = false); return }
        val link = k.link
        try {
            link.probe()
            markGood(k.location.id)
            publish()
        } catch (e: IOException) {
            when (classifier(e, Thread.currentThread().isInterrupted, null)) {
                FaultClass.SLOW -> BoxLog.i(TAG) { "keepalive slow; connection kept" } // A-B4: a spinning disk or a roam
                FaultClass.CANCEL -> Unit
                FaultClass.ACCESS -> if (isAuthFailure(e)) setState(KeeperState.AUTH_FAILED, k.location.id)
                else -> {
                    failures += k.location.id
                    try { link.reconnect(link.epoch, clock.nowMs() + StorageConstants.SMB_RECONNECT_BUDGET_MS) } catch (re: IOException) {
                        onFailure(k.location, link.lastConnectError ?: re)
                    }
                }
            }
        }
        scheduleKeepalive()
    }

    private fun scheduleKeepalive() {
        keepalive?.cancel(false)
        keepalive = if (foreground && snapshot.state == KeeperState.READY) exec.schedule(StorageConstants.SMB_KEEPALIVE_MS) { keepaliveTick() } else null
    }

    private fun scheduleBackgroundCheck() {
        background?.cancel(false)
        background = exec.schedule(StorageConstants.SMB_BACKGROUND_DISCONNECT_MS) {
            if (foreground) return@schedule
            val k = kept ?: return@schedule
            if (k.link.openFiles > 0) { scheduleBackgroundCheck(); return@schedule } // a ring file is open (A-S1)
            keepalive?.cancel(false); keepalive = null
            retry?.cancel(false); retry = null
            k.link.disconnect()
            setState(KeeperState.IDLE, k.location.id)
            BoxLog.i(TAG) { "background: disconnected" }
        }
    }

    /** 4 KiB to `.wake-<deviceId>` (A-B4). Failures are ignored: the next trigger writes it again. */
    private fun wake(share: RemoteShare) {
        val loc = kept?.location ?: return
        val dir = ringDir(loc)
        try {
            share.mkdirs(dir)
            val f = share.open("$dir/${SmbSweeper.wakeName(deviceId.id)}", OpenMode.CREATE_OVERWRITE)
            try { f.write(WAKE_BYTES, 0, WAKE_BYTES.size, 0L) } finally { f.closeAsync() }
        } catch (e: IOException) {
            BoxLog.i(TAG) { "wake write failed: ${SmbFaults.faultName(e)}" }
        }
    }

    private fun markGood(id: String) {
        snapshot = snapshot.copy(lastGoodMonoMs = clock.nowMs())
        lastGood.put(id, wallMs())
    }

    private fun setState(state: KeeperState, id: String?) {
        snapshot = snapshot.copy(
            locationId = id, state = state,
            lastGoodMonoMs = if (id != snapshot.locationId) KeeperSnapshot.NEVER else snapshot.lastGoodMonoMs,
            failureSeen = id != null && id in failures,
            networkUp = networkUp,
        )
        publishSnapshot()
        publish()
    }

    private fun publishSnapshot() {
        val s = snapshot
        snapshot = s.copy(networkUp = networkUp, demotedUntilMono = if (demotedId == s.locationId) demotedUntil else KeeperSnapshot.NEVER,
            failureSeen = s.locationId != null && s.locationId in failures)
    }

    private fun publish() {
        val s = snapshot
        val id = s.locationId ?: return
        val link = linkIfCurrent(id)
        val free = link?.lastFree()?.first
        val demoted = s.demotedUntilMono != KeeperSnapshot.NEVER && (clock.nowMs() < s.demotedUntilMono || s.lastGoodMonoMs < s.demotedUntilMono)
        val state = when (s.state) {
            KeeperState.IDLE -> { locations.clearKeeper(id); return }
            KeeperState.CONNECTING -> LocationState.CONNECTING
            KeeperState.READY -> if (demoted) LocationState.DEMOTED else LocationState.READY
            KeeperState.BACKOFF -> LocationState.UNREACHABLE
            KeeperState.AUTH_FAILED -> LocationState.SIGN_IN_NEEDED
        }
        locations.publishKeeper(id, state, free, null)
    }

    /** Known last-good wall time of [locationId] (this or an earlier process); null when none. */
    fun lastKnownGoodWall(locationId: String): Long? = lastGood.get(locationId)

    companion object {
        private const val TAG = "SmbKeeper"
        private val WAKE_BYTES = ByteArray(StorageConstants.WAKE_FILE_BYTES)

        /** `<path>/TheBox-rewind`, share-relative. */
        fun ringDir(location: StorageLocation): String {
            val base = location.smb?.path?.trim('/').orEmpty()
            return if (base.isEmpty()) StorageConstants.TIMESHIFT_DIR_SMB else "$base/${StorageConstants.TIMESHIFT_DIR_SMB}"
        }
    }
}

/**
 * The keeper's platform triggers (§5.3): `ProcessLifecycleOwner` ON_START/ON_STOP, the default network callback and the
 * first frame ([firstFrame], `StartupGate`). Registered once, from the main thread.
 */
object KeeperTriggers {
    fun install(context: Context, keeper: SmbSessionKeeper, firstFrame: (() -> Unit) -> Unit) {
        keeper.start()
        keeper.startWatchdog()
        firstFrame { keeper.startNow() }
        Handler(Looper.getMainLooper()).post {
            try {
                ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) = keeper.onForeground(true)
                    override fun onStop(owner: LifecycleOwner) = keeper.onForeground(false)
                })
            } catch (e: Exception) { BoxLog.w("SmbKeeper", "lifecycle observer not installed: ${e.javaClass.simpleName}") }
        }
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = keeper.onNetwork(true)
                override fun onLost(network: Network) = keeper.onNetwork(false)
            })
        } catch (e: Exception) { BoxLog.w("SmbKeeper", "network callback not installed: ${e.javaClass.simpleName}") }
    }
}
