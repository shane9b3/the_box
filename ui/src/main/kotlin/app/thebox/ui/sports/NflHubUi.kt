package app.thebox.ui.sports

import android.content.res.Resources
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.LeagueDetail
import app.thebox.core.sports.Market
import app.thebox.core.sports.NflBreak
import app.thebox.core.sports.NflPlay
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipOffer
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.EspnDetailMapper
import app.thebox.core.sports.hub.GameClip
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.PlayScore
import app.thebox.core.sports.hub.ScoringPlay
import app.thebox.core.sports.hub.StatKind
import app.thebox.core.sports.hub.StatRow
import app.thebox.core.sports.hub.StatRules
import app.thebox.core.sports.hub.StatValue
import app.thebox.ui.R
import java.text.NumberFormat
import java.util.Locale

/**
 * (NFL hub P3, docs/design/m6-nfl-hub.md §2, §3.1) The NFL's hub: the panel's rows per phase (§2.2), the fitted summary
 * lines with the board's live situation first (§2.4), the media and its reason captions (§2.1, §2.4), the slots and their
 * spoken reasons (§2.3), and the Scoring sheet's rows (§3.1). Every clip it offers passes [ClipOffer] at the build's wall
 * clock plus the detail's server skew (§4.3); the view model's offer tick rebuilds the panel at each expiry instant.
 */
internal object NflHubUi : HubLeagueUi {
    override val detailed: Boolean get() = true
    override val source: HubSource get() = HubSource.ESPN

    // ---- (Code review 2026-11-26, UI open defect #1 rows 1–4, 7, 9–11, 13, 14) the NFL's slot and offer branches

    override fun slotFor(action: SlotAction, c: HubSlots.Ctx): HubSlot? = when (action) {
        SlotAction.GOALS -> scoringSlot(c)
        SlotAction.WATCH_RECAP -> highlightSlot(c)
        SlotAction.WATCH_FROM_START -> fromStartSlot(c)
        SlotAction.MATCHUP -> matchupSlot(c)
        else -> null
    }

    override fun playableRecap(c: HubSlots.Ctx): Boolean = highlight(c.game, c.detail, offerNow(c.nowMs, c.detail)) != null

    /** Only a highlight offered now plays (the slot may be a frozen one whose clip just expired: then [recapGoneToast]). */
    override fun recapRef(game: GameSnapshot, detail: GameDetail?, nowMs: Long): ClipRef? =
        highlight(game, detail, offerNow(nowMs, detail))?.let { ClipRef(it.videoId, ClipKind.RECAP, game.id) }

    /** (UI m3) "ESPN removed this video; …" (§3.4). */
    override val recapGoneToast: HubToastKind get() = HubToastKind.VIDEO_EXPIRED

    override fun cardHighlights(game: GameSnapshot, nowMs: Long): Boolean = game.media != null && highlight(game, null, nowMs) != null

    override val videoKilled: Boolean get() = !SportsConstants.ESPN_VIDEO_ENABLED

    /** (§2.1, arch m7) The NFL detail carries no score: the headline is always the board's. */
    override val detailCarriesScore: Boolean get() = false

    /** (UI M1) Pre-game → live re-freezes `[Follow, Channels, Matchup]` to `[Follow, Channels, Scoring]`. */
    override val refreezeOnPhaseChange: Boolean get() = true

    /** (UI M1) The Matchup sheet is pre-game only (season leaders, the week's injury report); it closes at kick-off. */
    override fun matchupOpen(game: GameSnapshot): Boolean = pre(game)

    private fun pre(g: GameSnapshot) = g.phase == GamePhase.SCHEDULED || g.phase == GamePhase.PRE

    /**
     * (UI M1, m5) Matchup: pre-game only (a live summary's leaders are tonight's game numbers and its injuries the
     * game-day inactives), and enabled exactly when the sheet has a focusable row ([nflMatchupHasRows]).
     */
    internal fun matchupSlot(c: HubSlots.Ctx): HubSlot =
        HubSlot(SlotAction.MATCHUP, SlotLabel.MATCHUP, c.pre && c.detail?.let(::nflMatchupHasRows) == true)

    private val PRE_KINDS = listOf(StatKind.RECORD, StatKind.LAST_FIVE, StatKind.POINTS_PG, StatKind.POINTS_ALLOWED_PG, StatKind.YARDS_PG, StatKind.YARDS_ALLOWED_PG)
    private val LIVE_KINDS = listOf(StatKind.TOTAL_YARDS, StatKind.PASS_YARDS, StatKind.RUSH_YARDS, StatKind.THIRD_DOWN, StatKind.TURNOVERS)
    private val FINAL_KINDS = listOf(StatKind.TOTAL_YARDS, StatKind.PASS_YARDS, StatKind.RUSH_YARDS, StatKind.FIRST_DOWNS, StatKind.THIRD_DOWN, StatKind.TURNOVERS)

    /** The summary line's width (§2.4: "fitted" = the first candidate whose measured width is ≤ 664). */
    private const val LINE_W = HubPanelPlan.INNER_W.toFloat()

    private fun live(g: GameSnapshot) = g.phase == GamePhase.LIVE || g.phase == GamePhase.INTERMISSION

    // ------------------------------------------------------------------ rows (§2.2)

