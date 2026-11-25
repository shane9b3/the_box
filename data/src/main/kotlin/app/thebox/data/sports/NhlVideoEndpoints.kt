package app.thebox.data.sports

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.2, §6.3; WP-2) The NHL's video hosts: the public web player's script (the
 * key is read from it at run time, never stored here or anywhere in the repo) and the playback service. **https only.**
 * A debug build's `files/debug/sports_base` origin redirects both to `{origin}/bc/...` ([SportsEndpoints.videoFromOverride]);
 * only such an override may be plain http ([debugOverride]).
 */
data class NhlVideoEndpoints(
    val playerScript: String,
    val playbackBase: String,
    /** True only for the debug build's fake-panel redirect: the one case where the two hosts may be http (A-N6). */
    val debugOverride: Boolean = false,
) {
    /** May the key and the lookups go to [url]? https always; http only under the debug override. */
    fun allowed(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) || (debugOverride && url.startsWith("http://", ignoreCase = true))

    companion object {
        const val ACCOUNT_ID = "6415718365001"
        const val PLAYER_SCRIPT = "https://players.brightcove.net/$ACCOUNT_ID/default_default/index.min.js"
        /** `{PLAYBACK_BASE}{videoId}`. */
        const val PLAYBACK_BASE = "https://edge.api.brightcove.com/playback/v1/accounts/$ACCOUNT_ID/videos/"
        val DEFAULT = NhlVideoEndpoints(PLAYER_SCRIPT, PLAYBACK_BASE)

        /** The fake panel's paths under an override origin (tools/fakepanel/sports_video.py). */
        const val FAKE_SCRIPT_PATH = "/bc/player/index.min.js"
        const val FAKE_PLAYBACK_PATH = "/bc/playback/v1/accounts/$ACCOUNT_ID/videos/"
    }
}
