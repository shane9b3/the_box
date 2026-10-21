package app.thebox.ui.full

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import app.thebox.core.full.PillKind
import app.thebox.core.full.ScrubFocus
import app.thebox.core.full.ScrubScope
import app.thebox.core.full.ScrubState
import app.thebox.ui.theme.DesignTokens
import java.util.TimeZone

/**
 * The scrub track as one draw node (docs/design/m4-player-ux.md §3.5, §3.9, §4.1): at most 13 canvas ops per frame
 * (budget 14) and **no allocation per frame**: the paints, the [RectF] and the label buffers are created once here,
 * labels are written digit by digit through [TimeChars], and text runs go through `drawText(char[], …)`.
 *
 * The node spans the bar's width (design x 64…1856) and y 916…996; [scale] is device px per design px. Ops, in order:
 * 1. before the archive limit (white .05), 2. archive-available span (white .22), 3. after the live edge (base white
 * .08), 4. played span (FG), 5. live-edge marker (2 × 18 `#FF4D5E`), 6. playhead (14 px FG circle), 7. its accent ring
 * when the bar is focused, 8. preview (hollow 14 px accent circle), 9. preview bubble, 10. its label, 11. left label
 * (`barFrom`), 12. right label (`barTo`), 13. centre label (clock of the shown position and `−00:17:50` / `Catch-up`).
 *
 * Times are programme-frame ms; labels add [ScrubState.displayShiftMs] and the device zone offset. The playhead and the
 * live edge are extrapolated from [ScrubState.atMs] with [nowMs] (§2.1 `ScrubState` KDoc), so a 1 Hz redraw needs no
 * new state object.
 *
 * (M6 time-shift, docs/design/m6-timeshift.md §11.4, U-S3) [ScrubScope.REWIND_WINDOW] (a channel without catch-up) is
 * drawn by [drawWindow]: the window `[edge − length, edge]` moves with the edge; the track white 18 % 8 px, the saved
 * part white 45 %, watched (saved start → playhead) the accent; not-saved ranges keep the track colour over a 4 px
 * `#F2A93B` underline and read "Not saved" (22 px) when at least 120 px wide (no hatching); the scoped programme's
 * boundaries are ticks with its title; under the bar "Rewind from 21:02" left and "LIVE" right with the live time above
 * the marker (24 px), and the centre label as above. Still no allocation per frame. (Fix pass, UX review S11, the
 * 2026-10-17 legibility ruling) Window-mode text is never under 22 px: the live clock 24 px, the programme title and
 * the centre time 22 px; the title is cut short (or left out) so it never runs into the live clock.
 */
class ScrubBarRenderer(private val scale: Float) {
    /** 24 h clock (guide setting); 12 h writes `h:mm` like the guide. */
    var clock24: Boolean = true
    /** Device zone, captured by the composable (TimeZone.getDefault() clones, so never per frame). */
    var zone: TimeZone = TimeZone.getTimeZone("UTC")

