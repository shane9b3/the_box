package app.thebox.core.playback.resolve

// Commit-0 stub (docs/design/m2-playback-core.md §1.4/§1.5). Owner: WP-A. Replace the TODO bodies; keep the public names.
/** Case-insensitive header precedence and UA defaulting (§3.2.5). */
object HeaderPolicy {
    /**
     * Merges playlist and channel headers (channel wins, case-insensitive), applies [userAgentOverride] unless the
     * channel sets its own UA, and defaults the UA to [defaultUserAgent]. Keys come out canonical-cased.
     */
    fun merge(
        playlist: Map<String, String>,
        channel: Map<String, String>,
        playlistUserAgent: String?,
        defaultUserAgent: String,
        userAgentOverride: String? = null,
    ): Map<String, String> = TODO("WP-A")
}
