package app.thebox.core.sports

/**
 * Where the bug sits given the other overlays (docs/design/m6-score-bug.md §4.3, §4.4; WP-1). Pure. Returns the pill's
 * box in design px, or null when it is hidden. In place mode ([PlacementInput.placing]) only `modal` and `screenOff`
 * apply (U-B1).
 *
 * Commit 0: contract only; WP-1 fills the body.
 */
object ScoreBugPlacement {
    @Suppress("UNUSED_PARAMETER")
    fun place(input: PlacementInput, geo: OverlayGeometry): Box? = TODO("WP-1")
}
