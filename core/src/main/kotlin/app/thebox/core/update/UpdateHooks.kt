// Contract (M5 commit 0): docs/design/m5-setup-settings.md §1.3a (Application hook, effects delegation), §3.12.
// Changes go through the lead.
package app.thebox.core.update

/** The update card's buttons (S17); `GuideEffects.updateAction` delegates here in one line. WP-E implements (`:app`). */
fun interface UpdateActions {
    fun act(action: UpdateAction)
}

/**
 * `TheBoxApplication`'s last hook (§1.3a, "updates.scheduleCheck()"): schedules the periodic manifest check
 * (UPDATE_FIRST_CHECK_DELAY_MS, then UPDATE_CHECK_INTERVAL_MS) when `UpdatePrefs.checkAutomatically` is on. Returns at
 * once. WP-E implements (`:app`).
 */
fun interface UpdateCheckScheduler {
    fun scheduleCheck()
}