    override fun rows(res: Resources, m: HubPanelModel, g: GameSnapshot, away: HubName, home: HubName): RowsSpec {
        val final = g.phase == GamePhase.FINAL
        val pre = !live(g) && !final
        val header = res.getString(if (pre) R.string.sports_hub_stats_season else R.string.sports_hub_stats_team)
        val d = m.detail
        if (m.detailKind == DetailKind.FAILED && d == null) {
            return RowsSpec(header = header, rows = emptyList(), error = HubPanelContents.failureText(res, m.failure, m.slots, final, HubSource.ESPN))
        }
        val stats = if (pre) d?.seasonStats else d?.gameStats
        val byKind = stats.orEmpty().associateBy { it.kind }
        val kinds = when {
            pre -> PRE_KINDS
            final -> FINAL_KINDS
            else -> LIVE_KINDS
        }
        val rows = kinds.map { k -> statRow(res, k, byKind[k], away, home) }
        // Final: Time of possession is the extra row (§2.2), kept only while the media's floor holds (§2.6).
        val extra = if (final) listOfNotNull(byKind[StatKind.POSSESSION]?.let { statRow(res, StatKind.POSSESSION, it, away, home) }) else emptyList()
        return RowsSpec(header = header, rows = rows, extra = extra, loading = d == null)
    }

    /** One stat row: ESPN's value as drawn, [StatRules.better] for the white side, the spoken form (§3.7) as its description. */
    internal fun statRow(res: Resources, kind: StatKind, row: StatRow?, away: HubName, home: HubName): PanelRow {
        val label = res.getString(HubPanelContents.statLabel(kind))
        val none = res.getString(R.string.sports_hub_stat_none)
        if (row == null) return HubPanelContents.blank(label, none, away, home, res)
        val a = HubPanelContents.value(row.away)
        val h = HubPanelContents.value(row.home)
        val desc = res.getString(R.string.sports_hub_desc_stat, label, away.nickname, spoken(res, row.kind, row.away), home.nickname, spoken(res, row.kind, row.home))
        return PanelRow(label, a, h, StatRules.better(row), desc)
    }

    /** §3.7: "4 of 12", "28 minutes 29", "6 wins, 4 losses"; decimals and counts as written. */
    internal fun spoken(res: Resources, kind: StatKind, v: StatValue): String = when (v) {
        is StatValue.Fraction -> res.getString(R.string.sports_hub_desc_fraction, v.made, v.of)
        is StatValue.Clock -> res.getString(R.string.sports_hub_desc_clock, v.seconds / 60, v.seconds % 60)
        is StatValue.Text -> if (kind == StatKind.RECORD || kind == StatKind.LAST_FIVE) spokenRecord(res, v.text) ?: v.text else v.text
        else -> HubPanelContents.value(v)
    }

    /** "6-4" → "6 wins, 4 losses"; "4-5-1" → "4 wins, 5 losses, 1 tie"; anything else null (read as written). */
    internal fun spokenRecord(res: Resources, text: String): String? {
        val parts = text.trim().split('-')
        if (parts.size !in 2..3) return null
        val n = parts.map { it.trim().toIntOrNull() ?: return null }
        val words = buildList {
            add(res.getQuantityString(R.plurals.sports_hub_desc_wins, n[0], n[0]))
            add(res.getQuantityString(R.plurals.sports_hub_desc_losses, n[1], n[1]))
            if (n.size == 3) add(res.getQuantityString(R.plurals.sports_hub_desc_ties, n[2], n[2]))
        }
        return words.reduce { acc, w -> res.getString(R.string.sports_hub_desc_join_comma, acc, w) }
    }

    // ------------------------------------------------------------------ summary (§2.4)

    override fun summary(res: Resources, m: HubPanelModel, g: GameSnapshot, away: HubName, home: HubName, measure: Measure, factor: Float): SummarySpec {
        val d = m.detail
        val failed = m.detailKind == DetailKind.FAILED && d == null
        return when (g.phase) {
            GamePhase.LIVE, GamePhase.INTERMISSION -> {
                val out = ArrayList<SummaryLine>(2)
                val extra = ArrayList<PanelRow>(1)
                val nfl = g.detail as? LeagueDetail.Nfl
                situation(res, g, nfl, away, home, measure)?.let { (line, droppedTimeouts) ->
                    out += line
                    if (droppedTimeouts && nfl != null) timeoutsRow(res, nfl, away, home)?.let { extra += it }
                }
                when {
                    d != null -> out += latestScore(res, g, d, away, home)
                    failed -> boardLines(res, g).firstOrNull()?.let { out += it }
                }
                SummarySpec(out, extra)
            }
            GamePhase.FINAL -> {
                if (d == null) return SummarySpec(boardLines(res, g))
                val out = ArrayList<SummaryLine>(2)
                leadersLine(res, g, d, measure)?.let { out += it }
                d.recapHeadline?.takeIf { it.isNotBlank() }?.let { h ->
                    val label = res.getString(R.string.sports_hub_summary_recap)
                    out += SummaryLine(label, label + res.getString(R.string.sports_hub_summary_sep) + h.trim(), oneLine = true)
                }
                SummarySpec(out)
            }
            else -> {
                if (d == null) return SummarySpec(emptyList())
                val out = ArrayList<SummaryLine>(2)
                seasonLeaders(res, d, "passingYards", R.string.sports_hub_summary_passing_leaders)?.let { out += it }
                seasonLeaders(res, d, "rushingYards", R.string.sports_hub_summary_rushing_leaders)?.let { out += it }
                SummarySpec(out)
            }
        }
    }

