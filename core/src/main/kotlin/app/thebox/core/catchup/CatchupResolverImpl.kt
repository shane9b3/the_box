package app.thebox.core.catchup

import app.thebox.core.model.Catchup
import app.thebox.core.source.RawCatchup

/** Mode chain and archive-days chain (docs/design/m1-data-layer.md §3.8.1–3.8.2). Commit-0 stub; WP-C owns this file. */
class CatchupResolverImpl : CatchupResolver {
    override fun resolve(raw: RawCatchup, urlTemplate: String, xtreamStreamId: Long?, defaults: PlaylistCatchupDefaults): Catchup =
        TODO("WP-C")
}
