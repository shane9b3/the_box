// Contract (Event channels commit 0, complete): docs/design/m6-event-channels.md §4.3, §8.5.
package app.thebox.core.sports.hub

import app.thebox.core.sports.SportsConstants

/** The guide horizon of the hub's Channels slot (§8.5, owner ruling c). */
object ChannelHorizon {
    /**
     * True when a game starting at [startUtcMs] is beyond the guide: at or after `max(guideEndMs, nowMs + 12 h)`.
     * [guideEndMs]: `GameChannelMatcher.guideEndMs()`, null without a guide (then only the 12 h floor counts). The floor
     * keeps tonight's games when the guide is stale or missing: team channels and event names need no guide.
     */
    fun beyondGuide(startUtcMs: Long, guideEndMs: Long?, nowMs: Long): Boolean =
        startUtcMs >= maxOf(guideEndMs ?: Long.MIN_VALUE, nowMs + SportsConstants.HUB_LOOKAHEAD_MIN * 60_000L)
}