    /** Label (600) + text (400) as drawn: the label is the start of [SummaryLine.text]. */
    private fun width(line: SummaryLine, measure: Measure): Float {
        val label = line.label
        return if (label.isNotEmpty() && line.text.startsWith(label)) measure(label, 24, 600) + measure(line.text.removePrefix(label), 24, 400)
        else measure(line.text, 24, 400)
    }

    private fun firstFit(candidates: List<SummaryLine>, measure: Measure): Int {
        val i = candidates.indexOfFirst { width(it, measure) <= LINE_W }
        return if (i >= 0) i else candidates.lastIndex
    }

    /**
     * Live line 1 (§2.4), from the board's `situation`: the full line, then without Timeouts (which then becomes the extra
     * row), then without Red zone. Null for no situation, halftime, or a None play. The second value: Timeouts were dropped.
     */
    internal fun situation(res: Resources, g: GameSnapshot, nfl: LeagueDetail.Nfl?, away: HubName, home: HubName, measure: Measure): Pair<SummaryLine, Boolean>? {
        nfl ?: return null
        val sep = res.getString(R.string.sports_hub_summary_sep)
        if (nfl.delayed) return SummaryLine("", res.getString(R.string.sports_hub_sit_delayed), oneLine = true) to false
        if (g.phase == GamePhase.INTERMISSION && nfl.breakKind == NflBreak.HALFTIME) return null
        fun abbr(s: Side) = if (s == Side.AWAY) g.away.abbrev else g.home.abbrev
        fun nick(s: Side) = if (s == Side.AWAY) away.nickname else home.nickname
        return when (val p = nfl.play) {
            is NflPlay.Kickoff -> p.kicker?.let { SummaryLine("", res.getString(R.string.sports_hub_sit_kickoff, abbr(it)), oneLine = true) to false }
            is NflPlay.Dead -> SummaryLine("", res.getString(R.string.sports_hub_sit_after_score), oneLine = true) to false
            NflPlay.None -> null
            is NflPlay.Down -> {
                val ord = SportsText.ordinal(res, p.down)
                val down = when {
                    p.goalToGo -> res.getString(R.string.sports_hub_nfl_down_goal, ord)
                    p.distance != null -> res.getString(R.string.sports_hub_nfl_down_distance, ord, p.distance.toString())
                    else -> ord
                }
                val f = p.field
                val downAt = when {
                    f == null -> down
                    f.territory == null -> res.getString(R.string.sports_hub_sit_at_midfield, down)
                    else -> res.getString(R.string.sports_hub_sit_at, down, abbr(f.territory!!), f.yard)
                }
                val ball = nfl.possession?.let { res.getString(R.string.sports_hub_sit_ball, abbr(it)) }
                val red = if (nfl.redZone) res.getString(R.string.sports_hub_sit_red_zone) else null
                val timeouts = if (nfl.awayTimeouts != null && nfl.homeTimeouts != null) res.getString(R.string.sports_hub_sit_timeouts, nfl.awayTimeouts!!, nfl.homeTimeouts!!) else null
                // The spoken form (§2.4): "Bengals ball, second and 7 at the Commanders 35, red zone, timeouts 2 and 3".
                val spokenDown = spokenDown(res, p.down)
                val sDown = when {
                    p.goalToGo -> res.getString(R.string.sports_hub_desc_sit_goal, spokenDown)
                    p.distance != null -> res.getString(R.string.sports_hub_desc_sit_down, spokenDown, p.distance!!)
                    else -> spokenDown
                }
                val sAt = when {
                    f == null -> null
                    f.territory == null -> res.getString(R.string.sports_hub_desc_sit_midfield)
                    else -> res.getString(R.string.sports_hub_desc_sit_at, nick(f.territory!!), f.yard)
                }
                fun spokenOf(withRed: Boolean, withTimeouts: Boolean): String = listOfNotNull(
                    nfl.possession?.let { res.getString(R.string.sports_hub_desc_sit_ball, nick(it)) },
                    if (sAt == null) sDown else "$sDown $sAt",
                    if (withRed && red != null) res.getString(R.string.sports_hub_desc_sit_red_zone) else null,
                    if (withTimeouts && timeouts != null) res.getString(R.string.sports_hub_desc_sit_timeouts, nfl.awayTimeouts!!, nfl.homeTimeouts!!) else null,
                ).reduce { a, b -> res.getString(R.string.sports_hub_desc_join_comma, a, b) }
                fun lineOf(withRed: Boolean, withTimeouts: Boolean): SummaryLine {
                    val text = listOfNotNull(ball, downAt, if (withRed) red else null, if (withTimeouts) timeouts else null).joinToString(sep)
                    // The spoken form keeps every piece the board gave: the dropped Timeouts are the extra row, read there too.
                    return SummaryLine(ball.orEmpty(), text, oneLine = true, desc = spokenOf(withRed = red != null, withTimeouts = withTimeouts))
                }
                val candidates = buildList {
                    add(lineOf(withRed = true, withTimeouts = true) to false)
                    if (timeouts != null) add(lineOf(withRed = true, withTimeouts = false) to true)
                    if (red != null) add(lineOf(withRed = false, withTimeouts = false) to (timeouts != null))
                }
                candidates[firstFit(candidates.map { it.first }, measure)]
            }
        }
    }