    private var catchupLabel = CharArray(0)
    private var liveLabel = CharArray(0)
    // (M6 §11.4) "Rewind from %1$s" split around the time, "LIVE", "Not saved".
    private var rewindFromPrefix = CharArray(0)
    private var rewindFromSuffix = CharArray(0)
    private var liveEnd = CharArray(0)
    private var notSavedLabel = CharArray(0)
    private val endLabel = CharArray(64)
    private val liveClock = CharArray(8)
    private val wTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val endText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f * scale; color = FG70 }
    private val notSavedText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f * scale; color = UNSAVED; textAlign = Paint.Align.CENTER }
    private val tickText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = WINDOW_TITLE_PX * scale; color = FG70 }
    /** (S11) The live time above the marker, 24 px. */
    private val windowClock = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = WINDOW_CLOCK_PX * scale; color = FG70; textAlign = Paint.Align.RIGHT }
    /** The widest live time ("88:88"), measured once per typeface: the title stops short of it (no per-frame measure). */
    private var clockW = windowClock.measureText(CLOCK_SAMPLE)
    /** (S11) The centre label in window mode, 22 px. */
    private val windowCentre = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = WINDOW_CENTRE_PX * scale; color = FG; textAlign = Paint.Align.CENTER }

    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = PlayerUi.RING_W * scale }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f * scale; color = FG70 }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f * scale; color = FG; textAlign = Paint.Align.CENTER }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f * scale; color = CANVAS; textAlign = Paint.Align.CENTER }

    private val left = CharArray(8)
    private val right = CharArray(8)
    private val centre = CharArray(48)
    private val bubble = CharArray(16)
    /** Width of one mono digit at 15 design px, measured once per typeface (the bubble is sized from it). */
    private var monoAdvance = 9f * scale

    /** Localised words for the centre label, set once by the composable. */
    fun setLabels(catchup: String, live: String) {
        catchupLabel = catchup.toCharArray()
        liveLabel = live.toCharArray()
    }

    /** (M6 §11.4) The rewind window's words: [rewindFrom] holds one `%1$s` for the time; [liveEnd] "LIVE"; "Not saved". */
    fun setWindowLabels(rewindFrom: String, live: String, notSaved: String) {
        val i = rewindFrom.indexOf("%1\$s")
        rewindFromPrefix = (if (i >= 0) rewindFrom.substring(0, i) else rewindFrom).toCharArray()
        rewindFromSuffix = (if (i >= 0) rewindFrom.substring(i + 4) else "").toCharArray()
        liveEnd = live.toCharArray()
        notSavedLabel = notSaved.toCharArray()
    }

    /** JetBrains Mono from the app's fonts (null in tests: the platform default). */
    fun setTypeface(typeface: Typeface?) {
        small.typeface = typeface
        big.typeface = typeface
        bubbleText.typeface = typeface
        windowClock.typeface = typeface
        clockW = windowClock.measureText(CLOCK_SAMPLE)
        windowCentre.typeface = typeface
        monoAdvance = small.measureText("0").takeIf { it > 0f } ?: (9f * scale)
    }

    fun draw(canvas: Canvas, state: ScrubState, nowMs: Long, widthPx: Float, heightPx: Float) {
        if (state.scope == ScrubScope.REWIND_WINDOW) { drawWindow(canvas, state, nowMs, widthPx); return }
        val s = scale
        val trackL = (PlayerUi.TRACK_L - PlayerUi.BAR_L) * s
        val trackW = PlayerUi.TRACK_W * s
        val trackR = trackL + trackW
        val trackT = (PlayerUi.TRACK_T - PlayerUi.SCRUB_NODE_T) * s
        val trackB = trackT + PlayerUi.TRACK_H * s
        val cy = (trackT + trackB) / 2f
        val radius = PlayerUi.TRACK_R * s

        val dt = (nowMs - state.atMs).coerceAtLeast(0)
        val live = state.liveEdgeMs + dt
        val play = if (state.atLive) live else state.playheadMs + if (state.advancing) dt else 0L
        val from = state.barFromMs
        val span = (state.barToMs - from).coerceAtLeast(1L).toFloat()

        val xa = xOf(state.archiveFromMs, from, span, trackL, trackW)
        val xl = xOf(live, from, span, trackL, trackW)

        // 1–3: the three spans of the base.
        if (xa > trackL) {
            fill.color = BEFORE_ARCHIVE
            rect.set(trackL, trackT, xa, trackB)
            canvas.drawRoundRect(rect, radius, radius, fill)
        }
        if (xl > xa) {
            fill.color = ARCHIVE
            rect.set(xa, trackT, xl, trackB)
            canvas.drawRect(rect, fill)
        }
        if (trackR > xl) {
            fill.color = BASE
            rect.set(xl, trackT, trackR, trackB)
            canvas.drawRoundRect(rect, radius, radius, fill)
        }
        // 4: played.
        val xp = xOf(play, from, span, trackL, trackW)
        val playedFrom = maxOf(trackL, xa)
        if (xp > playedFrom) {
            fill.color = FG
            rect.set(playedFrom, trackT, xp, trackB)
            canvas.drawRect(rect, fill)
        }
        // 5: live edge marker, when the edge is inside the bar.
        if (live >= from && live <= state.barToMs) {
            fill.color = LIVE
            val half = PlayerUi.LIVE_MARK_W * s / 2f
            val h = PlayerUi.LIVE_MARK_H * s / 2f
            rect.set(xl - half, cy - h, xl + half, cy + h)
            canvas.drawRect(rect, fill)
        }
        // 6–7: playhead (+ ring when the bar has focus).
        val r = PlayerUi.PLAYHEAD_D * s / 2f
        fill.color = FG
        canvas.drawCircle(xp, cy, r, fill)
        if (state.focused && state.focus == ScrubFocus.BAR) {
            stroke.color = ACCENT
            canvas.drawCircle(xp, cy, r + stroke.strokeWidth / 2f, stroke)
        }
        // 8–10: the uncommitted preview.
        val preview = state.previewMs
        if (preview != null) {
            val xv = xOf(preview, from, span, trackL, trackW)
            stroke.color = ACCENT
            canvas.drawCircle(xv, cy, r - stroke.strokeWidth / 2f, stroke)
            val n = TimeChars.clock(bubble, 0, preview + state.displayShiftMs, zone.getOffset(preview + state.displayShiftMs), clock24, seconds = true)
            val half = (n * monoAdvance) / 2f + 10f * s
            val bx = xv.coerceIn(half, widthPx - half)
            fill.color = ACCENT
            rect.set(bx - half, 0f, bx + half, PlayerUi.BUBBLE_H * s)
            canvas.drawRoundRect(rect, 8f * s, 8f * s, fill)
            canvas.drawText(bubble, 0, n, bx, 19f * s, bubbleText)
        }
        // 11–13: labels under the track.
        val labelY = trackB + 26f * s
        small.textAlign = Paint.Align.LEFT
        val nl = TimeChars.clock(left, 0, from + state.displayShiftMs, zone.getOffset(from + state.displayShiftMs), clock24, seconds = false)
        canvas.drawText(left, 0, nl, trackL, labelY, small)
        small.textAlign = Paint.Align.RIGHT
        val to = state.barToMs + state.displayShiftMs
        val nr = TimeChars.clock(right, 0, to, zone.getOffset(to), clock24, seconds = false)
        canvas.drawText(right, 0, nr, trackR, labelY, small)
        val shown = preview ?: play
        val shownDisplay = shown + state.displayShiftMs
        var n = TimeChars.clock(centre, 0, shownDisplay, zone.getOffset(shownDisplay), clock24, seconds = true)
        n = appendStateLabel(n, state, preview, live, nowMs)
        canvas.drawText(centre, 0, n, (trackL + trackR) / 2f, labelY + 2f * s, big)
    }

    /** (M6 §11.4) The rewind window of a channel without catch-up; see the class KDoc. */
    fun drawWindow(canvas: Canvas, state: ScrubState, nowMs: Long, widthPx: Float) {
        val s = scale
        val trackL = (PlayerUi.TRACK_L - PlayerUi.BAR_L) * s
        val trackW = PlayerUi.TRACK_W * s
        val trackR = trackL + trackW
        val cy = (PlayerUi.TRACK_T + PlayerUi.TRACK_H / 2f - PlayerUi.SCRUB_NODE_T) * s
        val half = WINDOW_TRACK_H * s / 2f
        val trackT = cy - half
        val trackB = cy + half
        val radius = half

        val dt = (nowMs - state.atMs).coerceAtLeast(0)
        val live = state.liveEdgeMs + dt
        val from = state.barFromMs + dt // the window moves with the edge
        val to = state.barToMs + dt
        val span = (to - from).coerceAtLeast(1L).toFloat()
        val play = if (state.atLive) live else state.playheadMs + if (state.advancing) dt else 0L
        val saved = state.savedFromMs ?: state.archiveFromMs

        // Track, saved, watched.
        wTrack.color = WINDOW_TRACK
        rect.set(trackL, trackT, trackR, trackB)
        canvas.drawRoundRect(rect, radius, radius, wTrack)
        val xs = xOf(saved, from, span, trackL, trackW)
        val xl = xOf(live, from, span, trackL, trackW)
        if (xl > xs) {
            wTrack.color = WINDOW_SAVED
            rect.set(xs, trackT, xl, trackB)
            canvas.drawRect(rect, wTrack)
        }
        val xp = xOf(play, from, span, trackL, trackW)
        if (xp > xs) {
            wTrack.color = ACCENT
            rect.set(xs, trackT, xp, trackB)
            canvas.drawRect(rect, wTrack)
        }
        // Not saved: the track colour over a 4 px underline, labelled when wide enough.
        val gaps = state.notSaved
        for (i in 0 until gaps.count) {
            val a = xOf(gaps.startAt(i), from, span, trackL, trackW)
            val b = xOf(gaps.endAt(i), from, span, trackL, trackW)
            if (b <= a) continue
            wTrack.color = WINDOW_TRACK_OPAQUE
            rect.set(a, trackT, b, trackB)
            canvas.drawRect(rect, wTrack)
            wTrack.color = UNSAVED
            rect.set(a, trackB + 2f * s, b, trackB + 6f * s)
            canvas.drawRect(rect, wTrack)
            if (b - a >= NOT_SAVED_MIN_W * s && notSavedLabel.isNotEmpty()) {
                canvas.drawText(notSavedLabel, 0, notSavedLabel.size, (a + b) / 2f, trackT - 8f * s, notSavedText)
            }
        }
        // The live time's left edge (S11): the title stops short of it.
        val liveDisplay = live + state.displayShiftMs
        val nlc = TimeChars.clock(liveClock, 0, liveDisplay, zone.getOffset(liveDisplay), clock24, seconds = false)
        val clockLeft = xl - clockW
        // Programme boundaries: ticks, with the scoped programme's title over its start.
        val p = state.programme
        if (p != null) {
            val ps = app.thebox.core.full.state.ScrubModel.startMs(p)
            val pe = app.thebox.core.full.state.ScrubModel.endMs(p)
            wTrack.color = FG70
            if (ps in from..to) {
                val x = xOf(ps, from, span, trackL, trackW)
                rect.set(x - s, trackT - 6f * s, x + s, trackB + 6f * s)
                canvas.drawRect(rect, wTrack)
                tickText.textAlign = Paint.Align.LEFT
                val tx = (x + 4f * s).coerceAtMost(trackR - 200f * s)
                val room = clockLeft - TITLE_CLOCK_GAP * s - tx
                if (room > 0f) {
                    val n = tickText.breakText(p.title, true, room, null)
                    if (n > 0) canvas.drawText(p.title, 0, n, tx, trackT - 10f * s, tickText)
                }
            }
            if (pe in from..to) {
                val x = xOf(pe, from, span, trackL, trackW)
                rect.set(x - s, trackT - 6f * s, x + s, trackB + 6f * s)
                canvas.drawRect(rect, wTrack)
            }
        }
        // Live marker with the live time above it, playhead (+ focus ring), preview.
        fill.color = LIVE
        rect.set(xl - PlayerUi.LIVE_MARK_W * s / 2f, cy - PlayerUi.LIVE_MARK_H * s / 2f, xl + PlayerUi.LIVE_MARK_W * s / 2f, cy + PlayerUi.LIVE_MARK_H * s / 2f)
        canvas.drawRect(rect, fill)
        canvas.drawText(liveClock, 0, nlc, xl, trackT - 10f * s, windowClock)
        val r = PlayerUi.PLAYHEAD_D * s / 2f
        fill.color = FG
        canvas.drawCircle(xp, cy, r, fill)
        if (state.focused && state.focus == ScrubFocus.BAR) {
            stroke.color = ACCENT
            canvas.drawCircle(xp, cy, r + stroke.strokeWidth / 2f, stroke)
        }
        val preview = state.previewMs
        if (preview != null) {
            stroke.color = ACCENT
            canvas.drawCircle(xOf(preview, from, span, trackL, trackW), cy, r - stroke.strokeWidth / 2f, stroke)
        }
        // End labels (24 px): "Rewind from 21:02" (display frame) and "LIVE"; the centre as in the programme scope.
        val labelY = trackB + 30f * s
        var n = TimeChars.copy(endLabel, 0, rewindFromPrefix)
        val savedDisplay = saved + state.displayShiftMs
        n = TimeChars.clock(endLabel, n, savedDisplay, zone.getOffset(savedDisplay), clock24, seconds = false)
        if (n + rewindFromSuffix.size <= endLabel.size) n = TimeChars.copy(endLabel, n, rewindFromSuffix)
        endText.textAlign = Paint.Align.LEFT
        canvas.drawText(endLabel, 0, n, trackL, labelY, endText)
        endText.textAlign = Paint.Align.RIGHT
        canvas.drawText(liveEnd, 0, liveEnd.size, trackR, labelY, endText)
        val shown = preview ?: play
        val shownDisplay = shown + state.displayShiftMs
        var c = TimeChars.clock(centre, 0, shownDisplay, zone.getOffset(shownDisplay), clock24, seconds = true)
        c = appendStateLabel(c, state, preview, live, nowMs)
        canvas.drawText(centre, 0, c, (trackL + trackR) / 2f, labelY, windowCentre)
    }

    /** `  −00:17:50` while behind (or paused behind) the live edge, `  Catch-up` in catch-up, `  Live` on the edge. */
    private fun appendStateLabel(at: Int, state: ScrubState, preview: Long?, live: Long, nowMs: Long): Int {
        val pill = state.pill
        val behind: Long = when {
            preview != null && (state.atLive || pill.kind == PillKind.BEHIND || pill.kind == PillKind.PAUSED) -> live - preview
            pill.kind == PillKind.BEHIND || (pill.kind == PillKind.PAUSED && pill.behindMs > 0) ->
                pill.behindMs + if (pill.behindGrows) (nowMs - pill.atMs).coerceAtLeast(0) else 0L
            else -> -1L
        }
        if (behind > 0 && at + 14 <= centre.size) {
            centre[at] = ' '; centre[at + 1] = ' '
            return TimeChars.behind(centre, at + 2, behind)
        }
        val word = when {
            state.atLive && preview == null -> liveLabel
            pill.kind == PillKind.CATCH_UP || (pill.kind == PillKind.PAUSED && !state.atLive) -> catchupLabel
            else -> null
        } ?: return at
        if (word.isEmpty() || at + 2 + word.size > centre.size) return at
        centre[at] = ' '; centre[at + 1] = ' '
        return TimeChars.copy(centre, at + 2, word)
    }

    private fun xOf(t: Long, from: Long, span: Float, trackL: Float, trackW: Float): Float =
        trackL + ((t - from) / span).coerceIn(0f, 1f) * trackW

    private companion object {
        val FG = DesignTokens.Colors.Fg.toArgb()
        val FG70 = DesignTokens.Colors.Fg.copy(alpha = 0.7f).toArgb()
        val CANVAS = DesignTokens.Colors.Canvas.toArgb()
        val ACCENT = DesignTokens.Colors.Accent.toArgb()
        val LIVE = PlayerUi.LiveEdge.toArgb()
        val BASE = PlayerUi.TrackBase.toArgb()
        val ARCHIVE = PlayerUi.TrackArchive.toArgb()
        val BEFORE_ARCHIVE = PlayerUi.TrackBeforeArchive.toArgb()
        // (M6 §11.4)
        val WINDOW_TRACK = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f).toArgb()
        /** The track colour drawn opaque over the saved fill (the bar's own fill under it). */
        val WINDOW_TRACK_OPAQUE = androidx.compose.ui.graphics.Color(0xFF3A3D45).toArgb()
        val WINDOW_SAVED = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.45f).toArgb()
        val UNSAVED = androidx.compose.ui.graphics.Color(0xFFF2A93B).toArgb()
        const val WINDOW_TRACK_H = 8f
        /** (Fix pass S11) Window-mode text sizes (design px) and the gap kept between the title and the live time. */
        const val WINDOW_CLOCK_PX = 24f
        const val WINDOW_TITLE_PX = 22f
        const val WINDOW_CENTRE_PX = 22f
        const val TITLE_CLOCK_GAP = 16f
        const val CLOCK_SAMPLE = "88:88"
        /** "Not saved" is written on a range at least this wide (design px). */
        const val NOT_SAVED_MIN_W = 120f
    }
}
