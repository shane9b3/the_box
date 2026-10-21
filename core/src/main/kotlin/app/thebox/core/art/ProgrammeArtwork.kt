package app.thebox.core.art

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Programme poster lookup (owner rulings 2026-10-18 and 2026-10-19, docs/decisions/2026-10-18-tmdb-programme-posters.md).
 * The focused panel asks for a poster whenever the EPG carries no `<icon>` of its own, while Settings › TV guide ›
 * Programme posters is on and the build carries the app's own TMDB key (compiled in from `local.properties`). There is
 * no user key: a build without one never looks anything up. Implemented in `:data` (`TmdbProgrammeArtwork`), [NONE]
 * elsewhere.
 *
 * Privacy: a lookup sends the cleaned programme title (and the device language) to themoviedb.org and nothing else
 * (no channel, no playlist, no device id). The app's key is registered with `Redact`.
 */
interface ProgrammeArtwork {
    val status: StateFlow<ArtworkStatus>

    /**
     * A poster URL for [title], from the cache or the service; null when the lookup is off, nothing matched, the key
     * was rejected or the service could not be reached. Main-safe; suspends for IO. Never throws.
     */
    suspend fun poster(title: String, hint: ArtworkHint = ArtworkHint.ANY): String?

    companion object {
        val NONE: ProgrammeArtwork = object : ProgrammeArtwork {
            override val status: StateFlow<ArtworkStatus> = MutableStateFlow(ArtworkStatus.OFF)
            override suspend fun poster(title: String, hint: ArtworkHint): String? = null
        }
    }
}

/** What the EPG says about the programme, to pick the search endpoint (series or film) before the mixed one. */
enum class ArtworkHint { ANY, SERIES, MOVIE }

enum class ArtworkStatus {
    /** Posters are switched off, or the build has no TMDB key. */
    OFF,
    /** The app's key is in use; lookups run. */
    ON,
    /** The service answered 401 to the app's key: lookups stop; only an app update can fix it. */
    APP_KEY_REJECTED,
}
