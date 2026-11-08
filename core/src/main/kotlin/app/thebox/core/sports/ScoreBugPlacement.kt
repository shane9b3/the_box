package app.thebox.core.sports

import kotlin.math.round

/**
 * Where the bug sits given the other overlays (docs/design/m6-score-bug.md §4.3, §4.4; WP-1). Pure. Returns the pill's
 * box in design px, or null when it is hidden. In place mode ([PlacementInput.placing]) only `modal` and `screenOff`
 * apply (U-B1).
 *
 * Order of the rules: modal / screen off hide; place mode stops there; subtitles move a bottom corner to the top on
 * the same side; a bottom corner hides for the passive info bar and slides above a user bar ([SportsConstants.SCORE_BAR_GAP]);
 * the toast hides bottom left, the reminder bottom right; every other overlay (channel list, stats, group card,
 * picker, number entry, probing line) hides the bug only when its box actually intersects the bug's (U-N1).
 */
object ScoreBugPlacement {
    private const val SCREEN_W = 1920f
    private const val SCREEN_H = 1080f

    fun place(input: PlacementInput, geo: OverlayGeometry): Box? {
        if (input.screenOff || input.modal) return null
        if (input.placing) return cornerBox(input.corner, input.size, input.league)

        val corner = if (input.textTrackOn) toTop(input.corner) else input.corner
        var box = cornerBox(corner, input.size, input.league)

        if (isBottom(corner)) {
            when (input.bar) {
                BottomBar.NONE -> Unit
                BottomBar.PASSIVE_INFO -> return null
                BottomBar.USER_BAR -> box = above(box, geo.infoBarTop)
                BottomBar.QUICK -> box = above(box, geo.quickBarTop)
            }
            if (input.toast && isLeft(corner)) return null
            if (input.reminder && !isLeft(corner)) return null
        }

        val active = buildList {
            if (input.channelList) add(geo.channelList)
            if (input.stats) add(geo.stats)
            if (input.groupCard) add(geo.groupCard)
            if (input.picker) add(geo.picker)
            if (input.numberEntry) add(geo.numberEntry)
            input.probing?.let(::add)
        }
        return box.takeIf { b -> active.none { it.intersects(b) } }
    }

    /**
     * (Goal animation §2.4, S1; WP-1) Whether the takeover's glow may draw around the placed pill [box]: false while the
     * bug is lifted above a bar ([PlacementInput.bar] `USER_BAR` or `QUICK` with a bottom corner) or an active overlay
     * box (stats, channel list, group card, picker, number entry) intersects [box] inflated by [margin] (design px).
     *
     * The corner is the effective one, as in [place]: with subtitles on, a bottom corner sits at the top and no bar lifts
     * it. The probing line is not in the §2.4 list: it is a thin text line, and it already hides a bug it overlaps.
     */
    fun glowAllowed(input: PlacementInput, geo: OverlayGeometry, box: Box, margin: Float): Boolean {
        val corner = if (input.textTrackOn) toTop(input.corner) else input.corner
        if (isBottom(corner) && (input.bar == BottomBar.USER_BAR || input.bar == BottomBar.QUICK)) return false
        val halo = Box(box.l - margin, box.t - margin, box.r + margin, box.b + margin)
        if (input.stats && geo.stats.intersects(halo)) return false
        if (input.channelList && geo.channelList.intersects(halo)) return false
        if (input.groupCard && geo.groupCard.intersects(halo)) return false
        if (input.picker && geo.picker.intersects(halo)) return false
        if (input.numberEntry && geo.numberEntry.intersects(halo)) return false
        return true
    }

    /**
     * (Power play, docs/design/m6-power-play.md §4.2, §4.4; WP-1) The power-play tab's resting box (design px) for the
     * placed pill [box]: below the pill for an effective top corner, above it for a bottom one (after
     * [PlacementInput.textTrackOn]), at [input]'s size.
     */
    fun tabBox(input: PlacementInput, box: Box): Box {
        // Place mode puts the pill at the chosen corner itself (subtitles ignored, as in [place]).
        val corner = if (input.textTrackOn && !input.placing) toTop(input.corner) else input.corner
        val inset = tabInset(input.size, input.league)
        val depth = tabDepth(input.size)
        return if (isBottom(corner)) {
            Box(box.l + inset, box.t - depth, box.r - inset, box.t)
        } else {
            Box(box.l + inset, box.b, box.r - inset, box.b + depth)
        }
    }

