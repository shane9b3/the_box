package app.thebox.core.settings.tree

/**
 * WP-C's rows (docs/design/m5-setup-settings.md §1.3a, §3.3). Commit-0 stub: empty. WP-C owns this file and
 * lists every row it builds here (id, section, visibility, EffectTiming); [SettingsTree] already registers it.
 */
object ButtonMappingRows : RowSource {
    override val rows: List<RowSpec> = emptyList()
}
