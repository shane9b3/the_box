package app.thebox.settings

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import app.thebox.core.guide.DeviceClass
import app.thebox.core.input.KeyBindingStore
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.state.ExternalPlayerApp
import app.thebox.core.settings.state.PlaybackDomain
import app.thebox.core.settings.state.WpaPageEnv
import app.thebox.data.art.BuiltInKeys
import app.thebox.external.ExternalPlayerLauncher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device and app facts of WP-A's pages (docs/design/m5-setup-settings.md §3.3). [runningSoftwareAudioFallback] is
 * the value the engine was built with: the stored value the first time it is asked (the Playback page is the only place
 * that changes it, so the first ask precedes any change this process; `PlayerFactory.create` reads the same store).
 */
@Singleton
class WpaPageEnvImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SettingsStore,
    private val launcher: ExternalPlayerLauncher,
    private val restart: RestartNow,
    private val keys: KeyBindingStore,
    private val builtIn: BuiltInKeys,
    /** (M6 time-shift, §12) "Pause and rewind" Off deletes the ring file through the session factory's discard path. */
    private val timeshift: dagger.Lazy<app.thebox.player.timeshift.TimeshiftStartup>,
) : WpaPageEnv {
    override val hasTmdbKey: Boolean get() = builtIn.tmdb != null

    override val sdkInt: Int get() = Build.VERSION.SDK_INT

    override val deviceLowEnd: Boolean by lazy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        DeviceClass(lowRam = am.isLowRamDevice, totalMemBytes = mem.totalMem, memoryClassMb = am.memoryClass).lowEnd
    }

    @Volatile private var softwareAudioAtStart: Boolean? = null

    override fun system24h(): Boolean = DateFormat.is24HourFormat(context)

    override fun externalPlayers(): List<ExternalPlayerApp> = launcher.installed()

    override fun runningSoftwareAudioFallback(): Boolean? {
        if (!store.ready.value) return null
        return softwareAudioAtStart ?: store.flow(PlaybackDomain).value.softwareAudioFallback.also { softwareAudioAtStart = it }
    }

    override fun restartNow() = restart.restart()

    override fun keyOverrideCount(): Int = keys.overrides.value.entries.size

    /** (M6 §8.2, §12) StatFs of `cacheDir` (where `ring-<pid>.bin` lives) and the ring file's size. */
    override fun timeshiftStorage(): app.thebox.core.settings.state.TimeshiftStorageFacts? = try {
        val dir = context.cacheDir
        val fs = android.os.StatFs(dir.path)
        val ring = java.io.File(dir, "timeshift/ring-${android.os.Process.myPid()}.bin")
        app.thebox.core.settings.state.TimeshiftStorageFacts(fs.totalBytes, fs.availableBytes, if (ring.exists()) ring.length() else 0L)
    } catch (e: Exception) {
        null
    }

    /** (M6 §12) Off: the factory's idle discard (closes the fd, then deletes; never under an open buffered cycle). */
    override fun timeshiftOff() = timeshift.get().onTrimMemoryComplete()
}
