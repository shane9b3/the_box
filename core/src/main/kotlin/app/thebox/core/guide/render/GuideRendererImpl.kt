package app.thebox.core.guide.render

import androidx.collection.MutableIntFloatMap
import androidx.collection.MutableIntObjectMap
import app.thebox.core.common.Hash64
import app.thebox.core.guide.CellKind
import app.thebox.core.guide.GuideAsset
import app.thebox.core.guide.GuideCanvas
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideColors as C
import app.thebox.core.guide.GuideFrame
import app.thebox.core.guide.GuideGeometry as G
import app.thebox.core.guide.GuideMetrics
import app.thebox.core.guide.GuideRenderer
import app.thebox.core.guide.GuideTextMeasurer
import app.thebox.core.guide.GuideTextStyle
import app.thebox.core.guide.GuideWindowData
import app.thebox.core.guide.RowStatus
import app.thebox.core.guide.TimeLabels
import app.thebox.core.guide.CellFlags
import app.thebox.core.guide.layout.GridConstants
import app.thebox.core.guide.layout.rowTopPx
import kotlin.math.floor

/**
 * The grid renderer (docs/design/m3-guide.md §3.6, §4). One pass over [GuideCanvas] in device (window) px:
 *
 * 1. panel fill + border; 2. save, clip to the content box; 3. ruler (ticks, labels, the contextual label);
 * 4. clip to the rows area; per visible row: label cell, then save/clip to the row's timeline, archive band, cells
 * except the focused one, restore; 5. the focused cell (shadow, fill, ring, text, progress); 6. the now line
 * (glow, line, dot); 7. the channel-number bubble; 8. restore; 9. (decision 2026-10-19) the left-edge cue
 * ([GuideAsset.EDGE_CHEVRON]) in the grid's left gutter, outside the content clip and outside every row recording.
 * The clip cue ([GuideAsset.CLIP_CHEVRON]) is part of step 5: it sits inside the focused cell, which is never recorded.
 *
 * (code review 2026-10-14, O-2) Step 4 runs in two passes over the visible rows, backgrounds then text (cells do not
 * overlap, so the image is the same), each in its own trace section: `guide-draw-cells` and `guide-draw-text`; steps
 * 1–3 and 5–7 are `guide-draw-chrome`. Ruler label and channel-number widths are cached per clock form/zone and
 * scale; the name `TextFit` key is [GuideChannel.nameHash].
 *
 * **Zero allocation** in [draw] and [focusedCellBounds] (§4.2): titles, time texts and labels are pre-built strings
 * (snapshot, [TimeLabels] tables, constants); an ellipsis is a ranged `drawText` plus the constant "…" placed with
 * the [TextFit] cut width; channel numbers come from a lazily filled per-number string cache (one allocation per
 * distinct number per process, not per frame); no iterators, boxing or capturing lambdas. Main thread only (the
 * caches and the per-draw scratch fields are unsynchronised).
 */
class GuideRendererImpl(private val measurer: GuideTextMeasurer) : GuideRenderer {

    private val titleFit = TextFit(measurer, GridConstants.TEXT_FIT_SLOTS_TITLES)
    private val nameFit = TextFit(measurer, GridConstants.TEXT_FIT_SLOTS_NAMES)
    private val numbers = MutableIntObjectMap<String>()

    // Per-scale metrics of the text styles (frame px).
    private var preparedScale = -1f
    private var widthScale = 1f
    private val ellW = FloatArray(STYLES.size)
    private val asc = FloatArray(STYLES.size)
    private val desc = FloatArray(STYLES.size)
    /** (G-L7) Text caches follow [TimeLabels.version], not a probed label. */
    private var lastLabels: TimeLabels? = null
    private var labelsVersion = Int.MIN_VALUE
    /** O-2: ruler label width by minute of day (-1 = not measured), for the current labels version and scale. */
    private val rulerW = FloatArray(1440) { -1f }
    /** O-2: channel-number width by number, per scale. */
    private val numberW = MutableIntFloatMap()
    /** O-2: which part of the rows the current pass draws. */
    private var pass = PASS_ALL

    // Per-draw scratch (set at the top of draw/focusedCellBounds).
    private var s = 1f
    private var ws = 0
    private var off = 0f
    private var vFrom = 0
    private var vTo = 0
    private var now = 0
    private var contentRight = 0f
    private var labels: TimeLabels? = null
    /** Right edge (device px) of the clip chevron while the focused cell is drawn with it, else −1 (decision 2026-10-19). */
    private var clipCueRight = -1f

    /** Text-fit cache counters (hand-off measurements). */
    val textFitHits: Long get() = titleFit.hits + nameFit.hits
    val textFitMisses: Long get() = titleFit.misses + nameFit.misses

    /** Drops the text caches (`onTrimMemory`, §4.7). */
    override fun trimCaches() {
        titleFit.clear(); nameFit.clear(); numbers.clear(); numberW.clear()
    }

    // ------------------------------------------------------------------------------------------------------------
    // GuideRenderer
    // ------------------------------------------------------------------------------------------------------------

