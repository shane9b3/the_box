package app.thebox.ui.ambient

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** docs/design/m3-guide.md §3.14, §5.3 `AmbientRendererTest`: deterministic per hue; the layout of the field. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AmbientRendererTest {
    private fun hash(b: Bitmap): Long {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        var h = 1125899906842597L
        for (p in px) h = 31 * h + p
        return h
    }

    private fun lum(c: Int) = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)

    @Test
    fun sameHueSameBitmapDifferentHueDifferentBitmap() {
        val r = AmbientRenderer()
        val a = r.render(240f)
        assertEquals(AmbientRenderer.WIDTH, a.width)
        assertEquals(AmbientRenderer.HEIGHT, a.height)
        assertEquals(hash(a), hash(AmbientRenderer().render(240f)))
        assertNotEquals(hash(a), hash(r.render(20f)))
    }

    @Test
    fun blobATopLeftIsBrighterThanTheBottomOverlay() {
        val b = AmbientRenderer().render(240f)
        val blobA = b.getPixel(112, 60) // design (450, 240): blob A's centre
        val bottom = b.getPixel(240, 268) // the .92 overlay at the bottom edge
        assertTrue("blob ${"%08X".format(blobA)} vs bottom ${"%08X".format(bottom)}", lum(blobA) > lum(bottom) + 20)
        // Blob A at hue 240 is blue-dominant.
        assertTrue((blobA and 0xFF) > ((blobA shr 16) and 0xFF))
        // The base is opaque everywhere.
        assertEquals(0xFF, b.getPixel(479, 0) ushr 24)
    }

    @Test
    fun shadowIsRenderedOnceAtQuarterScale() {
        val s = AmbientRenderer().renderShadow()
        assertEquals(((720f + 2 * AmbientRenderer.SHADOW_MARGIN) * AmbientRenderer.SHADOW_SCALE).toInt(), s.width)
        val centre = s.getPixel(s.width / 2, s.height / 2) ushr 24
        val corner = s.getPixel(0, 0) ushr 24
        assertTrue("centre $centre corner $corner", centre > 100 && corner < 10)
    }

    @Test
    fun erfcMatchesKnownValues() {
        assertEquals(1f, AmbientRenderer.erfc(0f), 1e-4f)
        assertEquals(0.157299f, AmbientRenderer.erfc(1f), 1e-4f)
        assertEquals(1.842701f, AmbientRenderer.erfc(-1f), 1e-4f)
    }
}
