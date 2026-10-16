package app.thebox.core.settings.tree

/**
 * WP-B's onboarding rows (docs/design/m5-setup-settings.md §3.4). The onboarding pages (Welcome, AddSource, the
 * forms, import progress, groups review, matching report) hold form fields and actions, not settings: none has a
 * stored value with an `EffectTiming`, and none counts towards the simple-row target (§3.3.13). Their row ids all
 * start with `setup.` (`app.thebox.core.setup.pages`), so coverage tests can tell them apart. Intentionally empty.
 */
object SetupRows : RowSource {
    /** Prefix of every onboarding page row id. */
    const val PAGE_ROW_PREFIX = "setup."

    override val rows: List<RowSpec> = emptyList()
}
