package app.thebox.core.guide.render

import app.thebox.core.guide.CellKind
import app.thebox.core.guide.GridFocus
import app.thebox.core.guide.GuideAsset
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideColors as C
import app.thebox.core.guide.GuideFrame
import app.thebox.core.guide.GuideTextStyle
import app.thebox.core.guide.ReminderIndex
import app.thebox.core.guide.fakes.FakeGuideImage
import app.thebox.core.guide.fakes.FakeGuideImages
import app.thebox.core.guide.fakes.FakeGuideRows
import app.thebox.core.guide.fakes.FakeTextMeasurer
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.guide.fakes.GuideFixtures.Cell
import app.thebox.core.guide.fakes.RecordingGuideCanvas
import app.thebox.core.guide.fakes.RecordingGuideCanvas.Op
import app.thebox.core.guide.CellFlags
import app.thebox.core.guide.layout.GuideMetricsImpl
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** docs/design/m3-guide.md §3.6, §5.1 "GuideRendererTest" (recording canvas, fake measurer: chars × size × 0.5). */
class GuideRendererTest {
    private val now = GuideFixtures.NOW_MIN
    private val h19 = GuideFixtures.floor30(now) - 30
    private fun at(h: Int, m: Int = 0) = h19 + (h - 19) * 60 + m
    private val measurer = FakeTextMeasurer()
    private val renderer = GuideRendererImpl(measurer)
    private val canvas = RecordingGuideCanvas()
    private val m = GuideMetricsImpl(1f)

    private fun near(a: Float, b: Float, eps: Float = 0.05f) = abs(a - b) <= eps

    private fun frame(rows: List<List<Cell>>, channels: List<GuideChannel?> = List(rows.size) { GuideFixtures.channel(it) }, focusRow: Int = -1): GuideFrame {
        val f = GuideFrame()
        f.metrics = m
        f.rows = FakeGuideRows(channels)
        f.data = GuideFixtures.windowData(rows, fromMin = at(15), toMin = at(23))
        f.labels = GuideReferenceFrame.labels()
        f.nowMin = now
        f.windowStartMin = h19
        f.showLogos = false
        if (focusRow >= 0) {
            val c = rows[focusRow].first { it.start <= now && now < it.end }
            f.focus = GridFocus(focusRow, c.start, c.end, c.kind, now, h19, 0)
        }
        return f
    }

    private fun draw(f: GuideFrame, off: Float = 0f, row: Float = 0f): List<Op> {
        canvas.clear()
        renderer.draw(canvas, f, f.windowStartMin, off, row)
        return canvas.ops.toList()
    }

    private fun texts(ops: List<Op>) = ops.filterIsInstance<Op.Text>()

    private fun simpleRow(title: (Int) -> String = { "Show $it" }) = List(16) { k -> Cell(at(15) + 30 * k, at(15) + 30 * k + 30, title = title(k)) }

    @Test
    fun opOrderFollowsSection361() {
        val f = frame(List(8) { simpleRow() }, focusRow = 2)
        val ops = draw(f)
        // 1. Panel fill + border.
        assertEquals(Op.RoundRect(64f, 500f, 1856f, 960f, 24f, C.PANEL_FILL), ops[0])
        assertTrue(ops[1] is Op.Stroke && (ops[1] as Op.Stroke).argb == C.PANEL_BORDER)
        // 2. Save + clip to the content box.
        assertTrue(ops[2] is Op.Save)
        assertEquals(Op.Clip(81f, 501f, 1839f, 959f), ops[3])
        // 3. Ruler (own clip), contextual label first.
        assertEquals(Op.Clip(281f, 501f, 1839f, 549f), ops[5])
        val ruler = ops.subList(6, ops.indexOfFirst { it is Op.Clip && it.t == 549f })
        assertTrue(ruler.filterIsInstance<Op.Text>().map { it.text }.containsAll(listOf("19:00", "19:30", "20:00", "21:30")))
        // 4. Rows area clip, then per row: label, save/clip, cells, restore.
        val rowsClip = ops.indexOf(Op.Clip(81f, 549f, 1839f, 959f))
        assertTrue(rowsClip > 5)
        // 5. Focused cell after every row; 6. now line after it; 8. final restore.
        val shadow = ops.indexOfFirst { it is Op.Asset && it.asset == GuideAsset.FOCUS_SHADOW }
        val glow = ops.indexOfFirst { it is Op.Asset && it.asset == GuideAsset.NOW_GLOW }
        val lastRowCell = ops.indexOfLast { it is Op.RoundRect && it.argb == C.CELL_FILL }
        assertTrue(shadow > lastRowCell, "focused cell drawn after the rows")
        assertTrue(glow > shadow, "now line over the cells")
        assertTrue(ops[glow + 1] is Op.Rect && (ops[glow + 1] as Op.Rect).argb == C.ACCENT)
        assertTrue(ops[glow + 2] is Op.Circle)
        assertEquals(Op.Restore(0), ops.last())
        // Balanced saves.
        assertEquals(ops.count { it is Op.Save }, ops.count { it is Op.Restore })
    }