    override fun draw(canvas: GuideCanvas, frame: GuideFrame, windowStartMin: Int, scrollOffsetMin: Float, scrollRow: Float) {
        val m = frame.metrics ?: return
        begin(frame, m, windowStartMin, scrollOffsetMin)
        canvas.beginSection(SECTION_CHROME)

        // 1. Panel.
        val bw = s * G.PANEL_BORDER
        val r = s * G.PANEL_R
        canvas.fillRoundRect(m.panelLeft, m.panelTop, m.panelRight, m.panelBottom, r, C.PANEL_FILL)
        canvas.strokeRoundRect(m.panelLeft + bw / 2, m.panelTop + bw / 2, m.panelRight - bw / 2, m.panelBottom - bw / 2, r - bw / 2, bw, C.PANEL_BORDER)

        // 2. Content box.
        val outer = canvas.save()
        canvas.clipRect(m.labelLeft, m.headerTop, contentRight, m.rowsBottom)

        // 3. Ruler.
        drawRuler(canvas, frame, m)

        canvas.endSection()

        // 4. Rows: backgrounds, then text (O-2).
        val rowsSave = canvas.save()
        canvas.clipRect(m.labelLeft, m.rowsTop, contentRight, m.rowsBottom)
        canvas.beginSection(SECTION_CELLS)
        pass = PASS_BG
        drawRows(canvas, frame, m, scrollRow)
        canvas.endSection()
        canvas.beginSection(SECTION_TEXT)
        pass = PASS_TEXT
        drawRows(canvas, frame, m, scrollRow)
        pass = PASS_ALL
        canvas.endSection()
        canvas.restoreToCount(rowsSave)

        canvas.beginSection(SECTION_CHROME)
        // 5. Focused cell, last so its ring and shadow overlap the neighbours.
        drawFocused(canvas, frame, m, scrollRow)

        // 6. Now line, over the cells (B2.6).
        val relNow = now - ws
        if (relNow >= off && relNow <= off + G.WINDOW_MIN) {
            val x = m.xOf(now, ws, off)
            val lineTop = m.rowsTop - s * G.NOW_OVERHANG
            canvas.drawAsset(GuideAsset.NOW_GLOW, x - s * G.NOW_GLOW, lineTop, x + s * G.NOW_GLOW, m.rowsBottom, C.ACCENT)
            canvas.fillRect(x - s * G.NOW_W / 2, lineTop, x + s * G.NOW_W / 2, m.rowsBottom, C.ACCENT)
            canvas.fillCircle(x, m.rowsTop - s * G.NOW_DOT / 2, s * G.NOW_DOT / 2, C.ACCENT)
        }

        // 7. Bubble.
        drawBubble(canvas, frame, m, scrollRow)

        canvas.endSection()
        canvas.restoreToCount(outer)

        // 9. Left-edge cue (decision 2026-10-19): in the 16 px gutter left of labelLeft, which the content clip cuts
        // away, so it is drawn after that clip is restored. One asset op; no row key depends on it.
        if (frame.leftEdgeCue && frame.gridFocused) {
            canvas.beginSection(SECTION_CHROME)
            drawEdgeCue(canvas, frame, m, scrollRow)
            canvas.endSection()
        }
    }

    /** [GuideAsset.EDGE_CHEVRON]: 12 × 24 at design x 67, centred on the focused row; skipped when that row is off the rows area. */
    private fun drawEdgeCue(canvas: GuideCanvas, frame: GuideFrame, m: GuideMetrics, scrollRow: Float) {
        val f = frame.focus ?: return
        if (f.row < 0 || f.row >= frame.rows.size) return
        val mid = m.rowTopPx(f.row - scrollRow) + m.rowHeight / 2
        val h = s * CHEVRON_H / 2
        if (mid < m.rowsTop + h || mid > m.rowsBottom - h) return
        val l = m.panelLeft + Math.round(s * EDGE_CUE_DX)
        val r = l + Math.round(s * CHEVRON_W)
        canvas.drawAsset(GuideAsset.EDGE_CHEVRON, l, mid - h, r, mid + h, C.ACCENT)
    }

    private fun drawRows(canvas: GuideCanvas, frame: GuideFrame, m: GuideMetrics, scrollRow: Float) {
        val rows = frame.rows
        val base = frameKey(frame)
        var i = maxOf(0, floor(scrollRow).toInt())
        while (i < rows.size) {
            val top = m.rowTopPx(i - scrollRow)
            if (top >= m.rowsBottom) break
            val bottom = top + m.rowHeight
            if (bottom > m.rowsTop) {
                // O-2: a row whose inputs did not change (a vertical scroll animation frame) replays its recording.
                if (!canvas.beginRow(rowKey(base, frame, i), top)) {
                    drawRow(canvas, frame, m, i, top, bottom)
                    canvas.endRow()
                } else if (pass != PASS_BG && frame.showLogos) {
                    // Review 2026-11-10 S3: the replayed row's label did not call images.get, so tell the cache its
                    // logo is still on screen (no allocation: the URL is the channel's own field).
                    val url = rows.channel(i)?.logoUrl
                    if (url != null) frame.images?.touch(url)
                }
            }
            i++
        }
    }

    /** O-2: everything every row's ops depend on (per draw pass), except the row itself and its vertical position. */
    private fun frameKey(frame: GuideFrame): Long {
        var h = Hash64.long(ROW_KEY_SEED, pass.toLong())
        h = Hash64.long(h, frame.data?.seq ?: -1L)
        h = Hash64.long(h, frame.rows.version)
        h = Hash64.long(h, ws.toLong())
        h = Hash64.long(h, off.toRawBits().toLong())
        h = Hash64.long(h, now.toLong())
        h = Hash64.long(h, s.toRawBits().toLong())
        h = Hash64.long(h, labelsVersion.toLong())
        h = Hash64.long(h, frame.tunedHash)
        h = Hash64.long(h, System.identityHashCode(frame.reminders).toLong())
        h = Hash64.long(h, System.identityHashCode(frame.suspectArchive).toLong())
        h = Hash64.long(h, (if (frame.showLogos) 1L else 0L) or (if (frame.showNumbers) 2L else 0L) or (if (frame.gridFocused) 4L else 0L))
        h = Hash64.long(h, frame.images?.version ?: 0L)
        return h
    }

