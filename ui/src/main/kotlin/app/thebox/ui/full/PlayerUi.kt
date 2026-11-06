package app.thebox.ui.full

import androidx.compose.ui.graphics.Color
import app.thebox.ui.theme.DesignTokens

/**
 * Geometry (design px on the 1920 × 1080 canvas, `BoxDesignScale`: 1.dp = 1 design px) and colour tokens of the player
 * overlays (docs/design/m4-player-ux.md §3.9). Every number of the §3.9 table lives here once; `OverlayBoundsTest`
 * checks the rendered bounds against these. No blur anywhere (plan §3); translucent fills only (§8 Q10: opaque
 * fallbacks are these constants).
 */
object PlayerUi {
    /** The UI changes the displayed pill only after it has been stable this long (§3.2.3; hides M2 E-L8 flicker). */
    const val PILL_DEBOUNCE_MS = 400L
    /** Debounce of `ensureListNow` while the channel list moves (§3.1.4). */
    const val LIST_NOW_DEBOUNCE_MS = 120L
    /** Overlay fade (§3.9; snapped in low-end mode). */
    const val FADE_MS = 150

    // ---- bottom scrim under the info / scrub / quick bar
    const val SCRIM_TOP = 700f

    // ---- info bar / scrub bar
    const val BAR_L = 64f
    const val BAR_T = 820f
    const val BAR_W = 1792f
    const val BAR_H = 220f
    const val BAR_R = 24f
    const val BAR_PAD_H = 28f
    const val BAR_PAD_V = 24f
    const val LOGO = 64f
    const val LOGO_R = 12f
    /** Owner ruling 2026-10-17: the info bar's logo card fills the bar's inner height on the left; the channel info sits beside it. */
    const val BAR_LOGO_W = 240f
    const val BAR_LOGO_H = BAR_H - 2 * BAR_PAD_V
    const val BAR_LOGO_PAD = 16f
    const val BAR_GAP = 28f

    // ---- scrub track (absolute canvas coordinates)
    const val TRACK_L = 128f
    const val TRACK_T = 952f
    const val TRACK_W = 1664f
    const val TRACK_H = 6f
    const val TRACK_R = 3f
    const val LIVE_MARK_W = 2f
    const val LIVE_MARK_H = 18f
    const val PLAYHEAD_D = 14f
    const val RING_W = 3f
    /**
     * The renderer's node spans the bar's width (x 64…1856) from the preview bubble above the track (y 916) to the
     * label row under it (y 996).
     */
    const val SCRUB_NODE_T = 916f
    const val SCRUB_NODE_H = 80f
    const val BUBBLE_H = 26f

    // ---- channel list
    const val LIST_L = 48f
    const val LIST_T = 48f
    const val LIST_W = 620f
    const val LIST_H = 984f
    const val LIST_HEADER_H = 64f
    const val LIST_ROW_H = 72f
    const val LIST_PITCH = 80f
    const val LIST_VISIBLE = 10
    const val LIST_LOGO = 60f
    const val LIST_LOGO_W = 104f
    const val GROUPS_W = 260f
    const val TUNED_DOT = 7f

    // ---- quick bar (owner ruling 2026-10-15)
    const val QUICK_L = 64f
    const val QUICK_T = 952f
    const val QUICK_W = 1792f
    const val QUICK_H = 88f
    const val QUICK_PAD = 12f
    const val QUICK_PILL_H = 64f
    const val QUICK_PILL_PAD_H = 24f
    const val QUICK_GAP = 12f
    /** The focused pill stays at least this far inside either end of the row. */
    const val QUICK_EDGE = 64f

    // ---- picker sheet (ends above the quick bar)
    const val PICKER_L = 1376f
    const val PICKER_T = 48f
    const val PICKER_W = 496f
    const val PICKER_H = 880f
    const val PICKER_ROW_H = 64f

    // ---- stats overlay
    const val STATS_L = 64f
    const val STATS_T = 56f
    const val STATS_W = 600f
    const val STATS_LINE_H = 30f

    // ---- error card
    const val CARD_W = 720f
    const val CARD_PAD = 36f

    // ---- number entry (M3) and the group card
    const val NUMBER_L = 1520f
    const val NUMBER_T = 72f
    const val NUMBER_W = 320f
    const val NUMBER_H = 160f

    // ---- toasts and the reminder card in FULL (N8)
    const val TOAST_L = 64f
    const val TOAST_B = 96f
    const val TOAST_B_OVER_BAR = 276f
    const val TOAST_B_OVER_QUICK = 156f
    const val REMINDER_R = 64f
    /** Right edge of the reminder card while the picker sheet is open: 1920 − 1376 + 24. */
    const val REMINDER_R_BESIDE_PICKER = 568f

