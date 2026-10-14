package app.thebox.core.guide.render

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
import app.thebox.core.guide.layout.CellFlags
import app.thebox.core.guide.layout.GridConstants
import app.thebox.core.guide.layout.rowTopPx
import kotlin.math.floor

/**
 * The grid renderer (docs/design/m3-guide.md §3.6, §4). One pass over [GuideCanvas] in device (window) px:
 *
 * 1. panel fill + border; 2. save, clip to the content box; 3. ruler (ticks, labels, the contextual label);
 * 4. clip to the rows area; per visible row: label cell, then save/clip to the row's timeline, archive band, cells
 * except the focused one, restore; 5. the focused cell (shadow, fill, ring, text, progress); 6. the now line
 * (glow, line, dot); 7. the channel-number bubble; 8. restore.
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
    private var clockProbe: String? = null

    // Per-draw scratch (set at the top of draw/focusedCellBounds).
    private var s = 1f
    private var ws = 0
    private var off = 0f
    private var vFrom = 0
    private var vTo = 0
    private var now = 0
    private var contentRight = 0f
    private var labels: TimeLabels? = null

    /** Text-fit cache counters (hand-off measurements). */
    val textFitHits: Long get() = titleFit.hits + nameFit.hits
    val textFitMisses: Long get() = titleFit.misses + nameFit.misses

    /** Drops the text caches (`onTrimMemory`, §4.7). */
    fun trimCaches() {
        titleFit.clear(); nameFit.clear(); numbers.clear()
    }

    // ------------------------------------------------------------------------------------------------------------
    // GuideRenderer
    // ------------------------------------------------------------------------------------------------------------

    override fun draw(canvas: GuideCanvas, frame: GuideFrame, windowStartMin: Int, scrollOffsetMin: Float, scrollRow: Float) {
        val m = frame.metrics ?: return
        begin(frame, m, windowStartMin, scrollOffsetMin)

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

        // 4. Rows.
        val rowsSave = canvas.save()
        canvas.clipRect(m.labelLeft, m.rowsTop, contentRight, m.rowsBottom)
        val rows = frame.rows
        var i = maxOf(0, floor(scrollRow).toInt())
        while (i < rows.size) {
            val top = m.rowTopPx(i - scrollRow)
            if (top >= m.rowsBottom) break
            val bottom = top + m.rowHeight
            if (bottom > m.rowsTop) drawRow(canvas, frame, m, i, top, bottom)
            i++
        }
        canvas.restoreToCount(rowsSave)

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

        canvas.restoreToCount(outer)
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
        if (l != null) {
            val probe = l.hm(0)
            if (probe !== clockProbe) { clockProbe = probe; titleFit.clear(); nameFit.clear() }
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
            minLabelX = x + width(ctx, GuideTextStyle.RULER_CONTEXT) + s * CONTEXT_GAP
        }
        val ruleW = maxOf(1f, s * G.PANEL_BORDER)
        val hasContext = minLabelX > m.timelineLeft
        var t = vFrom - l.minuteOfDay(vFrom) % G.TICK_MIN
        while (t <= vTo) {
            val x = m.xOf(t, ws, off)
            if (x >= contentRight) break
            if (hasContext && x < m.timelineLeft) { t = next30(l, t); continue } // scrolled off under the label
            val text = l.hm(t)
            val tw = width(text, GuideTextStyle.RULER)
            var lx = x + s * G.TICK_LABEL_PAD
            if (lx < minLabelX) lx = minLabelX
            if (x + ruleW > m.timelineLeft) canvas.fillRect(x, m.headerTop, x + ruleW, m.headerBottom, C.TICK_RULE)
            if (lx + tw > m.timelineLeft) canvas.drawText(text, 0, text.length, lx, baseline, GuideTextStyle.RULER, C.TEXT_TICK)
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
        if (ch == null) drawSkeletonLabel(canvas, m, top, bottom, i == focusRow) else drawLabel(canvas, frame, m, ch, top, bottom, i == focusRow)
        val sv = canvas.save()
        canvas.clipRect(m.timelineLeft, maxOf(top, m.rowsTop), contentRight, minOf(bottom, m.rowsBottom))
        if (ch == null) {
            drawSkeletonBlocks(canvas, m, top, bottom)
        } else {
            drawArchiveBand(canvas, m, ch, top, bottom)
            drawCells(canvas, frame, m, ch, i, top, bottom)
        }
        canvas.restoreToCount(sv)
    }

    private fun drawLabel(canvas: GuideCanvas, frame: GuideFrame, m: GuideMetrics, ch: GuideChannel, top: Float, bottom: Float, focusedRow: Boolean) {
        val l = m.labelLeft
        val r = l + m.labelWidth
        if (focusedRow) canvas.fillRoundRect(l, top, r, bottom, s * G.CELL_R, C.ROW_FOCUS_FILL)
        val mid = (top + bottom) / 2
        var x = l + s * G.LABEL_PAD_X
        var right = r - s * G.LABEL_PAD_X
        if (ch.identityHash == frame.tunedHash && frame.tunedHash != 0L) {
            canvas.fillCircle(right - s * TUNED_DOT / 2, mid, s * TUNED_DOT / 2, C.LIVE)
            right -= s * (TUNED_DOT + LABEL_ICON_GAP)
        }
        if (ch.archiveDays > 0) {
            val g = s * GLYPH
            val asset = if (isSuspect(frame, ch)) GuideAsset.ARCHIVE_GLYPH_HOLLOW else GuideAsset.ARCHIVE_GLYPH
            canvas.drawAsset(asset, right - g, mid - g / 2, right, mid + g / 2, C.TEXT_LABEL)
            right -= g + s * LABEL_ICON_GAP
        }
        val num = ch.number
        if (frame.showNumbers && num != null) {
            val str = numberString(num)
            canvas.drawText(str, 0, str.length, x, centredBaseline(top, bottom, GuideTextStyle.CH_NUMBER), GuideTextStyle.CH_NUMBER, C.TEXT_LABEL)
            x += width(str, GuideTextStyle.CH_NUMBER) + s * G.LABEL_GAP
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
        drawFitted(canvas, nameFit, name, Hash64.of(name), GuideTextStyle.CH_NAME, x, avail, -1f, rtlFlag = false, complex = false,
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
        if (!focused) {
            val rr = s * G.CELL_R
            val bw = s * G.CELL_BORDER
            canvas.fillRoundRect(x0, top, x1, bottom, rr, if (onNow) C.CELL_NOW_FILL else C.CELL_FILL)
            canvas.strokeRoundRect(x0 + bw / 2, top + bw / 2, x1 - bw / 2, bottom - bw / 2, rr - bw / 2, bw, C.CELL_BORDER)
        }

        // Text (§3.6.4).
        val narrow = x1 - x0 < s * G.NARROW_CELL_W
        val pad = s * if (narrow) G.CELL_PAD_X_NARROW else G.CELL_PAD_X
        var textX = maxOf(x0, m.timelineLeft) + pad
        val rightEdge = x1 - pad
        if (rightEdge - textX >= s * G.MIN_TEXT_W) {
            val twoLines = programme && !narrow
            val tStyle = if (programme) GuideTextStyle.CELL_TITLE else GuideTextStyle.NO_INFO
            val ti = tStyle.ordinal
            val hi = GuideTextStyle.CELL_TIME.ordinal
            val titleBase: Float
            val timeBase: Float
            if (twoLines) {
                val block = asc[ti] + desc[ti] + s * G.CELL_LINE_GAP + asc[hi] + desc[hi]
                titleBase = top + (bottom - top - block) / 2 + asc[ti]
                timeBase = titleBase + desc[ti] + s * G.CELL_LINE_GAP + asc[hi]
            } else {
                titleBase = centredBaseline(top, bottom, tStyle)
                timeBase = 0f
            }
            if (programme && start > now && frame.reminders.contains(ch.identityHash, d.cellRawStart[c])) {
                val rd = s * REMINDER_DOT / 2
                canvas.fillCircle(textX + rd, titleBase - asc[ti] * 0.35f, rd, if (focused) C.CANVAS else C.LIVE)
                textX += s * (REMINDER_DOT + REMINDER_GAP)
            }
            val title = if (programme) d.cellTitle[c] else NO_INFO
            if (title != null && rightEdge - textX > 0f) {
                val key = if (programme) d.cellTitleKey[c] else NO_INFO_KEY
                val known = if (programme) scaledWidth(d.cellTitleWidth[c], d.metricsScale) else -1f
                drawFitted(canvas, titleFit, title, key, tStyle, textX, rightEdge - textX, known,
                    rtlFlag = (flags and CellFlags.RTL) != 0, complex = (flags and CellFlags.COMPLEX) != 0,
                    baseline = titleBase, argb = textColor, alignRight = true)
            }
            if (twoLines) drawTimeLine(canvas, d, c, textX, rightEdge, timeBase, textColor, archived, suspect)
        }

        if (onNow) {
            val ph = s * G.PROGRESS_H
            val bx0 = x0 + s * PROGRESS_INSET_X
            val bx1 = x1 - s * PROGRESS_INSET_X
            val pb = bottom - s * PROGRESS_INSET_B
            val frac = (now - start).toFloat() / (end - start)
            if (bx1 > bx0) canvas.fillRect(bx0, pb - ph, bx0 + (bx1 - bx0) * frac, pb, if (focused) C.ACCENT else C.CELL_PROGRESS)
        }
    }

    private fun drawTimeLine(canvas: GuideCanvas, d: GuideWindowData, c: Int, x: Float, rightEdge: Float, baseline: Float, textColor: Int, archived: Boolean, suspect: Boolean) {
        val color = alpha(textColor, C.TEXT_TIME_ALPHA)
        var tx = x
        if (archived) {
            val g = s * GLYPH
            canvas.drawAsset(if (suspect) GuideAsset.ARCHIVE_GLYPH_HOLLOW else GuideAsset.ARCHIVE_GLYPH,
                tx, baseline - g * 0.9f, tx + g, baseline + g * 0.1f, textColor)
            tx += g + s * GLYPH_GAP
        }
        val avail = rightEdge - tx
        if (avail <= 0f) return
        val text = d.cellTimeText[c]
        if (text != null) {
            val w = scaledWidth(d.cellTimeWidth[c], d.metricsScale).let { if (it < 0f) width(text, GuideTextStyle.CELL_TIME) else it }
            if (w <= avail) canvas.drawText(text, 0, text.length, tx, baseline, GuideTextStyle.CELL_TIME, color)
            return
        }
        // No pre-built time text (snapshot built without one): three pre-built pieces, drawn whole or not at all.
        val l = labels ?: return
        val a = l.hm(d.cellStart[c])
        val b = l.hm(d.cellEnd[c])
        val wa = width(a, GuideTextStyle.CELL_TIME)
        val wd = width(TIME_DASH, GuideTextStyle.CELL_TIME)
        val wb = width(b, GuideTextStyle.CELL_TIME)
        if (wa + wd + wb > avail) return
        canvas.drawText(a, 0, a.length, tx, baseline, GuideTextStyle.CELL_TIME, color)
        canvas.drawText(TIME_DASH, 0, TIME_DASH.length, tx + wa, baseline, GuideTextStyle.CELL_TIME, color)
        canvas.drawText(b, 0, b.length, tx + wa + wd, baseline, GuideTextStyle.CELL_TIME, color)
    }

    // ------------------------------------------------------------------------------------------------------------
    // Focused cell, bubble
    // ------------------------------------------------------------------------------------------------------------

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
            if (ch != null && c >= 0) drawCell(canvas, frame, m, ch, d!!, c, top, bottom, focused = true)
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
        val rtl = rtlFlag || fit.lastRtl
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
        const val ELLIPSIS = "…"
        const val NO_INFO = "No information"
        const val TIME_DASH = " – "
        private val NO_INFO_KEY = Hash64.of(NO_INFO)
        private val STYLES = GuideTextStyle.entries.toTypedArray()

        // Design px not in GuideGeometry (prototype / §3.6.3–§3.6.4).
        private const val TUNED_DOT = 7f
        private const val GLYPH = 12f
        private const val GLYPH_GAP = 6f
        private const val LABEL_ICON_GAP = 8f
        private const val REMINDER_DOT = 8f
        private const val REMINDER_GAP = 6f
        private const val PROGRESS_INSET_X = 4f
        private const val PROGRESS_INSET_B = 2f
        private const val SHADOW_BLUR = 30f
        private const val SHADOW_DY = 12f
        private const val CONTEXT_GAP = 12f
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
