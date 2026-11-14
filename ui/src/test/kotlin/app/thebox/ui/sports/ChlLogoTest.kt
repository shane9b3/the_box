package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.SportsFixtures
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (CHL WP-5, docs/design/m6-chl-leagues.md §3.5, S9, S10; lead rulings P6 / S9 / S10) The CHL logo rules: the no-alpha check
 * is a CHL-only Coil transformation that fails before the trim so the fallback shows; the URL is validated; NHL and NFL
 * requests are built exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChlLogoTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun bitmap(corners: IntArray?, hasAlpha: Boolean = true, w: Int = 40, h: Int = 30): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            this.setHasAlpha(hasAlpha)
            eraseColor(Color.argb(255, 20, 40, 120))
            if (corners != null) {
                setPixel(0, 0, corners[0]); setPixel(w - 1, 0, corners[1]); setPixel(0, h - 1, corners[2]); setPixel(w - 1, h - 1, corners[3])
            }
        }

    private val clear = Color.argb(0, 0, 0, 0)
    private val opaque = Color.argb(255, 255, 255, 255)

    @Test
    fun theCheckPassesOnlyABitmapWithAlphaAndATransparentCorner() {
        assertTrue(ChlLogoCheck.hasTransparentCorner(bitmap(intArrayOf(clear, clear, clear, clear))))
        assertTrue("one transparent corner is enough", ChlLogoCheck.hasTransparentCorner(bitmap(intArrayOf(opaque, opaque, opaque, clear))))
        assertFalse("all four corners opaque (a square badge)", ChlLogoCheck.hasTransparentCorner(bitmap(intArrayOf(opaque, opaque, opaque, opaque))))
        assertFalse("no alpha channel at all (WEN's 222.png)", ChlLogoCheck.hasTransparentCorner(bitmap(intArrayOf(clear, clear, clear, clear), hasAlpha = false)))
        // alpha at or below the trim's floor counts as empty
        assertTrue(ChlLogoCheck.hasTransparentCorner(bitmap(intArrayOf(opaque, opaque, opaque, Color.argb(app.thebox.ui.sports.LogoTrim.ALPHA_FLOOR, 9, 9, 9)))))
    }

    @Test
    fun theTransformationThrowsBeforeTheTrimSoTheFallbackShows() = runBlocking {
        val t = ChlLogoCheck()
        val size = coil3.size.Size.ORIGINAL
        val good = bitmap(intArrayOf(clear, clear, clear, clear))
        assertEquals(good, t.transform(good, size))
        val flat = bitmap(intArrayOf(opaque, opaque, opaque, opaque))
        val failed = runCatching { t.transform(flat, size) }
        assertTrue(failed.exceptionOrNull() is IllegalStateException)
        // The trim itself would have happily returned a logo of the flat bitmap: that is why the check runs first.
        assertNotNull(LogoTrim.trimAndFit(flat, 64, 46))
    }

    @Test
    fun theCheckComesFirstInAChlRequestAndIsAbsentFromNhlAndNflRequests() {
        val chl = logoTransformations(true, 64, 46)
        assertEquals(listOf("ChlLogoCheck", "LogoTrim(64,46)"), chl.map { it.cacheKey })
        for (url in listOf("https://assets.nhle.com/logos/nhl/svg/CGY_dark.svg", "https://a.espncdn.com/combiner/i?img=/i/teamlogos/nfl/500-dark/scoreboard/kc.png&h=200&w=200")) {
            assertFalse(url, ChlLogoCheck.isChlHost(url))
            assertEquals(listOf("LogoTrim(64,46)"), logoTransformations(ChlLogoCheck.isChlHost(url), 64, 46).map { it.cacheKey })
        }
        assertTrue(ChlLogoCheck.isChlHost("https://assets.leaguestat.com/ohl/logos/14.png"))
    }

    @Test
    fun theUrlValidationAcceptsOnlyTheDesignedShape() {
        val ok = listOf(
            "https://assets.leaguestat.com/ohl/logos/14.png", "https://assets.leaguestat.com/whl/logos/201.jpg",
            "https://assets.leaguestat.com/lhjmq/logos/3_295.jpeg", "https://assets.leaguestat.com/lhjmq/logos/60.png",
        )
        for (u in ok) assertTrue(u, ChlLogoCheck.isChlLogoUrl(u))
        val bad = listOf(
            "http://assets.leaguestat.com/ohl/logos/14.png",                      // not HTTPS
            "https://assets.leaguestat.com/ohl/logos/50x50/14.png",                // never the 50x50 folder
            "https://assets.leaguestat.com/ohl/logos/../x/14.png",                 // traversal
            "https://assets.leaguestat.com/ohl/logos/14.png?key=1",                // a query
            "https://assets.leaguestat.com/ohl/logos/14.gif",                      // another type
            "https://assets.leaguestat.com/ohl/logos/abc.png",                     // not an id
            "https://assets.leaguestat.com/chl/logos/14.png",                      // not a client
            "https://assets.leaguestat.com.evil.test/ohl/logos/14.png",            // another host
            "https://assets.leaguestat.com/ohl/logos/14.png/../../x.png",
            "https://assets.leaguestat.com/ohl/other/14.png",
            "https://evil.test/https://assets.leaguestat.com/ohl/logos/14.png",
            "",
        )
        for (u in bad) assertFalse(u, ChlLogoCheck.isChlLogoUrl(u))
        // A URL on the CHL host that is not a valid logo URL is never requested: the fetch answers null at once.
        val loader = ImageLoader.Builder(context).build()
        val fetch = coilLogoFetch(loader, context)
        assertNull(runBlocking { fetch("https://assets.leaguestat.com/ohl/logos/50x50/14.png", 64, 46) })
        assertNull(runBlocking { fetch("https://assets.leaguestat.com/ohl/logos/14.png?x=1", 64, 46) })
    }

    @Test
    fun theLeagueDecidesAndEverySpellingOfTheHostIsCheckedOrRejected() {
        // (Review L1) Odd spellings of the CHL host are seen as the CHL's (so never the NHL path) and then rejected.
        for (u in listOf(
            "HTTPS://ASSETS.LEAGUESTAT.COM/ohl/logos/14.png", "https://assets.leaguestat.com:443/ohl/logos/14.png",
            "https://x@assets.leaguestat.com/ohl/logos/14.png",
        )) {
            assertTrue(u, ChlLogoCheck.isChlHost(u))
            assertFalse(u, ChlLogoCheck.isChlLogoUrl(u))
        }
        // (Review L2) The fake panel's logo folder takes the same check and is a valid CHL logo URL.
        val fake = "http://10.0.2.2:8080/leaguestat/whl/logos/222.png"
        assertTrue(ChlLogoCheck.isChlHost(fake))
        assertTrue(ChlLogoCheck.isChlLogoUrl(fake))
        assertFalse(ChlLogoCheck.isChlLogoUrl("http://10.0.2.2:8080/leaguestat/whl/logos/../222.png"))
        assertFalse(ChlLogoCheck.isChlLogoUrl("http://10.0.2.2:8080/leaguestat/whl/logos/222.png?x=1"))
        // NHL and NFL URLs are never the CHL's
        assertFalse(ChlLogoCheck.isChlHost("https://assets.nhle.com/logos/nhl/svg/CGY_dark.svg"))
        assertFalse(ChlLogoCheck.isChlHost("http://10.0.2.2:8080/nhle/logos/nhl/svg/CGY_dark.svg"))
        // A CHL league's fetch refuses any URL that is not a valid CHL logo URL, whatever the host.
        val fetch = coilLogoFetch(ImageLoader.Builder(context).build(), context, chlLeague = true)
        assertNull(runBlocking { fetch("https://evil.test/x.png", 64, 46) })
        assertNull(runBlocking { fetch("https://assets.nhle.com/logos/nhl/svg/CGY_dark.svg", 64, 46) })
    }

    @Test
    fun theNegativeCacheIsBoundedAndExpires() {
        CardLogoCache.clearFailed()
        val u = "https://assets.leaguestat.com/ohl/logos/18.png"
        assertFalse(CardLogoCache.recentlyFailed(u, now = 0L))
        CardLogoCache.markFailed(u, now = 1_000L)
        assertTrue(CardLogoCache.recentlyFailed(u, now = 1_000L + CardLogoCache.FAILED_TTL_MS - 1))
        assertFalse("it expires after the TTL", CardLogoCache.recentlyFailed(u, now = 1_000L + CardLogoCache.FAILED_TTL_MS))
        // bounded: 200 more URLs push the oldest out
        CardLogoCache.markFailed("first", now = 5L)
        for (i in 0 until 200) CardLogoCache.markFailed("https://assets.leaguestat.com/ohl/logos/$i.png", now = 6L)
        assertFalse(CardLogoCache.recentlyFailed("first", now = 7L))
        assertTrue(CardLogoCache.recentlyFailed("https://assets.leaguestat.com/ohl/logos/199.png", now = 7L))
        CardLogoCache.clearFailed()
    }

    /** One recorded outcome row (S10): `league code team_id feed_url app_url status png corners verdict`. */
    private class Row(val code: String, val appUrl: String, val verdict: String, val png: String, val corners: String)

    private fun outcomes(): List<Row> =
        SportsFixtures.text("sports/chl/logos/logo-outcomes-2026-11-13.tsv").lines().drop(1).filter { it.isNotBlank() }.map {
            val c = it.split('\t')
            Row(c[1], c[4], c[8], c[6], c[7])
        }

    @Test
    fun theRecorded61OutcomesGiveTheLogoOrTheFallback() {
        val rows = outcomes()
        assertEquals(61, rows.size)
        assertEquals(55, rows.count { it.verdict == "logo" })
        assertEquals(setOf("BRAM", "BDN", "KEL", "PEN", "SAS", "WEN"), rows.filter { it.verdict != "logo" }.map { it.code }.toSet())
        for (r in rows) {
            assertTrue("${r.code} ${r.appUrl} is a valid CHL logo URL", ChlLogoCheck.isChlLogoUrl(r.appUrl))
            if (r.verdict == "logo") {
                // PNG RGBA with transparent corners (MON's is interlaced and Android decodes it): the check passes.
                assertTrue(r.code, ChlLogoCheck.hasTransparentCorner(bitmap(intArrayOf(clear, clear, clear, clear))))
            } else if (r.png == "RGB") {
                // WEN: no alpha, opaque corners: the check fails, so the code shows.
                assertFalse(r.code, ChlLogoCheck.hasTransparentCorner(bitmap(intArrayOf(opaque, opaque, opaque, opaque), hasAlpha = false)))
            } else {
                assertEquals("${r.code} has no image (404): the fetch fails and the code shows", "None", r.png)
            }
        }
        // 4-letter fallbacks: BRAM only (the capsule ships on day one, S10); the codes of the other five are 3 letters (discs).
        assertEquals(listOf("BRAM"), rows.filter { it.verdict != "logo" && it.code.length > 3 }.map { it.code })
    }

    @Test
    fun aChlRequestOfAFlatLogoEndsInAnErrorAndANormalOneGivesAFittedBitmap() {
        val loader = ImageLoader.Builder(context).build()
        fun run(b: Bitmap, chl: Boolean) = runBlocking { loader.execute(logoRequest(context, b, chl, 64, 46)) }
        val flat = bitmap(intArrayOf(opaque, opaque, opaque, opaque), hasAlpha = false)
        assertTrue("no alpha: the request fails, so the fallback shows", run(flat, chl = true) is ErrorResult)
        val squareBadge = bitmap(intArrayOf(opaque, opaque, opaque, opaque))
        assertTrue("alpha but opaque corners: fails before the trim", run(squareBadge, chl = true) is ErrorResult)
        val logo = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            for (y in 10 until 50) for (x in 10 until 70) setPixel(x, y, Color.argb(255, 200, 30, 40))
        }
        val ok = run(logo, chl = true)
        assertTrue(ok.toString(), ok is SuccessResult)
        val fitted = (ok as SuccessResult).image.toBitmap()
        assertTrue("trimmed to the art and fitted to 64 x 46: ${fitted.width} x ${fitted.height}", fitted.width <= 64 && fitted.height <= 46)
        // The same flat bitmap without the check (an NHL or NFL request) still gives a logo: those requests are unchanged.
        assertTrue(run(flat, chl = false) is SuccessResult)
        assertTrue(run(squareBadge, chl = false) is SuccessResult)
    }
}
