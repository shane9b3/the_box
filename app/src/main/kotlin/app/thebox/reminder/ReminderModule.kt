package app.thebox.reminder

import app.thebox.core.guide.ReminderAlerts
import app.thebox.playback.CatchupLauncherImpl
import app.thebox.ui.playback.CatchupLauncher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Reminder runtime and catch-up launch bindings (docs/design/m3-guide.md §1.5; owner WP-D). Commit 0 binds the stubs. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReminderModule {
    @Binds abstract fun alerts(impl: ReminderSchedulerImpl): ReminderAlerts
    @Binds abstract fun catchupLauncher(impl: CatchupLauncherImpl): CatchupLauncher
}
