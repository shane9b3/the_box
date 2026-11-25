package app.thebox.core.sports.hub

import app.thebox.core.sports.TvBroadcast

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §5.2 A; WP-3) The broadcaster alias table and its pure matcher: a
 * channel name's residue (country tag from `CountryTags` only, noise tokens dropped) must **equal** an alias of the code
 * (or an alias plus one region word); streaming-only codes and local affiliates have no aliases.
 *
 * Commit 0 stub: matches nothing. Only WP-3's `GameChannelMatcherImpl` calls it, so WP-3 may refine the signatures.
 */
object BroadcastAliases {
    /** The FTS4 `MATCH` text for [network]'s aliases (quoted phrases joined by `OR`), or null when it has none. */
    @Suppress("UNUSED_PARAMETER")
    fun ftsMatch(network: String): String? = null

    /** How a channel named [channelName] matches [broadcast], or null for no match. */
    @Suppress("UNUSED_PARAMETER")
    fun match(channelName: String, broadcast: TvBroadcast): MatchConfidence? = null
}
