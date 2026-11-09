package app.thebox.ui.guide.grid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.guide.GuideColors
import app.thebox.core.guide.GridFocus
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.Zone
import app.thebox.core.input.BoxAction
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
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
    fun accessibilityTracksServiceChangesAndRoutesActionsThroughTheMachine() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val shadow = shadowOf(manager)
        shadow.setEnabled(false)
        shadow.setTouchExplorationEnabled(false)
        val machine = FakeGuideStateMachine()
        machine.frame.focus = GridFocus(2, 1_000, 1_030, 0, 1_000, 1_000, 0)
        compose.setContent { GuideGridHost(machine, FakeGuideRenderer(), Modifier.fillMaxSize().testTag("a11y-grid")) }
        compose.waitForIdle()
        fun config() = compose.onNodeWithTag("a11y-grid").fetchSemanticsNode().config
        assertEquals(null, config().getOrNull(SemanticsActions.OnClick))
        compose.runOnIdle {
            shadow.setTouchExplorationEnabled(true)
            shadow.setEnabled(true) // The accessibility-state callback reads the complete current state.
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertEquals(listOf("row 2 at 1000"), config().getOrNull(SemanticsProperties.ContentDescription))
        compose.onNodeWithTag("a11y-grid").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(BoxAction.SELECT, (machine.events.last() as GuideEvent.Act).action)
        val nextChannel = config()[SemanticsActions.CustomActions].first { it.label == "Next channel" }
        compose.runOnIdle { assertTrue(nextChannel.action()) }
        assertEquals(BoxAction.DOWN, (machine.events.last() as GuideEvent.Act).action)

        val focus = machine.frame.focus
        compose.runOnIdle { machine.frame.focus = null; machine.bumpFrame() }
        compose.waitForIdle()
        assertEquals(null, config().getOrNull(SemanticsActions.OnClick))
        compose.runOnIdle { machine.frame.focus = focus; machine.bumpFrame() }
        compose.waitForIdle()
        assertNotNull(config().getOrNull(SemanticsActions.OnClick))

        compose.runOnIdle { machine.mutableUi.value = machine.mutableUi.value.copy(zone = Zone.HEADER) }
        compose.waitForIdle()
        assertEquals(null, config().getOrNull(SemanticsActions.OnClick))
        assertEquals(null, config().getOrNull(SemanticsProperties.ContentDescription))
        compose.runOnIdle { assertFalse("stale accessibility action cannot act outside the grid", nextChannel.action()) }
        compose.runOnIdle { shadow.setEnabled(false) }
    }

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
        // The now line (accent) is drawn at x ≈ 747 (label 280, decision 2026-11-09).
        val p = bmp.getPixel(747, 700)
        assertTrue(Integer.toHexString(p), Color.blue(p) > 180 && Color.red(p) < 150)
        assertEquals(GuideColors.CANVAS, bmp.getPixel(960, 300))
    }
}
