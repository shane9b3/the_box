package app.thebox.guide

import android.view.KeyEvent.KEYCODE_0
import android.view.KeyEvent.KEYCODE_5
import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_CHANNEL_DOWN
import android.view.KeyEvent.KEYCODE_CHANNEL_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
import android.view.KeyEvent.KEYCODE_MEDIA_REWIND
import android.view.KeyEvent.KEYCODE_MENU
import android.view.KeyEvent.KEYCODE_PAGE_DOWN
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.guide.GridFocus
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.HeaderChip
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.Zone
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-4: 30 scripted key sequences through the real key path (KeyEvent → KeyRouter → GuideStateMachine) end in the
 * states the §3.4 tables give (the same tables the JVM `GuideStateMachine` tests are generated from).
 *
 * Every sequence starts at GUIDE/GRID, no overlay, nothing tuned (so the back ladder's "something is tuned" step is
 * out of the way), on row [start] at its on-now cell. Row 0 is synthetic channel 1 (7-day archive); [plainRow] is the
 * first row whose channel has no archive. Expectations are written against `GuideUiState` (mode/zone/overlay/header)
 * and `GuideFrame.focus` only.
 */
@RunWith(AndroidJUnit4::class)
class KeysOnDeviceTest : GuideTestBase() {

    private class Ctx(val ui: GuideUiState, val focus: GridFocus?, val start: GridFocus, val now: Int)

    private class Seq(val name: String, val fromPlainRow: Boolean = false, val keys: GuideTestBase.() -> Unit, val expect: Ctx.() -> String?)

    private fun ok(cond: Boolean, msg: () -> String): String? = if (cond) null else msg()