    private fun spokenDown(res: Resources, n: Int): String = when (n) {
        1 -> res.getString(R.string.sports_hub_desc_down_1)
        2 -> res.getString(R.string.sports_hub_desc_down_2)
        3 -> res.getString(R.string.sports_hub_desc_down_3)
        4 -> res.getString(R.string.sports_hub_desc_down_4)
        else -> n.toString()
    }

    /** The extra Timeouts row (§2.2), when the situation line had to drop them; no side is highlighted. */
    private fun timeoutsRow(res: Resources, nfl: LeagueDetail.Nfl, away: HubName, home: HubName): PanelRow? {
        val a = nfl.awayTimeouts ?: return null
        val h = nfl.homeTimeouts ?: return null
        return HubPanelContents.simpleRow(res.getString(R.string.sports_hub_stat_timeouts), a.toString(), h.toString(), away, home, res)
    }

    /** Live line 2: "Latest score · Tee Higgins 31 Yd pass from Joe Burrow (CIN)", or (the Scoring slot's visible reason) "No points yet". */
    private fun latestScore(res: Resources, g: GameSnapshot, d: GameDetail, away: HubName, home: HubName): SummaryLine {
        val label = res.getString(R.string.sports_hub_summary_latest_score)
        val sep = res.getString(R.string.sports_hub_summary_sep)
        val last = d.plays.lastOrNull() ?: return SummaryLine(label, label + sep + res.getString(R.string.sports_hub_reason_no_points), oneLine = true)
        val abbr = if (last.side == Side.AWAY) g.away.abbrev else g.home.abbrev
        return SummaryLine(label, label + sep + res.getString(R.string.sports_hub_summary_score_play, playText(last), abbr), oneLine = true, desc = playDesc(res, last, away, home))
    }

    /** Final line 1 (§2.4): one game leader per category, fitted: 3 with team, 3 without, 2 with, 2 without, 1 with. */
    internal fun leadersLine(res: Resources, g: GameSnapshot, d: GameDetail, measure: Measure): SummaryLine? {
        val cats = listOf("passingYards" to R.string.sports_hub_leader_pass, "rushingYards" to R.string.sports_hub_leader_rush, "receivingYards" to R.string.sports_hub_leader_rec)
        val picks = cats.mapNotNull { (cat, fmt) ->
            val p = d.leaders.firstOrNull { it.category.equals(cat, ignoreCase = true) } ?: return@mapNotNull null
            val a = p.away
            val h = p.home
            val (side, l) = when {
                a == null && h == null -> return@mapNotNull null
                h == null -> Side.AWAY to a!!
                a == null -> Side.HOME to h
                h.value > a.value -> Side.HOME to h
                else -> Side.AWAY to a
            }
            val piece = res.getString(fmt, shortName(l.name), l.value)
            piece to res.getString(R.string.sports_hub_leader_team, piece, if (side == Side.AWAY) g.away.abbrev else g.home.abbrev)
        }
        if (picks.isEmpty()) return null
        val label = res.getString(R.string.sports_hub_summary_leaders)
        val sep = res.getString(R.string.sports_hub_summary_sep)
        fun line(n: Int, team: Boolean) = SummaryLine(label, label + sep + picks.take(n).joinToString(sep) { if (team) it.second else it.first }, oneLine = true)
        val candidates = buildList {
            for (n in picks.size downTo 2) { add(line(n, true)); add(line(n, false)) }
            add(line(1, true))
        }.distinct()
        return candidates[firstFit(candidates, measure)]
    }

    /** Pre-game: "Passing leaders · Love 2,838 · Stafford 2,623", away first. */
    private fun seasonLeaders(res: Resources, d: GameDetail, category: String, labelRes: Int): SummaryLine? {
        val p = d.leaders.firstOrNull { it.category.equals(category, ignoreCase = true) } ?: return null
        val parts = listOfNotNull(p.away, p.home).map { res.getString(R.string.sports_hub_leader_value, shortName(it.name), grouped(it.value)) }
        if (parts.isEmpty()) return null
        val label = res.getString(labelRes)
        val sep = res.getString(R.string.sports_hub_summary_sep)
        return SummaryLine(label, label + sep + parts.joinToString(sep), oneLine = true)
    }

    private fun grouped(n: Int): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(n.toLong())

    /** Today's board lines (the failed and loading states keep them, §2.5): "Passing · Name line (TEAM)", then Rushing. */
    private fun boardLines(res: Resources, g: GameSnapshot): List<SummaryLine> {
        val sep = res.getString(R.string.sports_hub_summary_sep)
        val leaders = g.extras?.leaders.orEmpty()
        return listOf("passing" to R.string.sports_hub_summary_passing, "rushing" to R.string.sports_hub_summary_rushing).mapNotNull { (cat, labelRes) ->
            val l = leaders.firstOrNull { it.category.equals(cat, true) } ?: return@mapNotNull null
            val team = if (l.side == Side.AWAY) g.away.abbrev else g.home.abbrev
            val label = res.getString(labelRes)
            SummaryLine(label, label + sep + "${l.name} ${l.line} ($team)".replace("  ", " "), oneLine = true)
        }
    }

