package app.thebox.core.refresh

import app.thebox.core.common.CancelCheck

/** Two-pass diff-merge engine (docs/design/m1-data-layer.md §3.3). Commit-0 stub; WP-A owns this file. */
class PlaylistDifferImpl(
    @Suppress("unused") private val deriver: StableKeyDeriver,
) : PlaylistDiffer {
    override fun merge(
        entries: EntryProducer,
        existing: ExistingChannels,
        sink: MergeSink,
        ctx: MergeContext,
        cancel: CancelCheck,
    ): RefreshReport = TODO("WP-A")
}
