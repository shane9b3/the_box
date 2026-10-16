package app.thebox.core.settings.tree

/**
 * WP-A's rows (docs/design/m5-setup-settings.md §1.3a, §3.3). Commit-0 stub: empty. WP-A owns this file and
 * lists every row it builds here (id, section, visibility, EffectTiming); [SettingsTree] already registers it.
 */
object NetworkRows : RowSource {
    override val rows: List<RowSpec> = emptyList()
}