    @Test
    fun focusedCellIsDrawnLastWithShadowFillRing() {
        val f = frame(List(6) { simpleRow() }, focusRow = 1)
        val ops = draw(f)
        val shadow = ops.indexOfFirst { it is Op.Asset && it.asset == GuideAsset.FOCUS_SHADOW }
        val fill = ops[shadow + 1] as Op.RoundRect
        val ring = ops[shadow + 2] as Op.Stroke
        assertEquals(C.FG, fill.argb)
        assertEquals(C.ACCENT, ring.argb); assertEquals(3f, ring.width); assertEquals(13.5f, ring.radius)
        assertEquals(fill.l - 1.5f, ring.l)
        val shadowOp = ops[shadow] as Op.Asset
        assertEquals(fill.l - 30f, shadowOp.l); assertEquals(fill.t - 30f + 12f, shadowOp.t)
        // Its title is dark on light; the on-now progress bar is accent.
        assertTrue(texts(ops.subList(shadow, ops.size)).any { it.argb == C.CANVAS && it.style == GuideTextStyle.CELL_TITLE })
        assertTrue(ops.subList(shadow, ops.size).any { it is Op.Rect && it.argb == C.ACCENT && it.b - it.t == 3f })
        // The focused cell is not also drawn in the row pass.
        assertEquals(1, ops.count { it is Op.RoundRect && it.l == fill.l && it.t == fill.t })
    }

    @Test
    fun focusInAnotherZoneDrawsRingOnlyAtHalfAccent() {
        val f = frame(List(6) { simpleRow() }, focusRow = 1)
        f.gridFocused = false
        val ops = draw(f)
        assertFalse(ops.any { it is Op.Asset && it.asset == GuideAsset.FOCUS_SHADOW })
        assertTrue(ops.any { it is Op.Stroke && it.argb == 0x805CA8F2.toInt() && it.width == 3f })
        assertTrue(ops.any { it is Op.RoundRect && it.argb == C.CELL_NOW_FILL })
    }

    @Test
    fun stickyTitleStartsAtTimelineLeft() {
        val rows = listOf(listOf(Cell(at(18), at(20), title = "Long film"), Cell(at(20), at(23), title = "Later")))
        val ops = draw(frame(rows))
        val t = texts(ops).first { it.text == "Long film" }
        assertEquals(281f + 16f, t.x)
    }

    @Test
    fun ellipsisCutAndWidth() {
        val title = "An extremely long programme title that cannot fit"
        val rows = listOf(listOf(Cell(at(15), at(19, 30)), Cell(at(19, 30), at(20), title = title), Cell(at(20), at(23))))
        val ops = draw(frame(rows))
        val cellL = m.xOf(at(19, 30), h19, 0f) + 3f
        val cellR = m.xOf(at(20), h19, 0f) - 3f
        val textX = cellL + 16f
        val avail = cellR - 16f - textX
        val prefix = texts(ops).first { title.startsWith(it.text) && it.text.isNotEmpty() && it.style == GuideTextStyle.CELL_TITLE }
        val n = ((avail.toInt() - 9f) / 9f).toInt()
        assertEquals(title.substring(0, n), prefix.text)
        val ell = texts(ops).first { it.text == GuideRendererImpl.ELLIPSIS && it.style == GuideTextStyle.CELL_TITLE }
        assertTrue(near(textX + n * 9f, ell.x), "ellipsis placed at the cut width")
        assertEquals(textX, prefix.x)
    }

