package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
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

/** docs/design/m6-score-bug.md §3: the trim, the fit into the 64 × 46 slot, and the follow-lifetime holder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScoreBugLogoTest {
    /** A 960 × 640 raster whose art fills only the middle 70 %: the U-S1 shape of the NHL SVGs. */
    private fun padded(): Bitmap = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).drawRect(144f, 96f, 816f, 544f, Paint().apply { color = Color.RED })
    }

    @Test
    fun alphaBoundsAreTheArt() {
        val r = LogoTrim.alphaBounds(padded())
        assertEquals(144, r.left); assertEquals(96, r.top); assertEquals(816, r.right); assertEquals(544, r.bottom)
    }

    @Test
    fun trimThenFitFillsTheSlotsLimitingAxis() {
        // Art 672 × 448 (3 : 2) into 64 × 46 (device px at scale 1): width-limited, 64 × 42.
        val b = LogoTrim.trimAndFit(padded(), 64, 46)
        assertEquals(64, b.width)
        assertEquals(42, b.height)
        // Without the trim the same raster would have fitted to 64 × 42 *including* 30 % padding: art only 45 px wide.
        val edge = b.getPixel(0, b.height / 2)
        assertEquals("the art touches the left edge", Color.RED, edge)
        // Four times the slot (the request size), trimmed to the same slot.
        val big = LogoTrim.trimAndFit(padded(), 256, 184)
        assertEquals(256, big.width)
    }

    @Test
    fun anEmptyBitmapIsLeftAlone() {
        val empty = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        val r = LogoTrim.alphaBounds(empty)
        assertEquals(40, r.width()); assertEquals(20, r.height())
    }

    @Test
    fun followLogosLoadBothReleaseOnUnfollowAndMarkFailures() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val logos = FollowLogos({ url, w, h ->
            if (url == "bad") null else { gate.await(); Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
        }, CoroutineScope(Dispatchers.Default))
        logos.start("good", "bad", 64, 46)
        gate.complete(Unit)
        val until = System.currentTimeMillis() + 3000
        while ((logos.away.image == null || !logos.home.failed) && System.currentTimeMillis() < until) Thread.sleep(10)
        assertNotNull(logos.away.image)
        assertNull(logos.home.image)
        assertTrue("a failed logo shows its disc at once", logos.home.failed)
        assertFalse(logos.away.failed)
        logos.release()
        assertNull("released with the follow", logos.away.image)
        assertFalse(logos.home.failed)
    }

    @Test
    fun noUrlOrNoLoaderMeansTheDiscImmediately() = runBlocking {
        val none = FollowLogos(null, CoroutineScope(Dispatchers.Unconfined))
        none.start("a", "b", 64, 46)
        assertTrue(none.away.failed && none.home.failed)
        val noUrl = FollowLogos({ _, w, h -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }, CoroutineScope(Dispatchers.Unconfined))
        noUrl.start(null, null, 64, 46)
        assertTrue(noUrl.away.failed && noUrl.home.failed)
    }
}
