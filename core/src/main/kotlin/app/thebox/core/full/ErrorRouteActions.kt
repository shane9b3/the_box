// Contract (M5 commit 0): docs/design/m5-setup-settings.md §1.3a (effects delegations), §3.14. Changes go through the lead.
package app.thebox.core.full

/**
 * Error-card buttons that act outside the player machine (§3.14): USE_SECURE_DNS (`network.dns = CLOUDFLARE`, then
 * retry, toast), REFRESH_PLAYLIST (`refreshNow(MANUAL)`, toast), OPEN_EXTERNAL (hand-off, §3.3.4) and the "Back to
 * Auto" of B2. `PlayerEffectsImpl.errorAction` delegates here in one line. Implemented by WP-C. Main thread,
 * non-blocking.
 */
fun interface ErrorRouteActions {
    fun run(button: ErrorButton, card: ErrorCardState)
}
