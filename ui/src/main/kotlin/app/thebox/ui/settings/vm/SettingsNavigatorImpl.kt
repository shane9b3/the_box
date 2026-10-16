package app.thebox.ui.settings.vm

import app.thebox.core.settings.SettingsMachine
import app.thebox.core.settings.SettingsNavigator
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.state.SettingsShellLink
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SettingsNavigator] (docs/design/m5-setup-settings.md §1.3a, §3.2.5, §3.14): what `GuideEffects.openSettings(route)`
 * and `PlayerEffects.openSettings(route)` do outside the machines.
 * - From the guide (`fromFull = false`) the guide machine has already entered SETTINGS and opens the settings machine on
 *   the route itself, so there is nothing to do here; (M5 integration) a page source inside a shown Settings (the phone
 *   page's restore → RestorePreview, WP-D) gets the route pushed.
 * - From FULL (error-card routes, `fromFull = true`, detached, S2) the guide must enter SETTINGS first: the settings
 *   machine's [SettingsShellLink.openFromFull] does it through the guide dispatch `AppRoot` linked, then opens the route.
 * The activity's [SettingsViewModel] attaches its machine (weakly: the navigator is a singleton, the machine is not).
 */
@Singleton
class SettingsNavigatorImpl @Inject constructor() : SettingsNavigator {
    private var machine: WeakReference<SettingsMachine>? = null

    fun attach(machine: SettingsMachine) {
        this.machine = WeakReference(machine)
    }

    override fun openSettings(route: SettingsRoute, fromFull: Boolean, detached: Boolean) {
        val m = machine?.get() ?: return
        val link = m as? SettingsShellLink
        if (!fromFull) {
            // (M5 integration) A page source moving on inside Settings (WP-D: the phone page → RestorePreview): push it
            // while Settings is shown. The guide's own openSettings already opened the machine on the route (no-op).
            val u = m.ui.value
            if (u.page != null && u.route != route) link?.push(route)
            return
        }
        if (link != null) link.openFromFull(route, detached) else m.open(route, fromFull = true, detached = detached)
    }
}
