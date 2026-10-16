package app.thebox.ui.input

import android.content.Context
import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.full.PlayerStateMachineFactory
import app.thebox.core.full.state.PlayerStateMachineFactoryImpl
import app.thebox.core.guide.DeviceClassSource
import app.thebox.core.guide.GuideDebugProbe
import app.thebox.core.guide.GuideStateMachineFactory
import app.thebox.core.guide.ReminderPlanner
import app.thebox.core.guide.reminder.ReminderPlannerImpl
import app.thebox.core.guide.state.GuideStateMachineImpl
import app.thebox.core.input.DefaultKeyMap
import app.thebox.core.input.EffectiveKeyMap
import app.thebox.core.input.KeyBindingStore
import app.thebox.core.input.RemappedKeyMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import app.thebox.core.input.KeyCapabilities
import app.thebox.core.input.KeyGestureDetector
import app.thebox.core.input.KeyGestureDetectorImpl
import app.thebox.core.input.KeyMap
import app.thebox.ui.full.PlayerDebugProbe
import app.thebox.ui.full.PlayerDebugProbeImpl
import app.thebox.ui.guide.GuideDebugProbeImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Input and state-machine bindings (docs/design/m3-guide.md §1.5; owner WP-D). Commit 0 binds the stubs. */
@Module
@InstallIn(SingletonComponent::class)
object InputModule {
    /**
     * (M5, docs/design/m5-setup-settings.md §3.8.4, decision §0.7) The router's key map: the defaults plus the user's
     * overrides, as an immutable snapshot rebuilt on Dispatchers.Default when they change (app-lifetime scope).
     */
    @Provides @Singleton fun effectiveKeyMap(store: KeyBindingStore): EffectiveKeyMap =
        RemappedKeyMap(DefaultKeyMap(), store.overrides, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    @Provides @Singleton fun keyMap(map: EffectiveKeyMap): KeyMap = map
    @Provides fun gestureDetector(): KeyGestureDetector = KeyGestureDetectorImpl()
    @Provides @Singleton fun keyCapabilities(@ApplicationContext context: Context): KeyCapabilities = KeyCapabilitiesImpl(context)
    @Provides @Singleton fun keyRouter(keyMap: KeyMap, detector: KeyGestureDetector, capabilities: KeyCapabilities): KeyRouter =
        KeyRouterImpl(keyMap, detector, capabilities)
    @Provides @Singleton fun deviceClass(@ApplicationContext context: Context): DeviceClassSource = DeviceClassSourceImpl(context)
    @Provides @Singleton fun debugProbe(): GuideDebugProbe = GuideDebugProbeImpl()
    @Provides fun stateMachineFactory(): GuideStateMachineFactory = GuideStateMachineImpl.FACTORY
    @Provides fun reminderPlanner(): ReminderPlanner = ReminderPlannerImpl()

    // M4 commit 0 (docs/design/m4-player-ux.md §1.2, §2.7): the player machine (WP-A) and its debug probe (WP-E).
    @Provides fun playerStateMachineFactory(): PlayerStateMachineFactory = PlayerStateMachineFactoryImpl()
    @Provides @Singleton fun playerDebugProbe(archive: ArchivePlayback): PlayerDebugProbe = PlayerDebugProbeImpl(archive)
}
