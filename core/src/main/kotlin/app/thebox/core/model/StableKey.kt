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

    /** Occurrence suffix for duplicate keys inside one playlist (2nd occurrence → `~2`). */
    fun withOccurrence(n: Int): StableKey = if (n <= 1) this else StableKey("$value~$n")

    companion object {
        fun xtream(streamId: Long): StableKey = StableKey("xc:$streamId")
        fun tvgId(tvgId: String, name: String): StableKey = StableKey("tvg:$tvgId|$name")
        fun urlPath(hostAgnosticPath: String): StableKey = StableKey("url:$hostAgnosticPath")
        fun nameGroup(name: String, group: String?): StableKey = StableKey("ng:$name|${group.orEmpty()}")

        /** Xtream VOD (`/movie/`) and series-episode (`/series/`) ids live in other id spaces. */
        fun xtreamMovie(streamId: Long): StableKey = StableKey("xcm:$streamId")
        fun xtreamEpisode(episodeId: Long): StableKey = StableKey("xce:$episodeId")
    }
}