    /**
     * (§2.4) ESPN's `shortName` after the first ". ": "J. Cook III" → "Cook III", "C.J. Stroud" → "Stroud", "A. St. Brown" →
     * "St. Brown"; without ". " the whole name. Never the last word. (Integration §18) The one implementation is
     * `EspnDetailMapper.leaderSurname`; `Leader.name` holds ESPN's raw `shortName`, and this is the only place it is applied
     * (the panel's leader lines and the Matchup sheet), so the non-idempotent rule runs exactly once.
     */
    internal fun shortName(name: String): String = EspnDetailMapper.leaderSurname(name)

    // ------------------------------------------------------------------ highlights and clips (§4.3)

    /** The reference instant of every offer check: the wall clock plus the detail's server skew (§4.3). */
    internal fun offerNow(nowMs: Long, d: GameDetail?): Long = nowMs + (d?.clockSkewMs ?: 0L)

    /** The game highlight offered now: the detail's RECAP clip, else the board's highlight (§4.3). */
    internal data class Highlight(val videoId: Long, val clip: GameClip?)

    internal fun highlight(g: GameSnapshot, d: GameDetail?, offerNowMs: Long): Highlight? {
        d?.clips?.firstOrNull { it.kind == ClipKind.RECAP && offered(it, offerNowMs) }?.let { return Highlight(it.videoId, it) }
        val media = g.media ?: return null
        val id = media.recapId ?: return null
        // A board highlight the detail lists as expired is not offered either.
        if (d?.clips?.any { it.videoId == id && !offered(it, offerNowMs) } == true) return null
        val exp = media.recapExpiresMs
        // (P3 note) The mapper sets the board's expiry with every highlight; a missing one is trusted as offered.
        return if (exp == null || ClipOffer.offered(null, exp, offerNowMs)) Highlight(id, null) else null
    }

    internal fun offered(c: GameClip, offerNowMs: Long): Boolean = ClipOffer.offered(c.embargoMs, c.expiresMs, offerNowMs)

    /** Every offered PLAY clip, oldest first (`originalPublishDate`): the More videos section (§3.1). */
    internal fun moreVideos(d: GameDetail?, offerNowMs: Long): List<GameClip> =
        d?.clips.orEmpty().filter { it.kind == ClipKind.PLAY && offered(it, offerNowMs) }.sortedWith(compareBy<GameClip> { it.publishedMs ?: Long.MAX_VALUE }.thenBy { it.videoId })

    /** Whether [ref] may be played now (the view model's WATCH_RECAP and the clip session's auto-advance). */
    override fun offeredRef(ref: ClipRef, game: GameSnapshot?, detail: GameDetail?, nowMs: Long): Boolean {
        val g = game
        val d = detail
        val at = offerNow(nowMs, d)
        d?.clips?.firstOrNull { it.videoId == ref.videoId }?.let { return offered(it, at) }
        if (g != null && g.media?.recapId == ref.videoId) return highlight(g, d, at)?.videoId == ref.videoId
        return true
    }

    /**
     * The next instant anything [g] shows changes with time alone (the view model's one timer, §4.3); null for none: an
     * offer changing (an expiry, an embargo lifting) and, (code review 2026-11-26, UI m1) on a final without an offered
     * highlight, the caption windows at "ended + `HUB_NFL_HIGHLIGHT_WAIT_MS`" (Highlights soon → No highlights) and
     * "ended + `HUB_CLIP_DEFAULT_LIFE_MS`" (→ "ESPN removes videos…"), on the wall clock as [noHighlightCaption] reads them.
     */
    override fun nextOfferChange(game: GameSnapshot, detail: GameDetail?, nowMs: Long, skewMs: Long): Long? {
        val g = game
        val d = detail
        val skew = skewMs
        val at = nowMs + skew
        val fromClips = d?.clips.orEmpty().mapNotNull { ClipOffer.changesAt(it.embargoMs, it.expiresMs, at) }
        val fromBoard = g.media?.recapExpiresMs?.let { ClipOffer.changesAt(null, it, at) }
        // Back from the reference clock to the wall clock the view model waits on.
        val offers = (fromClips + listOfNotNull(fromBoard)).map { it - skew }
        val windows = if (g.phase == GamePhase.FINAL && highlight(g, d, at) == null) {
            val ended = g.startUtcMs + SportsConstants.HUB_NFL_GAME_LENGTH_MS
            listOf(ended + SportsConstants.HUB_NFL_HIGHLIGHT_WAIT_MS, ended + SportsConstants.HUB_CLIP_DEFAULT_LIFE_MS).filter { it > nowMs }
        } else {
            emptyList()
        }
        return (offers + windows).minOrNull()
    }

    /** The game's estimated end (no source gives it; §5.2's game length), for the "Highlights soon" window (§2.3). */
    private fun endedAgoMs(g: GameSnapshot, nowMs: Long): Long = (nowMs - (g.startUtcMs + SportsConstants.HUB_NFL_GAME_LENGTH_MS)).coerceAtLeast(0L)

