package app.thebox.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.editor.ChannelEditorMachineImpl
import app.thebox.core.guide.GuideEvent
import app.thebox.core.input.BoxAction
import app.thebox.core.settings.SettingsRoute
import app.thebox.ui.settings.SettingsUi
import app.thebox.ui.theme.BoxTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * docs/design/m6-channel-editor.md §6.3 "ChannelEditorGoldenTest": the editor's E2 state (channels pane focused, two
 * channels ticked, badges, details, the Groups pane with its fixed row and current group) drawn at 1920 × 1080 over the
 * settings canvas, compared with `src/test/resources/golden/channel-editor-1080p.png`. The settings header is left out
 * (its clock changes). Same tolerance as the guide's GoldenFrameTest: a pixel differs when a channel is off by more
 * than 24, and at most 0.05 % may differ. Re-record with `THEBOX_RECORD_GOLDEN=1 ./gradlew :ui:testDebugUnitTest`; a
 * failing run writes the actual image to `ui/build/golden/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChannelEditorGoldenTest {
    @get:Rule val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun tearDown() = scope.cancel()

    private fun render(): Bitmap {
        val repo = FakeEditorRepository.sample()
        val texts = ResourceEditorTexts(context)
        val vm = ChannelEditorViewModel(
            ChannelEditorMachineImpl.FACTORY, repo, FakeParentalSession(), texts, EditorWriteRunner(repo, scope, Dispatchers.Unconfined),
            store = null, scope = scope, main = Dispatchers.Main, io = Dispatchers.Unconfined, nowMs = { 10_000L },
        )
        rule.setContent {
            BoxTheme {
                Box(Modifier.fillMaxSize().background(SettingsUi.Canvas)) { ChannelEditorHost(vm, SettingsRoute.ChannelEditor(1L, "ent")) }
            }
        }
        rule.waitForIdle()
        for (a in listOf(BoxAction.SELECT, BoxAction.DOWN, BoxAction.DOWN, BoxAction.SELECT)) {
            rule.runOnIdle { vm.act(GuideEvent.Act(a)) }
            rule.waitForIdle()
        }
        return rule.onRoot().captureToImage().asAndroidBitmap()
    }

    @Test
    fun editorFrameMatchesGolden() {
        val actual = render()
        assertEquals(1920, actual.width)
        assertEquals(1080, actual.height)
        val record = System.getenv("THEBOX_RECORD_GOLDEN") == "1"
        val golden = javaClass.getResourceAsStream("/golden/$NAME")?.use { BitmapFactory.decodeStream(it) }
        if (record || golden == null) {
            val dir = File(if (record) "src/test/resources/golden" else "build/golden").apply { mkdirs() }
            File(dir, NAME).outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!record) fail("no golden image; wrote ${File(dir, NAME).absolutePath} (re-run with THEBOX_RECORD_GOLDEN=1 to accept)")
            return
        }
        assertTrue(golden.width == actual.width && golden.height == actual.height)
        val differing = differing(actual, golden)
        val allowed = actual.width * actual.height / 2000
        if (differing > allowed) {
            val dir = File("build/golden").apply { mkdirs() }
            File(dir, NAME).outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fail("$differing pixels differ (allowed $allowed); actual written to ${File(dir, NAME).absolutePath}")
        }
    }

    @Test
    fun pixelsLandWhereTheGeometrySays() {
        val bmp = render()
        // Above the panes and between them: the opaque settings canvas.
        assertEquals(SettingsUi.Canvas.toArgbInt(), bmp.getPixel(960, 120))
        assertEquals(SettingsUi.Canvas.toArgbInt(), bmp.getPixel(636, 500))
        // The focused channel row (list index 2: pane top 144 + 128 + 2 × 80) is FG-filled; probe left of its text.
        val focused = bmp.getPixel(648 + 16 + 6, 144 + 128 + 2 * 80 + 36)
        assertTrue("focused fill (${Integer.toHexString(focused)})", Color.red(focused) > 220 && Color.green(focused) > 220)
        // Its tick box (x + 20, 24 px) is accent-filled (ticked): probe its corner area, off the tick stroke.
        val tick = bmp.getPixel(648 + 16 + 20 + 3, 144 + 128 + 2 * 80 + 36 - 9)
        assertTrue("tick fill (${Integer.toHexString(tick)})", Color.blue(tick) > 180 && Color.red(tick) < 150)
    }

    private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int = android.graphics.Color.argb(
        (alpha * 255).toInt(), (red * 255 + 0.5f).toInt(), (green * 255 + 0.5f).toInt(), (blue * 255 + 0.5f).toInt(),
    )

    private fun differing(actual: Bitmap, golden: Bitmap): Int {
        var n = 0
        for (y in 0 until actual.height) for (x in 0 until actual.width) {
            val a = actual.getPixel(x, y); val g = golden.getPixel(x, y)
            if (abs(Color.red(a) - Color.red(g)) > TOLERANCE || abs(Color.green(a) - Color.green(g)) > TOLERANCE ||
                abs(Color.blue(a) - Color.blue(g)) > TOLERANCE || abs(Color.alpha(a) - Color.alpha(g)) > TOLERANCE
            ) n++
        }
        return n
    }

    private companion object {
        const val NAME = "channel-editor-1080p.png"
        const val TOLERANCE = 24
    }
}
