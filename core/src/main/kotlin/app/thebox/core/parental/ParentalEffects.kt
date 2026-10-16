// Contract (M5 commit 0): docs/design/m5-setup-settings.md §1.3a (effects delegations), §3.9. Changes go through the lead.
package app.thebox.core.parental

import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideSourceEntry

/**
 * Parental side effects the guide and player effects delegate to in one line (§1.3a). Implemented in `:ui` by WP-C
 * over `ParentalPinStore`/`ParentalSession` (bound in WP-C's Hilt module). Main-safe, non-blocking: PBKDF2 and the
 * store writes run on IO.
 */
interface ParentalEffects {
    /**
     * Checks [digits] (exactly 4) against the stored verifier on IO and calls [answer] on main with the result
     * (`GuideEvent.PinChecked`). Zeroes [digits] after the check, also on failure.
     */
    fun verifyPin(token: Long, digits: CharArray, answer: (PinCheck) -> Unit)
    /** Channel menu Lock/Unlock (guide `MenuItem.LOCK_CHANNEL`/`UNLOCK_CHANNEL`, player `ChannelMenuItem.LOCK_CHANNEL`). */
    fun setChannelLocked(channel: GuideChannel, locked: Boolean)
    /** Group menu Lock/Unlock (`GroupMenuItem.LOCK`/`UNLOCK`). */
    fun setGroupLocked(entry: GuideSourceEntry, locked: Boolean)
}
