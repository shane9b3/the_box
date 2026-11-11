package app.thebox.core.settings.tree

import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.Visibility

/**
 * WP-E's rows (docs/design/m5-setup-settings.md §1.3a, §3.3.10). Five simple rows (§3.3.13) and one advanced. The
 * page's header card (wordmark, version, build type, ABI, installer; [HEADER]) is an Info row and, like the playlist
 * account card, not counted, so it is not registered. Sub-page rows (Diagnostics, NetworkTest, SyncStatus, Licences)
 * are read-only Info/Link/Item rows of those pages, not settings, and are not registered either. [INSTALL] is a SIMPLE
 * Action row shown directly below [UPDATE] only while a release is on offer or in progress; a registered row must be
 * on the page always (`DiagPagesTest`, `DiagnosticsTest` look every registered row up), so it is not registered and
 * the simple-row count stays 5.
 */
object AboutRows : RowSource {
    val UPDATE = RowId("about.update")
    /** Not registered: only present while an update is on offer (see the class comment). */
    val INSTALL = RowId("about.install")
    val DIAGNOSTICS = RowId("about.diagnostics")
    val EXPORT_LOGS = RowId("about.export_logs")
    val LICENCES = RowId("about.licences")
    val RESET = RowId("about.reset")
    val AUTO_CHECK = RowId("about.auto_check")
    /** Info, not counted. */
    val HEADER = RowId("about.header")

    override val rows: List<RowSpec> = listOf(
        RowSpec(UPDATE, SectionId.ABOUT, Visibility.SIMPLE, EffectTiming.LIVE, "E"),
        RowSpec(DIAGNOSTICS, SectionId.ABOUT, Visibility.SIMPLE, EffectTiming.NONE, "E"),
        RowSpec(EXPORT_LOGS, SectionId.ABOUT, Visibility.SIMPLE, EffectTiming.NONE, "E"),
        RowSpec(LICENCES, SectionId.ABOUT, Visibility.SIMPLE, EffectTiming.NONE, "E"),
        RowSpec(RESET, SectionId.ABOUT, Visibility.SIMPLE, EffectTiming.RESTART, "E"),
        RowSpec(AUTO_CHECK, SectionId.ABOUT, Visibility.ADVANCED, EffectTiming.LIVE, "E"),
    )
}
