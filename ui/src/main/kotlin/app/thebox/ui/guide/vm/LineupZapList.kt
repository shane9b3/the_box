package app.thebox.ui.guide.vm

import app.thebox.core.guide.GuideLibrary
import app.thebox.core.guide.GuideLineup
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.ZapList

/**
 * The zap list handed to `PlaybackController.tune` (docs/design/m3-guide.md §3.16.3, review S9). It holds the
 * **captured** row ids of one lineup and never touches the rows cache (which indexes the *current* lineup and is
 * main-only): `from` is resolved with [GuideLibrary.rowIdOf] and a scan (position 0 when absent), the step wraps, and
 * the target is loaded with [GuideLibrary.channels] (main-safe, IO inside). Ids that come back null (purged since the
 * capture) are skipped. The lineup predicate already excludes hidden and locked rows.
 */
class LineupZapList(lineup: GuideLineup, private val library: GuideLibrary) : ZapList {
    private val rowIds: LongArray = lineup.rowIds.copyOf()

    override val id: String = "lineup:" + lineup.version

    override suspend fun neighbour(from: ChannelIdentity, delta: Int): ChannelIdentity? {
        val n = rowIds.size
        if (n == 0) return null
        val fromRow = library.rowIdOf(from)
        var pos = if (fromRow == null) -1 else rowIds.indexOf(fromRow)
        val step = if (delta == 0) 0 else if (delta > 0) 1 else -1
        if (step == 0) return if (pos >= 0) from else load(0)
        if (pos < 0) {
            // Not in this lineup: start from position 0 (CH+ lands on row 0, CH− on the last row).
            pos = if (step > 0) -1 else 0
        }
        // |delta| steps over live rows, skipping purged ids; give up after one full lap.
        var remaining = kotlin.math.abs(delta)
        var i = pos
        var visited = 0
        var result: ChannelIdentity? = null
        while (remaining > 0 && visited < n) {
            i = Math.floorMod(i + step, n)
            visited++
            val id = load(i) ?: continue
            result = id
            remaining--
            visited = 0
            if (remaining == 0) return id
        }
        return result
    }

    private suspend fun load(i: Int): ChannelIdentity? = library.channels(rowIds, i, i + 1).firstOrNull()?.identity
}
