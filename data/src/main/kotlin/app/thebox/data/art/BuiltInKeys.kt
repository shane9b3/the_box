package app.thebox.data.art

/**
 * Keys the build ships with (owner ruling 2026-10-18). [tmdb] comes from `thebox.tmdbKey` in `local.properties` (or
 * `THEBOX_TMDB_KEY`) through `BuildConfig.TMDB_KEY`; null when the build has none. Never logged: `TmdbProgrammeArtwork`
 * registers it with `Redact`.
 */
data class BuiltInKeys(val tmdb: String?) {
    companion object {
        val NONE = BuiltInKeys(null)
    }
}
