package app.thebox.ui.full

/**
 * WP-D stub (docs/design/m4-player-ux.md §1.5, §3.9): geometry (design px on the 1920 × 1080 canvas) and colour tokens
 * of the player overlays. WP-D fills it from the §3.9 table.
 */
object PlayerUi {
    /** The UI changes the displayed pill only after it has been stable this long (§3.2.3; hides M2 E-L8 flicker). */
    const val PILL_DEBOUNCE_MS = 400L
    /** Debounce of `ensureListNow` while the channel list moves (§3.1.4). */
    const val LIST_NOW_DEBOUNCE_MS = 120L
}
