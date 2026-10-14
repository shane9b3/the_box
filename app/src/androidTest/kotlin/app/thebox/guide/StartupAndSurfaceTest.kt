package app.thebox.guide

import android.os.SystemClock
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.MainActivity
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.Overlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** I-1 startup, I-2 surface rule, I-3 hole (docs/design/m3-guide.md §5.5, §3.13). */
@RunWith(AndroidJUnit4::class)
class StartupAndSurfaceTest : GuideTestBase() {

    /** I-1: lineup shown, last channel tuned in the mini player, focus on its row at now within 3 s of first frame. */
    @Test
    fun startupShowsLastChannelAtNow() {
        // Make a "last channel": tune row 3 through the guide, then close the activity.
        launchGuide()
        awaitGuideReady()
        toRow(3)
        val last = tuneFocusedRow()
        stopPlayback()
        val t0 = SystemClock.elapsedRealtime()
        relaunchGuide()
        val firstFrame = SystemClock.elapsedRealtime()
        awaitUi("last channel tuned in the mini player", 3_000) { it.tuned.identity == last.identity && it.tuned.phase != MiniPhase.IDLE }
        awaitFrame("focus on the last channel's row at now", 3_000) { f ->
            val fo = f.focus
            fo != null && f.rows.channel(fo.row)?.identity == last.identity && nowMin() in fo.cellStart until fo.cellEnd
        }
        log("I-1: machine ${firstFrame - t0} ms after launch, startup state ${SystemClock.elapsedRealtime() - firstFrame} ms after it")
        assertTrue("startup within 3 s of the guide's first frame", SystemClock.elapsedRealtime() - firstFrame <= 3_000)
    }

    /** I-2: one SurfaceView; no ancestor with alpha < 1, a layer type, or clipToOutline. */
    @Test
    fun surfaceRule() {
        launchGuide()
        awaitGuideReady()
        toRow(0)
        tuneFocusedRow()
        val problems = main {
            val out = mutableListOf<String>()
            var activity: MainActivity? = null
            scenario!!.onActivity { activity = it }
            val root = activity!!.window.decorView
            val surfaces = mutableListOf<SurfaceView>()
            fun walk(v: View) {
                if (v is SurfaceView) surfaces += v
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
            if (surfaces.size != 1) out += "SurfaceViews in the window: ${surfaces.size}"
            surfaces.firstOrNull()?.let { s ->
                var p = s.parent
                while (p is View) {
                    if (p.alpha < 1f) out += "${p.javaClass.simpleName} alpha=${p.alpha}"
                    if (p.layerType != View.LAYER_TYPE_NONE) out += "${p.javaClass.simpleName} layerType=${p.layerType}"
                    if (p.clipToOutline) out += "${p.javaClass.simpleName} clipToOutline"
                    p = p.parent
                }
            }
            out
        }
        // TODO(integrator, WP-C): "no Compose graphicsLayer above VideoLayer" needs a semantics/layer dump; ui-test is not
        // on the androidTest classpath, so only the View-level part is asserted here (D1 on hardware covers the rest).
        assertTrue("surface rule: $problems", problems.isEmpty())
    }

    /** I-3: hole centre shows video, corner probes and ring are chrome, overlays keep the hole clear. */
    @Test
    fun holePunch() {
        launchGuide()
        awaitGuideReady()
        toRow(0)
        tuneFocusedRow()
        Thread.sleep(1_500) // a few frames of video
        val hole = holeRect()
        val shot = screenshot("i3-hole")
        val centre = colourfulness(shot, hole)
        log("I-3 hole=${hole.toList()} centre colourfulness=$centre")
        assertTrue("hole centre shows the colour-bar video (colourfulness $centre)", centre > 20.0)
        val video = shot.getPixel((hole[0] + hole[2]) / 2, (hole[1] + hole[3]) / 2)
        val corners = listOf(hole[0] + 1 to hole[1] + 1, hole[2] - 2 to hole[1] + 1, hole[0] + 1 to hole[3] - 2, hole[2] - 2 to hole[3] - 2)
        for ((x, y) in corners) {
            val c = shot.getPixel(x, y)
            assertTrue("corner probe ($x,$y) is chrome, not video: ${Integer.toHexString(c)}", c != video && lum(c) < 200)
        }
        val midX = (hole[0] + hole[2]) / 2
        val ring = shot.getPixel(midX, hole[1] - 1)
        val ambient = shot.getPixel(midX, (hole[1] - 12).coerceAtLeast(0))
        assertTrue("ring brighter than the ambient (${lum(ring)} vs ${lum(ambient)})", lum(ring) > lum(ambient))

        // Overlays must not cover the hole: open each reachable one and check the hole still shows video.
        val opened = mutableListOf<String>()
        fun checkOverlay(name: String, open: () -> Unit) {
            goHome()
            open()
            Thread.sleep(400)
            val o = ui().overlay
            opened += "$name=${o?.javaClass?.simpleName}"
            val c = colourfulness(screenshot("i3-$name"), hole)
            assertTrue("$name overlay keeps the hole clear (colourfulness $c)", c > 20.0)
        }
        checkOverlay("groups") { press(KeyEvent.KEYCODE_DPAD_UP); press(KeyEvent.KEYCODE_DPAD_CENTER) }
        checkOverlay("days") { press(KeyEvent.KEYCODE_DPAD_UP); press(KeyEvent.KEYCODE_DPAD_RIGHT); press(KeyEvent.KEYCODE_DPAD_CENTER) }
        checkOverlay("menu") { longPress(KeyEvent.KEYCODE_DPAD_CENTER) }
        checkOverlay("number") { press(KeyEvent.KEYCODE_5) }
        checkOverlay("sheet") {
            press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) // a future programme
            press(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        log("I-3 overlays checked: $opened")
        assertTrue("Groups overlay opened", opened.any { it.startsWith("groups=Groups") })
        // TODO(integrator): Exit dialog, reminder card and toasts are checked by bounds in the WP-C hand-off screenshots;
        // their exact Compose bounds are not reachable without a semantics dump.
        goHome()
        assertEquals(null, ui().overlay as Overlay?)
    }

}