    @Test
    fun pastArchiveAndSuspectColours() {
        val rows = List(3) { listOf(Cell(at(15), at(19, 30), title = "Past"), Cell(at(19, 30), at(20), title = "Now"), Cell(at(20), at(23), title = "Next")) }
        val ch = listOf(GuideFixtures.channel(0, archiveDays = 0), GuideFixtures.channel(1, archiveDays = 7), GuideFixtures.channel(2, archiveDays = 7))
        val f = frame(rows, ch)
        f.suspectArchive = longArrayOf(ch[2].identityHash)
        val ops = draw(f)
        val past = texts(ops).filter { it.text == "Past" }
        assertEquals(listOf(C.TEXT_PAST, C.TEXT_ARCHIVE, C.TEXT_PAST), past.map { it.argb })
        assertEquals(C.FG, texts(ops).first { it.text == "Next" }.argb)
        assertTrue(ops.any { it is Op.Asset && it.asset == GuideAsset.ARCHIVE_GLYPH && it.argb == C.TEXT_ARCHIVE }, "↺ before the time")
        assertTrue(ops.any { it is Op.Asset && it.asset == GuideAsset.ARCHIVE_GLYPH_HOLLOW }, "suspect row")
        // Archive band on archive rows only, ending at now.
        val bands = ops.filterIsInstance<Op.Rect>().filter { it.argb == C.ARCHIVE_BAND }
        assertEquals(2, bands.size)
        assertTrue(bands.all { near(it.r, m.xOf(now, h19, 0f)) && it.l == 281f })
        // Time line at .6 of the text colour.
        assertTrue(texts(ops).any { it.style == GuideTextStyle.CELL_TIME && it.argb == 0x99EEF1F6.toInt() })
    }

    @Test
    fun reminderDotBeforeTitle() {
        val rows = listOf(listOf(Cell(at(15), at(20)), Cell(at(20), at(21), title = "Match"), Cell(at(21), at(23))))
        val f = frame(rows)
        f.reminders = ReminderIndex.of(listOf(GuideFixtures.channel(0).identityHash to at(20)))
        val ops = draw(f)
        val dot = ops.filterIsInstance<Op.Circle>().first { it.argb == C.LIVE }
        val title = texts(ops).first { it.text == "Match" }
        assertTrue(dot.cx < title.x)
        assertTrue(near(m.xOf(at(20), h19, 0f) + 3f + 16f + 4f, dot.cx))
    }

    @Test
    fun nowLineX() {
        val ops = draw(frame(List(6) { simpleRow() }))
        val line = ops.filterIsInstance<Op.Rect>().first { it.argb == C.ACCENT }
        assertTrue(near(687.8f, (line.l + line.r) / 2))
        assertEquals(549f - 6f, line.t)
        val dot = ops.filterIsInstance<Op.Circle>().first { it.argb == C.ACCENT }
        assertEquals(549f - 5f, dot.cy); assertEquals(5f, dot.radius)
    }

    @Test
    fun skeletonRowsAndLoadingCells() {
        val rows = List(2) { simpleRow() }
        val f = frame(rows, channels = listOf(GuideFixtures.channel(0), null, GuideFixtures.channel(2)))
        val ops = draw(f)
        val skel = ops.filterIsInstance<Op.RoundRect>().filter { it.argb == C.SKELETON }
        // Row 1 (no channel): two label bars + hour blocks (19–22 → 3 or 4 blocks).
        assertTrue(skel.any { it.r - it.l == 40f && it.b - it.t == 12f })
        assertTrue(skel.any { it.r - it.l == 110f && it.b - it.t == 16f })
        // Row 2 is outside the snapshot: LOADING blocks cut at :00/:30.
        val row2Top = 549f + 2 * 68f
        val loading = skel.filter { it.t == row2Top }
        assertTrue(loading.size >= 6)
        assertTrue(loading.all { abs((it.r + 3f - (it.l - 3f)) - 259.67f) < 0.1f || it.l < 281f + 5f || it.r > 1830f })
    }

