package app.thebox.core.identity

import app.thebox.core.refresh.DerivedKey
import app.thebox.core.refresh.KeyContext
import app.thebox.core.refresh.StableKeyDeriver
import app.thebox.core.source.ParsedChannel

/** StableKey priority chain (docs/design/m1-data-layer.md §3.2). Commit-0 stub; WP-A owns this file. */
class StableKeyDeriverImpl : StableKeyDeriver {
    override fun derive(entry: ParsedChannel, ctx: KeyContext): DerivedKey = TODO("WP-A")
}
