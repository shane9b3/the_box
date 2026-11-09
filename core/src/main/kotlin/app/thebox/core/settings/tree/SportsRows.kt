package app.thebox.core.settings.tree

import app.thebox.core.settings.EffectTiming.LIVE
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.Visibility.ADVANCED

/**
 * (Score bug WP-4b, docs/design/m6-score-bug.md §7, lead ruling on U-B2) The Sports section. **Every row is ADVANCED**, so
 * the simple count stays at 35 and the section is absent in simple mode, as NETWORK is. The day-to-day corner and delay
 * live in the player's quick bar and the Sports tab chips.
 */
object SportsRows : RowSource {
    val SHOW = RowId("sports.show")
    val CORNER = RowId("sports.corner")
    val DELAY = RowId("sports.delay")
    val SIZE = RowId("sports.size")
    /** (Update interval §5.1) The followed game's poll interval for the league on the Sports tab (`SportsPrefs.league`). */
    val INTERVAL = RowId("sports.interval")

    override val rows: List<RowSpec> = listOf(SHOW, CORNER, DELAY, INTERVAL, SIZE).map {
        RowSpec(it, SectionId.SPORTS, ADVANCED, LIVE, "S") // "S" (score bug): WpaPages builds the page and applies the intents, outside the WP-A simple-count sections
    }
}
