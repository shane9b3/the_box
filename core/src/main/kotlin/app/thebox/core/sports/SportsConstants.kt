package app.thebox.core.sports

/**
 * Every number of docs/design/m6-score-bug.md Appendix A, once. Times in ms unless the name says S; sizes in design px
 * (1 design px = window width ÷ 1920). `:ui`'s `PlayerUi.SCORE_*` alias the geometry ones.
 */
object SportsConstants {
    // ---- cadence (§2.3)
    /** The CDN's 20 s cache bucket (`Date − Age` gives its start; A-S2). */
    const val CDN_BUCKET_MS = 20_000L
    /** `POLL_PHASE_MS`: a live poll fires this long after the bucket end; drawn once per install in this range. */
    const val POLL_PHASE_MIN_MS = 1_000L
    const val POLL_PHASE_MAX_MS = 2_000L
    /** Intermission polls skip this many buckets (one poll per 40 s). */
    const val INTERMISSION_BUCKET_SKIP = 1
    /** Pre-game: sleep until start − this, then poll every [PRE_POLL_MS] (every other bucket) until LIVE. */
    const val PRE_WAKE_MS = 120_000L
    const val PRE_POLL_MS = 40_000L
    /** `UNSEEN` = max(phase cadence, this). */
    const val POLL_UNSEEN_MS = 120_000L
    /** Day list (`DayPollPolicy`): while any game that day is live or pre-game (aligned) / otherwise. */
    const val DAY_POLL_LIVE_MS = 30_000L
    const val DAY_POLL_IDLE_MS = 300_000L

    // ---- failures (§2.4)
    const val BACKOFF_BASE_MS = 20_000L
    const val BACKOFF_MAX_MS = 300_000L
    /** ±20 % jitter on every back-off step. */
    const val BACKOFF_JITTER = 0.20
    /** Two NotFounds at least this far apart remove the follow (A-S12). */
    const val NOT_FOUND_CONFIRM_MS = 20_000L
    /** `Age` is clamped to [0, AGE_MAX_S]. */
    const val AGE_MAX_S = 60

    // ---- clock and queue (§2.5). Owner ruling 6: no local countdown, so no extrapolation cap and no hold.
    /** Stale after max(this, 2 × current cadence) with no Ok while live or in intermission. */
    const val STALE_AFTER_MS = 60_000L
    /** Hidden after this long stale (the follow is kept). */
    const val HIDE_AFTER_MS = 300_000L
    /** History kept in the delay queue; the newest qualifying snapshot is never pruned (A-N6). */
    const val QUEUE_KEEP_MS = 130_000L
    /** The user's delay choices (0 = "Off"). */
    val DELAY_STEPS_S: List<Int> = listOf(0, 5, 10, 15, 20, 30, 45, 60, 90, 120)

    // ---- the followed game (§2.6)
    const val EXPIRE_AFTER_FINAL_MS = 300_000L
    const val HARD_EXPIRY_MS = 28_800_000L
    /**
     * (Goal animation, docs/design/m6-goal-animation.md §5.1) `ScoreBugView.goalSide` is non-null for this long after a
     * goal becomes visible on the delayed timeline: the *goal mark*. The bottom line is never replaced.
     */
    const val GOAL_MARK_MS = 4_500L

    // ---- HTTP (§2.1, §2.2)
    const val SPORTS_CALL_TIMEOUT_MS = 10_000L
    const val MAX_BODY_BYTES = 524_288L

    // ---- logos (§3)
    const val LOGO_WAIT_MS = 1_500L
    /** Logos are rasterised at this multiple of the slot, then trimmed (U-S1). */
    const val LOGO_RASTER_SCALE = 4
    /** The fallback disc (abbreviation, Instrument Sans 18/700). */
    const val LOGO_DISC_D = 44f
    const val LOGO_DISC_TEXT_PX = 18

    // ---- the pill (§4.1; Standard size, design px)
    const val SCORE_W = 404f
    const val SCORE_H = 70f
    const val SCORE_RADIUS = 35f
    /** Large is Standard × this, segment by segment. */
    const val SCORE_LARGE_SCALE = 1.25f
    /**
     * Appendix A's Large pill. Note 404 × 1.25 = 505 and 70 × 1.25 = 87.5: the design rounds to 506 × 88 (WP-3 decides
     * the per-segment rounding; the bounds tests use these two).
     */
    const val SCORE_LARGE_W = 506f
    const val SCORE_LARGE_H = 88f
    const val SCORE_PAD = 16f
    const val SCORE_LOGO_W = 64f
    const val SCORE_LOGO_H = 46f
    const val SCORE_LOGO_GAP = 8f
    const val SCORE_DIGITS_W = 48f
    const val SCORE_DIVIDER_GAP = 12f
    const val SCORE_DIVIDER_W = 1f
    const val SCORE_DIVIDER_H = 34f
    const val SCORE_CENTRE_W = 106f

    // ---- type (§4.1)
    const val SCORE_TEXT_PX = 40
    const val SCORE_CLOCK_PX = 26
    const val SCORE_PERIOD_PX = 20
    const val SCORE_PERIOD_ALPHA = 0.70f
    const val SCORE_ONE_LINE_PX = 24
    /** Centre strings over [SCORE_CENTRE_W] step down by [SCORE_FIT_STEP_PX], never below this; no ellipsis (U-N3). */
    const val SCORE_TEXT_FLOOR_PX = 18
    const val SCORE_FIT_STEP_PX = 2
    /** The stale / delayed centre column alpha (U-S11). */
    const val SCORE_DIM_ALPHA = 0.55f

