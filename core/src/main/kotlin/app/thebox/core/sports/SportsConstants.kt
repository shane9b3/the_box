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

    // ---- the update interval (docs/design/m6-sports-update-interval.md; owner rulings 29–32). Seconds; 0 = Auto.
    /** (Ruling 30) The NHL list starts at its 20 s CDN bucket; multiples of 20 keep the phase alignment exact. */
    val INTERVAL_CHOICES_NHL_S: List<Int> = listOf(20, 40, 60)
    /** (Ruling 32) The NFL floor is 5 s; Auto never goes under it. */
    val INTERVAL_CHOICES_NFL_S: List<Int> = listOf(5, 10, 15, 20, 30)
    /** Auto before [METER_MIN] samples. */
    const val INTERVAL_DEFAULT_NHL_S = 20
    const val INTERVAL_DEFAULT_NFL_S = 5
    /** (CHL §2.5, P1) One setting for OHL, WHL and QMJHL, from completion; the floor is 5 s and Auto never goes under it. */
    val INTERVAL_CHOICES_CHL_S: List<Int> = listOf(5, 10, 15, 20, 30)
    const val INTERVAL_DEFAULT_CHL_S = 5
    /** The meter keeps this many samples per league (good polls, plus failed ones at their elapsed time, review S6). */
    const val METER_WINDOW = 20
    /** (Review S6) p90 only from this many samples; below it, the default. */
    const val METER_MIN = 10
    /** Work takes at most 1 / this of an interval (a duty-cycle choice, not a safety one: polls never stack; review N1). */
    const val METER_FACTOR = 3
    /** (Review S9) Auto moves down only when `METER_FACTOR × p90` fits in this share of the lower choice ... */
    const val METER_DOWN_SHARE = 0.8
    /** ... for this many samples in a row. */
    const val METER_DOWN_STREAK = 10
    /** A failed poll counts in the window at its elapsed time, at most this (the call timeout). */
    const val METER_FAIL_CAP_MS = 10_000
    /** The meters are written to `sports_follow` at most this often (and at once when the slowed or endpoint state changes). */
    const val METER_PERSIST_MS = 60_000L
    /** NFL back-off base at a set interval (5, 10, 20 … 300 s); a 429 keeps [BACKOFF_BASE_MS]. */
    const val BACKOFF_FAST_BASE_MS = 5_000L
    /** Breaks poll at 2 × the live interval, never under this. */
    const val INTERMISSION_MIN_MS = 20_000L
    /** (Review S2) After a 429 (or a 403) every NFL choice is at least this, for the rest of the follow. */
    const val AUTO_429_FLOOR_S = 10
    /** MB/h before a measurement (gzip bytes per poll; docs F2, F7). */
    const val EST_WIRE_BYTES_NHL = 1_500
    const val EST_WIRE_BYTES_NFL = 2_500
    /**
     * (CHL §2.5, S4; WP-3 fix pass, lead ruling S2) Six conditional Firebase requests per poll. Firebase is HTTP/1.1 and
     * sends nothing compressed, so each request and its answer (a 304 included) carry about 650 B of uncompressed headers;
     * the CHL source counts those headers with the bodies, so this is a poll's true wire cost (about 4 KB in play).
     */
    const val EST_WIRE_BYTES_CHL = 4_000
    /** (Review S3) On the board fallback, the per-event endpoint is tried again this often. */
    const val PER_EVENT_RETRY_MS = 600_000L
    /** Two shape failures in a row (a 200 that does not decode, a JSON 400) switch the follow to the board. */
    const val PER_EVENT_SWITCH_AFTER = 2
    /**
     * (Fix pass 5) The S5 older-body guard holds a run of older NFL bodies at most this long. A body that merely lags
     * (the board fallback is 6–9 s behind per-event; the board's `max-age` is 10 s) is at most ~20 s old, so 30 s never
     * lets a lagging copy through; and it is the longest NFL choice, so a correction (an overturned score whose
     * `lastPlay.id` went back down) shows on the first poll ≥ 30 s after it first arrived, at any choice.
     */
    const val NFL_OLDER_HOLD_MAX_MS = 30_000L

    /** The interval choices of [league] (no Auto). */
    fun intervalChoices(league: League): List<Int> = when (league.group) {
        LeagueGroup.NHL -> INTERVAL_CHOICES_NHL_S
        LeagueGroup.NFL -> INTERVAL_CHOICES_NFL_S
        LeagueGroup.CHL -> INTERVAL_CHOICES_CHL_S
    }

    fun intervalDefault(league: League): Int = when (league.group) {
        LeagueGroup.NHL -> INTERVAL_DEFAULT_NHL_S
        LeagueGroup.NFL -> INTERVAL_DEFAULT_NFL_S
        LeagueGroup.CHL -> INTERVAL_DEFAULT_CHL_S
    }

    // ---- CHL (docs/design/m6-chl-leagues.md, Appendix A)
    /** (§2.7) The cap on one Firebase node or Today overlay body; `lscluster` bodies keep [MAX_BODY_BYTES]. */
    const val CHL_NODE_MAX_BYTES = 131_072L
    /** (§2.3, P7, Q7) A CHL league's "today" rolls over at 05:00 in its reference zone (`SportsDays`). */
    const val CHL_DAY_ROLLOVER_H = 5
    /** (§3.3) Regular periods; regular-season overtime (3-on-3); playoff overtime. Game time in s. */
    const val CHL_REG_PERIOD_S = 1_200
    const val CHL_OT_REG_S = 300
    const val CHL_OT_PLAYOFF_S = 1_200
    /** (§5.6 H3) A counted penalty starting more than this after now hides the bar. */
    const val CHL_PP_FUTURE_TOLERANCE_S = 5
    /** (§5.6 H5) The window of game time in which a goal's pp / sh flag is checked against the model. */
    const val CHL_PP_CONSISTENCY_WINDOW_S = 120
    /**
     * (CHL B3, §4.1) `ScoreBugMachine` holds the last shown power play through consecutive `powerPlayUnknown` snapshots
     * while the shown snapshot's anchor is less than this after the first unknown one's; then the tab closes silently.
     */
    const val PP_UNKNOWN_HOLD_MS = 30_000L
    /** (§6.4, P6) The fallback capsule's side padding for a code of more than 3 characters, design px. */
    const val LOGO_CAPSULE_PAD = 10f
    /** (§6.4) A capsule wider than the 64 px logo slot may overhang it by at most this, each side, design px. */
    const val LOGO_CAPSULE_OVERHANG_MAX = 4f
    /** (§6.4) The least gap from the capsule's edge to the score's ink (the widest of 0–19), design px. */
    const val LOGO_CAPSULE_SCORE_GAP_MIN = 6f
    /**
     * (§6.4, lead ruling on O3 / review S1–S2, 2026-11-13) The text floor of a **4-character** code in a logo fallback (the
     * Pill capsule, the Pill goal badge, the Stacked goal badge): the 2 px step-down may go below the general 18 px floor
     * to this. Never a display alias. Every other text keeps [SCORE_TEXT_FLOOR_PX].
     */
    const val LOGO_FALLBACK_CODE4_FLOOR_PX = 16

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

    // ---- power-play tab (docs/design/m6-power-play.md §11; Standard design px, Large × 1.25 unless stated)
    /** Transition lengths: an OPEN (slide, text, shine and pulse), a CLOSE, and one PULSE (a kind change). */
    const val PP_OPEN_MS = 1_900L
    const val PP_CLOSE_MS = 600L
    const val PP_PULSE_MS = 450L
    /** Channel durations (§3.3): the slide (both ways), the tab shadow (both ways), the pill tint and halo (both ways). */
    const val PP_SLIDE_MS = 550L
    const val PP_SHADOW_MS = 400L
    const val PP_TINT_MS = 600L
    /** The text on an OPEN: delay, alpha fade, offset move; [PP_TEXT_OUT_MS] the fade and move on a CLOSE. */
    const val PP_TEXT_DELAY_MS = 380L
    const val PP_TEXT_ALPHA_MS = 300L
    const val PP_TEXT_MOVE_MS = 350L
    const val PP_TEXT_OUT_MS = 200L
    /** The shine sweep and the label pulse on an OPEN (the pulse is 2 × [PP_PULSE_MS] halves, ×1 → [PP_PULSE_SCALE] → 1). */
    const val PP_SHINE_DELAY_MS = 500L
    const val PP_SHINE_MS = 900L
    const val PP_PULSE_DELAY_MS = 1_000L
    const val PP_PULSE_SCALE = 1.07f
    /** No shine or pulse on an OPEN that starts with `from.slide` above this (a reversed close). */
    const val PP_REVERSE_SLIDE_MAX = 0.05f
    /** The tab (§4.1): side inset (width 404 − 2 × 31 = 342), visible depth, overlap hidden under the pill, far-corner radius. */
    const val PP_TAB_INSET = 31f
    const val PP_TAB_DEPTH = 36f
    const val PP_TAB_HIDDEN = 20f
    const val PP_TAB_RADIUS = 18f
    /** The tab's type (Instrument Sans Bold), tracking in em, the label → time gap and the divider (white × alpha). */
    const val PP_TEXT_PX = 20f
    const val PP_TRACK_EM = 0.05f
    const val PP_GAP = 10f
    const val PP_DIVIDER_W = 1f
    const val PP_DIVIDER_H = 16f
    const val PP_DIVIDER_ALPHA = 0.5f
    /** The text's hard shadow (no blur): dy and black alpha. */
    const val PP_TEXT_SHADOW_DY = 1f
    const val PP_TEXT_SHADOW_ALPHA = 0.35f
    /** The text centre from the pill edge (§4.1). */
    const val PP_TEXT_CENTRE = 18f
    /** The slide travel and the text's offset toward the pill at the start of its move. */
    const val PP_TRAVEL = 40f
    const val PP_TEXT_DY = 4f
    /** The shine band: width, alpha, left edge travel (tab-local). */
    const val PP_SHINE_W = 31f
    const val PP_SHINE_ALPHA = 0.25f
    const val PP_SHINE_FROM = -72f
    const val PP_SHINE_TO = 425f
    /** The tab shadow: dy (Standard 5, Large 6), σ (× d at both sizes, not × 1.25; N1) and alpha. */
    const val PP_SHADOW_DY = 5f
    const val PP_SHADOW_DY_LARGE = 6f
    const val PP_SHADOW_SIGMA = 8f
    const val PP_SHADOW_ALPHA = 0.5f
    /** The logo halo: σ (Standard 4, Large 5) and alpha (× `TeamColours.tintScale`). */
    const val PP_HALO_SIGMA = 4f
    const val PP_HALO_SIGMA_LARGE = 5f
    const val PP_HALO_ALPHA = 0.95f
    /** The pill tint's horizontal gradient from the power-play end: stop positions and alphas (× `tintScale`). */
    val PP_TINT_STOPS: List<Float> = listOf(0f, 0.25f, 0.58f)
    val PP_TINT_ALPHAS: List<Float> = listOf(0.9f, 0.5f, 0f)
    /** The tab's vertical gradient (§6.1): `mixBlack(tab, k)` at each stop, dark at the pill side (ruling 17: .10 far). */
    val PP_GRADIENT_STOPS: List<Float> = listOf(0f, 0.5f, 1f)
    val PP_GRADIENT_BLACK: List<Float> = listOf(0.70f, 0.28f, 0.10f)
    /** C5 (white tab text, measured at gradient position [PP_C5_POINT]) and C6 (white digits on the tint, at x [PP_C6_X] of 404). */
    const val PP_C5_MIN = 4.5
    const val PP_C5_POINT = 0.80f
    const val PP_C6_MIN = 4.5
    const val PP_C6_X = 92f
    /** `TeamColours.tintScale` drops from 1 in these steps until C6 passes (§6.3). */
    const val PP_TINT_SCALE_STEP = 0.05f
    /** The feed's seconds are kept only in 0..this (so `m:ss` is never 5 glyphs, N2). */
    const val PP_SECS_MAX = 599
    /** `ScoreBugPlacement.tabAllowed` inflates the tab box by this (the shadow's reach, N8). */
    const val PP_TAB_NEIGHBOUR = 29f
    /** The label masks are rasterised at this multiple of the rest size (the pulse never re-rasterises a glyph). */
    const val PP_LABEL_ATLAS_SCALE = 1.25f

    // ---- place mode (§6)
    const val PLACE_RING_W = 3f
    const val PLACE_HINT_PX = 22

    // ---- Sports card (§5.2)
    const val CARD_W = 564f
    const val CARD_H = 128f
    const val CARD_LOGO_W = 64f
    const val CARD_LOGO_H = 44f
    const val CARD_LOGO_RADIUS = 8f

    // ------------------------------------------------------------------ NFL (docs/design/m6-nfl.md §12)
    // Standard design px; Large × [SCORE_LARGE_SCALE] unless stated. Height and radius are the NHL's.

    // ---- the pill (§4.1, §4.2)
    const val NFL_SCORE_W = 520f
    const val NFL_SCORE_LARGE_W = 650f
    const val NFL_SCORE_DIGITS_W = 50f
    const val NFL_DOWN_W = 111f
    /** The clock (700, tabular), the quarter (600, α [NFL_QUARTER_ALPHA]), the down column (700), the one-line state (600). */
    const val NFL_CLOCK_PX = 26f
    const val NFL_QUARTER_PX = 20f
    const val NFL_QUARTER_ALPHA = 0.70f
    const val NFL_DOWN_PX = 22f
    const val NFL_ONE_LINE_PX = 24f
    /** The down text steps 2 px down (to 18) while wider than [NFL_DOWN_W] − this. */
    const val NFL_DOWN_FIT_AIR = 6f

    // ---- mapping (§1.3)
    /** `pre` within this of the start (by the body's server `Date`) is PRE. */
    const val NFL_PRE_WINDOW_MS = 3_600_000L
    /** (S8) `lastPlay.type.id` of the coin toss: an `in` body still at it is PRE. */
    const val NFL_COIN_TOSS_TYPE = "70"
    /** (Fix pass C3) A quarter's clock at its start (15:00): an `in` body with no `lastPlay` is PRE only at or above it. */
    const val NFL_QUARTER_SECS = 900
    /** (N6) `lastPlay.type.id` of a kick-off: its team is the receiver. */
    const val NFL_KICKOFF_TYPE = "53"

    // ---- HTTP (§1.5)
    const val ESPN_MAX_BODY_BYTES = 1_048_576L
    /** The combiner logo's edge in px (4 × the 46 px slot height, rounded). */
    const val ESPN_LOGO_PX = 200

    // ---- the tab (§4.3, §4.5). Depth, hidden overlap, radius, shadow and divider: the PP_* values.
    const val NFL_TAB_INSET = 37f
    const val NFL_TAB_INSET_LARGE = 46f
    const val NFL_TAB_ICON = 20f
    const val NFL_TAB_ICON_STROKE = 1.4f
    const val NFL_TAB_ICON_GAP = 10f
    const val NFL_TAB_LEAD_WEIGHT = 700
    const val NFL_TAB_TAIL_WEIGHT = 600
    const val NFL_TAB_TRACK_EM = 0.05f
    /** (Ruling 26) The red-zone tail's red (ARGB) and its cross-fade. */
    const val NFL_RED_ZONE = 0xFFC8102E.toInt()
    const val NFL_RED_ZONE_FADE_MS = 250L

    // ---- the takeover (§5.1, §5.7)
    const val NFL_TAKEOVER_MS = 3_600L
    const val NFL_POP_MS = 900L
    /** The goal mark of a +7/+8 pair (ruling 25); one score uses [GOAL_MARK_MS]. */
    const val NFL_DOUBLE_MARK_MS = 9_000L
    const val NFL_PAIR_SECOND_START_MS = 4_500L

    // ---- the flash grid (§5.3)
    const val NFL_FLASH_GRID_MS = 450L
    const val NFL_FLASH_START_MS = 450L
    const val NFL_FLASH_CYCLES = 3
    const val NFL_FLASH_ALPHA = 0.42f

    // ---- the goalposts (§5.1)
    const val NFL_POSTS_IN_MS = 400L
    const val NFL_POSTS_IN_DELAY_MS = 200L
    const val NFL_POSTS_HIT_START_MS = 1_800L
    const val NFL_POSTS_HIT_CYCLES = 3
    const val NFL_POSTS_ALPHA = 0.4f
    const val NFL_POSTS_HIT_ALPHA = 0.95f
    const val NFL_POSTS_X = 355f
    const val NFL_POSTS_Y = 9f
    const val NFL_POSTS_W = 43f
    const val NFL_POSTS_H = 52f
    const val NFL_POSTS_DY = 8f

    // ---- the pinned tab's pulse (§5.1, §5.3)
    const val NFL_TAB_PULSE_CYCLES = 8
    const val NFL_TAB_PULSE_GAIN = 0.45f

    // ---- the streaks (§5.1)
    const val NFL_STREAK1_W = 51f
    const val NFL_STREAK2_W = 19f
    const val NFL_STREAK_FROM = -85f
    const val NFL_STREAK_TO = 616f
    const val NFL_STREAK2_START_MS = 1_800L

    // ---- the ball (§5.1, B5)
    const val NFL_BALL_PX = 34f
    /** The ball sprite's ink lies within this of its centre at any rotation. */
    const val NFL_BALL_INK_R = 15f

    // ---- the run (TD, two-point, safety)
    const val NFL_RUN_FROM = -42f
    const val NFL_RUN_TO = 562f
    const val NFL_RUN_Y = 18f
    const val NFL_RUN_ROT = 900f
    const val NFL_RUN_ALPHA = 0.4f
    const val NFL_RUN_ALPHA_IN = 0.15f
    const val NFL_RUN_ALPHA_OUT = 0.85f
    const val NFL_RUN_START_MS = 300L
    const val NFL_RUN_MS = 1_300L
    /** The run's inner band: `clipRect(this, 0, W − this, H)`. */
    const val NFL_RUN_BAND = 4.4f

    // ---- the arc (XP, FG; D8)
    const val NFL_ARC_P0_X = 33f
    const val NFL_ARC_P0_Y = 44f
    const val NFL_ARC_P1_X = 202f
    const val NFL_ARC_P1_Y = -7f
    const val NFL_ARC_P2_X = 366f
    const val NFL_ARC_P2_Y = 12f
    /** The ball's scale and rotation (degrees) at P0, P1, P2. */
    val NFL_ARC_SCALES: List<Float> = listOf(1f, 0.75f, 0.45f)
    val NFL_ARC_ROTATIONS: List<Float> = listOf(0f, 360f, 720f)
    const val NFL_ARC_ALPHA = 0.55f
    const val NFL_ARC_ALPHA_IN = 0.10f
    const val NFL_ARC_ALPHA_OUT = 0.85f
    const val NFL_ARC_START_MS = 300L
    const val NFL_ARC_MS = 1_400L
    /** The arc's and the posts' straight-section band: `clipRect(this, 0, W − this, H)`. */
    const val NFL_ARC_BAND = 35f

    // ---- the word (§5.2)
    const val NFL_WORD_PX = 48f
    const val NFL_WORD_TRACK_EM = 0.03f
    const val NFL_WORD_SHADOW_DY = 2f
    const val NFL_WORD_SHADOW_ALPHA = 0.28f
    const val NFL_LETTER_START_MS = 420L
    const val NFL_LETTER_MS = 420L
    const val NFL_LETTER_STAGGER_MS = 55L
    const val NFL_LETTER_DROP = 22f
    const val NFL_LETTER_MID = 0.6f
    const val NFL_LETTER_FROM_SCALE = 1.9f
    const val NFL_LETTER_MID_SCALE = 0.94f
    const val NFL_LETTER_OVERSHOOT = -2f
    const val NFL_WORD_PULSE_START_MS = 1_400L
    const val NFL_WORD_PULSE_CYCLE_MS = 500L
    const val NFL_WORD_PULSE_CYCLES = 3
    const val NFL_WORD_PULSE = 1.05f

    // ---- the logo badge (§5.1)
    const val NFL_LOGO_START_MS = 450L
    const val NFL_LOGO_MS = 600L
    const val NFL_LOGO_DX = -32f
    const val NFL_LOGO_FROM_SCALE = 0.4f
    const val NFL_LOGO_FROM_DEG = -25f
    const val NFL_LOGO_MID = 0.7f
    const val NFL_LOGO_MID_DX = 2f
    const val NFL_LOGO_MID_SCALE = 1.1f
    const val NFL_LOGO_MID_DEG = 4f
    const val NFL_LOGO_SHADOW_DY = 2f
    const val NFL_LOGO_SHADOW_ALPHA = 0.25f

    // ---- the layout and the score block (§5.2)
    const val NFL_TAKEOVER_PAD = 24f
    const val NFL_TAKEOVER_GAP = 14f
    const val NFL_POINTS_PX = 32f
    const val NFL_TALLY_PX = 20f
    const val NFL_BLOCK_W = 74f
    const val NFL_BLOCK_GAP = 2f
    const val NFL_POINTS_START_MS = 1_100L
    const val NFL_POINTS_MS = 500L
    const val NFL_POINTS_DX = 19f
    const val NFL_POINTS_FROM_SCALE = 0.6f
    const val NFL_POINTS_MID = 0.7f
    const val NFL_POINTS_MID_DX = -1.6f
    const val NFL_POINTS_MID_SCALE = 1.12f

    // ---- the score pop (§5.1)
    const val NFL_POP_FROM_SCALE = 1.5f
    const val NFL_POP_K1 = 0.4f
    const val NFL_POP_K1_SCALE = 0.9f
    const val NFL_POP_K2 = 0.7f
    const val NFL_POP_K2_SCALE = 1.06f

    // ---- the atlas (§5.2, N2)
    /** The longest word ("EXTRA POINT", 11 with the space) fits; the frame's letter arrays are this long. */
    const val NFL_LETTERS_MAX = 12
    const val NFL_ATLAS_SCALE = 1.5f

    // ==== Stacked style (docs/design/m6-score-bug-stacked-theme.md §13; Stacked commit 0). Standard design px; Large is
    // × SCORE_LARGE_SCALE (1.25) unless stated. Mock-px geometry is in [StackedMock]; × STACKED_SCALE gives design px.

    // ---- data (§3.3)
    /** `TeamLine.sog` is kept only in 0..SOG_MAX; anything else (or non-numeric) is null. */
    const val SOG_MAX = 199

    // ---- scale, footprint and raised texts (§4.1, §4.2, §4.3)
    /** The mock-up × this = Standard design px (S5; owner Q1, the alternative is .50). */
    const val STACKED_SCALE = 0.45f
    /**
     * The footprint (`ScoreBugPlacement.cornerBox` for STACKED): [STACKED_W_MAX] × ([STACKED_H] + [STACKED_BAR_H]) =
     * 392 × 141 Standard; Large × 1.25 (490 × 176.25).
     */
    const val STACKED_W_MAX = 392f
    /** The top part (two rows): 240 mock px. */
    const val STACKED_H = 108f
    /** The bar (S5: the mock's 40 / 66 text-to-bar proportion at 20-px text). */
    const val STACKED_BAR_H = 33f
    /** The drawn box's widths: the rows (540 mock), the SOG column (130), the clock column (200). */
    const val STACKED_ROWS_W = 243f
    const val STACKED_SOG_W = 58.5f
    const val STACKED_CLOCK_W = 90f
    /** Raised information text (S5): the bar text, the takeover tally, the SOG label. */
    const val STACKED_BAR_TEXT_PX = 20f
    const val STACKED_TALLY_PX = 20f
    const val STACKED_SOG_LABEL_PX = 18f
    /** The clock column's strings step down by this to [STACKED_FIT_MIN_PX], never below, no ellipsis (§4.2 fit rules). */
    const val STACKED_FIT_STEP_PX = 2f
    const val STACKED_FIT_MIN_PX = 18f
    /** The takeover's badge, and the logo bitmap size requested from `FollowLogos` (S6). Large 84.375. */
    const val STACKED_GOAL_LOGO = 67.5f
    /** The takeover's side padding drops from 18 to this when the content has under [STACKED_TAKEOVER_SLACK_MIN] slack. */
    const val STACKED_TAKEOVER_PAD_TIGHT = 8f
    const val STACKED_TAKEOVER_SLACK_MIN = 18f
    /** The row divider, the column inset lines and the bar hairline: 1 design px, not scaled. */
    const val STACKED_HAIRLINE_W = 1f
    /** The faux-italic fallback (§11, N2) if the italic face fails to load: Bold with this `textSkewX`. */
    const val STACKED_FAUX_ITALIC_SKEW_X = -0.21f

    // ---- colours (§5)
    const val STACKED_ROW_BG = 0xFF12284F.toInt()
    const val STACKED_COL_BG = 0xFF15191F.toInt()
    const val STACKED_BORDER = 0xFF2B3550.toInt()
    const val STACKED_INSET_LINE = 0xFF2B3240.toInt()
    const val STACKED_RULE = 0xFF5B6170.toInt()
    const val STACKED_SOG_LABEL = 0xFFC3C7D0.toInt()
    const val STACKED_BAR_NEUTRAL = 0xFF1B2440.toInt()
    /** White at this alpha: the row divider and the bar's hairline (S2: on every bar). */
    const val STACKED_BAR_HAIRLINE_ALPHA = 0.22f
    /** Dimmed text (stale, delayed): the clock column and the bar words. */
    const val STACKED_DIM_ALPHA = 0.55f
    /** Stripe ≥ 3.0 against the row navy (WCAG 1.4.11); bar stripes A and B ≥ 2.0 against their bar; A vs B ≥ 1.5. */
    const val STACKED_STRIPE_MIN = 3.0
    const val STACKED_BAR_STRIPE_MIN = 2.0
    const val STACKED_BAR_AB_MIN = 1.5
    /** Two stripes collide below this CIELAB ΔE76 (S3). */
    const val STACKED_STRIPE_DE_MIN = 20.0
    /** C7: white and the scorer's pop colour ≥ 3.0 on the row-flash blend; the peak α starts at .55, steps .05 down. */
    const val STACKED_C7_MIN = 3.0
    const val STACKED_ROW_FLASH_ALPHA = 0.55f
    const val STACKED_ROW_FLASH_STEP = 0.05f
    /** The still version's frame-B row tint: a fixed alpha of the scorer's flash colour (§7.3). */
    const val STACKED_STILL_ROW_TINT = 0.25f
    /** Bar stripe candidates: `mixBlack(tab, .5)` and `towardWhite(tab, .4)` (S2). */
    const val STACKED_BAR_A_BLACK = 0.5
    const val STACKED_BAR_B_WHITE = 0.4
    /** The stripe lightening step toward white (5 %) and the PP bar background's `mixBlack(tab, .172)`. */
    const val STACKED_STRIPE_WHITE_STEP = 0.05
    const val STACKED_BAR_BG_BLACK = 0.172

    // ---- shadow and glow (§6)
    const val STACKED_SHADOW_DY = 5.4f
    const val STACKED_SHADOW_SIGMA = 8.1f
    const val STACKED_SHADOW_ALPHA = 0.55f
    /** The nine-slice source square (≥ 6σ + 1); Large has its own (not × 1.25). */
    const val STACKED_SHADOW_SRC = 50f
    const val STACKED_SHADOW_SRC_LARGE = 62f
    /** The exact-box glow bakes kept per size (LRU). */
    const val STACKED_GLOW_CACHE = 2
    /** Hard text shadows: the bar text (1 px), the logo badge (2.7 px), both black .25. */
    const val STACKED_BAR_TEXT_SHADOW_DY = 1f
    const val STACKED_BADGE_SHADOW_DY = 2.7f
    const val STACKED_TEXT_SHADOW_ALPHA = 0.25f

    // ---- layout transitions (§7.1; CSS transitions; ms)
    const val STACKED_SOG_MS = 450L
    const val STACKED_SOG_CLOSE_DELAY_MS = 350L
    const val STACKED_SOG_IN_ALPHA_MS = 300L
    const val STACKED_SOG_IN_MOVE_MS = 350L
    const val STACKED_SOG_IN_DELAY_MS = 300L
    const val STACKED_SOG_OUT_MS = 200L
    /** The SOG contents' x offset when hidden (−12 mock). */
    const val STACKED_SOG_DX = -5.4f
    const val STACKED_CLOCK_MS = 450L
    const val STACKED_CLOCK_OPEN_DELAY_MS = 350L
    const val STACKED_CLOCK_OUT_MS = 150L
    const val STACKED_CLOCK_IN_MS = 300L
    const val STACKED_CLOCK_IN_DELAY_MS = 500L
    const val STACKED_BAR_MS = 400L
    const val STACKED_BAR_OPEN_DELAY_MS = 400L
    const val STACKED_BAR_STRIPE_IN_MS = 450L
    const val STACKED_BAR_STRIPE_A_DELAY_MS = 700L
    const val STACKED_BAR_STRIPE_B_DELAY_MS = 780L
    const val STACKED_BAR_STRIPE_OUT_MS = 300L
    /** The bar stripes' travel when hidden (−140 mock), and their CSS skew. */
    const val STACKED_BAR_STRIPE_TRAVEL = -63f
    const val STACKED_BAR_SKEW_DEG = -30f
    const val STACKED_BAR_TEXT_IN_ALPHA_MS = 350L
    const val STACKED_BAR_TEXT_IN_MOVE_MS = 450L
    const val STACKED_BAR_TEXT_DELAY_MS = 850L
    const val STACKED_BAR_TEXT_OUT_MS = 150L
    /** The bar text's x offset when hidden (−24 mock). */
    const val STACKED_BAR_TEXT_DX = -10.8f
    /** A bar **change** (§7.1): stripes and text out, background lerp and content swap at 300, stripes back at 300 / 380, text at 450. */
    const val STACKED_BAR_CHANGE_OUT_MS = 300L
    const val STACKED_BAR_CHANGE_STRIPE_A_DELAY_MS = 300L
    const val STACKED_BAR_CHANGE_STRIPE_B_DELAY_MS = 380L
    const val STACKED_BAR_CHANGE_TEXT_DELAY_MS = 450L
    const val STACKED_BAR_CHANGE_MS = 900L
    /** A bar **text swap** (§7.1, S10): text out 150, swap, text in 350 / 450; stripes and background untouched. */
    const val STACKED_BAR_SWAP_OUT_MS = 150L
    const val STACKED_BAR_SWAP_IN_ALPHA_MS = 350L
    const val STACKED_BAR_SWAP_IN_MOVE_MS = 450L
    const val STACKED_BAR_SWAP_MS = 600L
    /** The row stripe's widen / narrow (3.6 ⇄ 6.3). */
    const val STACKED_ROW_STRIPE_MS = 400L
    /** The row flash after a goal (§7.2), from 3 600 ms; `GoalSpec.STACKED.rowFlashMs`. */
    const val STACKED_ROW_FLASH_MS = 1_200L
}

