package app.thebox.ui.guide.grid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.guide.GuideColors
import app.thebox.core.guide.fakes.FakeGuideImages
import app.thebox.core.guide.fakes.FakeGuideRenderer
import app.thebox.core.guide.fakes.FakeGuideStateMachine
import app.thebox.core.guide.fakes.FakeTextMeasurer
import app.thebox.core.guide.layout.GuideMetricsImpl
import app.thebox.core.guide.render.GuideReferenceFrame
import app.thebox.core.guide.render.GuideRendererImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Compose host and the View fallback (docs/design/m3-guide.md §3.6.5, B2.9): the host draws through the
 * renderer, a `frameVersion` bump redraws without recomposing, and the window-start rebase keeps the drawn
 * position continuous; the View host draws the same renderer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuideGridHostTest {
    @get:Rule val compose = createComposeRule()

    /** Robolectric does not draw on its own: capturing the root forces a draw pass. */
    private fun drawNow() { compose.onRoot().captureToImage() }

    @Test
    fun drawsAndRedrawsWithoutRecomposition() {
        val machine = FakeGuideStateMachine()
        machine.frame.metrics = GuideMetricsImpl(1f)
        machine.frame.windowStartMin = 1_000
        val renderer = FakeGuideRenderer()
        var compositions = 0
        compose.setContent {
            SideEffect { compositions++ }
            Box(Modifier.fillMaxSize()) { GuideGridHost(machine, renderer, Modifier.fillMaxSize()) }
        }
        compose.waitForIdle()
        drawNow()
        val first = renderer.draws
        val composedOnce = compositions
        assertTrue("drew at least once", first > 0)
        assertEquals(1_000, renderer.lastWindowStartMin)
        repeat(3) {
            machine.bumpFrame()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            drawNow()
        }
        assertTrue("redrew on frameVersion (${renderer.draws} > $first)", renderer.draws > first)
        assertEquals("frame bumps do not recompose the caller", composedOnce, compositions)
    }

    @Test
    fun windowStartChangeIsRebasedThenAnimatedToZero() {
        val machine = FakeGuideStateMachine()
        machine.frame.metrics = GuideMetricsImpl(1f)
        machine.frame.windowStartMin = 1_000
        val renderer = FakeGuideRenderer()
        compose.mainClock.autoAdvance = false
        compose.setContent { GuideGridHost(machine, renderer, Modifier.fillMaxSize()) }
        compose.mainClock.advanceTimeByFrame()
        machine.frame.windowStartMin = 1_030
        machine.bumpFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        drawNow()
        // Drawn position stays continuous: base + offset starts at the old window (1,000) and moves towards 1,030.
        assertEquals(1_030, renderer.lastWindowStartMin)
        val pos = renderer.lastWindowStartMin + renderer.lastScrollOffsetMin
        assertTrue("mid-animation position $pos", pos >= 1_000f && pos < 1_030f)
        compose.mainClock.advanceTimeBy(400)
        compose.mainClock.advanceTimeByFrame()
        drawNow()
        assertEquals(0f, renderer.lastScrollOffsetMin, 0.001f)
    }

    @Test
    fun logoCallbackAttachesWhenImagesArriveLater() {
        val machine = FakeGuideStateMachine()
        machine.frame.metrics = GuideMetricsImpl(1f)
        val renderer = FakeGuideRenderer()
        compose.setContent { GuideGridHost(machine, renderer, Modifier.fillMaxSize()) }
        compose.waitForIdle()
        drawNow()
        val images = FakeGuideImages()
        machine.frame.images = images
        machine.bumpFrame()
        compose.waitForIdle()
        drawNow()
        assertNotNull("host listens for logo arrivals", images.onLoaded)
        val before = renderer.draws
        images.complete("logo://1")
        compose.waitForIdle()
        drawNow()
        assertTrue(renderer.draws > before)
    }

    @Test
    fun lowEndSnaps() {
        val machine = FakeGuideStateMachine()
        machine.frame.metrics = GuideMetricsImpl(1f)
        machine.frame.lowEnd = true
        val renderer = FakeGuideRenderer()
        compose.mainClock.autoAdvance = false
        compose.setContent { GuideGridHost(machine, renderer, Modifier.fillMaxSize()) }
        compose.mainClock.advanceTimeByFrame()
        machine.frame.topRow = 12
        machine.bumpFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        drawNow()
        assertEquals(12f, renderer.lastScrollRow, 0f)
    }

    @Test
    fun viewHostDrawsTheSameRenderer() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val measurer = FakeTextMeasurer()
        val view = GuideGridView(context, GuideRendererImpl(measurer))
        view.frame = GuideReferenceFrame.build(measurer)
        view.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1920, 1080)
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(GuideColors.CANVAS)
        view.draw(Canvas(bmp))
        // The now line (accent) is drawn at x ≈ 688.
        val p = bmp.getPixel(688, 700)
        assertTrue(Integer.toHexString(p), Color.blue(p) > 180 && Color.red(p) < 150)
        assertEquals(GuideColors.CANVAS, bmp.getPixel(960, 300))
    }
}