    /** Within the wait for a highlight (§2.3): ended under `HUB_NFL_HIGHLIGHT_WAIT_MS` ago and nothing expired yet. */
    private fun highlightSoon(g: GameSnapshot, d: GameDetail?, nowMs: Long): Boolean =
        !anyExpired(g, d, nowMs) && endedAgoMs(g, nowMs) < SportsConstants.HUB_NFL_HIGHLIGHT_WAIT_MS

    /**
     * A highlight was listed (detail or board) and is past its expiry: ESPN removed it (§2.4 "removes videos"). (Code
     * review 2026-11-26, UI m2) A highlight still under embargo is not "removed": it reads Highlights soon until it lifts.
     */
    private fun anyExpired(g: GameSnapshot, d: GameDetail?, nowMs: Long): Boolean {
        val at = offerNow(nowMs, d)
        if (d?.clips?.any { it.kind == ClipKind.RECAP && !ClipOffer.listed(it.expiresMs, at) } == true) return true
        val media = g.media ?: return false
        return media.recapId != null && media.recapExpiresMs != null && !ClipOffer.offered(null, media.recapExpiresMs!!, at)
    }

    /** The reason caption (§2.4) of a final without an offered highlight: soon, didn't post, removed, or video off. */
    internal fun noHighlightCaption(res: Resources, g: GameSnapshot, d: GameDetail?, nowMs: Long, videoOff: Boolean): String = res.getString(
        when {
            videoOff -> R.string.sports_hub_caption_videos_off_espn
            anyExpired(g, d, nowMs) -> R.string.sports_hub_caption_highlights_removed
            endedAgoMs(g, nowMs) < SportsConstants.HUB_NFL_HIGHLIGHT_WAIT_MS -> R.string.sports_hub_caption_highlights_soon
            endedAgoMs(g, nowMs) < SportsConstants.HUB_CLIP_DEFAULT_LIFE_MS -> R.string.sports_hub_caption_highlights_none
            else -> R.string.sports_hub_caption_highlights_removed
        },
    )

    // ------------------------------------------------------------------ media (§2.1, §2.4)

    override fun media(res: Resources, m: HubPanelModel, g: GameSnapshot, clock24: Boolean, locale: Locale, lowEnd: Boolean): MediaSpec {
        val d = m.detail
        if (g.phase == GamePhase.FINAL) {
            if (m.videoOff) return HubPanelContents.art(res, g, m, res.getString(R.string.sports_hub_caption_videos_off_espn), clock24, locale)
            val hl = highlight(g, d, offerNow(m.nowMs, d))
                // (UX M3) After the highlight expires: the matchup art with the reason, never a picture that looks playable.
                ?: return HubPanelContents.art(res, g, m, noHighlightCaption(res, g, d, m.nowMs, videoOff = false), clock24, locale)
            val clip = hl.clip
            val caption = clip?.durationMs?.let { res.getString(R.string.sports_hub_caption_highlights, SportsText.duration(it)) }
                ?: res.getString(R.string.sports_hub_row_game_highlights)
            // Low-end devices never decode the 1920 thumbnail (§4.5): art with the caption and no disc.
            if (lowEnd || clip?.thumbUrl == null) return HubPanelContents.art(res, g, m, caption, clock24, locale)
            val info = ClipInfo(ClipRef(clip.videoId, ClipKind.RECAP, g.id), clip.title, null, clip.durationMs, null, clip.thumbUrl)
            return MediaSpec.Poster(clip.videoId, info, res.getString(R.string.sports_hub_tag_highlights), caption)
        }
        return HubPanelContents.art(res, g, m, venueNetwork(res, g, d), clock24, locale)
    }

    /** "SoFi Stadium · Netflix": the venue and the first national network; either alone; null with neither (§2.4). */
    internal fun venueNetwork(res: Resources, g: GameSnapshot, d: GameDetail?): String? {
        val venue = d?.venue?.takeIf { it.isNotBlank() }
        val network = (g.broadcasts.firstOrNull { it.market == Market.NATIONAL } ?: g.broadcasts.firstOrNull())?.network?.takeIf { it.isNotBlank() }
        return when {
            venue != null && network != null -> res.getString(R.string.sports_hub_caption_venue_network, venue, network)
            else -> venue ?: network
        }
    }

    // ------------------------------------------------------------------ slots (§2.3)

    override fun slots(c: HubSlots.Ctx): List<HubSlot> {
        if (c.postponed) return List(3) { HubSlot.EMPTY }
        if (c.final) {
            val first = when {
                c.detailRetry -> HubSlots.forAction(SlotAction.RETRY, c)
                highlightSlot(c).usable -> highlightSlot(c)
                c.catchUp.isNotEmpty() -> fromStartSlot(c)
                else -> highlightSlot(c)
            }
            return listOf(first, scoringSlot(c), HubSlots.forAction(SlotAction.BOX_SCORE, c))
        }
        val first = if (c.detailRetry) HubSlots.forAction(SlotAction.RETRY, c) else HubSlots.forAction(SlotAction.FOLLOW, c)
        val third = if (c.live) scoringSlot(c) else HubSlots.forAction(SlotAction.MATCHUP, c)
        return listOf(first, HubSlots.forAction(SlotAction.CHANNEL, c), third)
    }

