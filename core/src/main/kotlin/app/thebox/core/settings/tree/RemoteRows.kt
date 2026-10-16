package app.thebox.core.settings.tree

import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.EffectTiming.LIVE
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.Visibility
import app.thebox.core.settings.Visibility.ADVANCED
import app.thebox.core.settings.Visibility.SIMPLE

/**
 * WP-A: the Remote control section (docs/design/m5-setup-settings.md §3.3.7). `remote.mapping` is the section's Link
 * into WP-C's ButtonMapping pages; it is registered here once (WP-C's `ButtonMappingRows` lists the mapping pages' rows).
 */
object RemoteRows : RowSource {
    val MAPPING = RowId("remote.mapping")
    val UPDOWN = RowId("remote.updown")
    val INVERT = RowId("remote.invert")
    val BACK = RowId("remote.back")
    val EXIT = RowId("remote.exit")

    override val rows: List<RowSpec> = listOf(
        a(MAPPING, SIMPLE, LIVE),
        a(UPDOWN, SIMPLE, LIVE),
        a(INVERT, ADVANCED, LIVE),
        a(BACK, ADVANCED, LIVE),
        a(EXIT, ADVANCED, LIVE),
    )

    private fun a(id: RowId, v: Visibility, e: EffectTiming) = RowSpec(id, SectionId.REMOTE, v, e, "A")
}