    @Test
    fun narrowAndTinyCells() {
        val rows = listOf(listOf(Cell(at(15), at(19)), Cell(at(19), at(19, 12), title = "Narrow"), Cell(at(19, 12), at(19, 15), title = "Tiny"), Cell(at(19, 15), at(23))))
        val ops = draw(frame(rows))
        val t = texts(ops)
        // 12 min ≈ 98 px < 120: title only, 8 px padding, no time line.
        val narrow = t.firstOrNull { it.text.startsWith("Narr") || it.text == "Narrow" }
        assertNotNull(narrow)
        assertEquals(m.xOf(at(19), h19, 0f) + 3f + 8f, narrow.x)
        assertFalse(t.any { it.style == GuideTextStyle.CELL_TIME && abs(it.x - narrow.x) < 1f })
        // 3 min ≈ 26 px: no text at all.
        assertFalse(t.any { it.text.startsWith("T") && it.style == GuideTextStyle.CELL_TITLE })
    }

    @Test
    fun gapsSayNoInformation() {
        val rows = listOf(listOf(Cell(at(15), at(19)), Cell(at(19), at(20), kind = CellKind.GAP), Cell(at(20), at(23))))
        val ops = draw(frame(rows))
        val t = texts(ops).first { it.style == GuideTextStyle.NO_INFO }
        assertEquals(GuideRendererImpl.NO_INFO, t.text)
        assertEquals(C.TEXT_PAST, t.argb)
    }

    @Test
    fun nothingDrawnAbovePanelTop() {
        val f = GuideReferenceFrame.build(measurer, images = FakeGuideImages().also { im -> repeat(40) { im.loaded["logo://$it"] = FakeGuideImage(160, 90, "logo://$it") } })
        val ops = draw(f, row = GuideReferenceFrame.SCROLL_ROW)
        for (op in ops) {
            val top = when (op) {
                is Op.Rect -> op.t; is Op.RoundRect -> op.t; is Op.Stroke -> op.t; is Op.Circle -> op.cy - op.radius
                is Op.Text -> op.baseline - op.style.sizePx; is Op.Image -> op.t; is Op.Asset -> op.t; is Op.Clip -> op.t
                else -> 1e9f
            }
            assertTrue(top >= 500f, "$op above the panel")
        }
        // Logos replace names: one image per row with a loaded logo, no CH_NAME text.
        assertEquals(7, ops.count { it is Op.Image })
        assertFalse(texts(ops).any { it.style == GuideTextStyle.CH_NAME })
    }

    @Test
    fun logosOffFailedOrLoadingShowTheName() {
        val images = FakeGuideImages(failing = hashSetOf("logo://0"))
        val ch = listOf(GuideFixtures.channel(0, logoUrl = "logo://0"), GuideFixtures.channel(1, logoUrl = "logo://1"), GuideFixtures.channel(2))
        val f = frame(List(3) { simpleRow() }, ch)
        f.images = images
        f.showLogos = true
        val names = texts(draw(f)).filter { it.style == GuideTextStyle.CH_NAME }.map { it.text }
        assertEquals(listOf("Channel 0", "Channel 1", "Channel 2"), names)
        assertEquals(listOf("logo://1"), images.requested.distinct(), "failed URLs are not requested again")
    }

