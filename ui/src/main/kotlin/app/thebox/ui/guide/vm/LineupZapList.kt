package app.thebox.ui.guide.vm

import app.thebox.core.guide.GuideLibrary
import app.thebox.core.guide.GuideLineup
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.ZapList

/** WP-C stub (docs/design/m3-guide.md §1.4, §3.16.3, review S9): a captured lineup; `GuideLibrary` on IO, never the rows cache. */
class LineupZapList(private val lineup: GuideLineup, private val library: GuideLibrary) : ZapList {
    override val id: String get() = TODO("WP-C")
    override suspend fun neighbour(from: ChannelIdentity, delta: Int): ChannelIdentity? = TODO("WP-C")
}
