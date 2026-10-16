package app.thebox.update

import app.thebox.core.update.UpdateAction
import app.thebox.core.update.UpdateActions
import app.thebox.core.update.UpdateState
import javax.inject.Inject

/**
 * The S17 card's buttons (docs/design/m5-setup-settings.md §3.12.3), reached through `GuideEffects.updateAction`:
 * Update downloads, verifies and opens the installer in one press; Later hides the card until the next start; Skip this
 * version stores `skippedVersionCode` (automatic checks then ignore that release, a manual check still offers it).
 */
class UpdateActionsImpl @Inject constructor(private val updater: UpdaterImpl) : UpdateActions {
    override fun act(action: UpdateAction) {
        when (action) {
            UpdateAction.UPDATE -> updater.updateNow()
            UpdateAction.LATER -> updater.later()
            UpdateAction.SKIP -> {
                val vc = (updater.state.value as? UpdateState.Available)?.manifest?.versionCode ?: return updater.later()
                updater.skip(vc)
            }
        }
    }
}
