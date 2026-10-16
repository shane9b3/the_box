package app.thebox.ui.parental

import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.parental.ParentalEffects
import app.thebox.core.parental.PinCheck
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/**
 * Parental effects over `ParentalPinStore`/`ParentalSession` (docs/design/m5-setup-settings.md §1.3a, §3.9).
 * **Commit-0 stub (WP-C replaces the body):** no PIN can be checked (Unavailable, fail closed; digits zeroed); lock
 * actions do nothing.
 */
class ParentalEffectsImpl @Inject constructor() : ParentalEffects {
    override fun verifyPin(token: Long, digits: CharArray, answer: (PinCheck) -> Unit) {
        digits.fill('\u0000')
        answer(PinCheck.Unavailable)
    }
    override fun setChannelLocked(channel: GuideChannel, locked: Boolean) = Unit
    override fun setGroupLocked(entry: GuideSourceEntry, locked: Boolean) = Unit
}

/** WP-C's `:ui` bindings. Commit 0 binds the stub; WP-C owns this file. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ParentalUiModule {
    @Binds abstract fun parentalEffects(impl: ParentalEffectsImpl): ParentalEffects
}
