// Contract (M3 commit 0): docs/design/m3-guide.md §2.3. Verbatim; changes go through the lead.
package app.thebox.core.guide

/** Opaque image handle (an Android Bitmap in :ui). */
interface GuideImage { val widthPx: Int; val heightPx: Int }

/** Pre-rendered assets the renderer draws as images (no runtime blur, B1.3/B2.6). */
enum class GuideAsset { FOCUS_SHADOW, NOW_GLOW, ARCHIVE_GLYPH, ARCHIVE_GLYPH_HOLLOW, LOCK_GLYPH }

/**
 * Toolkit-agnostic drawing surface in device px (architecture review B2.9 step 3). Implementations must not
 * allocate per call. Colours are ARGB ints.
 */
interface GuideCanvas {
    fun save(): Int
    fun restoreToCount(count: Int)
    fun clipRect(l: Float, t: Float, r: Float, b: Float)
    fun fillRect(l: Float, t: Float, r: Float, b: Float, argb: Int)
    fun fillRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, argb: Int)
    fun strokeRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, width: Float, argb: Int)
    fun fillCircle(cx: Float, cy: Float, radius: Float, argb: Int)
    /** Draws text[start, end) with its baseline at [baseline]. */
    fun drawText(text: String, start: Int, end: Int, x: Float, baseline: Float, style: GuideTextStyle, argb: Int)
    fun drawImage(image: GuideImage, l: Float, t: Float, r: Float, b: Float, alpha: Float)
    /** Draws a pre-rendered asset stretched to the rect, multiplied by [argb] where the asset is a mask. */
    fun drawAsset(asset: GuideAsset, l: Float, t: Float, r: Float, b: Float, argb: Int)
}

/** Text measurement in device px. One instance per thread (Paint is not thread-safe). */
interface GuideTextMeasurer {
    val scale: Float
    fun width(text: String, start: Int, end: Int, style: GuideTextStyle): Float
    /** Count of chars of text[start, end) that fit in [maxWidth] measuring forwards (Paint.breakText). */
    fun fit(text: String, start: Int, end: Int, style: GuideTextStyle, maxWidth: Float): Int
    /** Positive distances from baseline, device px. */
    fun ascent(style: GuideTextStyle): Float
    fun descent(style: GuideTextStyle): Float
}

/** Logo cache (§3.7). Non-blocking: [get] returns null and starts a load; [onLoaded] fires on main. */
interface GuideImages {
    fun get(url: String): GuideImage?
    /** True when [url] failed this session (draw the name instead, do not retry). */
    fun failed(url: String): Boolean
    var onLoaded: (() -> Unit)?
    /** Trim to the cap, least recently drawn first (called on lineup change). */
    fun trim()
}

/** Channel rows of the current lineup (WP-B's cache). Main-thread reads only. */
interface GuideRows {
    val size: Int
    val version: Long
    /** null = block not loaded yet (draw a skeleton). */
    fun channel(index: Int): GuideChannel?
    /** Lineup index of [rowId], −1 if not in this lineup. O(n) scan, call on events only, never per frame. */
    fun indexOf(rowId: Long): Int

    companion object {
        val EMPTY: GuideRows = object : GuideRows {
            override val size = 0; override val version = 0L
            override fun channel(index: Int): GuideChannel? = null
            override fun indexOf(rowId: Long) = -1
        }
    }
}

/** Sorted set of `Hash64.long(identityHash, rawStartMin)`; binary search, no allocation. */
class ReminderIndex(private val keys: LongArray) {
    fun contains(identityHash: Long, rawStartMin: Int): Boolean =
        java.util.Arrays.binarySearch(keys, app.thebox.core.common.Hash64.long(identityHash, rawStartMin.toLong())) >= 0
    val size: Int get() = keys.size
    companion object {
        val EMPTY = ReminderIndex(LongArray(0))
        fun of(entries: List<Pair<Long, Int>>): ReminderIndex =
            ReminderIndex(LongArray(entries.size) { app.thebox.core.common.Hash64.long(entries[it].first, entries[it].second.toLong()) }.also { it.sort() })
    }
}

/**
 * Everything the renderer reads, owned and mutated by the state machine on main. The host reads
 * GuideStateMachine.frameVersion inside its draw lambda so a change invalidates the draw only.
 */
class GuideFrame {
    var metrics: GuideMetrics? = null
    var rows: GuideRows = GuideRows.EMPTY
    var data: GuideWindowData? = null
    /** Target window; the host animates towards these (§3.6.5). */
    var windowStartMin: Int = 0
    var topRow: Int = 0
    var focus: GridFocus? = null
    /** False while focus is in another zone: the row highlight stays, the focused cell draws as "selected" (ring only). */
    var gridFocused: Boolean = true
    var nowMin: Int = 0
    var earliestMin: Int = Int.MIN_VALUE
    var tunedHash: Long = 0
    var reminders: ReminderIndex = ReminderIndex.EMPTY
    /** Sorted identity hashes whose archive failed this session (hollow ↺, product §3.5). */
    var suspectArchive: LongArray = LongArray(0)
    var showLogos: Boolean = true
    var showNumbers: Boolean = true
    var lowEnd: Boolean = false
    var contextLabel: String = ""
    /** Channel-number bubble while Up/Down is held (product S7); −1 = hidden. */
    var bubbleNumber: Int = -1
    var labels: TimeLabels? = null
    var images: GuideImages? = null
}

interface GuideRenderer {
    /**
     * Draws the whole grid panel. The animated window start is [windowStartMin] (Int epoch minutes, normally
     * `frame.windowStartMin`) + [scrollOffsetMin] (small Float, about −180..+180; §2.12 item 3); [scrollRow] is the
     * animated top row. Zero allocation.
     */
    fun draw(canvas: GuideCanvas, frame: GuideFrame, windowStartMin: Int, scrollOffsetMin: Float, scrollRow: Float)
    /** Device-px bounds of the focused cell at the current scroll into [out] (l, t, r, b); false if off-screen. */
    fun focusedCellBounds(frame: GuideFrame, windowStartMin: Int, scrollOffsetMin: Float, scrollRow: Float, out: FloatArray): Boolean
    /** Accessibility text for the focused cell (§3.6.6). Allocates; call debounced. */
    fun describeFocus(frame: GuideFrame): String
}