    /**
     * (Power play §4.4; WP-1) Whether the tab may show for the placed pill [box]: false in place mode, or when an active
     * overlay (channel list, stats, group card, picker, number entry, probing line) intersects [tabBox] inflated by
     * [SportsConstants.PP_TAB_NEIGHBOUR]. False closes only the tab track; the pill track stays (S3).
     */
    fun tabAllowed(input: PlacementInput, geo: OverlayGeometry, box: Box): Boolean {
        if (input.placing) return false
        val t = tabBox(input, box)
        val n = SportsConstants.PP_TAB_NEIGHBOUR
        val reach = Box(t.l - n, t.t - n, t.r + n, t.b + n)
        if (input.channelList && geo.channelList.intersects(reach)) return false
        if (input.stats && geo.stats.intersects(reach)) return false
        if (input.groupCard && geo.groupCard.intersects(reach)) return false
        if (input.picker && geo.picker.intersects(reach)) return false
        if (input.numberEntry && geo.numberEntry.intersects(reach)) return false
        if (input.probing?.intersects(reach) == true) return false
        return true
    }

    /**
     * (Power play §4.1) The tab's side inset: Standard 31, Large 39 (31 × 1.25 rounded, so the Large tab is 428 wide).
     */
    fun tabInset(size: ScoreBugSize): Float =
        if (size == ScoreBugSize.LARGE) round(SportsConstants.PP_TAB_INSET * SportsConstants.SCORE_LARGE_SCALE) else SportsConstants.PP_TAB_INSET

    /** (NFL §4.3) The tab's side inset for [league]: the NFL's is Standard 37, Large 46 (tab widths 446 / 558). */
    fun tabInset(size: ScoreBugSize, league: League): Float = when (league) {
        League.NHL -> tabInset(size)
        League.NFL -> if (size == ScoreBugSize.LARGE) SportsConstants.NFL_TAB_INSET_LARGE else SportsConstants.NFL_TAB_INSET
    }

    /** (Power play §4.1) The tab's visible depth below (or above) the pill: Standard 36, Large 45. */
    fun tabDepth(size: ScoreBugSize): Float =
        if (size == ScoreBugSize.LARGE) SportsConstants.PP_TAB_DEPTH * SportsConstants.SCORE_LARGE_SCALE else SportsConstants.PP_TAB_DEPTH

    /** The resting box of [corner] at [size] (§4.3). */
    fun cornerBox(corner: ScoreCorner, size: ScoreBugSize): Box = cornerBox(corner, size, League.NHL)

    /** (NFL §4.1, §4.4) The resting box of [corner] at [size] for [league]'s pill width (the height is shared). */
    fun cornerBox(corner: ScoreCorner, size: ScoreBugSize, league: League): Box {
        val w = width(size, league)
        val h = height(size)
        val l = if (isLeft(corner)) SportsConstants.SCORE_INSET_X else SCREEN_W - SportsConstants.SCORE_INSET_X - w
        val t = if (isBottom(corner)) SCREEN_H - SportsConstants.SCORE_INSET_Y - h else SportsConstants.SCORE_INSET_Y
        return Box(l, t, l + w, t + h)
    }

    fun width(size: ScoreBugSize): Float =
        if (size == ScoreBugSize.LARGE) SportsConstants.SCORE_LARGE_W else SportsConstants.SCORE_W

    /** (NFL §4.4) The pill's width for [league]: the NFL's is Standard 520, Large 650. */
    fun width(size: ScoreBugSize, league: League): Float = when (league) {
        League.NHL -> width(size)
        League.NFL -> if (size == ScoreBugSize.LARGE) SportsConstants.NFL_SCORE_LARGE_W else SportsConstants.NFL_SCORE_W
    }

    fun height(size: ScoreBugSize): Float =
        if (size == ScoreBugSize.LARGE) SportsConstants.SCORE_LARGE_H else SportsConstants.SCORE_H

    private fun above(box: Box, barTop: Float): Box {
        val bottom = barTop - SportsConstants.SCORE_BAR_GAP
        if (box.b <= bottom) return box
        val dy = box.b - bottom
        return Box(box.l, box.t - dy, box.r, box.b - dy)
    }

    private fun isLeft(c: ScoreCorner) = c == ScoreCorner.TOP_LEFT || c == ScoreCorner.BOTTOM_LEFT
    private fun isBottom(c: ScoreCorner) = c == ScoreCorner.BOTTOM_LEFT || c == ScoreCorner.BOTTOM_RIGHT
    private fun toTop(c: ScoreCorner) = when (c) {
        ScoreCorner.BOTTOM_LEFT -> ScoreCorner.TOP_LEFT
        ScoreCorner.BOTTOM_RIGHT -> ScoreCorner.TOP_RIGHT
        else -> c
    }
}
