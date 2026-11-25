package app.thebox.data.sports

import android.content.Context
import app.thebox.core.common.BoxLog
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsDebugOverride
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// (Sports Hub commit 0, docs/design/m6-sports-hub.md §4.2, A-S6) Moved out of NhlScoreSource.kt unchanged; WP-2 owns this
// file afterwards (the video hosts' debug redirect, §10.3).

/**
 * The NHL's base URLs (docs/design/m6-score-bug.md §2.1, §2.2). One place; a **debug** build can point both at the fake
 * panel (A-N3): `adb shell run-as app.thebox.debug sh -c 'mkdir -p files/debug && echo http://10.0.2.2:8080 >
 * files/debug/sports_base'`, then restart the app. The file is read only in a debuggable app (`SportsDebugOverride`, see [resolve]).
 */
data class SportsEndpoints(val apiBase: String, val logoBase: String) {
    companion object {
        const val API_BASE = "https://api-web.nhle.com/v1/"
        const val LOGO_BASE = "https://assets.nhle.com/logos/nhl/svg/"
        val DEFAULT = SportsEndpoints(API_BASE, LOGO_BASE)

        /** Under `filesDir`; one line, an origin such as `http://10.0.2.2:8080` (the one constant in `:core`). */
        const val DEBUG_OVERRIDE_FILE = SportsConstants.DEBUG_OVERRIDE_FILE

        /**
         * An override origin → `{origin}/v1/` and `{origin}/logos/nhl/svg/` (the fake panel serves the NHL's own paths).
         * Null for a blank or unusable text (not http/https, or not a URL).
         */
        fun fromOverride(text: String?): SportsEndpoints? {
            val t = text?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
            val url = t.toHttpUrlOrNull() ?: return null
            if (url.scheme != "http" && url.scheme != "https") return null
            val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
            return SportsEndpoints("$origin/v1/", "$origin/logos/nhl/svg/")
        }

        /** The endpoints for this build: [DEFAULT], or the debug override file in a debug build. Call off the main thread. */
        fun resolve(context: Context): SportsEndpoints {
            // (Integration review L4) The shared check: the installed app's FLAG_DEBUGGABLE; a release build never opens the file.
            val debuggable = SportsDebugOverride.isDebuggable(context.applicationInfo.flags)
            if (!debuggable) return DEFAULT
            val text = SportsDebugOverride.text(context.filesDir, debuggable)
            return fromOverride(text)?.also { BoxLog.w(TAG, "debug build: sports endpoints overridden") } ?: DEFAULT
        }

        private const val TAG = "SportsEndpoints"
    }
}