    // ---- subtitles (N5)
    const val SUB_PAD_DEFAULT = 0.08f
    const val SUB_PAD_MAX = 0.5f
    const val SUB_PAD_GAP = 16f

    // ---- catch-up probing line (FullScreenHost: TopCenter, 72 from the top). Its width follows the text; the score bug
    // (score bug commit 0, docs/design/m6-score-bug.md §4.4, U-N1) tests against this widest box.
    const val PROBING_T = 72f
    const val PROBING_W = 720f
    const val PROBING_H = 56f

    // ---- score bug (score bug commit 0, docs/design/m6-score-bug.md §4; aliases of SportsConstants, the one source)
    const val SCORE_W = app.thebox.core.sports.SportsConstants.SCORE_W
    const val SCORE_H = app.thebox.core.sports.SportsConstants.SCORE_H
    const val SCORE_RADIUS = app.thebox.core.sports.SportsConstants.SCORE_RADIUS
    const val SCORE_LARGE_W = app.thebox.core.sports.SportsConstants.SCORE_LARGE_W
    const val SCORE_LARGE_H = app.thebox.core.sports.SportsConstants.SCORE_LARGE_H
    const val SCORE_INSET_X = app.thebox.core.sports.SportsConstants.SCORE_INSET_X
    const val SCORE_INSET_Y = app.thebox.core.sports.SportsConstants.SCORE_INSET_Y
    const val SCORE_BAR_GAP = app.thebox.core.sports.SportsConstants.SCORE_BAR_GAP
    const val SCORE_SLIDE_MS = app.thebox.core.sports.SportsConstants.SCORE_SLIDE_MS
    /** Stats overlay: the bound the score bug avoids (16 lines of [STATS_LINE_H] plus padding; the box grows with its lines). */
    const val STATS_MAX_H = 16 * STATS_LINE_H + 28f
    /** Group card at (64, 56): the bound the score bug avoids (30 px text, 24/14 padding, a long group name). */
    const val GROUP_CARD_L = 64f
    const val GROUP_CARD_T = 56f
    const val GROUP_CARD_W = 720f
    const val GROUP_CARD_H = 70f

    /**
     * (Score bug commit 0, §4.4, A-B2) The other overlays' boxes for `ScoreBugPlacement` (`:core` never imports this
     * object). `ScoreBugBoundsTest` checks it equals the `:core` fixture.
     */
    fun overlayGeometry(): app.thebox.core.sports.OverlayGeometry = app.thebox.core.sports.OverlayGeometry(
        channelList = app.thebox.core.sports.Box(LIST_L, LIST_T, LIST_L + LIST_W, LIST_T + LIST_H),
        stats = app.thebox.core.sports.Box(STATS_L, STATS_T, STATS_L + STATS_W, STATS_T + STATS_MAX_H),
        groupCard = app.thebox.core.sports.Box(GROUP_CARD_L, GROUP_CARD_T, GROUP_CARD_L + GROUP_CARD_W, GROUP_CARD_T + GROUP_CARD_H),
        picker = app.thebox.core.sports.Box(PICKER_L, PICKER_T, PICKER_L + PICKER_W, PICKER_T + PICKER_H),
        numberEntry = app.thebox.core.sports.Box(NUMBER_L, NUMBER_T, NUMBER_L + NUMBER_W, NUMBER_T + NUMBER_H),
        infoBarTop = BAR_T,
        quickBarTop = QUICK_T,
    )

    /** The probing line's widest box (centred at the top). */
    val probingBox: app.thebox.core.sports.Box =
        app.thebox.core.sports.Box((1920f - PROBING_W) / 2, PROBING_T, (1920f + PROBING_W) / 2, PROBING_T + PROBING_H)

    // ---- colours
    val Panel = DesignTokens.Colors.Canvas
    val BarFill = Panel.copy(alpha = 0.78f)
    val PanelFill = Panel.copy(alpha = 0.86f)
    val CardFill = Panel.copy(alpha = 0.94f)
    val Border09 = Color.White.copy(alpha = 0.09f)
    val Border12 = Color.White.copy(alpha = 0.12f)
    val QuickFill = Color.Black.copy(alpha = 0.55f)
    val StatsFill = Color.Black.copy(alpha = 0.62f)
    val ScrimBottom = Color.Black.copy(alpha = 0.55f)
    val ChipFill = Color.White.copy(alpha = 0.08f)
    val PausedFill = Color.White.copy(alpha = 0.12f)
    val TrackBase = Color.White.copy(alpha = 0.08f)
    val TrackArchive = Color.White.copy(alpha = 0.22f)
    val TrackBeforeArchive = Color.White.copy(alpha = 0.05f)
    val ProgressBase = Color.White.copy(alpha = 0.20f)
    val LiveEdge = Color(0xFFFF4D5E)
}
