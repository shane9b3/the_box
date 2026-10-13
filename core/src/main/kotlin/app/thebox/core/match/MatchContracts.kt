package app.thebox.core.match

import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.refresh.ChannelIdentity

data class MatchSubject(
    val identity: ChannelIdentity,
    val name: String,
    val tvgId: String?,
    val tvgName: String?,
    val countryHint: String?,
)

/** Persisted by name in epg_match.method: append-only. */
enum class MatchMethod { OVERRIDE, ID_EXACT, ID_CASE_INSENSITIVE, ID_BASE, TVG_NAME, DISPLAY_NAME, FUZZY }

enum class MatchStatus { AUTO, SUGGESTED }

data class MatchCandidate(val ref: EpgChannelRef, val displayName: String, val score: Float, val method: MatchMethod)

data class MatchResult(
    val subject: ChannelIdentity,
    val sourceId: Long,
    /** The applied match (status AUTO), or the best suggestion (status SUGGESTED), or null. */
    val best: MatchCandidate?,
    val status: MatchStatus?,
    /** Up to MatchSettings.maxSuggestions alternatives, best first (excluding [best]). */
    val suggestions: List<MatchCandidate>,
)

/** Product §4.3 "Automatic matching". */
enum class MatchLevel { EXACT_ONLY, EXACT_AND_NAMES, SUGGEST_FUZZY, AUTO_FUZZY }

data class MatchSettings(
    val level: MatchLevel = MatchLevel.SUGGEST_FUZZY,
    val caseInsensitiveIds: Boolean = true,
    val autoThreshold: Float = 0.92f,
    val suggestThreshold: Float = 0.75f,
    val ambiguityMargin: Float = 0.02f,
    val maxSuggestions: Int = 3,
)
