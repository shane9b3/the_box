// Contract (M5 commit 0): docs/design/m5-setup-settings.md §1.3a (effects delegations). Changes go through the lead.
package app.thebox.core.settings

/**
 * What `GuideEffects.openSettings(route)` and `PlayerEffects.openSettings(route)` do outside the machines (§1.3a: the
 * commit-0 effects delegate here in one line). Implemented in `:ui` by WP-A (`ui/.../settings/vm/`), which reaches the
 * settings machine (`SettingsMachine.open(route, fromFull, detached)`) and, from FULL, the guide machine through the
 * host. Main thread, non-blocking.
 */
interface SettingsNavigator {
    /**
     * Settings opens (or opened) on [route]. [fromFull]: BACK at the bottom of the ladder returns to FULL. [detached]
     * (S2): a standalone layer with nothing of Settings behind it (error-card routes, the sync chip's SyncStatus).
     */
    fun openSettings(route: SettingsRoute, fromFull: Boolean, detached: Boolean)
}
