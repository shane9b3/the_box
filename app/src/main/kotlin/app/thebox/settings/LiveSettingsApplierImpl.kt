package app.thebox.settings

import app.thebox.core.catchup.archive.LiveSettingsApplier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/**
 * Applies REPREPARE-class settings once when Settings closes (docs/design/m5-setup-settings.md §2.12 item 6, S9):
 * `archive.resume()` at the playhead during an archive session, else `controller.reapplySettings()`.
 * **Commit-0 stub (WP-A replaces the body):** does nothing (no setting is editable before WP-A).
 */
class LiveSettingsApplierImpl @Inject constructor() : LiveSettingsApplier {
    override fun apply() = Unit
}

/** WP-A's `:app` bindings (§2.13 SettingsBindingsModule). Commit 0 binds the stub; WP-A owns this file. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsBindingsModule {
    @Binds abstract fun liveSettingsApplier(impl: LiveSettingsApplierImpl): LiveSettingsApplier
}