/**
 * (Stacked §4.2, §13) The mock-up's geometry in **mock px** (`design/nhl-stacked/Stacked.dc.html`, drawn at 870 × 240 + a
 * 66 bar). Design px = mock px × [SportsConstants.STACKED_SCALE] (Large × 1.25 more), except where §4.2 raises or drops a
 * value: the bar height ([SportsConstants.STACKED_BAR_H]), the bar text, tally and SOG label sizes, the 1-px lines (not
 * scaled), the score slot (widened from 90 to [SCORE_SLOT_W]), the SOG label's rules (dropped) and the name line (dropped).
 */
object StackedMock {
    const val TOP_H = 240f
    const val ROW_W = 540f
    const val ROW_H = 120f
    /** The row stripe, and its power-play width. */
    const val STRIPE_W = 8f
    const val PP_STRIPE_W = 14f
    /** The logo slot: margin-left, width; the logo's height. */
    const val LOGO_ML = 22f
    const val LOGO_SLOT_W = 112f
    const val LOGO_H = 96f
    /** The team code: padding-left, size (800 → Instrument Sans Bold). */
    const val CODE_PL = 30f
    const val CODE_PX = 60f
    /** The score slot: width (the mock's 90 widened so "10" fits), margin-right, size. */
    const val SCORE_SLOT_W = 102f
    const val SCORE_MR = 34f
    const val SCORE_PX = 78f
    /** The SOG column, its digits, its label (19 → raised to 18 design px, letter-spacing .08 em). */
    const val SOG_COL_W = 130f
    const val SOG_DIGITS_PX = 54f
    const val SOG_LABEL_PX = 19f
    const val SOG_LABEL_TRACK_EM = 0.08f
    /** The clock column, the period slot (height, bottom padding, size), the rule's side margin, the clock size. */
    const val CLOCK_COL_W = 200f
    const val PERIOD_SLOT_H = 112f
    const val PERIOD_PAD_B = 14f
    const val PERIOD_PX = 48f
    const val RULE_MARGIN = 26f
    const val CLOCK_PX = 64f
    /** The bar (66 → [SportsConstants.STACKED_BAR_H] 33), its stripes, and its text. */
    const val BAR_H = 66f
    const val BAR_STRIPE_A_L = 46f
    const val BAR_STRIPE_B_L = 84f
    const val BAR_STRIPE_W = 26f
    const val BAR_STRIPE_OVERHANG = 10f
    const val BAR_STRIPE_TRAVEL = -140f
    const val BAR_TEXT_PL = 140f
    const val BAR_TEXT_GAP = 26f
    const val BAR_TEXT_PX = 40f
    /** The box's corner radius; its drop shadow (offset y, blur). */
    const val BOX_RADIUS = 6f
    const val SHADOW_DY = 12f
    const val SHADOW_BLUR = 36f
    /** The goal panel: padding, gap, badge size, GOAL size and tracking, the tally (30 → raised to 20 design px). */
    const val GOAL_PAD = 40f
    const val GOAL_GAP = 30f
    const val GOAL_LOGO = 150f
    const val GOAL_WORD_PX = 140f
    const val GOAL_TRACK_EM = 0.04f
    const val TALLY_PX = 30f
    const val TALLY_LINE_GAP = 6f
    /** The streaks: widths and overhang. */
    const val STREAK1_W = 110f
    const val STREAK2_W = 40f
    const val STREAK_OVERHANG = 40f
}
