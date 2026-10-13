package app.thebox.core.model

/**
 * Identity of a channel that survives playlist refreshes, host changes and re-ordering.
 * User data (favourites, prefs, reminders, recordings) is keyed by `(playlistId, StableKey)`.
 *
 * Priority (master plan §3 "Identity"), highest first:
 * 1. Xtream `stream_id`
 * 2. `tvg-id|name`
 * 3. host-agnostic URL path
 * 4. `name|group`
 *
 * The prefix records which rule produced the key so a later rule change can be migrated.
 * Normalisation (case, quality suffixes, whitespace) belongs to the M1 key builder, not here.
 */
@JvmInline
value class StableKey(val value: String) {
    init {
        require(value.isNotBlank()) { "StableKey must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        fun xtream(streamId: Long): StableKey = StableKey("xc:$streamId")
        fun tvgId(tvgId: String, name: String): StableKey = StableKey("tvg:$tvgId|$name")
        fun urlPath(hostAgnosticPath: String): StableKey = StableKey("url:$hostAgnosticPath")
        fun nameGroup(name: String, group: String?): StableKey = StableKey("ng:$name|${group.orEmpty()}")
    }
}