    @Test
    fun tunedDotAndNumbers() {
        val f = frame(List(3) { simpleRow() })
        f.tunedHash = GuideFixtures.channel(1).identityHash
        val ops = draw(f)
        val dot = ops.filterIsInstance<Op.Circle>().single { it.argb == C.LIVE }
        assertEquals(281f - 12f - 3.5f, dot.cx)
        assertTrue(near(549f + 68f + 30f, dot.cy))
        assertEquals(listOf("1", "2", "3"), texts(ops).filter { it.style == GuideTextStyle.CH_NUMBER }.map { it.text })
        f.showNumbers = false
        assertTrue(texts(draw(f)).none { it.style == GuideTextStyle.CH_NUMBER })
    }

    @Test
    fun generationSwapWithSameTextIdAndShorterTitleIsSafe() {
        // B4: a stale cache entry keyed by textId would index past the new, shorter title.
        val long = "A long title from yesterday's generation"
        val rows1 = listOf(listOf(Cell(at(15), at(19, 30)), Cell(at(19, 30), at(20), title = long), Cell(at(20), at(23))))
        val f = frame(rows1)
        draw(f)
        val rows2 = listOf(listOf(Cell(at(15), at(19, 30)), Cell(at(19, 30), at(20), title = "Short"), Cell(at(20), at(23))))
        val d2 = GuideFixtures.windowData(rows2, fromMin = at(15), toMin = at(23), generation = 2)
        assertEquals(f.data!!.cellTextId[1], d2.cellTextId[1], "same generation-local textId")
        d2.cellTitleKey[1] = f.data!!.cellTitleKey[1] // worst case: a stale/colliding content key too
        f.data = d2
        val ops = draw(f)
        assertTrue(texts(ops).any { it.text == "Short" })
        // A rename to a shorter channel name.
        val names = FakeGuideRows(listOf(GuideFixtures.channel(0, name = "A channel with a very long display name indeed")))
        f.rows = names
        draw(f)
        names.rows = listOf(GuideFixtures.channel(0, name = "BBC"))
        assertTrue(texts(draw(f)).any { it.text == "BBC" })
    }

    @Test
    fun emojiAtTheCutIsNotSplit() {
        val title = "Match of the Day 😀😀 highlights and more"
        val rows = listOf(listOf(Cell(at(15), at(19, 30)), Cell(at(19, 30), at(19, 55), title = title), Cell(at(19, 55), at(23))))
        val ops = draw(frame(rows))
        // The fake measurer's cut lands on the low surrogate of the first emoji (18 chars); it backs off to 17.
        assertTrue(texts(ops).any { it.text == "Match of the Day " })
        for (t in texts(ops)) {
            assertFalse(t.text.isNotEmpty() && Character.isHighSurrogate(t.text.last()), "split surrogate in '${t.text}'")
        }
    }

    @Test
    fun arabicTitleRightAlignedWithEllipsisOnTheLeft() {
        val title = "الأخبار المسائية من القاهرة مباشرة"
        val rows = listOf(listOf(Cell(at(15), at(19, 30)), Cell(at(19, 30), at(20), title = title, flags = CellFlags.RTL), Cell(at(20), at(23))))
        val ops = draw(frame(rows))
        val cellR = m.xOf(at(20), h19, 0f) - 3f
        val body = texts(ops).first { it.style == GuideTextStyle.CELL_TITLE && title.startsWith(it.text) && it.text.isNotEmpty() }
        val ell = texts(ops).first { it.style == GuideTextStyle.CELL_TITLE && it.text == GuideRendererImpl.ELLIPSIS }
        assertTrue(ell.x < body.x, "ellipsis on the left")
        assertTrue(near(cellR - 16f, body.x + body.text.length * 9f, 1f), "right-aligned to the inner edge")
    }

