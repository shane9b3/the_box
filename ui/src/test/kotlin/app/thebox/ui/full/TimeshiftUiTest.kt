package app.thebox.ui.full

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.full.PausedHeader
import app.thebox.core.full.PillKind
import app.thebox.core.full.PillState
import app.thebox.core.full.ScrubFocus
import app.thebox.core.full.ScrubScope
import app.thebox.core.full.ScrubState
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.playback.timeshift.TimeRanges
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.management.ManagementFactory
import java.util.TimeZone

/**
 * The time-shift drawing (docs/design/m6-timeshift.md §11.4–§11.6, §17.3): `PillFormatTest`, `ScrubWindowTest`,
 * `PausedHeaderTest`, the hint row, and the paused card's dim layer and drift positions. 1920 × 1080 window, so 1 design
 * px = 1 px.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class TimeshiftUiTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private val ch = GuideFixtures.channel(1, archiveDays = 0)
    private val now = PlayerOverlayStates.NOW_MS

    // ---------------------------------------------------------------- PillFormatTest (§11.4, U-S7)

    @Test
    fun pillFormat() {
        assertEquals("4:12", PlayerText.behindText(252_000))
        assertEquals("1:04:12", PlayerText.behindText(3_852_000))
        assertEquals("0:05", PlayerText.behindText(5_400))
        assertEquals("PAUSED · 4:12 behind", PlayerText.pillTime(res, PillKind.PAUSED, 252_000))
        assertEquals("4:12 BEHIND", PlayerText.pillTime(res, PillKind.BEHIND, 252_000))
        assertEquals("1:04:12 BEHIND", PlayerText.pillTime(res, PillKind.BEHIND, 3_852_000))
        assertNull("a plain pause stays PAUSED", PlayerText.pillTime(res, PillKind.PAUSED, 0))
        val buf = CharArray(16)
        assertEquals("4:12", String(buf, 0, pillTime(buf, 252_000)))
        assertEquals("12:00", String(buf, 0, pillTime(buf, 720_000)))
        assertEquals("1:04:12", String(buf, 0, pillTime(buf, 3_852_000)))
        assertEquals("0:00", String(buf, 0, pillTime(buf, -1)))
    }

    // ---------------------------------------------------------------- PausedHeaderTest (§11.5)

    @Test
    fun pausedHeaderLines() {
        val f = PausedFacts(pauseRemainingMs = 17 * 60_000L + 59_000, windowMs = 30 * 60_000L, holdMs = 10 * 60_000L)
        assertEquals("Can stay paused 17 more min", PlayerText.pausedHeader(res, PausedHeader.SAVING, f))
        assertEquals("Saving stopped: rewind holds 30 min. Press OK to continue from where you paused.", PlayerText.pausedHeader(res, PausedHeader.FULL, f))
        assertEquals("Saving stopped after 10 min to free your provider connection. Press OK to continue from where you paused.", PlayerText.pausedHeader(res, PausedHeader.HOLD_LIMIT, f))
        assertEquals("Saving stopped: another device is using your provider connection. Press OK to continue from where you paused.", PlayerText.pausedHeader(res, PausedHeader.SLOT_LOST, f))
        assertEquals("Can stay paused up to 3 h: catch-up covers the rest.", PlayerText.pausedHeader(res, PausedHeader.CATCHUP_COVERS, f))
        val media = f.copy(mediaKeys = true)
        assertEquals("Saving stopped: another device is using your provider connection. Press OK or ▶ to continue from where you paused.", PlayerText.pausedHeader(res, PausedHeader.SLOT_LOST, media))
    }

    // ---------------------------------------------------------------- ScrubWindowTest (§11.4, U-S3)

    internal class RecordingCanvas : Canvas() {
        val ops = ArrayList<String>()
        val texts = ArrayList<String>()
        /** (Fix pass S11) Each text with its size and its horizontal extent. */
        class Drawn(val text: String, val size: Float, val left: Float, val right: Float)
        val drawn = ArrayList<Drawn>()
        private fun extent(text: String, x: Float, paint: Paint) {
            if (!record) return
            val w = paint.measureText(text)
            val left = when (paint.textAlign) { Paint.Align.RIGHT -> x - w; Paint.Align.CENTER -> x - w / 2f; else -> x }
            drawn += Drawn(text, paint.textSize, left, left + w)
        }
        var record = true
        private fun op(name: String) { if (record) ops += name }
        override fun drawRect(rect: RectF, paint: Paint) = op("rect")
        override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) = op("roundRect")
        override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) = op("circle")
        override fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) {
            op("text")
            if (record) { texts += String(text, index, count); extent(String(text, index, count), x, paint) }
        }
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            op("text")
            if (record) { texts += text; extent(text, x, paint) }
        }
        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            op("text")
            if (record) { texts += text.substring(start, end); extent(text.substring(start, end), x, paint) }
        }
    }

    private fun renderer() = ScrubBarRenderer(1f).apply {
        zone = TimeZone.getTimeZone("UTC")
        clock24 = true
        setLabels("Catch-up", "Live")
        setWindowLabels("Rewind from %1\$s", "LIVE", "Not saved")
    }

    /** A paused LOCAL bar without catch-up: 30 min window, 20 min saved, [gaps] not saved. */
    private fun window(gaps: TimeRanges = TimeRanges.EMPTY): ScrubState {
        val p = PlayerOverlayStates.scrub(ch)
        return p.copy(
            scope = ScrubScope.REWIND_WINDOW, barFromMs = now - 1_800_000, barToMs = now, windowFromMs = now - 1_800_000,
            savedFromMs = now - 1_200_000, archiveFromMs = now - 1_200_000, liveEdgeMs = now, playheadMs = now - 600_000, atMs = now,
            advancing = false, notSaved = gaps, pill = PillState(PillKind.PAUSED, behindMs = 600_000, atMs = now, behindGrows = true),
            programme = null, focus = ScrubFocus.BAR,
        )
    }

    @Test
    fun rewindWindowLabelsAndNotSaved() {
        val r = renderer()
        val c = RecordingCanvas().also { r.draw(it, window(), now, 1792f, 80f) }
        fun hm(ms: Long) = String.format(java.util.Locale.ROOT, "%02d:%02d", (ms / 3_600_000) % 24, (ms / 60_000) % 60)
        assertTrue(c.texts.toString(), "Rewind from ${hm(now - 1_200_000)}" in c.texts)
        assertTrue("LIVE" in c.texts)
        assertTrue("the live time above the marker", hm(now) in c.texts)
        assertFalse("Not saved" in c.texts)
        // 1664 px span 30 min: 120 px ≈ 130 s. A 3 min gap is labelled, a 1 min gap only underlined.
        val wide = RecordingCanvas().also { r.draw(it, window(TimeRanges(longArrayOf(now - 900_000, now - 720_000))), now, 1792f, 80f) }
        assertEquals(1, wide.texts.count { it == "Not saved" })
        val narrow = RecordingCanvas().also { r.draw(it, window(TimeRanges(longArrayOf(now - 900_000, now - 840_000))), now, 1792f, 80f) }
        assertEquals(0, narrow.texts.count { it == "Not saved" })
        assertTrue("the gap still draws its underline", narrow.ops.count { it == "rect" } > c.ops.count { it == "rect" })
    }

    @Test
    fun rewindWindowMovesWithTheEdgeAndAllocatesNothing() {
        val r = renderer()
        val s = window(TimeRanges(longArrayOf(now - 900_000, now - 720_000)))
        val c = RecordingCanvas().apply { record = false }
        repeat(2_000) { r.draw(c, s, now + it, 1792f, 80f) }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val perDraw = (0 until 3).minOf { _ ->
            val before = bean.getThreadAllocatedBytes(id)
            repeat(1_000) { r.draw(c, s, now + it * 1_000L, 1792f, 80f) }
            (bean.getThreadAllocatedBytes(id) - before) / 1_000.0
        }
        assertTrue("allocated $perDraw bytes per draw", perDraw < 16.0)
    }

    // ---------------------------------------------------------------- the bar's header line and hint row (§11.5, §11.6)

    @Test
    fun headerLineAndHintRow() {
        val state = window().copy(header = PausedHeader.SAVING, hint = true, focused = true)
        rule.setContent { BoxTheme { Box(Modifier.size(1920.dp, 1080.dp)) { ScrubBar(state, now, Modifier, ch, true, PausedFacts(pauseRemainingMs = 17 * 60_000L)) } } }
        rule.waitForIdle()
        val header = rule.onNodeWithTag(SCRUB_HEADER_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val bar = rule.onNodeWithTag(SCRUB_BAR_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("$header inside $bar", header.top >= bar.top && header.bottom <= bar.bottom)
        rule.onAllNodesWithTag(SCRUB_HINT_TAG, useUnmergedTree = true).assertCountEquals(1)
    }

    // ---------------------------------------------------------------- the paused card (§11.5)

    /**
     * Fix pass S4: the longest header lines (HOLD_LIMIT, SLOT_LOST, with "Press OK or ▶") fit the card's body without an
     * ellipsis at 1920 × 1080, so the next step is never cut, and the card stays inside its height budget.
     */
    @Test
    fun theCardShowsTheWholeHeaderLineWithItsNextStep() {
        val header = androidx.compose.runtime.mutableStateOf(PausedHeader.HOLD_LIMIT)
        val facts = PausedFacts(dimmed = true, dimSinceMs = System.currentTimeMillis(), mediaKeys = true, holdMs = 10 * 60_000L, windowMs = 30 * 60_000L)
        rule.setContent {
            BoxTheme {
                Box(Modifier.size(1920.dp, 1080.dp)) {
                    PausedCard(PillState(PillKind.PAUSED, behindMs = 760_000, atMs = System.currentTimeMillis(), behindGrows = true), header.value, facts)
                }
            }
        }
        for (h in listOf(PausedHeader.HOLD_LIMIT, PausedHeader.SLOT_LOST, PausedHeader.FULL)) {
            header.value = h
            rule.waitForIdle()
            val node = rule.onNodeWithTag(PAUSED_CARD_BODY_TAG, useUnmergedTree = true).fetchSemanticsNode()
            val layouts = ArrayList<androidx.compose.ui.text.TextLayoutResult>()
            node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val l = layouts.single()
            assertTrue("$h: ${l.layoutInput.text}", l.layoutInput.text.text.endsWith("to continue from where you paused."))
            assertFalse("$h: no ellipsis (${l.lineCount} lines)", l.hasVisualOverflow)
            assertTrue("$h: ${l.lineCount} lines", l.lineCount <= CARD_BODY_LINES)
            val card = rule.onNodeWithTag(PAUSED_CARD_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$h: card height ${card.height} within $CARD_H", card.height <= CARD_H)
        }
    }

    @Test
    fun pausedCardDrawsOnlyWhenDimmedAndDriftsInsideTheSafeArea() {
        val dimmed = androidx.compose.runtime.mutableStateOf(PausedFacts())
        rule.setContent {
            BoxTheme {
                Box(Modifier.size(1920.dp, 1080.dp)) {
                    PausedCard(PillState(PillKind.PAUSED, behindMs = 760_000, atMs = System.currentTimeMillis(), behindGrows = true), PausedHeader.SAVING, dimmed.value)
                }
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithTag(PAUSED_DIM_TAG, useUnmergedTree = true).assertCountEquals(0)
        dimmed.value = PausedFacts(dimmed = true, dimSinceMs = System.currentTimeMillis(), pauseRemainingMs = 17 * 60_000L)
        rule.waitForIdle()
        assertEquals(Rect(0f, 0f, 1920f, 1080f), rule.onNodeWithTag(PAUSED_DIM_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        val card = rule.onNodeWithTag(PAUSED_CARD_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("$card in the safe area", card.left >= SAFE_X && card.top >= SAFE_Y && card.right <= 1920f - SAFE_X && card.bottom <= 1080f - SAFE_Y)
        // Every drift position keeps the card's budget inside the safe area, and the card moves every minute.
        for (k in 0 until CARD_POSITIONS.size * 2) {
            val (x, y) = cardPosition(k * 60_000L + 30_000L)
            assertTrue(x >= SAFE_X && y >= SAFE_Y && x + CARD_W <= 1920f - SAFE_X && y + CARD_H <= 1080f - SAFE_Y)
            if (k > 0) assertTrue("moves at minute $k", cardPosition(k * 60_000L) != cardPosition((k - 1) * 60_000L))
        }
    }
}
