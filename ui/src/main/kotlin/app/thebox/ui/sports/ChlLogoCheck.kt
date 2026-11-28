package app.thebox.ui.sports

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation

/**
 * (CHL design §3.5, S9, lead ruling P6/S10) The CHL-only no-alpha check. A HockeyTech logo that is a flat opaque picture
 * (no transparency: WEN's `222.png` is RGB) would draw as a white or black box on the bug, so it must fall back to the code
 * disc / capsule instead.
 *
 * It is a Coil [Transformation] added to a request **only when the URL is under the CHL logo base** ([isChlHost]); the
 * NHL and NFL requests are built exactly as before. It runs **before** [LogoTrim] and **throws** when the decoded bitmap has
 * no alpha channel or all four corner pixels are opaque (alpha above [LogoTrim.ALPHA_FLOOR]); the request then ends in an
 * `ErrorResult`, so no logo is returned and the fallback shows. A transformation cannot return null, and after trimming the
 * corners of a square badge can be opaque, which is why the check is its own step ahead of the trim.
 */
class ChlLogoCheck : Transformation() {
    override val cacheKey: String get() = "ChlLogoCheck"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (!hasTransparentCorner(input)) throw NoTransparency()
        return input
    }

    /** (Integration review S2) The no-alpha refusal: a permanent answer for this URL (the cards cache it for 6 h). */
    class NoTransparency : IllegalStateException("CHL logo has no transparency")

    companion object {
        /** The HockeyTech logo host (design §2.1: `https://assets.leaguestat.com/{client}/logos/{file}`). */
        const val HOST = "assets.leaguestat.com"

        /** The one logo form of a release build: the real host, a client folder, `logos/`, then `{id}[_{n}].png|jpg|jpeg`. */
        private val CHL_LOGO = Regex("""https://assets\.leaguestat\.com/(ohl|whl|lhjmq)/logos/[0-9]+(_[0-9]+)?\.(png|jpg|jpeg)""")

        /** What follows `{origin}/leaguestat/` in the fake panel's form (design §2.8), only ever accepted for the override's origin. */
        private val FAKE_TAIL = Regex("""(ohl|whl|lhjmq)/logos/[0-9]+(_[0-9]+)?\.(png|jpg|jpeg)""")

        /**
         * (CHL hub fix pass, A1) The one headshot form on the real host, exactly what `HockeyTechEndpoints.headshot` builds:
         * a client folder, `240x240/`, then a 1–12 digit player id and `.jpg`. Nothing else on the host is a headshot.
         */
        private val CHL_HEADSHOT = Regex("""https://assets\.leaguestat\.com/(ohl|whl|lhjmq)/240x240/[0-9]{1,12}\.jpg""")

        /** What follows `{origin}/leaguestat/` in the fake panel's headshot form, only ever accepted for the override's origin. */
        private val FAKE_HEADSHOT_TAIL = Regex("""(ohl|whl|lhjmq)/240x240/[0-9]{1,12}\.jpg""")

        /** The data source's debug override file under `filesDir` (one line, an origin): the one constant in `:core`. */
        const val DEBUG_OVERRIDE_FILE = app.thebox.core.sports.SportsConstants.DEBUG_OVERRIDE_FILE

        /**
         * The debug endpoint override's origin (`scheme://host[:port]`, no trailing slash), or null. It is non-null **only** when
         * the app is a debug build ([debuggable]) and the override file the data source reads (`HockeyTechEndpoints.resolve`,
         * the same file as `SportsEndpoints`) holds an http or https origin. A release build, or a debug build without the
         * file, always gets null: only the real host's form is then a CHL logo URL.
         */
        fun overrideOrigin(filesDir: java.io.File, debuggable: Boolean): String? {
            // (Integration review L4) The same check and read as the data sources (`SportsDebugOverride`).
            val text = app.thebox.core.sports.SportsDebugOverride.text(filesDir, debuggable)
                ?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
            val uri = try { java.net.URI(text) } catch (e: java.net.URISyntaxException) { return null }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host ?: return null
            return "$scheme://$host" + if (uri.port >= 0) ":${uri.port}" else ""
        }

        /** [overrideOrigin] for this app: debuggable build and the override file under `filesDir`. */
        fun overrideOrigin(context: android.content.Context): String? =
            overrideOrigin(context.filesDir, app.thebox.core.sports.SportsDebugOverride.isDebuggable(context.applicationInfo.flags))

        /**
         * True when [url] is a well-formed CHL logo URL: exactly the real host's form (https, no `..`, no query, no other
         * path), or, only when [overrideOrigin] is non-null, the fake panel's `{overrideOrigin}/leaguestat/{client}/logos/{file}`
         * on **that** origin and no other.
         */
        fun isChlLogoUrl(url: String, overrideOrigin: String? = null): Boolean =
            CHL_LOGO.matches(url) || (overrideOrigin != null && url.startsWith("$overrideOrigin/leaguestat/") &&
                FAKE_TAIL.matches(url.removePrefix("$overrideOrigin/leaguestat/")))

        /**
         * (CHL hub fix pass, A1) True when [url] is a well-formed CHL headshot URL: exactly the real host's
         * `https://assets.leaguestat.com/{ohl|whl|lhjmq}/240x240/{digits}.jpg` (no `..`, query or other path), or, only when
         * [overrideOrigin] is non-null, the fake panel's `{overrideOrigin}/leaguestat/{client}/240x240/{digits}.jpg` on that
         * origin and no other. A logo URL is not a headshot, and a headshot is not a logo ([isChlLogoUrl] refuses it).
         */
        fun isChlHeadshotUrl(url: String, overrideOrigin: String? = null): Boolean =
            CHL_HEADSHOT.matches(url) || (overrideOrigin != null && url.startsWith("$overrideOrigin/leaguestat/") &&
                FAKE_HEADSHOT_TAIL.matches(url.removePrefix("$overrideOrigin/leaguestat/")))

        /**
         * (CHL hub fix pass, A1) Whether the headshot loader may request [url]: any URL off the CHL host as before (the NHL's
         * and ESPN's headshots are untouched), and on the CHL host ([isChlHost]) only a [isChlHeadshotUrl]. Every other URL
         * on that host (a logo, another folder, a query, a `..`) is never requested from a headshot slot.
         */
        fun headshotAllowed(url: String, overrideOrigin: String? = null): Boolean =
            !isChlHost(url, overrideOrigin) || isChlHeadshotUrl(url, overrideOrigin)

        /**
         * True when [url] points at the CHL logo host (in any spelling) or, with an override, at the override's `/leaguestat/`
         * folder, valid or not: such a URL is checked, and an invalid one is never requested.
         */
        fun isChlHost(url: String, overrideOrigin: String? = null): Boolean =
            url.startsWith("https://$HOST/", ignoreCase = true) || url.startsWith("http://$HOST/", ignoreCase = true) ||
                url.startsWith("https://$HOST:", ignoreCase = true) || url.contains("@$HOST", ignoreCase = true) ||
                (overrideOrigin != null && url.startsWith("$overrideOrigin/leaguestat/"))

        /** True when the bitmap has an alpha channel and at least one of its four corner pixels is transparent. */
        fun hasTransparentCorner(b: Bitmap): Boolean {
            if (!b.hasAlpha()) return false
            val w = b.width
            val h = b.height
            if (w <= 0 || h <= 0) return false
            val xs = intArrayOf(0, w - 1)
            val ys = intArrayOf(0, h - 1)
            for (y in ys) for (x in xs) if ((b.getPixel(x, y) ushr 24) <= LogoTrim.ALPHA_FLOOR) return true
            return false
        }
    }
}