    private fun rowKey(base: Long, frame: GuideFrame, row: Int): Long {
        val f = frame.focus
        var h = Hash64.long(base, row.toLong())
        // The focused row draws its label highlight and leaves out the focused cell.
        h = Hash64.long(h, if (f != null && f.row == row) f.cellStart.toLong() else Long.MIN_VALUE)
        return h
    }

    override fun focusedCellBounds(frame: GuideFrame, windowStartMin: Int, scrollOffsetMin: Float, scrollRow: Float, out: FloatArray): Boolean {
        val m = frame.metrics ?: return false
        val f = frame.focus ?: return false
        begin(frame, m, windowStartMin, scrollOffsetMin)
        val top = m.rowTopPx(f.row - scrollRow)
        val bottom = top + m.rowHeight
        if (bottom <= m.rowsTop || top >= m.rowsBottom) return false
        val inset = s * G.CELL_INSET_X
        val x0 = m.xOf(f.cellStart, ws, off) + inset
        val x1 = m.xOf(f.cellEnd, ws, off) - inset
        if (x1 <= m.timelineLeft || x0 >= contentRight) return false
        out[0] = x0; out[1] = top; out[2] = x1; out[3] = bottom
        return true
    }

    override fun describeFocus(frame: GuideFrame): String {
        val f = frame.focus ?: return ""
        val ch = frame.rows.channel(f.row) ?: return "Loading"
        val labels = frame.labels
        val sb = StringBuilder(ch.displayName)
        if (ch.number != null) sb.append(", channel ").append(ch.number)
        val d = frame.data
        val c = if (d == null) -1 else findCell(d, f.row, f.cellStart)
        val title = if (c >= 0 && d!!.cellKind[c] == CellKind.PROGRAMME) d.cellTitle[c] else null
        if (c >= 0 && d!!.cellKind[c] != CellKind.PROGRAMME) sb.append(", ").append(NO_INFO)
        else if (f.cellKind == CellKind.LOADING) sb.append(", loading")
        if (title != null) sb.append(", ").append(title)
        if (labels != null && f.cellKind != CellKind.LOADING) {
            sb.append(", ").append(labels.hm(f.cellStart)).append(" to ").append(labels.hm(f.cellEnd))
        }
        val nowMin = frame.nowMin
        when {
            f.cellStart <= nowMin && nowMin < f.cellEnd -> sb.append(", on now")
            f.cellEnd <= nowMin && c >= 0 && inArchive(frame, ch, d!!.cellRawStart[c], d.cellEnd[c]) -> sb.append(", catch-up available")
        }
        if (c >= 0 && d!!.cellKind[c] == CellKind.PROGRAMME && frame.reminders.contains(ch.identityHash, d.cellRawStart[c])) {
            sb.append(", reminder set")
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------------------------------------------------

    private fun begin(frame: GuideFrame, m: GuideMetrics, windowStartMin: Int, scrollOffsetMin: Float) {
        if (m.scale != preparedScale) prepare(m.scale)
        val l = frame.labels
        if (l != null && (l !== lastLabels || l.version != labelsVersion)) {
            lastLabels = l
            labelsVersion = l.version
            titleFit.clear(); nameFit.clear()
            rulerW.fill(-1f)
        }
        labels = l
        s = m.scale
        ws = windowStartMin
        off = scrollOffsetMin
        val offFloor = floor(scrollOffsetMin).toInt()
        vFrom = windowStartMin + offFloor
        vTo = vFrom + G.WINDOW_MIN + 1
        now = frame.nowMin
        contentRight = m.timelineLeft + m.timelineWidth
    }

    private fun prepare(scale: Float) {
        val mm = measurer
        if (mm is RescalableTextMeasurer && mm.scale != scale) mm.rescale(scale)
        widthScale = if (mm.scale > 0f) scale / mm.scale else 1f
        titleFit.widthScale = widthScale
        nameFit.widthScale = widthScale
        titleFit.clear(); nameFit.clear()
        rulerW.fill(-1f)
        numberW.clear()
        for (k in STYLES.indices) {
            val st = STYLES[k]
            ellW[k] = mm.width(ELLIPSIS, 0, 1, st) * widthScale
            asc[k] = mm.ascent(st) * widthScale
            desc[k] = mm.descent(st) * widthScale
        }
        preparedScale = scale
    }

    // ------------------------------------------------------------------------------------------------------------
    // Ruler
    // ------------------------------------------------------------------------------------------------------------

    private fun drawRuler(canvas: GuideCanvas, frame: GuideFrame, m: GuideMetrics) {
        val l = labels ?: return
        val sv = canvas.save()
        canvas.clipRect(m.timelineLeft, m.headerTop, contentRight, m.headerBottom)
        val baseline = centredBaseline(m.headerTop, m.headerBottom, GuideTextStyle.RULER)
        // Contextual label at the ruler's left edge (§3.5.2); the first tick label is pushed after it.
        var minLabelX = -Float.MAX_VALUE
        val ctx = frame.contextLabel
        if (ctx.isNotEmpty()) {
            val x = m.timelineLeft + s * G.TICK_LABEL_PAD
            val cb = centredBaseline(m.headerTop, m.headerBottom, GuideTextStyle.RULER_CONTEXT)
            canvas.drawText(ctx, 0, ctx.length, x, cb, GuideTextStyle.RULER_CONTEXT, C.TEXT_LABEL)
            minLabelX = x + width(ctx, GuideTextStyle.RULER_CONTEXT) + s * G.CONTEXT_GAP
        }
        val ruleW = maxOf(1f, s * G.PANEL_BORDER)
        val hasContext = minLabelX > m.timelineLeft
        var t = vFrom - l.minuteOfDay(vFrom) % G.TICK_MIN
        while (t <= vTo) {
            val x = m.xOf(t, ws, off)
            if (x >= contentRight) break
            if (hasContext && x < m.timelineLeft) { t = next30(l, t); continue } // scrolled off under the label
            val text = l.hm(t)
            val tw = rulerWidth(l, t, text)
            var lx = x + s * G.TICK_LABEL_PAD
            var show = true
            if (lx < minLabelX) {
                lx = minLabelX
                // (decision 2026-11-09) With the bigger type a long context label ("THIS MORNING") can push this label
                // into the next tick's: then this one is dropped, so every label that is drawn sits on its own rule.
                val nextLx = m.xOf(next30(l, t), ws, off) + s * G.TICK_LABEL_PAD
                if (lx + tw + s * G.CONTEXT_GAP > nextLx) show = false
            }
            if (x + ruleW > m.timelineLeft) canvas.fillRect(x, m.headerTop, x + ruleW, m.headerBottom, C.TICK_RULE)
            if (show && lx + tw > m.timelineLeft) canvas.drawText(text, 0, text.length, lx, baseline, GuideTextStyle.RULER, C.TEXT_TICK)
            minLabelX = -Float.MAX_VALUE
            t = next30(l, t)
        }
        canvas.restoreToCount(sv)
    }

    // ------------------------------------------------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------------------------------------------------

    private fun drawRow(canvas: GuideCanvas, frame: GuideFrame, m: GuideMetrics, i: Int, top: Float, bottom: Float) {
        val ch = frame.rows.channel(i)
        val focusRow = frame.focus?.row ?: -1
        val bg = pass != PASS_TEXT
        if (ch == null) { if (bg) drawSkeletonLabel(canvas, m, top, bottom, i == focusRow) } else drawLabel(canvas, frame, m, ch, top, bottom, i == focusRow)
        val sv = canvas.save()
        // The row's own bounds (the caller's rows-area clip bounds it further), so a recording replays anywhere (O-2).
        canvas.clipRect(m.timelineLeft, top, contentRight, bottom)
        if (ch == null) {
            if (bg) drawSkeletonBlocks(canvas, m, top, bottom)
        } else {
            if (bg) drawArchiveBand(canvas, m, ch, top, bottom)
            drawCells(canvas, frame, m, ch, i, top, bottom)
        }
        canvas.restoreToCount(sv)
    }

    private fun drawLabel(canvas: GuideCanvas, frame: GuideFrame, m: GuideMetrics, ch: GuideChannel, top: Float, bottom: Float, focusedRow: Boolean) {
        val l = m.labelLeft
        val r = l + m.labelWidth
        val bg = pass != PASS_TEXT
        if (focusedRow && bg) canvas.fillRoundRect(l, top, r, bottom, s * G.CELL_R, C.ROW_FOCUS_FILL)
        val mid = (top + bottom) / 2
        var x = l + s * G.LABEL_PAD_X
        var right = r - s * G.LABEL_PAD_X
        if (ch.identityHash == frame.tunedHash && frame.tunedHash != 0L) {
            if (bg) canvas.fillCircle(right - s * G.TUNED_DOT / 2, mid, s * G.TUNED_DOT / 2, C.LIVE)
            right -= s * (G.TUNED_DOT + G.LABEL_ICON_GAP)
        }
        if (ch.archiveDays > 0) {
            val g = s * G.LABEL_GLYPH
            val asset = if (isSuspect(frame, ch)) GuideAsset.ARCHIVE_GLYPH_HOLLOW else GuideAsset.ARCHIVE_GLYPH
            if (bg) canvas.drawAsset(asset, right - g, mid - g / 2, right, mid + g / 2, C.TEXT_LABEL)
            right -= g + s * G.LABEL_ICON_GAP
        }
        if (pass == PASS_BG) return
        val num = ch.number
        if (frame.showNumbers && num != null) {
            val str = numberString(num)
            canvas.drawText(str, 0, str.length, x, centredBaseline(top, bottom, GuideTextStyle.CH_NUMBER), GuideTextStyle.CH_NUMBER, C.TEXT_LABEL)
            x += numberWidth(num, str) + s * G.LABEL_GAP
        }
        val avail = right - x
        if (avail <= 0f) return
        val url = ch.logoUrl
        val images = frame.images
        if (frame.showLogos && url != null && images != null && !images.failed(url)) {
            val img = images.get(url)
            if (img != null && img.widthPx > 0 && img.heightPx > 0) {
                val slotW = minOf(s * GridConstants.LOGO_W, avail)
                val slotH = s * GridConstants.LOGO_H
                val k = minOf(slotW / img.widthPx, slotH / img.heightPx)
                val w = img.widthPx * k
                val h = img.heightPx * k
                canvas.drawImage(img, x, mid - h / 2, x + w, mid + h / 2, 1f)
                return
            }
        }
        val name = ch.displayName
        drawFitted(canvas, nameFit, name, ch.nameHash, GuideTextStyle.CH_NAME, x, avail, -1f, rtlFlag = false, complex = false,
            baseline = centredBaseline(top, bottom, GuideTextStyle.CH_NAME), argb = C.FG, alignRight = false)
    }

    private fun drawSkeletonLabel(canvas: GuideCanvas, m: GuideMetrics, top: Float, bottom: Float, focusedRow: Boolean) {
        val l = m.labelLeft
        if (focusedRow) canvas.fillRoundRect(l, top, l + m.labelWidth, bottom, s * G.CELL_R, C.ROW_FOCUS_FILL)
        val mid = (top + bottom) / 2
        val x = l + s * G.LABEL_PAD_X
        val rr = s * SKELETON_BAR_R
        canvas.fillRoundRect(x, mid - s * 6, x + s * 40, mid + s * 6, rr, C.SKELETON)
        val x2 = x + s * (40 + G.LABEL_GAP)
        canvas.fillRoundRect(x2, mid - s * 8, x2 + s * 110, mid + s * 8, rr, C.SKELETON)
    }

    /** Skeleton rounded rects every 60 minutes (rows whose channel block is not loaded). */
    private fun drawSkeletonBlocks(canvas: GuideCanvas, m: GuideMetrics, top: Float, bottom: Float) {
        val inset = s * G.CELL_INSET_X
        var t = floorHour(vFrom)
        while (t < vTo) {
            val e = nextHour(t)
            canvas.fillRoundRect(m.xOf(t, ws, off) + inset, top, m.xOf(e, ws, off) - inset, bottom, s * G.CELL_R, C.SKELETON)
            t = e
        }
    }

    /** LOADING cells: skeleton rects over [a, b) cut at local :00/:30. */
    private fun drawLoading(canvas: GuideCanvas, m: GuideMetrics, a: Int, b: Int, top: Float, bottom: Float) {
        if (pass == PASS_TEXT) return
        val l = labels
        val inset = s * G.CELL_INSET_X
        var t = a
        while (t < b) {
            val e = minOf(b, if (l == null) Math.floorDiv(t, 30) * 30 + 30 else next30(l, t))
            val x0 = m.xOf(t, ws, off) + inset
            val x1 = m.xOf(e, ws, off) - inset
            if (x1 - x0 >= 1f) canvas.fillRoundRect(x0, top, x1, bottom, s * G.CELL_R, C.SKELETON)
            t = e
        }
    }

    private fun drawArchiveBand(canvas: GuideCanvas, m: GuideMetrics, ch: GuideChannel, top: Float, bottom: Float) {
        val days = minOf(ch.archiveDays, MAX_ARCHIVE_DAYS)
        if (days <= 0 || (now - ws) <= off) return // N14: no band when now is left of the window
        val aStart = now - days * 1440
        val xa = m.xOf(aStart, ws, off)
        val x0 = maxOf(xa, m.timelineLeft)
        val x1 = minOf(m.xOf(now, ws, off), contentRight)
        if (x1 > x0) canvas.fillRect(x0, top, x1, bottom, C.ARCHIVE_BAND)
        if (xa > m.timelineLeft && xa < contentRight) canvas.fillRect(xa, top, xa + maxOf(1f, s), bottom, C.TICK_RULE)
    }

    private fun drawCells(canvas: GuideCanvas, frame: GuideFrame, m: GuideMetrics, ch: GuideChannel, row: Int, top: Float, bottom: Float) {
        val d = frame.data
        if (d == null || row < d.firstRow || row >= d.firstRow + d.rowCount) {
            drawLoading(canvas, m, vFrom, vTo, top, bottom); return
        }
        val r = row - d.firstRow
        val n = d.rowCellCount[r]
        if (d.rowStatus[r] == RowStatus.PENDING || n == 0) {
            drawLoading(canvas, m, vFrom, vTo, top, bottom); return
        }
        val f = d.rowFirstCell[r]
        val end = f + n
        // First cell ending after vFrom (binary search, N14).
        var lo = f
        var hi = end
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (d.cellEnd[mid] <= vFrom) lo = mid + 1 else hi = mid
        }
        var c = lo
        if (c < end && d.cellStart[c] > vFrom) drawLoading(canvas, m, vFrom, minOf(d.cellStart[c], vTo), top, bottom)
        val focus = frame.focus
        val fStart = if (focus != null && focus.row == row) focus.cellStart else Int.MIN_VALUE
        var lastEnd = if (c > f) d.cellEnd[c - 1] else vFrom
        while (c < end && d.cellStart[c] < vTo) {
            if (d.cellStart[c] != fStart) drawCell(canvas, frame, m, ch, d, c, top, bottom, focused = false)
            lastEnd = d.cellEnd[c]
            c++
        }
        if (c >= end && lastEnd < vTo) drawLoading(canvas, m, maxOf(lastEnd, vFrom), vTo, top, bottom)
    }

    /**
     * One cell in its unfocused state ([focused] = false) or the grid-focused style ([focused] = true): fill,
     * border (or the focused shadow/fill/ring, drawn by the caller), text, progress.
     */
    private fun drawCell(canvas: GuideCanvas, frame: GuideFrame, m: GuideMetrics, ch: GuideChannel, d: GuideWindowData, c: Int, top: Float, bottom: Float, focused: Boolean) {
        val flags = d.cellFlags[c]
        if ((flags and CellFlags.SLIVER) != 0) return
        val start = d.cellStart[c]
        val end = d.cellEnd[c]
        val inset = s * G.CELL_INSET_X
        val x0 = m.xOf(start, ws, off) + inset
        val x1 = m.xOf(end, ws, off) - inset
        if (x1 - x0 < 1f) return
        val kind = d.cellKind[c]
        val programme = kind == CellKind.PROGRAMME
        val onNow = programme && start <= now && now < end
        val past = programme && end <= now
        val archiveStart = now - minOf(ch.archiveDays, MAX_ARCHIVE_DAYS) * 1440
        val archived = past && ch.archiveDays > 0 && d.cellRawStart[c] >= archiveStart
        val suspect = archived && isSuspect(frame, ch)
        val textColor = when {
            focused -> C.CANVAS
            !programme -> C.TEXT_PAST
            past -> if (archived && !suspect) C.TEXT_ARCHIVE else C.TEXT_PAST
            else -> C.FG
        }
        if (!focused && pass != PASS_TEXT) {
            val rr = s * G.CELL_R
            val bw = s * G.CELL_BORDER
            canvas.fillRoundRect(x0, top, x1, bottom, rr, if (onNow) C.CELL_NOW_FILL else C.CELL_FILL)
            canvas.strokeRoundRect(x0 + bw / 2, top + bw / 2, x1 - bw / 2, bottom - bw / 2, rr - bw / 2, bw, C.CELL_BORDER)
        }

        // Text (§3.6.4).
        val narrow = x1 - x0 < s * G.NARROW_CELL_W
        val pad = s * if (narrow) G.CELL_PAD_X_NARROW else G.CELL_PAD_X
        var textX = maxOf(x0, m.timelineLeft) + pad
        // Decision 2026-10-19: the clip chevron leads the focused cell; the title starts after it when the pad is too
        // small to clear it (only while the cue is on; the focused cell is drawn outside every row recording).
        if (focused && clipCueRight >= 0f && textX - clipCueRight < s * CLIP_CUE_MIN_GAP) textX = clipCueRight + s * CLIP_CUE_TEXT_GAP
        val rightEdge = x1 - pad
        if (pass != PASS_BG && rightEdge - textX >= s * G.MIN_TEXT_W) {
            // Owner ruling 2026-10-17: cells draw the title only (no time line); the ruler carries the times.
            val tStyle = if (programme) GuideTextStyle.CELL_TITLE else GuideTextStyle.NO_INFO
            val ti = tStyle.ordinal
            val titleBase = centredBaseline(top, bottom, tStyle)
            if (archived) {
                // The catch-up cue (↺, hollow when suspect) now leads the title instead of the removed time line.
                val g = s * G.CELL_GLYPH
                canvas.drawAsset(if (suspect) GuideAsset.ARCHIVE_GLYPH_HOLLOW else GuideAsset.ARCHIVE_GLYPH,
                    textX, titleBase - g * 0.9f, textX + g, titleBase + g * 0.1f, textColor)
                textX += g + s * G.GLYPH_GAP
            }
            if (programme && start > now && frame.reminders.contains(ch.identityHash, d.cellRawStart[c])) {
                val rd = s * G.REMINDER_DOT / 2
                canvas.fillCircle(textX + rd, titleBase - asc[ti] * 0.35f, rd, if (focused) C.CANVAS else C.LIVE)
                textX += s * (G.REMINDER_DOT + G.REMINDER_GAP)
            }
            val title = if (programme) d.cellTitle[c] else NO_INFO
            if (title != null && rightEdge - textX > 0f) {
                val key = if (programme) d.cellTitleKey[c] else NO_INFO_KEY
                val known = if (programme) scaledWidth(d.cellTitleWidth[c], d.metricsScale) else -1f
                drawFitted(canvas, titleFit, title, key, tStyle, textX, rightEdge - textX, known,
                    rtlFlag = (flags and CellFlags.RTL) != 0, complex = (flags and CellFlags.COMPLEX) != 0,
                    baseline = titleBase, argb = textColor, alignRight = true)
            }
        }

        if (onNow && pass != PASS_TEXT) {
            val ph = s * G.PROGRESS_H
            val bx0 = x0 + s * G.PROGRESS_INSET_X
            val bx1 = x1 - s * G.PROGRESS_INSET_X
            val pb = bottom - s * G.PROGRESS_INSET_B
            val frac = (now - start).toFloat() / (end - start)
            if (bx1 > bx0) canvas.fillRect(bx0, pb - ph, bx0 + (bx1 - bx0) * frac, pb, if (focused) C.ACCENT else C.CELL_PROGRESS)
        }
    }

    private fun drawFocused(canvas: GuideCanvas, frame: GuideFrame, m: GuideMetrics, scrollRow: Float) {
        val f = frame.focus ?: return
        if (f.row < 0 || f.row >= frame.rows.size) return
        val top = m.rowTopPx(f.row - scrollRow)
        val bottom = top + m.rowHeight
        if (bottom <= m.rowsTop || top >= m.rowsBottom) return
        val d = frame.data
        val c = if (d == null) -1 else findCell(d, f.row, f.cellStart)
        val inset = s * G.CELL_INSET_X
        val x0 = m.xOf(f.cellStart, ws, off) + inset
        val x1 = m.xOf(f.cellEnd, ws, off) - inset
        if (x1 - x0 < 1f || x1 <= m.timelineLeft || x0 >= contentRight) return
        val ch = frame.rows.channel(f.row)
        val sv = canvas.save()
        canvas.clipRect(m.timelineLeft, m.rowsTop, contentRight, m.rowsBottom)
        val rr = s * G.CELL_R
        val ring = s * G.FOCUS_RING
        if (frame.gridFocused) {
            val sh = s * SHADOW_BLUR
            val dy = s * SHADOW_DY
            canvas.drawAsset(GuideAsset.FOCUS_SHADOW, x0 - sh, top - sh + dy, x1 + sh, bottom + sh + dy, C.SHADOW)
            canvas.fillRoundRect(x0, top, x1, bottom, rr, C.FG)
            canvas.strokeRoundRect(x0 - ring / 2, top - ring / 2, x1 + ring / 2, bottom + ring / 2, rr + ring / 2, ring, C.ACCENT)
            // Clip cue (decision 2026-10-19): at the cell's visible left inner edge, in the focused title's colour.
            val cueL = if (frame.clipCue) Math.round(maxOf(x0, m.timelineLeft) + s * CLIP_CUE_DX).toFloat() else 0f
            if (frame.clipCue) clipCueRight = cueL + Math.round(s * CHEVRON_W)
            if (ch != null && c >= 0) drawCell(canvas, frame, m, ch, d!!, c, top, bottom, focused = true)
            if (frame.clipCue) {
                val mid = (top + bottom) / 2
                val h = s * CHEVRON_H / 2
                canvas.drawAsset(GuideAsset.CLIP_CHEVRON, cueL, mid - h, clipCueRight, mid + h, C.CANVAS)
                clipCueRight = -1f
            }
        } else {
            if (ch != null && c >= 0) drawCell(canvas, frame, m, ch, d!!, c, top, bottom, focused = false)
            else canvas.fillRoundRect(x0, top, x1, bottom, rr, C.SKELETON)
            canvas.strokeRoundRect(x0 - ring / 2, top - ring / 2, x1 + ring / 2, bottom + ring / 2, rr + ring / 2, ring, ACCENT_HALF)
        }
        canvas.restoreToCount(sv)
    }

    private fun drawBubble(canvas: GuideCanvas, frame: GuideFrame, m: GuideMetrics, scrollRow: Float) {
        val number = frame.bubbleNumber
        val f = frame.focus
        if (number < 0 || f == null) return
        val top = m.rowTopPx(f.row - scrollRow)
        val cy = top + m.rowHeight / 2
        val w = s * BUBBLE_W
        val h = s * BUBBLE_H
        val l = m.labelLeft + (m.labelWidth - w) / 2
        val t = cy - h / 2
        canvas.fillRoundRect(l, t, l + w, t + h, s * BUBBLE_R, BUBBLE_FILL)
        val str = numberString(number)
        val tw = width(str, GuideTextStyle.BUBBLE)
        canvas.drawText(str, 0, str.length, l + (w - tw) / 2, centredBaseline(t, t + h, GuideTextStyle.BUBBLE), GuideTextStyle.BUBBLE, C.FG)
    }

    // ------------------------------------------------------------------------------------------------------------
    // Text helpers
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Draws [text] in [avail] px from [x]: whole when it fits ([knownWidth] ≥ 0 skips the cache), else the
     * [TextFit] cut plus "…". Right-to-left text is drawn with the ellipsis on its left; with [alignRight] it is
     * also right-aligned in the space (cells, S6).
     */
    private fun drawFitted(
        canvas: GuideCanvas, fit: TextFit, text: String, key: Long, style: GuideTextStyle, x: Float, avail: Float,
        knownWidth: Float, rtlFlag: Boolean, complex: Boolean, baseline: Float, argb: Int, alignRight: Boolean,
    ) {
        if (knownWidth >= 0f && knownWidth <= avail) {
            val dx = if (rtlFlag && alignRight) avail - knownWidth else 0f
            canvas.drawText(text, 0, text.length, x + dx, baseline, style, argb)
            return
        }
        val n = fit.fit(text, key, style, avail, ellW[style.ordinal], complex)
        val w = fit.lastWidth
        // G-S4: cells carry the pager's first-strong-character flag; names (no flag) use the fit's own detection.
        val rtl = if (alignRight) rtlFlag else rtlFlag || fit.lastRtl
        if (n >= text.length) {
            val dx = if (rtl && alignRight) avail - w else 0f
            canvas.drawText(text, 0, text.length, x + dx, baseline, style, argb)
            return
        }
        val ew = ellW[style.ordinal]
        if (!rtl) {
            if (n > 0) canvas.drawText(text, 0, n, x, baseline, style, argb)
            canvas.drawText(ELLIPSIS, 0, 1, x + w, baseline, style, argb)
        } else {
            val tx = if (alignRight) x + avail - w else x + ew
            canvas.drawText(ELLIPSIS, 0, 1, tx - ew, baseline, style, argb)
            if (n > 0) canvas.drawText(text, 0, n, tx, baseline, style, argb)
        }
    }

    private fun width(text: String, style: GuideTextStyle): Float = measurer.width(text, 0, text.length, style) * widthScale

    /** O-2: the ruler label at minute [t] (text [text] = `hm(t)`), measured once per minute of day per labels/scale. */
    private fun rulerWidth(l: TimeLabels, t: Int, text: String): Float {
        val k = l.minuteOfDay(t)
        val cached = rulerW[k]
        if (cached >= 0f) return cached
        return width(text, GuideTextStyle.RULER).also { rulerW[k] = it }
    }

    private fun numberWidth(n: Int, str: String): Float {
        val cached = numberW.getOrDefault(n, -1f)
        if (cached >= 0f) return cached
        if (numberW.size >= MAX_NUMBER_STRINGS) numberW.clear()
        return width(str, GuideTextStyle.CH_NUMBER).also { numberW[n] = it }
    }

    private fun scaledWidth(w: Float, dataScale: Float): Float = if (w < 0f || dataScale <= 0f) -1f else w * (s / dataScale)

    private fun centredBaseline(top: Float, bottom: Float, style: GuideTextStyle): Float {
        val k = style.ordinal
        return top + (bottom - top - (asc[k] + desc[k])) / 2 + asc[k]
    }

    private fun numberString(n: Int): String {
        val cached = numbers[n]
        if (cached != null) return cached
        if (numbers.size >= MAX_NUMBER_STRINGS) numbers.clear()
        val str = n.toString()
        numbers[n] = str
        return str
    }

    // ------------------------------------------------------------------------------------------------------------
    // Time helpers
    // ------------------------------------------------------------------------------------------------------------

    private fun next30(l: TimeLabels, t: Int): Int {
        val e = t + (G.TICK_MIN - l.minuteOfDay(t) % G.TICK_MIN)
        return if (e <= t) t + G.TICK_MIN else e
    }

    private fun floorHour(t: Int): Int { val l = labels; return if (l == null) Math.floorDiv(t, 60) * 60 else t - l.minuteOfDay(t) % 60 }

    private fun nextHour(t: Int): Int { val l = labels; return if (l == null) Math.floorDiv(t, 60) * 60 + 60 else t + (60 - l.minuteOfDay(t) % 60) }

    private fun isSuspect(frame: GuideFrame, ch: GuideChannel): Boolean =
        frame.suspectArchive.isNotEmpty() && java.util.Arrays.binarySearch(frame.suspectArchive, ch.identityHash) >= 0

    private fun inArchive(frame: GuideFrame, ch: GuideChannel, rawStart: Int, end: Int): Boolean =
        ch.archiveDays > 0 && end <= frame.nowMin && !isSuspect(frame, ch) &&
            rawStart >= frame.nowMin - minOf(ch.archiveDays, MAX_ARCHIVE_DAYS) * 1440

    private fun findCell(d: GuideWindowData, row: Int, start: Int): Int {
        if (row < d.firstRow || row >= d.firstRow + d.rowCount) return -1
        val r = row - d.firstRow
        val f = d.rowFirstCell[r]
        val idx = java.util.Arrays.binarySearch(d.cellStart, f, f + d.rowCellCount[r], start)
        return if (idx >= 0) idx else -1
    }

    companion object {
        /** O-2 trace sub-sections of `guide-draw`. */
        const val SECTION_CHROME = "guide-draw-chrome"
        const val SECTION_CELLS = "guide-draw-cells"
        const val SECTION_TEXT = "guide-draw-text"
        private const val PASS_ALL = 0
        private const val PASS_BG = 1
        private const val PASS_TEXT = 2
        private const val ROW_KEY_SEED = 0x6775696465726f77L
        const val ELLIPSIS = "…"
        const val NO_INFO = "No information"
        const val TIME_DASH = " – "
        private val NO_INFO_KEY = Hash64.of(NO_INFO)
        private val STYLES = GuideTextStyle.entries.toTypedArray()

        // Design px not in GuideGeometry (prototype / §3.6.3–§3.6.4).
        /** Decision 2026-10-19 chevrons (EDGE_CHEVRON, CLIP_CHEVRON): 12 × 24 design px. */
        private const val CHEVRON_W = 12f
        private const val CHEVRON_H = 24f
        /** Edge chevron left = panel left (x 64) + 3 = design x 67 (2 px clear of the 1 px border; ends at 79, 2 px before labelLeft 81). */
        private const val EDGE_CUE_DX = 3f
        /** Clip chevron left = the focused cell's visible left + 4. */
        private const val CLIP_CUE_DX = 4f
        /** The title moves right of the clip chevron when less than this gap is left, and then starts this far after it. */
        private const val CLIP_CUE_MIN_GAP = 2f
        private const val CLIP_CUE_TEXT_GAP = 4f
        private const val SHADOW_BLUR = 30f
        private const val SHADOW_DY = 12f
        private const val SKELETON_BAR_R = 4f
        private const val BUBBLE_W = 160f
        private const val BUBBLE_H = 88f
        private const val BUBBLE_R = 20f
        private const val BUBBLE_FILL = 0xE60A0D13.toInt()          // CANVAS at .9
        private val ACCENT_HALF = alpha(C.ACCENT, 0.5f)
        private const val MAX_ARCHIVE_DAYS = 3650
        private const val MAX_NUMBER_STRINGS = 4096

        private fun alpha(argb: Int, f: Float): Int {
            val a = ((argb ushr 24) * f + 0.5f).toInt().coerceIn(0, 255)
            return (a shl 24) or (argb and 0xFFFFFF)
        }
    }
}
