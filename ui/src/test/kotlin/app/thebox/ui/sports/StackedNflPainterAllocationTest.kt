package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.NflTimeoutMark
import app.thebox.core.sports.FieldZone
import app.thebox.core.sports.NflBarKey
import app.thebox.core.sports.NflBarKind
import app.thebox.core.sports.NflScore
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.StackedNflTrack
import app.thebox.core.sports.StackedNflTransition
import app.thebox.core.sports.TabTail
import app.thebox.ui.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.management.ManagementFactory

/**
 * NFL Stacked §4.5 / §9.2 `StackedNflPainterAllocationTest` (WP-3; GA §2.5): [StackedNflPainter.drawFrame] (shadow, a
 * REKEY in flight with the flag's shake and wave, a red-zone bar, rows with logos, balls, a burning pip, the clock column,
 * and the attached takeover with its glow, the row flash and the pop) and the score painter's own draws allocate nothing
 * per frame. Measured as `StackedPainterAllocationTest` (the thread's allocated bytes over 300 frames, least of two passes
 * after warm-ups). Real inputs (integration pass): the views' `StackedNflTargets.of`, `StackedNflPainter.frame` / `fillBar`,
 * `NflStackedColours`, frames sampled from `StackedNflTimeline`, the takeover under `NflScoreSpec.STACKED`; the takeover,
 * glow and shadow handed in through [StackedNflDrawArgs] as the runner does. The runner's whole draw (its sampling, the
 * args fill and this) is measured by `StackedNflEndToEndTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StackedNflPainterAllocationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val res = context.resources
    private val bold = sansTypeface(context, R.font.instrument_sans_bold)
    private val italic = StackedPainter.barTypeface(context)
    private val barlow = sansTypeface(context, R.font.barlow_condensed_black_italic)
    private val kit = StackedNflTestKit

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

    @Test
    fun drawFrameAllocatesNothing() {
        for (large in listOf(false, true)) {
            val s = if (large) K.SCORE_LARGE_SCALE else 1f
            val m = if (large) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN
            val bmp = Bitmap.createBitmap(Math.round(K.STACKED_NFL_W_MAX * s + 2 * m), Math.round((K.STACKED_H + K.STACKED_BAR_H) * s + 2 * m), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp).apply { translate(m + 90f * s, m) }
            val p = StackedNflPainter(s, large, bold, italic)
            val shadow = p.bakeShadow()
            val sp = StackedNflScorePainter(s, large, barlow, bold, kit.SPEC)
            val pair = kit.pair("KC", "BUF")
            val logo = kit.logo("KC", Math.round(58.5f * s))
            sp.begin(Side.AWAY, listOf(NflScore(NflScoreKind.TOUCHDOWN, 6, 30, 17)), pair.away.goal, logo, "KC", "BUF", K.STACKED_NFL_W_MAX)
            val glow = sp.bakeGlow(K.STACKED_NFL_W_MAX, K.STACKED_H + K.STACKED_BAR_H)
            val flag = NflBarContent().also { p.fillBar(it, NflBarKey(NflBarKind.FLAG, null), pair, "KC", "BUF", res) }
            val red = NflBarContent().also {
                p.fillBar(it, NflBarKey(NflBarKind.PLAY, Side.HOME, down = app.thebox.core.sports.DownText.Down(1, 10, false), tail = TabTail.RedZone), pair, "KC", "BUF", res)
            }
            val play = NflBarContent().also {
                p.fillBar(it, NflBarKey(NflBarKind.PLAY, Side.AWAY, down = app.thebox.core.sports.DownText.Down(1, 10, false), tail = TabTail.Field(FieldZone.OWN, 35)), pair, "KC", "BUF", res)
            }
            val timeout = kit.view(homeTimeouts = 1, timeout = NflTimeoutMark(Side.HOME, 1, confirmed = true))
            val tg = kit.targets(timeout)
            val open = kit.rest(tg)
            val frames = Array(300) { i ->
                // A REKEY in flight, the clock column folding, the burn and the flag's shake and wave, all from the timeline.
                val f = kit.rest(tg)
                kit.at(f, StackedNflTrack.BAR, StackedNflTransition.REKEY, i * 2f, open)
                kit.at(f, StackedNflTrack.CLOCK, StackedNflTransition.CLOSE, i * 1.5f, open)
                kit.burn(f, Side.HOME, 2, 1, i * 8f)
                kit.flag(f, i * 16f)
            }
            val states = arrayOf(kit.state(p, timeout, res), kit.state(p, kit.staleInPlay(), res))
            val scores = Array(300) { kit.score(it * 16f, NflScoreKind.TOUCHDOWN) }
            val a = StackedNflDrawArgs().apply { colours = pair; awayLogo = logo; homeLogo = kit.logo("BUF", Math.round(58.5f * s)); this.shadow = shadow }
            for (mirrored in listOf(false, true)) for (up in listOf(false, true)) {
                a.mirrored = mirrored
                a.up = up
                a.takeover = null; a.glow = null; a.glowOn = false
                val plain = allocated {
                    for (i in frames.indices) {
                        a.state = states[i % 2]; a.frame = frames[i]
                        a.bar = if (i % 3 == 0) red else flag; a.barPrev = play
                        a.score = null; a.takeoverOn = false
                        p.drawFrame(canvas, a)
                    }
                }
                a.takeover = sp; a.glow = glow; a.glowOn = true
                val takeover = allocated {
                    for (i in frames.indices) {
                        a.state = states[0]; a.frame = frames[i]; a.bar = play; a.barPrev = null
                        a.score = scores[i]; a.scoreSide = Side.AWAY; a.takeoverOn = true
                        p.drawFrame(canvas, a)
                    }
                }
                println("StackedNflPainter.drawFrame × 300 ${if (large) "Large" else "Std"} m=$mirrored up=$up: plain $plain B, takeover $takeover B")
                assertTrue("plain: $plain B", plain < LIMIT)
                assertTrue("takeover: $takeover B", takeover < LIMIT)
            }
        }
    }

    private companion object {
        /** The pill's own bound for 300 frames (GA §8.3), best effort under Robolectric. */
        const val LIMIT = 4_096L
    }
}
