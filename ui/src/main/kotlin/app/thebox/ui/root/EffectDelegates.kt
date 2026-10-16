package app.thebox.ui.root

import app.thebox.core.full.ErrorRouteActions
import app.thebox.core.parental.ParentalEffects
import app.thebox.core.parental.PinCheck
import app.thebox.core.settings.SettingsNavigator
import app.thebox.core.update.UpdateActions
import javax.inject.Inject

/**
 * (M5 commit 0, docs/design/m5-setup-settings.md §1.3a) The interfaces the new effect methods of `GuideEffectsImpl` and
 * `PlayerEffectsImpl` delegate to in one line: `SettingsNavigator` (WP-A), `ParentalEffects` and `ErrorRouteActions`
 * (WP-C), `UpdateActions` (WP-E). Each WP binds its implementation in its own Hilt module; the effects files are never
 * edited again.
 */
class EffectDelegates @Inject constructor(
    val settings: SettingsNavigator,
    val parental: ParentalEffects,
    val errors: ErrorRouteActions,
    val updates: UpdateActions,
) {
    companion object {
        /** Does nothing (view-model tests that predate M5; the PIN check answers Unavailable and zeroes the digits). */
        val NONE = EffectDelegates(
            settings = object : SettingsNavigator {
                override fun openSettings(route: app.thebox.core.settings.SettingsRoute, fromFull: Boolean, detached: Boolean) = Unit
            },
            parental = object : ParentalEffects {
                override fun verifyPin(token: Long, digits: CharArray, answer: (PinCheck) -> Unit) {
                    digits.fill('\u0000')
                    answer(PinCheck.Unavailable)
                }
                override fun setChannelLocked(channel: app.thebox.core.guide.GuideChannel, locked: Boolean) = Unit
                override fun setGroupLocked(entry: app.thebox.core.guide.GuideSourceEntry, locked: Boolean) = Unit
            },
            errors = ErrorRouteActions { _, _ -> },
            updates = UpdateActions { },
        )
    }
}