    /**
     * Code review 2026-10-14, G-S4: an LTR title with an Arabic word is an LTR paragraph. With the pager's flags it is
     * left-aligned and cut on the right ("Match of the D…"), not right-aligned with the ellipsis on the left.
     */
    @Test
    fun mixedLtrTitleWithArabicStaysLeftAligned() {
        val title = "Match of the Day (مباراة اليوم) extended highlights and analysis"
        val flags = app.thebox.core.guide.feed.GuideWindowPagerImpl.textFlagsOf(title)
        val rows = listOf(listOf(Cell(at(15), at(19, 30)), Cell(at(19, 30), at(20), title = title, flags = flags), Cell(at(20), at(23))))
        val ops = draw(frame(rows))
        val cellL = m.xOf(at(19, 30), h19, 0f)
        val body = texts(ops).first { it.style == GuideTextStyle.CELL_TITLE && title.startsWith(it.text) && it.text.isNotEmpty() }
        val ell = texts(ops).first { it.style == GuideTextStyle.CELL_TITLE && it.text == GuideRendererImpl.ELLIPSIS }
        assertTrue(body.text.startsWith("Match"), "the cut keeps the logical start")
        assertTrue(ell.x > body.x, "ellipsis after the prefix, on the right")
        assertTrue(body.x < cellL + 40f, "left-aligned at the inner edge (${body.x} vs cell ${cellL})")
    }

    @Test
    fun noArchiveBandWhenNowIsLeftOfTheWindow() {
        val ch = listOf(GuideFixtures.channel(0, archiveDays = 7))
        val f = frame(listOf(simpleRow()), ch)
        f.windowStartMin = at(21)
        val ops = draw(f)
        assertTrue(ops.none { it is Op.Rect && it.argb == C.ARCHIVE_BAND })
        assertTrue(ops.none { it is Op.Asset && it.asset == GuideAsset.NOW_GLOW })
        // And a fractional scroll just past now: still no band (N14).
        f.windowStartMin = h19
        assertTrue(draw(f, off = 47.5f).none { it is Op.Rect && it.argb == C.ARCHIVE_BAND })
    }

    @Test
    fun bubbleOverTheLabelColumn() {
        val f = frame(List(6) { simpleRow() }, focusRow = 2)
        f.bubbleNumber = 105
        val ops = draw(f)
        val pill = ops.filterIsInstance<Op.RoundRect>().first { it.r - it.l == 160f }
        assertEquals(81f + 20f, pill.l)
        assertTrue(near(549f + 2 * 68f + 30f, (pill.t + pill.b) / 2))
        assertTrue(texts(ops).any { it.text == "105" && it.style == GuideTextStyle.BUBBLE })
    }

    @Test
    fun animatedScrollShiftsCellsAndRows() {
        val f = frame(List(8) { simpleRow() })
        val a = draw(f).filterIsInstance<Op.Text>().first { it.text == "19:30" }
        val b = draw(f, off = 0.5f).filterIsInstance<Op.Text>().first { it.text == "19:30" }
        assertTrue(near(a.x - b.x, 4.33f, 0.01f))
        // Half a row: 7 label rows drawn.
        val ops = draw(f, row = 0.5f)
        assertEquals(7, texts(ops).count { it.style == GuideTextStyle.CH_NUMBER })
    }

    @Test
    fun focusedCellBoundsAndDescription() {
        val f = frame(List(6) { simpleRow() }, focusRow = 2)
        val out = FloatArray(4)
        assertTrue(renderer.focusedCellBounds(f, h19, 0f, 0f, out))
        assertTrue(near(m.xOf(at(19, 30), h19, 0f) + 3f, out[0]))
        assertEquals(549f + 136f, out[1]); assertEquals(549f + 136f + 60f, out[3])
        assertFalse(renderer.focusedCellBounds(f, h19, 0f, 10f, out), "scrolled off")
        val d = renderer.describeFocus(f)
        assertEquals("Channel 2, channel 3, Show 9, 19:30 to 20:00, on now", d)
    }

    @Test
    fun scaleChangeUsesScaledWidths() {
        // Measurer at scale 1, metrics at scale 2: widths double, nothing overflows wrongly.
        val f = frame(List(6) { simpleRow() }, focusRow = 1)
        f.metrics = GuideMetricsImpl(2f)
        val ops = draw(f)
        val t = texts(ops).first { it.text == "Show 10" }
        assertEquals(2 * (m.xOf(at(20), h19, 0f) + 3f + 16f), t.x)
        assertEquals(Op.RoundRect(128f, 1000f, 3712f, 1920f, 48f, C.PANEL_FILL), ops[0])
    }
}
