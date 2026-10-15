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
    @Provides @Singleton fun keyMap(): KeyMap = DefaultKeyMap()
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
