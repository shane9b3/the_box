// WP-S2 (docs/design/m6-storage-locations.md §3.2, §3.5, §6; owner ruling 3; A-S8).
package app.thebox.player.timeshift

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import app.thebox.core.common.BoxLog
import app.thebox.core.storage.StorageFlags
import app.thebox.core.storage.VolumeCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * USB drives and SD cards listed by `getExternalFilesDirs(null)` (index > 0, mounted, removable), refreshed on
 * `box-ts-sweep` at start and on every `MEDIA_*` broadcast; the volume sweep; removal (`stopRecording(STORAGE_EVICTED)`
 * on the receiver, `storage.close()` posted to `box-ts-discard`). Started by `TimeshiftStartup.onAppStart`.
 *
 * - **Listing** (§3.2): no permission, no SAF. The uuid and label come from `StorageManager.getStorageVolume(dir)`; a
 *   device that lists no drive shows no USB option (owner ruling 3). [StorageFlags.VOLUME_ENABLED] false lists none.
 * - **Removal** (§3.2, A-S8): a runtime receiver (scheme `file`) for `MEDIA_EJECT`, `_UNMOUNTED`, `_BAD_REMOVAL` and
 *   `_REMOVED`. On main it only asks the factory to stop the ring and post the fd close ([onGone], never waits); the
 *   listing is refreshed on [executor].
 * - **Sweep** (§3.5): at start and on every `MEDIA_MOUNTED`, on [executor]: every file in `<vol>/…/files/timeshift/`
 *   not made by this process (`ring-<pid>-…`) is deleted (app-private, one device).
 */
@Singleton
internal class VolumeWatcher internal constructor(
    private val context: Context,
    /** The factory's `onVolumeGone(uuid, mountPath)`; main thread, must not wait. */
    private val onGone: (uuid: String?, mountPath: String?) -> Boolean,
    private val lister: () -> List<VolumeCatalog.Volume> = { listVolumes(context) },
    private val executor: Executor = SWEEP,
    private val pid: Int = android.os.Process.myPid(),
) : VolumeCatalog {
    @Inject constructor(
        @ApplicationContext context: Context,
        /** Lazy: the start-up refresh must not build the playback graph; only a removal asks for it. */
        sessions: dagger.Lazy<TimeshiftSessionFactoryImpl>,
    ) : this(context, { uuid, path -> sessions.get().onVolumeGone(uuid, path) })

    private val _volumes = MutableStateFlow<List<VolumeCatalog.Volume>>(emptyList())
    override val volumes: StateFlow<List<VolumeCatalog.Volume>> get() = _volumes

    private val started = AtomicBoolean(false)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = onBroadcast(intent)
    }

    /** Registers the receiver and lists and sweeps the volumes once (main; the I/O runs on [executor]). Idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (!StorageFlags.VOLUME_ENABLED) return
        val filter = IntentFilter().apply {
            for (a in GONE_ACTIONS) addAction(a)
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addDataScheme("file")
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, filter)
        } catch (e: RuntimeException) {
            BoxLog.w(TAG, "volume receiver not registered: ${e.javaClass.simpleName}")
        }
        executor.execute { refresh(); sweepAll() }
    }

    /** Main (the receiver). */
    internal fun onBroadcast(intent: Intent) {
        val action = intent.action ?: return
        val path = intent.data?.path
        if (action in GONE_ACTIONS) {
            val uuid = path?.let { p -> _volumes.value.firstOrNull { it.dir.path == p || it.dir.path.startsWith(p.trimEnd('/') + "/") }?.uuid }
            try {
                if (onGone(uuid, path)) BoxLog.i(TAG) { "time-shift ring stopped: its drive is going away (${action.substringAfterLast('.')})" }
            } catch (e: RuntimeException) {
                BoxLog.w(TAG, "volume removal handling failed: ${e.javaClass.simpleName}")
            }
            executor.execute { refresh() }
        } else if (action == Intent.ACTION_MEDIA_MOUNTED) {
            executor.execute { refresh(); sweepAll() }
        }
    }

    /** Re-lists the volumes ([executor]). */
    internal fun refresh() {
        _volumes.value = try { lister() } catch (e: Exception) {
            BoxLog.w(TAG, "volume listing failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    /** §3.5: deletes every file in each volume's ring folder that this process did not make ([executor]). */
    internal fun sweepAll(): Int {
        var deleted = 0
        for (v in _volumes.value) {
            val files = File(v.dir, TimeshiftStorageImpl.DIR_NAME).listFiles() ?: continue
            val mine = "${TimeshiftStorageImpl.RING_PREFIX}$pid-"
            for (f in files) {
                if (f.name.startsWith(mine)) continue
                if (if (f.isDirectory) f.deleteRecursively() else f.delete()) deleted++
            }
        }
        if (deleted > 0) BoxLog.i(TAG) { "swept $deleted stale time-shift file(s) on removable storage" }
        return deleted
    }

    companion object {
        private const val TAG = "VolumeWatcher"
        private val SWEEP: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "box-ts-sweep").apply { isDaemon = true } }
        val GONE_ACTIONS = setOf(Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_BAD_REMOVAL, Intent.ACTION_MEDIA_REMOVED)

        /** `getExternalFilesDirs(null)`, entries > 0 that are non-null, mounted and removable (§3.2). */
        fun listVolumes(context: Context): List<VolumeCatalog.Volume> {
            val dirs = try { context.getExternalFilesDirs(null) } catch (e: RuntimeException) { return emptyList() }
            val sm = context.getSystemService(StorageManager::class.java)
            val out = ArrayList<VolumeCatalog.Volume>()
            for (i in 1 until dirs.size) {
                val d = dirs[i] ?: continue
                try {
                    if (Environment.getExternalStorageState(d) != Environment.MEDIA_MOUNTED) continue
                    if (!Environment.isExternalStorageRemovable(d)) continue
                    val sv = sm?.getStorageVolume(d)
                    val uuid = sv?.uuid ?: uuidFromPath(d) ?: continue
                    val label = sv?.getDescription(context)?.takeIf { it.isNotBlank() } ?: uuid
                    val st = StatFs(d.path)
                    out += VolumeCatalog.Volume(uuid, label, d, st.totalBytes, st.availableBytes)
                } catch (e: Exception) {
                    BoxLog.w(TAG, "volume $i skipped: ${e.javaClass.simpleName}")
                }
            }
            return out
        }

        /** `/storage/ABCD-1234/Android/data/<pkg>/files` → `ABCD-1234`. */
        internal fun uuidFromPath(dir: File): String? {
            val parts = dir.absolutePath.split('/').filter { it.isNotEmpty() }
            val i = parts.indexOf("storage")
            return parts.getOrNull(i + 1)?.takeIf { i >= 0 && it != "emulated" && it != "self" }
        }
    }
}
