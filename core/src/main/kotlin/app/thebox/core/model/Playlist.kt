package app.thebox.core.model

/** A configured source. Credentials are not part of the model; they live in the Keystore-backed store. */
data class Playlist(
    val id: Long,
    val name: String,
    val source: PlaylistSource,
    val epgUrls: List<String> = emptyList(),
    val userAgent: String? = null,
    val timeBase: TimeBase = TimeBase.DEFAULT,
    /** Xtream `max_connections`, or a user override; null = unknown (treated as 1). */
    val maxConnections: Int? = null,
)

sealed interface PlaylistSource {
    data class M3uUrl(val url: String) : PlaylistSource

    data class M3uFile(val uri: String) : PlaylistSource

    /** [server] is the panel base URL; username/password are referenced by [credentialsRef]. */
    data class Xtream(val server: String, val credentialsRef: String) : PlaylistSource
}
