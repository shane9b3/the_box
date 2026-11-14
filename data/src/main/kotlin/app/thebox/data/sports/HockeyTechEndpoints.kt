package app.thebox.data.sports

import android.content.Context
import app.thebox.core.common.BoxLog
import app.thebox.core.common.Redact
import app.thebox.core.sports.League
import app.thebox.data.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException

/**
 * (CHL §2.2, owner decision 1, lead rulings P3 and L6) The public site keys HockeyTech's `lscluster` feed takes, as the
 * leagues' own web sites embed them. **This is the only file that holds them**: not in a test, fixture, doc, fake panel
 * or tool (the fake panel and the debug override use [HockeyTechEndpoints.fakeKey]).
 *
 * They are registered with [Redact] when this object is initialised, before any request or exception can carry them;
 * `HockeyTechScoreSource`'s companion touches the object on load. The CHL-wide key is out of v1.
 */
internal object HockeyTechKeys {
    val OHL: String = "2976319eb44abe94"
    val WHL: String = "41b145a848f4bd67"
    val QMJHL: String = "f322673b6bcae299"

    init {
        listOf(OHL, WHL, QMJHL).forEach(Redact::registerSecret)
    }

    /** The key of a CHL [league]. */
    fun of(league: League): String = when (league) {
        League.OHL -> OHL
        League.WHL -> WHL
        League.QMJHL -> QMJHL
        League.NHL, League.NFL -> throw IllegalArgumentException("no HockeyTech key for $league")
    }

    /** Forces the initialiser (the registration) from a companion. */
    fun touch() = Unit
}

/**
 * (CHL §2.1, §2.8) HockeyTech's base URLs and the key per league.
 * - [feedUrl]: the keyed `lscluster` feed (`gamesbydate`, `teamsbyseason`, `seasons`);
 * - [firebaseBase]: HockeyTech's Firebase database, ending in `/svf/` (the client code follows);
 * - [logoBase]: the logo host, ending in `/` (`{client}/logos/{file}` follows; §3.5).
 * - [key]: the `lscluster` key of a league.
 *
 * A **debug** build's `files/debug/sports_base` origin (the same file as [SportsEndpoints]) redirects all three to the
 * fake panel (`tools/fakepanel/sports_chl.py`): `{origin}/hockeytech/feed/index.php`, `{origin}/firebase/svf/`,
 * `{origin}/leaguestat/`, and then sends the fake panel's own keys ([fakeKey]), never the real ones.
 */
class HockeyTechEndpoints internal constructor(
    val feedUrl: String,
    val firebaseBase: String,
    val logoBase: String,
    internal val key: (League) -> String,
) {
    /** The validated logo folder of a league (§3.5): `{logoBase}{client}/logos/`. */
    fun logoPrefix(league: League): String = "$logoBase${client(league)}/logos/"

    /** `F` of §2.1: the league's Firebase subtree, ending in `/`. */
    fun firebase(league: League): String = "$firebaseBase${client(league)}/"

    /** Never prints the key (L6). */
    override fun toString(): String = "HockeyTechEndpoints(${Redact.host(feedUrl)}, ${Redact.host(firebaseBase)}, ${Redact.host(logoBase)})"

    companion object {
        const val FEED_URL = "https://lscluster.hockeytech.com/feed/index.php"
        const val FIREBASE_BASE = "https://leaguestat-b9523.firebaseio.com/svf/"
        const val LOGO_BASE = "https://assets.leaguestat.com/"

        val DEFAULT = HockeyTechEndpoints(FEED_URL, FIREBASE_BASE, LOGO_BASE, HockeyTechKeys::of)

        /** HockeyTech's client code of a CHL league (`lhjmq` for the QMJHL; it never leaves `:data`, §1.1). */
        fun client(league: League): String = when (league) {
            League.OHL -> "ohl"
            League.WHL -> "whl"
            League.QMJHL -> "lhjmq"
            League.NHL, League.NFL -> throw IllegalArgumentException("not a CHL league: $league")
        }

        /** The fake panel's key for a league (`tools/fakepanel/sports_chl.py` accepts exactly these). */
        fun fakeKey(league: League): String = "fakepanel-${client(league)}"

        /**
         * An override origin → the fake panel's paths under it, with [fakeKey]. Null for a blank or unusable text (not
         * http/https, or not a URL). Mirrors [SportsEndpoints.fromOverride].
         */
        fun fromOverride(text: String?): HockeyTechEndpoints? {
            val t = text?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
            val url = t.toHttpUrlOrNull() ?: return null
            if (url.scheme != "http" && url.scheme != "https") return null
            val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
            return HockeyTechEndpoints("$origin/hockeytech/feed/index.php", "$origin/firebase/svf/", "$origin/leaguestat/", ::fakeKey)
        }

        /** The endpoints for this build: [DEFAULT], or the debug override file in a debug build. Call off the main thread. */
        fun resolve(context: Context): HockeyTechEndpoints {
            if (!BuildConfig.DEBUG) return DEFAULT
            val text = try {
                File(context.filesDir, SportsEndpoints.DEBUG_OVERRIDE_FILE).takeIf { it.isFile }?.readText()
            } catch (e: IOException) {
                null
            }
            return fromOverride(text)?.also { BoxLog.w(TAG, "debug build: CHL endpoints overridden") } ?: DEFAULT
        }

        private const val TAG = "HockeyTechEndpoints"
    }
}
