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
        check(hasTransparentCorner(input)) { "CHL logo has no transparency" }
        return input
    }

    companion object {
        /** The HockeyTech logo host (design §2.1: `https://assets.leaguestat.com/{client}/logos/{file}`). */
        const val HOST = "assets.leaguestat.com"

        /** The whole URL, as §3.5 validates it: the base, a client folder, `logos/`, then `{id}[_{n}].png|jpg|jpeg`. */
        private val CHL_LOGO = Regex("""https://assets\.leaguestat\.com/(ohl|whl|lhjmq)/logos/[0-9]+(_[0-9]+)?\.(png|jpg|jpeg)""")

        /** True when [url] is a well-formed CHL logo URL (no `..`, no query, no other host, no other path). */
        fun isChlLogoUrl(url: String): Boolean = CHL_LOGO.matches(url)

        /** True when [url] points at the CHL logo host at all, valid or not (an invalid one is never requested). */
        fun isChlHost(url: String): Boolean = url.startsWith("https://$HOST/") || url.startsWith("http://$HOST/")

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
