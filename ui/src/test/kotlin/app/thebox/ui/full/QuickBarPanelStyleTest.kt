package app.thebox.ui.full

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.thebox.core.full.QuickBarState
import app.thebox.core.full.QuickItem
import app.thebox.core.playback.AspectMode
import app.thebox.ui.theme.BoxTheme
import app.thebox.ui.theme.DesignTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (Settings panel A5, §5.8, §10 `QuickBarPanelStyleTest`) While the panel is open the Settings pill is drawn **active, not
 * focused**: White .16 fill, a 2 px accent border, FG text and `stateDescription` "Open"; no pill has the focused FG fill.
 * With the panel closed the bar is exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickBarPanelStyleTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val items = listOf(QuickItem.FAVOURITE_ADD, QuickItem.START_OVER, QuickItem.STREAM_INFO, QuickItem.SETTINGS)
    private fun bar(panelOpen: Boolean) = QuickBarState(items, focused = 3, statsOn = false, audio = null, subtitle = null, aspect = AspectMode.FIT, decoder = null, previous = null, panelOpen = panelOpen)
    private val state = mutableStateOf<QuickBarState?>(null)

    private fun show(s: QuickBarState) {
        if (state.value == null) {
            state.value = s
            rule.setContent { BoxTheme { Box(Modifier.fillMaxSize().background(DesignTokens.Colors.Canvas)) { state.value?.let { QuickBar(it, Modifier.offset(64.dp, 952.dp)) } } } }
        } else state.value = s
        rule.waitForIdle()
    }

    private fun pills(): List<SemanticsNode> = rule.onAllNodesWithTag(QUICK_ITEM_TAG, useUnmergedTree = true).fetchSemanticsNodes()

    /** The pill's fill, sampled inside its left end (inside the border, before the label). */
    private fun fill(n: SemanticsNode): Int {
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val b = n.boundsInRoot
        return bmp.getPixel((b.left + 10).toInt(), (b.top + b.height / 2).toInt())
    }

    private fun lum(c: Int): Float = (0.2126f * android.graphics.Color.red(c) + 0.7152f * android.graphics.Color.green(c) + 0.0722f * android.graphics.Color.blue(c)) / 255f

    @Test
    fun settingsPillIsActiveWhileThePanelIsOpen() {
        show(bar(panelOpen = false))
        val focused = pills()[3]
        assertNull("closed: no state", focused.config.getOrNull(SemanticsProperties.StateDescription))
        assertTrue("closed: the focused pill has the FG fill", lum(fill(focused)) > 0.8f)

        show(bar(panelOpen = true))
        val ps = pills()
        assertEquals("Open", ps[3].config.getOrNull(SemanticsProperties.StateDescription))
        for (i in 0..2) assertNull(ps[i].config.getOrNull(SemanticsProperties.StateDescription))
        val active = fill(ps[3])
        assertTrue("open: the Settings pill is not FG-filled (${Integer.toHexString(active)})", lum(active) < 0.35f)
        assertTrue("open: lighter than a plain pill (White .16 vs .08)", lum(active) > lum(fill(ps[0])))
        // The 2 px accent border at the pill's left edge, mid-height.
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val b = ps[3].boundsInRoot
        val edge = bmp.getPixel(b.left.toInt() + 1, (b.top + b.height / 2).toInt())
        val accent = DesignTokens.Colors.Accent
        val dr = android.graphics.Color.red(edge) - (accent.red * 255)
        val dg = android.graphics.Color.green(edge) - (accent.green * 255)
        val db = android.graphics.Color.blue(edge) - (accent.blue * 255)
        assertTrue("open: accent border (${Integer.toHexString(edge)})", dr * dr + dg * dg + db * db < 60f * 60f)
        java.io.File("build/settings-panel").apply { mkdirs() }.let { d ->
            java.io.File(d, "quick-bar-active.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
