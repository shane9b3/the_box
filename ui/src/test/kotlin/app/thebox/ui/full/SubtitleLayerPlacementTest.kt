package app.thebox.ui.full

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.media3.common.text.Cue
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.playback.RectPx
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * U-B2 (V1): where captions are drawn, not where the view is. A 1920 × 1080 `SubtitleView` built and fed exactly as
 * `SubtitleLayer` does (lift as bottom view padding, fraction 0) is drawn into a bitmap with native graphics; the
 * lowest painted row is the text's bottom. Three cue types: an unset line, WebVTT's "auto" line (`−1`, NUMBER) and a
 * CEA-608 bottom row (NUMBER). With the info bar (top 820) the text ends ≥ 16 px above it; without a bar it keeps the
 * 8 % margin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubtitleLayerPlacementTest {
    private val w = 1920
    private val h = 1080
    private val rect = RectPx(0, 0, w, h)

    private val cues = mapOf(
        "unset line" to Cue.Builder().setText("Unset line caption").build(),
        "WebVTT auto line (−1 NUMBER)" to Cue.Builder().setText("WebVTT caption").setLine(-1f, Cue.LINE_TYPE_NUMBER).build(),
        "CEA-608 row 15 (−1 NUMBER, start)" to Cue.Builder().setText("CC1 caption").setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .setPosition(0.1f).setPositionAnchor(Cue.ANCHOR_TYPE_START).build(),
    )

    private fun textBottom(cue: Cue, liftPx: Int): Int {
        val view = newSubtitleView(ApplicationProvider.getApplicationContext())
        applySubtitleState(view, listOf(cue), liftPx)
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val row = IntArray(w)
        for (y in h - 1 downTo 0) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            if (row.any { (it ushr 24) != 0 }) return y + 1
        }
        return -1
    }

    @Test
    fun captionsSitAboveTheInfoBar() {
        val lift = subtitleLiftPx(rect, barTopPx = PlayerUi.BAR_T, scale = 1f)
        for ((name, cue) in cues) {
            val bottom = textBottom(cue, lift)
            assertTrue("$name: drawn ($bottom)", bottom > 0)
            assertTrue("$name: text bottom $bottom ≤ bar top − 16 (${PlayerUi.BAR_T - 16})", bottom <= PlayerUi.BAR_T - 16)
        }
    }

    @Test
    fun captionsKeepTheEightPercentMarginWithoutABar() {
        val lift = subtitleLiftPx(rect, barTopPx = null, scale = 1f)
        for ((name, cue) in cues) {
            val bottom = textBottom(cue, lift)
            assertTrue("$name: drawn ($bottom)", bottom > 0)
            assertTrue("$name: text bottom $bottom ≤ 0.92 × height", bottom <= 0.92f * h)
        }
    }

    @Test
    fun liftFollowsTheHighestBar() {
        val video = RectPx(0, 0, w, h)
        assertTrue(subtitleLiftPx(video, null, 1f) == kotlin.math.ceil(0.08f * h).toInt())
        assertTrue(subtitleLiftPx(video, PlayerUi.BAR_T, 1f) == (h - (PlayerUi.BAR_T - 16f)).toInt())
        assertTrue(subtitleLiftPx(video, PlayerUi.QUICK_T, 1f) == (h - (PlayerUi.QUICK_T - 16f)).toInt())
        // A letterboxed 4:3 picture ending above the bar keeps its 8 %.
        val small = RectPx(240, 0, 1680, 700)
        assertTrue(subtitleLiftPx(small, PlayerUi.BAR_T, 1f) == kotlin.math.ceil(0.08f * 700).toInt())
    }
}
