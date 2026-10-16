package app.thebox.core.settings.tree

import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.EffectTiming.LIVE
import app.thebox.core.settings.EffectTiming.NEXT_EPG_IMPORT
import app.thebox.core.settings.EffectTiming.NONE
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.Visibility
import app.thebox.core.settings.Visibility.ADVANCED
import app.thebox.core.settings.Visibility.SIMPLE

/**
 * WP-A: the TV guide section (docs/design/m5-setup-settings.md §3.3.2). `guide.daily_hour` is the number-pad half of
 * "Daily at a set time" (N4): shown only while `guide.interval` is Daily.
 */
object GuideRows : RowSource {
    val SOURCES = RowId("guide.sources")
    val STATUS = RowId("guide.status")
    val FIX_UNMATCHED = RowId("guide.fix_unmatched")
    val INTERVAL = RowId("guide.interval")
    val DAILY_HOUR = RowId("guide.daily_hour")
    val MATCHING = RowId("guide.matching")
    val SHIFT = RowId("guide.shift")
    val PAST_DAYS = RowId("guide.past_days")
    val FUTURE_DAYS = RowId("guide.future_days")
    val DESCRIPTIONS = RowId("guide.descriptions")

    override val rows: List<RowSpec> = listOf(
        a(SOURCES, SIMPLE, NEXT_EPG_IMPORT),
        a(STATUS, SIMPLE, NONE),
        // guide.fix_unmatched is built on this page but registered by WP-B's MatchingRows (one owner per row id).
        a(INTERVAL, ADVANCED, LIVE),
        a(DAILY_HOUR, ADVANCED, LIVE),
        a(MATCHING, ADVANCED, NEXT_EPG_IMPORT),
        a(SHIFT, ADVANCED, LIVE),
        a(PAST_DAYS, ADVANCED, NEXT_EPG_IMPORT),
        a(FUTURE_DAYS, ADVANCED, NEXT_EPG_IMPORT),
        a(DESCRIPTIONS, ADVANCED, NEXT_EPG_IMPORT),
    )

    private fun a(id: RowId, v: Visibility, e: EffectTiming) = RowSpec(id, SectionId.GUIDE, v, e, "A")
}
