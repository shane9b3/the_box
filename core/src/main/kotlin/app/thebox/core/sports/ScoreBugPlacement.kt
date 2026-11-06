package app.thebox.core.sports

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
        if (input.placing) return cornerBox(input.corner, input.size)

        val corner = if (input.textTrackOn) toTop(input.corner) else input.corner
        var box = cornerBox(corner, input.size)

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

    /** The resting box of [corner] at [size] (§4.3). */
    fun cornerBox(corner: ScoreCorner, size: ScoreBugSize): Box {
        val w = width(size)
        val h = height(size)
        val l = if (isLeft(corner)) SportsConstants.SCORE_INSET_X else SCREEN_W - SportsConstants.SCORE_INSET_X - w
        val t = if (isBottom(corner)) SCREEN_H - SportsConstants.SCORE_INSET_Y - h else SportsConstants.SCORE_INSET_Y
        return Box(l, t, l + w, t + h)
    }

    fun width(size: ScoreBugSize): Float =
        if (size == ScoreBugSize.LARGE) SportsConstants.SCORE_LARGE_W else SportsConstants.SCORE_W

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
