package app.thebox.core.catchup

import app.thebox.core.model.TimeBase

/** Per-mode archive URL builders (docs/design/m1-data-layer.md §3.8.4–3.8.7). Commit-0 stub; WP-C owns this file. */
class CatchupUrlBuilderImpl : CatchupUrlBuilder {
    override fun build(target: CatchupTarget, timeBase: TimeBase, window: ArchiveWindow, variant: String?): CatchupBuild = TODO("WP-C")
    override fun variants(target: CatchupTarget): List<String> = TODO("WP-C")
}
