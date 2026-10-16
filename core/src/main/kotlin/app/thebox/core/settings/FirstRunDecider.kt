// Contract (M5 commit 0, lead amendment): docs/design/m5-setup-settings.md §3.4.1, §1.9a. Changes go through the lead.
package app.thebox.core.settings

/**
 * The first-run decision `GuideViewModel.startup()` makes once after `SettingsStore.ready` and `settingsAttached`
 * (§3.4.1): no row in `playlist` and `SetupState.completed == false` → [SettingsRoute.FirstRun], else null (the
 * normal startup tune). Seam so the owning WP never edits `GuideViewModel` (WP-C's file, §1.3a). **WP-A** implements it
 * in `data/.../settings/FirstRunDeciderImpl.kt` over the `setup` domain ([SetupDomain]) and `PlaylistDao` and binds it
 * in `SettingsDataModule`; WP-B sets `SetupState.completed` when the first import reaches stage 3 or a restore finishes.
 */
fun interface FirstRunDecider {
    suspend fun route(): SettingsRoute?

    companion object {
        /** Commit-0 stub and the test default: always the guide. */
        val NONE: FirstRunDecider = FirstRunDecider { null }
    }
}
