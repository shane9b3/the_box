package app.thebox.core.art

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A scripted [ProgrammeArtwork]: [posters] by cleaned title, every call recorded. */
class FakeProgrammeArtwork(
    val posters: MutableMap<String, String> = HashMap(),
    /** A lookup suspends this long first (cancellation tests). */
    var delayMs: Long = 0,
) : ProgrammeArtwork {
    val statusFlow = MutableStateFlow(ArtworkStatus.OFF)
    override val status: StateFlow<ArtworkStatus> get() = statusFlow
    val lookups = ArrayList<Pair<String, ArtworkHint>>()

    override suspend fun poster(title: String, hint: ArtworkHint): String? {
        lookups += title to hint
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
        return posters[title]
    }
}