    /** ▶ Highlights while one is offered and video is on; else the grey Highlights soon / No highlights (no glyph). */
    internal fun highlightSlot(c: HubSlots.Ctx): HubSlot {
        if (!c.videoOff && highlight(c.game, c.detail, offerNow(c.nowMs, c.detail)) != null) return HubSlot(SlotAction.WATCH_RECAP, SlotLabel.HIGHLIGHTS, true)
        val soon = !c.videoOff && highlightSoon(c.game, c.detail, c.nowMs)
        return HubSlot(SlotAction.WATCH_RECAP, if (soon) SlotLabel.HIGHLIGHTS_SOON else SlotLabel.NO_HIGHLIGHTS, false)
    }

    internal fun fromStartSlot(c: HubSlots.Ctx): HubSlot = HubSlot(SlotAction.WATCH_FROM_START, SlotLabel.FROM_START, c.catchUp.isNotEmpty())

    /**
     * Scoring (n): always enabled on a final; live, enabled with a scoring play or an offered clip, or while the detail is
     * loading or failed (the sheet then says why). Otherwise the grey "Scoring", spoken "No points yet".
     */
    internal fun scoringSlot(c: HubSlots.Ctx): HubSlot {
        val d = c.detail
        val n = d?.plays?.size ?: 0
        val label = if (n > 0) SlotLabel.SCORING_N else SlotLabel.SCORING
        val enabled = c.final || d == null || n > 0 || (!c.videoOff && (moreVideos(d, offerNow(c.nowMs, d)).isNotEmpty() || highlight(c.game, d, offerNow(c.nowMs, d)) != null))
        return HubSlot(SlotAction.GOALS, label, enabled, n)
    }

    override fun slotReasons(res: Resources, m: HubPanelModel, slots: List<HubSlot>): List<String?> {
        val g = m.game
        return slots.map { s ->
            if (s.action == null || s.enabled || g == null) null
            else when (s.label) {
                SlotLabel.HIGHLIGHTS_SOON -> res.getString(R.string.sports_hub_caption_highlights_soon)
                SlotLabel.NO_HIGHLIGHTS -> noHighlightCaption(res, g, m.detail, m.nowMs, m.videoOff)
                SlotLabel.SCORING, SlotLabel.SCORING_N -> res.getString(R.string.sports_hub_reason_no_points)
                // (Integration §18, P3 note 16) Grey while the summary loads, or when ESPN lists nothing for the sheet.
                // (Code review 2026-11-26, UI m4) A failed summary is not "still loading"; (M1) after kick-off it is not offered.
                SlotLabel.MATCHUP -> res.getString(
                    when {
                        !pre(g) -> R.string.sports_hub_reason_matchup_after_kickoff
                        m.detail == null && m.detailKind == DetailKind.FAILED -> R.string.sports_hub_reason_matchup_failed
                        m.detail == null -> R.string.sports_hub_reason_matchup_loading
                        else -> R.string.sports_hub_reason_matchup_none
                    },
                )
                else -> null
            }
        }
    }

    // ------------------------------------------------------------------ clips and the Scoring sheet (§3.1)

    /** None, by design (§4.2): the panel and the sheet build their pictures from `GameClip`, never `info()`. */
    override fun clipRefs(game: GameSnapshot, detail: GameDetail?, infos: Map<Long, ClipInfo>, goalsOpen: Boolean): List<ClipRef> = emptyList()

    override fun scoringRows(
        res: Resources,
        game: GameSnapshot,
        detail: GameDetail?,
        infos: Map<Long, ClipInfo>,
        catchUp: List<ChannelCandidate>,
        offerNowMs: Long,
        videoOn: Boolean,
    ): List<ScoringRow> = buildList {
        val final = game.phase == GamePhase.FINAL
        val d = detail
        val away = HubNames.of(game.id.league, game.away)
        val home = HubNames.of(game.id.league, game.home)
        // 1. Scoring plays, in game order (no video marks, §3.1); or the §3.2 state when there are none.
        if (d != null) {
            if (d.plays.isEmpty()) {
                if (final) add(ScoringRow.Info(res.getString(R.string.sports_hub_scoring_final_none)))
                else if (live(game)) add(ScoringRow.Info(res.getString(R.string.sports_hub_scoring_live_none)))
            } else {
                d.plays.forEach { p -> add(eventRow(res, game, p, away, home)) }
            }
        }
        // 2. and 3. Game highlights, then More videos; video off: one info row instead (§3.2).
        if (!videoOn) {
            add(ScoringRow.Info(res.getString(R.string.sports_hub_caption_videos_off_espn)))
        } else {
            val hl = highlight(game, d, offerNowMs)
            if (hl != null) {
                add(ScoringRow.Video(ClipRef(hl.videoId, ClipKind.RECAP, game.id), res.getString(R.string.sports_hub_row_game_highlights), hl.clip?.durationMs, hl.clip?.thumbUrl))
            } else if (final) {
                add(ScoringRow.Info(noHighlightCaption(res, game, d, offerNowMs - (d?.clockSkewMs ?: 0L), videoOff = false)))
            }
            val more = moreVideos(d, offerNowMs)
            if (more.isNotEmpty()) {
                add(ScoringRow.Header(res.getString(R.string.sports_hub_header_more_videos)))
                more.forEach { c -> add(ScoringRow.Video(ClipRef(c.videoId, ClipKind.PLAY, game.id), c.title, c.durationMs, c.thumbUrl)) }
            }
        }
        // 4. Full game from the start (final, a catch-up airing found).
        if (final) catchUp.filter { it.airing != null }.forEachIndexed { n, c -> add(ScoringRow.CatchUp(c, n)) }
    }