    private val sequences = listOf(
        Seq("DOWN → next row", keys = { press(KEYCODE_DPAD_DOWN) }) { ok(ui.zone == Zone.GRID && focus?.row == start.row + 1) { "row ${focus?.row}" } },
        Seq("DOWN DOWN UP", keys = { press(KEYCODE_DPAD_DOWN, 2); press(KEYCODE_DPAD_UP) }) { ok(focus?.row == start.row + 1) { "row ${focus?.row}" } },
        Seq("UP on row 0 → HEADER/GROUP", keys = { press(KEYCODE_DPAD_UP) }) {
            ok(ui.zone == Zone.HEADER && ui.headerChips.getOrNull(ui.headerFocused) == HeaderChip.GROUP) { "zone ${ui.zone} chip ${ui.headerFocused}" }
        },
        Seq("UP UP → PANEL", keys = { press(KEYCODE_DPAD_UP, 2) }) { ok(ui.zone == Zone.PANEL) { "zone ${ui.zone}" } },
        Seq("UP×3 → TABS", keys = { press(KEYCODE_DPAD_UP, 3) }) { ok(ui.zone == Zone.TABS) { "zone ${ui.zone}" } },
        Seq("UP×3 DOWN → PANEL", keys = { press(KEYCODE_DPAD_UP, 3); press(KEYCODE_DPAD_DOWN) }) { ok(ui.zone == Zone.PANEL) { "zone ${ui.zone}" } },
        Seq("UP×3 OK on Guide tab → GRID", keys = { press(KEYCODE_DPAD_UP, 3); press(KEYCODE_DPAD_CENTER) }) {
            ok(ui.zone == Zone.GRID && ui.mode == ScreenMode.GUIDE && focus?.row == start.row) { "zone ${ui.zone} mode ${ui.mode}" }
        },
        Seq("UP DOWN → GRID, focus kept", keys = { press(KEYCODE_DPAD_UP); press(KEYCODE_DPAD_DOWN) }) {
            ok(ui.zone == Zone.GRID && focus == start.copy(windowStartMin = focus!!.windowStartMin)) { "zone ${ui.zone} focus $focus" }
        },
        Seq("UP BACK → GRID", keys = { press(KEYCODE_DPAD_UP); press(KEYCODE_BACK) }) { ok(ui.zone == Zone.GRID && ui.overlay == null) { "zone ${ui.zone}" } },
        Seq("Group chip OK → Groups", keys = { press(KEYCODE_DPAD_UP); press(KEYCODE_DPAD_CENTER) }) { ok(ui.overlay is Overlay.Groups) { "overlay ${ui.overlay}" } },
        Seq("Groups BACK closes", keys = { press(KEYCODE_DPAD_UP); press(KEYCODE_DPAD_CENTER); press(KEYCODE_BACK) }) {
            ok(ui.overlay == null && ui.zone == Zone.HEADER) { "overlay ${ui.overlay} zone ${ui.zone}" }
        },
        Seq("Day chip OK → Days", keys = { press(KEYCODE_DPAD_UP); press(KEYCODE_DPAD_RIGHT); press(KEYCODE_DPAD_CENTER) }) { ok(ui.overlay is Overlay.Days) { "overlay ${ui.overlay}" } },
        Seq("Days BACK closes", keys = { press(KEYCODE_DPAD_UP); press(KEYCODE_DPAD_RIGHT); press(KEYCODE_DPAD_CENTER); press(KEYCODE_BACK) }) { ok(ui.overlay == null) { "overlay ${ui.overlay}" } },
        Seq("PAGE_DOWN → +6 rows", keys = { press(KEYCODE_PAGE_DOWN) }) { ok(focus?.row == start.row + 6 && focus.topRow >= 6) { "focus $focus" } },
        // CH− = ZAP_DOWN = PAGE_DOWN in the grid (§3.4.2, §3.18.2); CH+ = ZAP_UP = PAGE_UP.
        Seq("CH− CH− → +12 rows", keys = { press(KEYCODE_CHANNEL_DOWN, 2) }) { ok(focus?.row == start.row + 12) { "row ${focus?.row}" } },
        Seq("CH− CH+ → back", keys = { press(KEYCODE_CHANNEL_DOWN); press(KEYCODE_CHANNEL_UP) }) { ok(focus?.row == start.row) { "row ${focus?.row}" } },
        Seq("RIGHT → next cell", keys = { press(KEYCODE_DPAD_RIGHT) }) { ok(focus?.row == start.row && focus.cellStart >= start.cellEnd) { "focus $focus" } },
        Seq("RIGHT LEFT → same cell", keys = { press(KEYCODE_DPAD_RIGHT); press(KEYCODE_DPAD_LEFT) }) {
            ok(focus?.cellStart == start.cellStart && focus.cellEnd == start.cellEnd) { "focus $focus start $start" }
        },
        Seq("LEFT on an archive row → earlier cell", keys = { press(KEYCODE_DPAD_LEFT) }) {
            ok(ui.overlay == null && focus != null && focus.cellEnd <= start.cellStart) { "overlay ${ui.overlay} focus $focus" }
        },
        Seq("LEFT on a row without archive → Groups", fromPlainRow = true, keys = { press(KEYCODE_DPAD_LEFT) }) { ok(ui.overlay is Overlay.Groups) { "overlay ${ui.overlay}" } },
        Seq("Groups CH− applies the next entry, stays open", fromPlainRow = true, keys = { press(KEYCODE_DPAD_LEFT); press(KEYCODE_CHANNEL_DOWN) }) {
            val g = ui.overlay as? Overlay.Groups
            ok(g != null && g.current == g.focused) { "overlay ${ui.overlay}" }
        },
        Seq("Groups RIGHT closes", fromPlainRow = true, keys = { press(KEYCODE_DPAD_LEFT); press(KEYCODE_DPAD_RIGHT) }) { ok(ui.overlay == null) { "overlay ${ui.overlay}" } },
        Seq("FF → +3 h", keys = { press(KEYCODE_MEDIA_FAST_FORWARD) }) {
            ok(focus != null && focus.anchorMin - start.anchorMin in 150..210) { "anchor ${focus?.anchorMin} vs ${start.anchorMin}" }
        },
        Seq("FF RW → back", keys = { press(KEYCODE_MEDIA_FAST_FORWARD); press(KEYCODE_MEDIA_REWIND) }) {
            ok(focus != null && kotlin.math.abs(focus.anchorMin - start.anchorMin) <= 30) { "anchor ${focus?.anchorMin} vs ${start.anchorMin}" }
        },
        Seq("long OK → Menu", keys = { longPress(KEYCODE_DPAD_CENTER) }) { ok(ui.overlay is Overlay.Menu) { "overlay ${ui.overlay}" } },
        Seq("long OK BACK → closed", keys = { longPress(KEYCODE_DPAD_CENTER); press(KEYCODE_BACK) }) { ok(ui.overlay == null && ui.mode == ScreenMode.GUIDE) { "overlay ${ui.overlay}" } },
        Seq("MENU → Menu", keys = { press(KEYCODE_MENU) }) { ok(ui.overlay is Overlay.Menu) { "overlay ${ui.overlay}" } },
        Seq("digit 5 → Number(5)", keys = { press(KEYCODE_5) }) { ok((ui.overlay as? Overlay.Number)?.digits == "5") { "overlay ${ui.overlay}" } },
        Seq("digits 1 0 BACK → closed", keys = { press(KEYCODE_0 + 1); press(KEYCODE_0); press(KEYCODE_BACK) }) { ok(ui.overlay == null) { "overlay ${ui.overlay}" } },
        Seq("BACK at home, nothing tuned → exit hint", keys = { press(KEYCODE_BACK) }) {
            ok((ui.overlay as? Overlay.Exit)?.dialog == false) { "overlay ${ui.overlay} (BACK_TWICE expected)" }
        },
        Seq("RIGHT×4 BACK → home at now", keys = { press(KEYCODE_DPAD_RIGHT, 4); press(KEYCODE_BACK) }) {
            ok(ui.overlay == null && ui.mode == ScreenMode.GUIDE && focus != null && now in focus.cellStart until focus.cellEnd && focus.row == start.row) { "focus $focus" }
        },
        Seq("DOWN×3 FF BACK → home keeps the row", keys = { press(KEYCODE_DPAD_DOWN, 3); press(KEYCODE_MEDIA_FAST_FORWARD); press(KEYCODE_BACK) }) {
            ok(focus != null && focus.row == start.row + 3 && now in focus.cellStart until focus.cellEnd) { "focus $focus" }
        },
    )

    @Test
    fun scriptedSequences() {
        launchGuide()
        awaitGuideReady()
        stopPlayback()
        val plainRow = findRow { it.archiveDays == 0 }
        val failures = mutableListOf<String>()
        for ((i, s) in sequences.withIndex()) {
            goHome()
            toRow(if (s.fromPlainRow) plainRow else 0)
            press(KEYCODE_BACK, intervalMs = 300) // not home? → home (now); at home this shows the exit hint …
            goHome() // … which goHome lets time out
            val start = focus()!!
            s.keys(this)
            Thread.sleep(500)
            val ctx = Ctx(ui(), focus(), start, nowMin())
            val err = runCatching { s.expect(ctx) }.getOrElse { "threw $it" }
            log("I-4 #${i + 1} ${s.name}: ${err ?: "ok"}")
            if (err != null) failures += "#${i + 1} ${s.name}: $err (ui=${ctx.ui.brief()})"
        }
        goHome()
        if (failures.isNotEmpty()) fail("${failures.size}/${sequences.size} sequences differ from the §3.4 tables:\n" + failures.joinToString("\n"))
    }
}
