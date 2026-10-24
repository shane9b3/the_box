// WP-S1 (docs/design/m6-storage-locations.md §2, §5, §17): the storage-location bindings. Binding RingTargetSource is the
// switch that turns the feature on in :player's factory (which falls back to InternalRingTargets when it is absent).
package app.thebox.data.storage

import android.app.ActivityManager
import android.content.Context
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.RingTargetSource
import app.thebox.core.playback.timeshift.TimeshiftConstants
import app.thebox.core.storage.NetworkFolders
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageLocations
import app.thebox.core.storage.VolumeCatalog
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.StartupGate
import app.thebox.data.storage.discovery.ComputerFinder
import app.thebox.data.storage.discovery.ComputerFinderImpl
import app.thebox.data.storage.smb.KeeperTriggers
import app.thebox.data.storage.smb.LastGoodStore
import app.thebox.data.storage.smb.SmbExecutor
import app.thebox.data.storage.smb.SmbSessionKeeper
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {
    @Binds abstract fun locations(impl: StorageLocationsImpl): StorageLocations
    @Binds abstract fun folders(impl: NetworkFoldersImpl): NetworkFolders
    @Binds abstract fun finder(impl: ComputerFinderImpl): ComputerFinder
    @Binds abstract fun ringTargets(impl: StorageTargetsImpl): RingTargetSource

    companion object {
        private val MONO = MonoClock { android.os.SystemClock.elapsedRealtime() }

        @Provides @Singleton
        fun dirs(@ApplicationContext context: Context): StorageDirs = StorageDirs(context.noBackupFilesDir)

        @Provides @Singleton
        fun deviceIdentity(dirs: StorageDirs): DeviceIdentity = DeviceIdentity(dirs.noBackup)

        /**
         * The keeper (§5.3) on `box-smb`. Its I/O timeout follows the device's remote pool (lead ruling 5): 8 s with the
         * 160-block pool (`memoryClass` ≥ 192 MiB), 4 s with 96. The first connect waits for the first frame or 2 s (A-N6).
         */
        @Provides @Singleton
        fun keeper(
            @ApplicationContext context: Context,
            locations: StorageLocationsImpl,
            deviceId: DeviceIdentity,
            dirs: StorageDirs,
            gate: StartupGate,
            @ApplicationScope scope: CoroutineScope,
        ): SmbSessionKeeper {
            val memoryClass = runCatching { context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 0 }.getOrDefault(0)
            val io = if (memoryClass >= TimeshiftConstants.REMOTE_POOL_MEMORY_CLASS_MB) StorageConstants.SMB_IO_TIMEOUT_MS else StorageConstants.SMB_IO_TIMEOUT_SMALL_POOL_MS
            val keeper = SmbSessionKeeper(
                locations = locations, deviceId = deviceId, lastGood = LastGoodStore(File(dirs.noBackup, LastGoodStore.FILE_NAME)),
                exec = SmbExecutor("box-smb", MONO), clock = MONO, wallMs = System::currentTimeMillis,
                pid = android.os.Process.myPid(), ioTimeoutMs = io,
            )
            KeeperTriggers.install(context, keeper) { onFirstFrame -> scope.launch { gate.awaitOpen(); onFirstFrame() } }
            return keeper
        }

        @Provides @Singleton
        fun targets(
            @ApplicationContext context: Context,
            locations: StorageLocationsImpl,
            keeper: SmbSessionKeeper,
            volumes: VolumeCatalog,
            deviceId: DeviceIdentity,
        ): StorageTargetsImpl = StorageTargetsImpl(
            locations = locations, keeper = keeper, volumes = volumes, deviceId = deviceId,
            internalDir = File(context.cacheDir, INTERNAL_DIR), clock = MONO, wallMs = System::currentTimeMillis,
            pid = android.os.Process.myPid(),
        )

        /** Today's internal ring folder (`TimeshiftStorageImpl.DIR_NAME`, §3.5). */
        const val INTERNAL_DIR = "timeshift"
    }
}
