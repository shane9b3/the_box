package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.BarKind
import app.thebox.core.sports.GoalFrame
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.ui.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.management.ManagementFactory

/**
 * Stacked §6 / §10.2 `StackedPainterAllocationTest` (WP-2; GA §2.5, §8.3): [StackedPainter.drawFrame] (the whole
 * composite: shadow, bar with a change and a swap in flight, rows with logos, SOG and clock columns, the takeover with its
 * glow, the row flash and the pop) and [StackedGoalPainter]'s [StackedGoalPainter.drawGlow] /
 * [StackedGoalPainter.drawTakeover] allocate nothing per frame. Measured with the thread's allocated-bytes counter over
 * 300 frames after two warm-up passes (best effort under Robolectric's native canvas, so the bound is a few KB, as the
 * pill's own test has it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StackedPainterAllocationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val res = context.resources
    private val bold = sansTypeface(context, R.font.instrument_sans_bold)
    private val italic = StackedPainter.barTypeface(context)
    private val barlow = sansTypeface(context, R.font.barlow_condensed_black_italic)

    /**
     * Bytes [block] allocates on this thread: the least of two measured passes after three warm-ups. A per-frame
     * allocation shows in every pass; the first-ever pass of the suite can carry a one-off (class loading, a native glyph
     * cache growing under Robolectric: measured 6–7 KB once, then 0 B), which the minimum leaves out.
     */
    private fun allocated(block: () -> Unit): Long {
        repeat(3) { block() }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        var least = Long.MAX_VALUE
        repeat(2) {
            val before = bean.getThreadAllocatedBytes(id)
            block()
            least = minOf(least, bean.getThreadAllocatedBytes(id) - before)
        }
        return least
    }

    private class Rig(val s: Float, large: Boolean, val painter: StackedPainter, val goal: StackedGoalPainter) {
        val m = if (large) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN
        val bmp: Bitmap = Bitmap.createBitmap(Math.round(K.STACKED_W_MAX * s + 2 * m), Math.round((K.STACKED_H + K.STACKED_BAR_H) * s + 2 * m), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp).apply { translate(m, m) }
    }

    private fun rig(large: Boolean): Rig {
        val s = if (large) K.SCORE_LARGE_SCALE else 1f
        return Rig(
            s, large, StackedPainter(s, large, bold, italic),
            StackedGoalPainter(s, large, barlow, bold, res.getString(R.string.sports_goal_word), StackedFixtures.SPEC),
        )
    }

    private fun logos(px: Int): FollowLogos =
        FollowLogos({ _, w, h -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) } }, CoroutineScope(Dispatchers.Unconfined))
            .apply { start("EDM", "TOR", px, px) }

    @Test
    fun drawFrameAllocatesNothingThroughLayoutTransitionsAndATakeover() {
        for (large in listOf(false, true)) {
            val r = rig(large)
            val p = r.painter
            val pair = StackedFixtures.EDM_TOR
            val held = logos(Math.round(K.STACKED_GOAL_LOGO * r.s))
            val cur = BarContent().apply { kind = BarKind.INTERMISSION; period = PeriodLabel(PeriodKind.REG, 2); p.fillBar(this, pair, "EDM", "TOR", res) }
            val prev = BarContent().apply { kind = BarKind.POWER_PLAY; side = Side.AWAY; secs = 97; p.fillBar(this, pair, "EDM", "TOR", res) }
            // 300 frames: the bar changing (prev words, then the swap), the SOG opening, the clock folding, the stripe narrowing.
            val frames = Array(300) { i ->
                val k = i / 299f
                StackedFixtures.frame(sog = k, clock = 1f - k, bar = 1f, stripeAway = 1f - k).apply {
                    barSwapped = k > 0.33f; barBgMix = k; stripeAX = -63f * (1f - k); barTextAlpha = k; barTextDx = -10.8f * (1f - k)
                }
            }
            val bodies = arrayOf(
                StackedFixtures.body(p),
                StackedFixtures.body(p, slot = app.thebox.core.sports.ClockSlot.NoUpdate, slotText = res.getString(R.string.sports_no_update), dim = true),
            )
            val goals = Array(300) { StackedFixtures.goalFrame(it * 16f, still = false) }
            val stills = Array(300) { StackedFixtures.goalFrame(it * 16f, still = true) }
            val badge = held.away.image!!.asAndroidBitmap()
            r.goal.begin(Side.AWAY, pair.away.goal, badge, "EDM", "TOR", 3, 1, frames[0].boxW)
            val masks = r.goal.bakeGlow(frames[0].boxW * r.s, (K.STACKED_H + K.STACKED_BAR_H) * r.s)!!
            val a = StackedDrawArgs().apply {
                colours = pair; bar = cur; barPrev = prev; logos = held; logoWaited = true
                shadow = p.bakeShadow(); goal = r.goal; glow = masks; glowOn = true
            }
            // (Owner request 2026-11-13) And mirrored, as a right-hand corner draws it.
            for (up in listOf(false, true)) for (mirrored in listOf(false, true)) {
                a.up = up
                a.mirrored = mirrored
                val plain = allocated {
                    for (i in frames.indices) { a.body = bodies[i % 2]; a.f = frames[i]; a.g = null; p.drawFrame(r.canvas, a) }
                }
                val takeover = allocated {
                    for (i in frames.indices) { a.body = bodies[0]; a.f = frames[i]; a.g = goals[i]; a.still = false; p.drawFrame(r.canvas, a) }
                }
                val still = allocated {
                    for (i in frames.indices) { a.body = bodies[0]; a.f = frames[i]; a.g = stills[i]; a.still = true; p.drawFrame(r.canvas, a) }
                }
                println("StackedPainter.drawFrame × 300 ${if (large) "Large" else "Standard"} up=$up mirrored=$mirrored: plain $plain B, takeover $takeover B, still $still B")
                assertTrue("plain: allocated $plain B", plain < LIMIT)
                assertTrue("takeover: allocated $takeover B", takeover < LIMIT)
                assertTrue("still: allocated $still B", still < LIMIT)
            }
        }
    }

    @Test
    fun theGoalPainterDrawsAllocateNothing() {
        val r = rig(false)
        val badge = Bitmap.createBitmap(67, 67, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        r.goal.begin(Side.HOME, StackedFixtures.TOR.goal, badge, "EDM", "TOR", 2, 2, 391.5f)
        val glow = r.goal.bakeGlow(391.5f, 141f)!!
        val frames = Array(300) { StackedFixtures.goalFrame(it * 16f, still = false, GoalFrame()) }
        for (mirrored in listOf(false, true)) {
            val bytes = allocated {
                for (g in frames) {
                    r.goal.extent(0f, 141f)
                    r.goal.drawGlow(r.canvas, g, glow, 0f, 0f)
                    r.goal.drawTakeover(r.canvas, g, mirrored)
                }
            }
            println("StackedGoalPainter.drawGlow + drawTakeover × 300 mirrored=$mirrored: $bytes B")
            assertTrue("mirrored=$mirrored: allocated $bytes B", bytes < LIMIT)
        }
    }

    @Test
    fun theNineSliceDrawAllocatesNothing() {
        val r = rig(true)
        val shadow = r.painter.bakeShadow()!!
        val paint = Paint().apply { color = Color.BLACK; alpha = 140 }
        val bytes = allocated {
            for (i in 0 until 300) shadow.draw(r.canvas, 0f, 0f, (300f + i % 90) * r.s, (108f + i % 33) * r.s, paint)
        }
        println("NineSlice.draw × 300: $bytes B")
        assertTrue("allocated $bytes B", bytes < LIMIT)
    }

    private companion object {
        /** The pill's own bound for 300 frames (GA §8.3), best effort under Robolectric. */
        const val LIMIT = 4_096L
    }
}
