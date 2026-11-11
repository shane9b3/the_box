// Contract (M3 commit 0): docs/design/m3-guide.md §2.3. Verbatim; changes go through the lead.
package app.thebox.core.guide

/** Opaque image handle (an Android Bitmap in :ui). */
interface GuideImage { val widthPx: Int; val heightPx: Int }

/** Pre-rendered assets the renderer draws as images (no runtime blur, B1.3/B2.6). */
enum class GuideAsset {
    FOCUS_SHADOW, NOW_GLOW, ARCHIVE_GLYPH, ARCHIVE_GLYPH_HOLLOW, LOCK_GLYPH,
    /**
     * (decision 2026-10-19, guide Left and Groups) Left-pointing chevrons, 12 × 24 design px, drawn as masks: EDGE_CHEVRON
     * in the grid's left gutter while the next single Left opens Groups; CLIP_CHEVRON at the focused cell's left inner
     * edge while that cell is clipped by the window and an earlier watchable cell exists.
     */
    EDGE_CHEVRON, CLIP_CHEVRON,
}

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
    /**
     * (code review 2026-10-14, O-2) Trace sub-sections of the draw pass (`guide-draw-chrome`, `-cells`, `-text`); the
     * Android adapter maps them to `android.os.Trace`. [name] is a constant (no allocation). Default: nothing.
     */
    fun beginSection(name: String) {}
    fun endSection() {}

    /**
     * (code review 2026-10-14, O-2) Per-row recording. The renderer calls [beginRow] before drawing one row pass with a
     * [key] that covers everything the row's ops depend on except its vertical position [top]. True: a recording for
     * [key] was replayed (translated to [top]) and the renderer skips the row; false: the renderer draws the row and then
     * calls [endRow]. Default: no recording (every row is drawn directly; the counting canvas and API ≤ 28).
     */
    fun beginRow(key: Long, top: Float): Boolean = false
    fun endRow() {}
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
    /** (code review 2026-10-14, O-2) Bumped whenever what [get] returns can change (a load, a trim, a clear). */
    val version: Long get() = 0
    fun get(url: String): GuideImage?
    /**
     * (2026-11-10, review S3) [url] is on screen in a row the renderer replayed from its recording (O-2), so [get] was
     * not called for it this draw. Keeps a pending load alive and the cached image recently used. It may start a load
     * exactly where [get] would (not cached, not failed, not in flight): a recording can outlive a load that was
     * cancelled while its row was off screen, and nothing else would restart it. Must not allocate otherwise.
     * Default: nothing (a cache that never cancels loads).
     */
    fun touch(url: String) {}
    /** True when [url] failed this session (draw the name instead, do not retry). */
    fun failed(url: String): Boolean
    var onLoaded: (() -> Unit)?
    /** Trim to the cap, least recently drawn first (called on lineup change). */
    fun trim()
    /** (amended 2026-10-14, §2.13 item 6) `onTrimMemory` ≥ RUNNING_LOW: drop every cached image (§4.7). */
    fun clear() = trim()
}

/** Channel rows of the current lineup (WP-B's cache). Main-thread reads only. */
interface GuideRows {
    val size: Int
    val version: Long
    /** null = block not loaded yet (draw a skeleton). */
    fun channel(index: Int): GuideChannel?
    /** Lineup index of [rowId], −1 if not in this lineup. O(n) scan, call on events only, never per frame. */
    fun indexOf(rowId: Long): Int
    /**
     * (amended 2026-10-14, §2.13 item 8) [GuideLineup.version] of the lineup these rows belong to (0 = none). The
     * pager stamps it on every snapshot, and the state machine drops a snapshot whose version is not the current
     * lineup's (S10a).
     */
    val lineupVersion: Long get() = 0L

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
    /**
     * (amended 2026-10-14, §2.13 item 2) False: the host snaps to the new target instead of animating. Set by the
     * state machine from the navigator's `GridMoveResult.Moved.animate` on every placement.
     */
    var animate: Boolean = true
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
    /**
     * (decision 2026-10-19) True while the next single Left in the grid opens Groups (no earlier watchable cell): the
     * renderer draws [GuideAsset.EDGE_CHEVRON] beside the focused row. Kept by the state machine on every publish.
     */
    var leftEdgeCue: Boolean = false
    /** (decision 2026-10-19) True while the focused cell starts before the window and Left will move: [GuideAsset.CLIP_CHEVRON]. */
    var clipCue: Boolean = false
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
    /** (amended 2026-10-14, §2.13 item 6) `onTrimMemory` ≥ RUNNING_LOW: drop the text-fit caches (§4.7). */
    fun trimCaches() = Unit
}