    // ---- placement (§4.3, §4.4)
    const val SCORE_INSET_X = 96f
    const val SCORE_INSET_Y = 56f
    /** A bottom-corner bug sits this far above a user bar. */
    const val SCORE_BAR_GAP = 16f
    const val SCORE_SLIDE_MS = 150

    // ---- glass (§4.2)
    const val GLASS_RING_ALPHA = 0.30f
    const val GLASS_FILL_ALPHA = 0.86f
    const val GLASS_HIGHLIGHT_ALPHA = 0.06f
    const val GLASS_HAIRLINE_TOP_ALPHA = 0.16f
    const val GLASS_HAIRLINE_BOTTOM_ALPHA = 0.06f
    const val GLASS_SHADOW_BLUR = 24f
    const val GLASS_SHADOW_DY = 8f
    const val GLASS_SHADOW_ALPHA = 0.45f
    const val SCORE_DIVIDER_ALPHA = 0.18f

    // ---- goal takeover (docs/design/m6-goal-animation.md §10; Standard design px, Large × 1.25 unless stated)
    /** The takeover (lead, panel, glow) runs 0..GOAL_TAKEOVER_MS; the score pop follows for GOAL_POP_MS. */
    const val GOAL_TAKEOVER_MS = 3_600L
    const val GOAL_POP_MS = 900L
    /** The flash: 3 × 400 ms from 420 ms (2.5 Hz, flash ruling, B3), peak alpha .45. */
    const val GOAL_FLASH_CYCLES = 3
    const val GOAL_FLASH_CYCLE_MS = 400L
    const val GOAL_FLASH_DELAY_MS = 420L
    const val GOAL_FLASH_ALPHA = 0.45f
    /** Side padding and the badge → GOAL gap. */
    const val GOAL_PAD = 24f
    const val GOAL_GAP = 14f
    /** GOAL: Barlow Condensed Black Italic, size, tracking, hard shadow; pre-rendered at GOAL_ATLAS_SCALE (S2). */
    const val GOAL_WORD_PX = 52f
    const val GOAL_TRACK = 2f
    const val GOAL_SHADOW_DY = 2f
    const val GOAL_SHADOW_ALPHA = 0.28f
    const val GOAL_ATLAS_SCALE = 1.5f
    /** The tally ("2 – 1", 700 tabular) and the badge fallback (the abbreviation, Instrument Sans Bold). */
    const val GOAL_TALLY_PX = 22f
    const val GOAL_BADGE_TEXT_PX = 24f
    /** The streaks: widths, CSS skewX (degrees), alphas (streak 1 accent, streak 2 white). */
    const val GOAL_STREAK1_W = 49f
    const val GOAL_STREAK2_W = 19f
    const val GOAL_STREAK_SKEW_DEG = -24f
    const val GOAL_STREAK1_ALPHA = 0.6f
    const val GOAL_STREAK2_ALPHA = 0.35f
    /** The letters' drop and overshoot, and the tally's slide. */
    const val GOAL_LETTER_DROP = 25f
    const val GOAL_LETTER_OVERSHOOT = -2f
    const val GOAL_TALLY_SLIDE = 20f
    /** The pop clamp (N5): the popped digit string's width stays within the digit column + 2 × 8. */
    const val GOAL_POP_MAX_W = SCORE_DIGITS_W + 2 * 8f
    /** The glow masks (§2.4): spread, σ, alpha. Large low = Standard × 1.25; Large peak is capped at σ 22. */
    const val GOAL_GLOW_LOW_SPREAD = 2f
    const val GOAL_GLOW_LOW_SIGMA = 10f
    const val GOAL_GLOW_LOW_ALPHA = 0.55f
    const val GOAL_GLOW_PEAK_SPREAD = 8f
    const val GOAL_GLOW_PEAK_SIGMA = 22f
    const val GOAL_GLOW_PEAK_ALPHA = 0.80f
    const val GOAL_GLOW_LARGE_PEAK_SPREAD = 10f
    const val GOAL_GLOW_LARGE_PEAK_SIGMA = 22f
    /** The bug's bounded layer margin `M` (§1): max(peak glow margin, glass shadow margin). Not × 1.25. */
    const val GOAL_LAYER_MARGIN = 74f
    const val GOAL_LAYER_MARGIN_LARGE = 76f
    /** Test only: the glow's alpha at the 56 px screen-edge inset stays at or below this. */
    const val GOAL_GLOW_EDGE_MAX_ALPHA = 0.02f
    /** Team colour rules (§3.2): C1 asserted (exceptions in `NhlTeamColours`), C2 / C3 reported, C4 asserted. */
    const val GOAL_C1_MIN = 4.5
    const val GOAL_C2_REPORT = 3.0
    const val GOAL_C3_REPORT = 1.5
    const val GOAL_C4_MIN = 4.5
    /** `glow` = base when its contrast against [GLASS_DARK] is at least this, else the accent. */
    const val GOAL_GLOW_BASE_MIN = 2.0
    /** The glass over dark video (PanelFill .86 over black) and over white ice (SB §4.2), opaque ARGB. */
    const val GLASS_DARK = 0xFF090B10.toInt()
    const val GLASS_WHITE_ICE = 0xFF2C2F34.toInt()

    // ---- place mode (§6)
    const val PLACE_RING_W = 3f
    const val PLACE_HINT_PX = 22

    // ---- Sports card (§5.2)
    const val CARD_W = 564f
    const val CARD_H = 128f
    const val CARD_LOGO_W = 64f
    const val CARD_LOGO_H = 44f
    const val CARD_LOGO_RADIUS = 8f
}
