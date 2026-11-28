package app.thebox.ui.sports

import android.graphics.Bitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.test.junit4.createComposeRule
import app.thebox.ui.guide.screen.GuideUi
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.thebox.ui.guide.screen.ProvideShellImageLoader
import coil3.EventListener
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (CHL hub fix pass, A1) The headshot slot and the CHL host: exactly `https://assets.leaguestat.com/{client}/240x240/{id}.jpg`
 * (and the fake panel's form on the override's origin) is a CHL headshot; every other URL on that host is never requested
 * from a headshot slot; the NHL's and ESPN's headshots are requested as before. The composable loads a CHL headshot through
 * the shell loader (a fake fetcher here) and reaches success.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChlHeadshotTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun onlyTheHeadshotShapeIsAHeadshotOnTheChlHost() {
        val ok = listOf(
            "https://assets.leaguestat.com/ohl/240x240/9330.jpg", "https://assets.leaguestat.com/whl/240x240/30765.jpg",
            "https://assets.leaguestat.com/lhjmq/240x240/1.jpg", "https://assets.leaguestat.com/ohl/240x240/123456789012.jpg",
        )
        for (u in ok) {
            assertTrue(u, ChlLogoCheck.isChlHeadshotUrl(u))
            assertTrue(u, ChlLogoCheck.headshotAllowed(u))
            assertFalse("a headshot is not a logo: $u", ChlLogoCheck.isChlLogoUrl(u))
        }
        val bad = listOf(
            "http://assets.leaguestat.com/ohl/240x240/9330.jpg",                  // not HTTPS
            "https://assets.leaguestat.com/ohl/240x240/1234567890123.jpg",        // 13 digits
            "https://assets.leaguestat.com/ohl/240x240/93a0.jpg",                 // not numeric
            "https://assets.leaguestat.com/ohl/240x240/.jpg",
            "https://assets.leaguestat.com/ohl/240x240/9330.png",                 // another type
            "https://assets.leaguestat.com/ohl/240x240/9330.jpg?x=1",             // a query
            "https://assets.leaguestat.com/ohl/240x240/../logos/14.png",          // traversal
            "https://assets.leaguestat.com/ohl/120x160/9330.jpg",                 // another size folder
            "https://assets.leaguestat.com/ohl/60x60/9330.jpg",
            "https://assets.leaguestat.com/chl/240x240/9330.jpg",                 // not a client
            "https://assets.leaguestat.com/ohl/logos/14.png",                     // a logo
            "https://assets.leaguestat.com/chl/broadcasters/chl-tv.png",
            "HTTPS://ASSETS.LEAGUESTAT.COM/ohl/240x240/9330.jpg",
            "https://assets.leaguestat.com:443/ohl/240x240/9330.jpg",
            "https://x@assets.leaguestat.com/ohl/240x240/9330.jpg",
        )
        for (u in bad) {
            assertFalse(u, ChlLogoCheck.isChlHeadshotUrl(u))
            assertFalse("never requested from a headshot slot: $u", ChlLogoCheck.headshotAllowed(u))
        }
        // Off the CHL host nothing changes: the NHL's and ESPN's headshots are requested as before.
        for (u in listOf("https://assets.nhle.com/mugs/nhl/20252026/OTT/8480801.png", "https://a.espncdn.com/i/headshots/nfl/players/full/3139477.png")) {
            assertTrue(u, ChlLogoCheck.headshotAllowed(u))
            assertFalse(u, ChlLogoCheck.isChlHeadshotUrl(u))
        }
        // The fake panel's form, only on the override's own origin.
        val o = "http://10.0.2.2:8080"
        assertTrue(ChlLogoCheck.isChlHeadshotUrl("$o/leaguestat/ohl/240x240/9884.jpg", o))
        assertTrue(ChlLogoCheck.headshotAllowed("$o/leaguestat/whl/240x240/30765.jpg", o))
        assertFalse("no override", ChlLogoCheck.isChlHeadshotUrl("$o/leaguestat/ohl/240x240/9884.jpg"))
        assertFalse("another origin", ChlLogoCheck.isChlHeadshotUrl("https://evil.test/leaguestat/ohl/240x240/9884.jpg", o))
        assertFalse("userinfo trick", ChlLogoCheck.isChlHeadshotUrl("http://10.0.2.2:8080@evil.test/leaguestat/ohl/240x240/1.jpg", o))
        assertFalse(ChlLogoCheck.headshotAllowed("$o/leaguestat/ohl/logos/14.png", o))
        assertFalse(ChlLogoCheck.headshotAllowed("$o/leaguestat/ohl/240x240/1.jpg?x", o))
        assertTrue("the real host's form passes with an override too", ChlLogoCheck.isChlHeadshotUrl("https://assets.leaguestat.com/ohl/240x240/9330.jpg", o))
        // The logo check is unchanged: a headshot URL is still no logo, with or without the override.
        assertFalse(ChlLogoCheck.isChlLogoUrl("$o/leaguestat/ohl/240x240/9884.jpg", o))
    }

    /** What the slot asked the loader for, and how each request ended. */
    private fun loaderLog(log: MutableList<String>): ImageLoader {
        val bmp = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(90, 60, 30)) }
        val jpeg = java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        return ImageLoader.Builder(context)
            .components {
                add(Fetcher.Factory<coil3.Uri> { data, _, _ ->
                    Fetcher { log += "fetch $data"; SourceFetchResult(ImageSource(Buffer().write(jpeg), okio.FileSystem.SYSTEM), "image/jpeg", DataSource.NETWORK) }
                })
            }
            .eventListener(object : EventListener() {
                override fun onSuccess(request: ImageRequest, result: SuccessResult) { log += "ok" }
                override fun onError(request: ImageRequest, result: ErrorResult) { log += "error" }
            })
            .build()
    }

    @Test fun aChlHeadshotLoadsAndANonHeadshotUrlOnTheHostIsNeverRequested() {
        // Written from Coil's threads: a synchronized list, read under its lock.
        val log: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val loader = loaderLog(log)
        val urls = listOf(
            "https://assets.leaguestat.com/ohl/240x240/9330.jpg",
            "https://assets.leaguestat.com/ohl/logos/14.png",
            "https://assets.nhle.com/mugs/nhl/20252026/OTT/8480801.png",
        )
        rule.setContent {
            ProvideShellImageLoader(loader) {
                androidx.compose.foundation.layout.Column {
                    urls.forEachIndexed { i, u -> HubHeadshot(u, "p$i", 72.dp, onLight = i == 0) }
                }
            }
        }
        // Generous: under the full suite's load Coil's IO threads can be slow to start.
        rule.waitUntil(30_000) { synchronized(log) { log.count { it == "ok" } >= 2 } }
        rule.waitForIdle()
        val seen = synchronized(log) { log.toList() }
        assertEquals(setOf("fetch ${urls[0]}", "fetch ${urls[2]}", "ok"), seen.toSet())
        assertFalse("the logo URL was never requested", seen.any { it.contains("/logos/") })
    }

    @Test fun theFocusedRowsDiscIsVisibleOnTheLightFill() {
        // The focused row's fill is the FG colour; White08 on it is invisible, the on-light disc is not.
        val fg = GuideUi.Fg
        val onWhite = GuideUi.White08.compositeOver(fg)
        val onLight = HEADSHOT_ON_LIGHT.compositeOver(fg)
        fun diff(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color) = maxOf(kotlin.math.abs(a.red - b.red), kotlin.math.abs(a.green - b.green), kotlin.math.abs(a.blue - b.blue))
        assertTrue("White08 vanishes on the focused fill", diff(onWhite, fg) < 0.02f)
        assertTrue("the on-light disc stands out", diff(onLight, fg) > 0.10f)
    }
}
