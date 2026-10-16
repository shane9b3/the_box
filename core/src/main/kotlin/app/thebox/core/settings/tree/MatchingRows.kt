package app.thebox.core.settings.tree

import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.Visibility

/**
 * WP-B's guide-matching row (docs/design/m5-setup-settings.md §3.3.2, §3.6.3): "Fix unmatched channels" on the TV
 * guide page (WP-A builds that page and links the row to `SettingsRoute.FixGuide()`; WP-B owns the row and its
 * pages). LIVE: a choice applies per channel at once (setOverride + patch).
 */
object MatchingRows : RowSource {
    override val rows: List<RowSpec> = listOf(
        RowSpec(RowId("guide.fix_unmatched"), SectionId.GUIDE, Visibility.SIMPLE, EffectTiming.LIVE, owner = "B"),
    )
}
