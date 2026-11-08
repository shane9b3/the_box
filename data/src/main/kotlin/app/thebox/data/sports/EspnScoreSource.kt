package app.thebox.data.sports

import android.content.Context
import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.ScoreSource
import app.thebox.core.sports.TeamLine
import app.thebox.data.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/**
 * ESPN's base URLs (docs/design/m6-nfl.md §1.5). A **debug** build's `files/debug/sports_base` origin (the same file as
 * [SportsEndpoints]) serves both leagues: `{origin}/apis/site/v2/sports/football/nfl/` and the logo base `{origin}/`
 * (the fake panel serves `/combiner/i`).
 */
data class EspnEndpoints(val apiBase: String, val logoBase: String) {
    companion object {
        const val API_BASE = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/"
        const val LOGO_BASE = "https://a.espncdn.com/"
        val DEFAULT = EspnEndpoints(API_BASE, LOGO_BASE)

        /** An override origin → the ESPN paths under it. Null for a blank or unusable text (not http/https, or not a URL). */
        fun fromOverride(text: String?): EspnEndpoints? {
            val t = text?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
            val url = t.toHttpUrlOrNull() ?: return null
            if (url.scheme != "http" && url.scheme != "https") return null
            val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
            return EspnEndpoints("$origin/apis/site/v2/sports/football/nfl/", "$origin/")
        }

        /** The endpoints for this build: [DEFAULT], or the debug override file in a debug build. Call off the main thread. */
        fun resolve(context: Context): EspnEndpoints {
            if (!BuildConfig.DEBUG) return DEFAULT
            val text = try {
                File(context.filesDir, SportsEndpoints.DEBUG_OVERRIDE_FILE).takeIf { it.isFile }?.readText()
            } catch (e: IOException) {
                null
            }
            return fromOverride(text)?.also { BoxLog.w(TAG, "debug build: ESPN endpoints overridden") } ?: DEFAULT
        }

        private const val TAG = "EspnEndpoints"
    }
}

/**
 * The NFL [ScoreSource] over ESPN's site scoreboard (docs/design/m6-nfl.md §1.5; WP-2).
 *
 * Commit 0 stub: every call answers `Failed(HTTP_OTHER)` without a request, so the day list reads "did not answer" and
 * nothing can be followed. WP-2 writes the transport, the streaming decode into `EspnBoardDto` / `EspnGameDto`, the cap,
 * the remembered dates, the week retry and the logos.
 */
class EspnScoreSource internal constructor(
    @Suppress("unused") private val client: OkHttpClient,
    private val mono: MonoClock,
    endpoints: () -> EspnEndpoints,
) : ScoreSource {
    /** Resolved on first use (the actor's first request, off the main thread). */
    @Suppress("unused") private val endpoints: EspnEndpoints by lazy(endpoints)

    override val league: League = League.NFL

    override suspend fun today(): Fetch<DayBoard> = notYet()

    override suspend fun day(dayKey: String): Fetch<DayBoard> = notYet()

    override suspend fun game(id: GameId): Fetch<GameSnapshot> = notYet()

    /** Commit 0: the feed's own logo URL (or none); WP-2 derives and validates the combiner URL. */
    override fun logoUrl(team: TeamLine): String = team.logo.orEmpty()

    private fun notYet(): Fetch<Nothing> = Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs())
}
