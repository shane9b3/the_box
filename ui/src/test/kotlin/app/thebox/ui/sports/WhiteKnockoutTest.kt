package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WhiteKnockoutTest {
    private fun logoOnWhite(): Bitmap {
        // A 40x40 white square, a navy ring from 8..31 with a white centre from 14..25.
        val b = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.WHITE)
        for (y in 8..31) for (x in 8..31) b.setPixel(x, y, Color.rgb(10, 30, 110))
        for (y in 14..25) for (x in 14..25) b.setPixel(x, y, Color.WHITE)
        return b
    }

    @Test
    fun theWhiteAroundTheLogoIsClearedAndTheWhiteInsideItIsKept() {
        val out = WhiteKnockout.knockOut(logoOnWhite())
        assertEquals("corner is transparent", 0, out.getPixel(0, 0) ushr 24)
        assertEquals("surround next to the logo is transparent", 0, out.getPixel(7, 20) ushr 24)
        assertEquals("the logo is opaque", 255, out.getPixel(10, 20) ushr 24)
        assertEquals("enclosed white stays opaque white", Color.WHITE, out.getPixel(20, 20))
        assertTrue("the CHL check now accepts it", ChlLogoCheck.hasTransparentCorner(out))
    }

    @Test
    fun aPictureWithNoWhiteEdgeStaysOpaque() {
        val b = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.rgb(20, 20, 20))
        val out = WhiteKnockout.knockOut(b)
        assertEquals(255, out.getPixel(0, 0) ushr 24)
        assertFalse("still refused by the CHL check", ChlLogoCheck.hasTransparentCorner(out))
    }

    @Test
    fun aLightRimPixelBesideTheClearedAreaIsFaded() {
        val b = logoOnWhite()
        b.setPixel(7, 20, Color.rgb(200, 205, 225)) // an anti-aliased edge pixel, below the white floor
        val a = WhiteKnockout.knockOut(b).getPixel(7, 20) ushr 24
        assertTrue("partly transparent, was $a", a in 1..254)
    }
}