    /** One scoring play's row (§3.1): the team logo, the play text, "Q2 1:55 · CIN TD · CIN leads 10–0", the spoken form. */
    internal fun eventRow(res: Resources, game: GameSnapshot, p: ScoringPlay, away: HubName, home: HubName): ScoringRow.Event {
        val sep = res.getString(R.string.sports_hub_summary_sep)
        val abbr = if (p.side == Side.AWAY) game.away.abbrev else game.home.abbrev
        val type = typeWord(res, p.kind)?.let { res.getString(R.string.sports_hub_play_team_type, abbr, it) }
        val score = when {
            p.away == p.home -> res.getString(R.string.sports_hub_goal_tied, p.away, p.home)
            p.away > p.home -> res.getString(R.string.sports_hub_goal_leads, game.away.abbrev, p.away, p.home)
            else -> res.getString(R.string.sports_hub_goal_leads, game.home.abbrev, p.home, p.away)
        }
        val line2 = listOfNotNull(res.getString(R.string.sports_hub_goal_when, quarter(res, p.period), p.clock), type, score).joinToString(sep)
        val line1 = playText(p).ifBlank { type ?: abbr }
        return ScoringRow.Event(p, "play:${p.id}", line1, line2, playDesc(res, p, away, home), RowIcon.TeamLogo(p.side), clip = null)
    }

    /** ESPN's text before " (": the kicker's PAT and the like are left out of the drawn line. */
    internal fun playText(p: ScoringPlay): String = p.text.substringBefore(" (").trim()

    /** "Q2", "OT", "2OT": the scoring sheet's quarter word, the same style everywhere in the sheet (§3.1). */
    internal fun quarter(res: Resources, p: PeriodLabel): String = when (p.kind) {
        PeriodKind.REG -> res.getString(R.string.sports_hub_quarter_n, p.number)
        PeriodKind.OT -> if (p.number <= 1) res.getString(R.string.sports_hub_period_ot) else res.getString(R.string.sports_hub_period_ot_n, p.number)
        PeriodKind.SO -> res.getString(R.string.sports_hub_period_ot)
    }

    private fun typeWord(res: Resources, k: PlayScore): String? = when (k) {
        PlayScore.TD -> res.getString(R.string.sports_hub_play_td)
        PlayScore.FG -> res.getString(R.string.sports_hub_play_fg)
        PlayScore.SAFETY -> res.getString(R.string.sports_hub_play_safety)
        PlayScore.DEF_TD -> res.getString(R.string.sports_hub_play_def_td)
        PlayScore.OTHER -> null
    }

    /** §3.7: "Bengals touchdown. Tee Higgins 31 yard pass from Joe Burrow. Second quarter, 1:55. Bengals lead 10 to 0." */
    internal fun playDesc(res: Resources, p: ScoringPlay, away: HubName, home: HubName): String {
        val team = if (p.side == Side.AWAY) away.nickname else home.nickname
        val type = res.getString(
            when (p.kind) {
                PlayScore.TD -> R.string.sports_hub_desc_play_td
                PlayScore.FG -> R.string.sports_hub_desc_play_fg
                PlayScore.SAFETY -> R.string.sports_hub_desc_play_safety
                PlayScore.DEF_TD -> R.string.sports_hub_desc_play_def_td
                PlayScore.OTHER -> R.string.sports_hub_desc_play_other
            },
        )
        val text = playText(p).replace(Regex("\\bYds?\\b"), res.getString(R.string.sports_hub_desc_yard)).trimEnd('.')
        val whenText = when (p.period.kind) {
            PeriodKind.REG -> res.getString(R.string.sports_hub_desc_quarter_when, spokenQuarter(res, p.period.number), p.clock)
            else -> res.getString(R.string.sports_hub_desc_overtime_when, p.clock)
        }
        val score = when {
            p.away == p.home -> res.getString(R.string.sports_hub_desc_play_tied, p.away, p.home)
            p.away > p.home -> res.getString(R.string.sports_hub_desc_play_leads, away.nickname, p.away, p.home)
            else -> res.getString(R.string.sports_hub_desc_play_leads, home.nickname, p.home, p.away)
        }
        return res.getString(R.string.sports_hub_desc_play, team, type, text, whenText, score)
    }

    private fun spokenQuarter(res: Resources, n: Int): String = when (n) {
        1 -> res.getString(R.string.sports_hub_desc_quarter_1)
        2 -> res.getString(R.string.sports_hub_desc_quarter_2)
        3 -> res.getString(R.string.sports_hub_desc_quarter_3)
        4 -> res.getString(R.string.sports_hub_desc_quarter_4)
        else -> n.toString()
    }
}
