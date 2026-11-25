package app.thebox.data.sports

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.2, §6.3; WP-2) The NHL's video hosts: the public web player's script (the
 * key is read from it at run time, never stored here or anywhere in the repo) and the playback service. **https only.**
 * A debug build's `files/debug/sports_base` origin redirects both to `{origin}/bc/...` (WP-2, through `SportsEndpoints`).
 */
data class NhlVideoEndpoints(val playerScript: String, val playbackBase: String) {
    companion object {
        const val ACCOUNT_ID = "6415718365001"
        const val PLAYER_SCRIPT = "https://players.brightcove.net/$ACCOUNT_ID/default_default/index.min.js"
        /** `{PLAYBACK_BASE}{videoId}`. */
        const val PLAYBACK_BASE = "https://edge.api.brightcove.com/playback/v1/accounts/$ACCOUNT_ID/videos/"
        val DEFAULT = NhlVideoEndpoints(PLAYER_SCRIPT, PLAYBACK_BASE)
    }
}
