// Contract (NFL Stacked commit 0): docs/design/m6-nfl-stacked.md §6, §8.1. The signature of [NflStackedColours.pair] is
// fixed for WP-2 (the table), WP-3 (the painters read `StackedPair`) and WP-4 (`StackedNflHooks.colours`, through
// `StackedColourTable.pair(League.NFL, …)`); changes go through the lead.
package app.thebox.core.sports

import java.util.concurrent.atomic.AtomicReferenceArray
import app.thebox.core.sports.SportsConstants as K

/**
 * (NFL Stacked §2.6) One bar's four colours, ARGB: [bg] the background, [a] / [b] the two skewed stripes, [fg] the words,
 * the divider's base colour and the flag icon. [NflStackedColours.bar] resolves them from a bar key and the game's pair.
 */
data class NflBarColours(val bg: Int, val a: Int, val b: Int, val fg: Int)

/**
 * (NFL Stacked §6.1, Rec 4) The NFL Stacked colours: its own object, so no NHL or CHL code path is touched. The 32
 * `NflTeamColours` rows plus `NflTeamColours.FALLBACK`, each resolved once with `NhlStackedColours.derive` (the one
 * derivation: stripe, `barBg = mixBlack(tab, .172)`, `barA`, `barB`, the true `goal` colours, the C7 `rowFlashAlpha`),
 * and its own `(n + 1)²` pair cache with `NhlStackedColours`' look-alike rule (ΔE76 < `STACKED_STRIPE_DE_MIN` → home
 * swaps, then away). The §6.2 table and every ST §5 rule are asserted by `NflStackedColoursTest` (WP-2).
 *
 * `StackedColourTable.pair(League.NFL, …)` delegates here.
 */
object NflStackedColours {
    /**
     * The game's pair (abbreviations trimmed and upper-cased; unknown or null → the fallback), with the §6.2 collision
     * rule applied. Cached per pair: the same instance for the same two teams, allocation-free after the first lookup.
     */
    fun pair(away: String?, home: String?): StackedPair {
        val a = index(away)
        val h = index(home)
        val slot = a * SLOTS + h
        PAIRS.get(slot)?.let { return it }
        val made = derivePair(TEAMS[a], TEAMS[h])
        return if (PAIRS.compareAndSet(slot, null, made)) made else PAIRS.get(slot) ?: made
    }

    /** The team's own resolution, before any collision swap (trimmed, upper-cased; unknown or null → the fallback). */
    fun of(abbrev: String?): StackedColours = TEAMS[index(abbrev)].primary

    /**
     * (§2.6, §6.3) The colours of the bar [key] in the game [pair]:
     * - a team kind ([NflBarKind.PLAY], [NflBarKind.PAT], [NflBarKind.KICKOFF], [NflBarKind.TIMEOUT], [NflBarKind.SCORE])
     *   with a side: that side's `barBg` / `barA` / `barB` (after the pair's swap: the bar colours never swap), white words;
     * - [NflBarKind.FLAG]: the fixed yellow bar `STACKED_NFL_FLAG_BG`, stripe A and words `STACKED_NFL_FLAG_FG`, stripe B
     *   `STACKED_NFL_FLAG_B` (the one fixed-colour exception to the derived stripes: the mock's white B is 1.46 : 1 on
     *   the yellow, S-D6);
     * - everything else (a team kind with no side, S-D10; the break kinds; NO_UPDATE / DELAYED): the neutral bar
     *   `STACKED_BAR_NEUTRAL` with the away / home **row** stripes (after the swap), white words.
     *
     * The red-zone patch under the sub text is [RED_ZONE_SUB], not part of this.
     */
    fun bar(key: NflBarKey, pair: StackedPair): NflBarColours {
        if (key.kind == NflBarKind.FLAG) return FLAG
        val side = key.side
        if (side != null && isTeam(key.kind)) {
            val c = if (side == Side.AWAY) pair.away else pair.home
            return NflBarColours(c.barBg, c.barA, c.barB, WHITE)
        }
        return NflBarColours(K.STACKED_BAR_NEUTRAL, pair.away.stripe, pair.home.stripe, WHITE)
    }

    /** The team kinds: the team's own bar colours when the key has a side. */
    fun isTeam(kind: NflBarKind): Boolean = when (kind) {
        NflBarKind.PLAY, NflBarKind.PAT, NflBarKind.KICKOFF, NflBarKind.TIMEOUT, NflBarKind.SCORE -> true
        else -> false
    }

    /** (§2.6) The yellow flag bar. */
    val FLAG = NflBarColours(K.STACKED_NFL_FLAG_BG, K.STACKED_NFL_FLAG_FG, K.STACKED_NFL_FLAG_B, K.STACKED_NFL_FLAG_FG)

    /** (§2.6, R26) The red-zone sub patch: `mixBlack(NFL_RED_ZONE, STACKED_BAR_BG_BLACK)` = `#A60D26` (white 7.76). */
    val RED_ZONE_SUB: Int = NhlTeamColours.mixBlack(K.NFL_RED_ZONE, K.STACKED_BAR_BG_BLACK)

    // ------------------------------------------------------------------ the table

    /** (§6.2, as `NhlStackedColours`) Home swaps to its other colour; failing that, home keeps its primary and away swaps. */
    private fun derivePair(away: NhlStackedColours.Resolved, home: NhlStackedColours.Resolved): StackedPair {
        if (!NhlStackedColours.collides(away.primary.stripe, home.primary.stripe)) return StackedPair(away.primary, home.primary)
        if (!NhlStackedColours.collides(away.primary.stripe, home.swapped.stripe)) return StackedPair(away.primary, home.swapped)
        return StackedPair(away.swapped, home.primary)
    }

    private const val WHITE = 0xFFFFFFFF.toInt()

    /** The 32 teams in a fixed order, then the fallback (index [FALLBACK_INDEX]). */
    private val KEYS: List<String> = NflTeamColours.TABLE.keys.sorted()
    private val INDEX: Map<String, Int> = KEYS.withIndex().associate { (i, k) -> k to i }
    private val FALLBACK_INDEX = KEYS.size
    private val SLOTS = KEYS.size + 1

    /** Every team resolved once, then the fallback. */
    private val TEAMS: Array<NhlStackedColours.Resolved> = Array(SLOTS) { i ->
        if (i < KEYS.size) NhlStackedColours.derive(KEYS[i], NflTeamColours.of(KEYS[i]))
        else NhlStackedColours.derive(null, NflTeamColours.FALLBACK)
    }

    /** The pair cache, filled on first use (racing writers make equal values; the first one wins). */
    private val PAIRS = AtomicReferenceArray<StackedPair?>(SLOTS * SLOTS)

    private fun index(abbrev: String?): Int {
        val key = abbrev?.trim()?.uppercase() ?: return FALLBACK_INDEX
        return INDEX[key] ?: FALLBACK_INDEX
    }

    /** (Test) The resolved team, primary and swapped. */
    internal fun resolved(abbrev: String?): NhlStackedColours.Resolved = TEAMS[index(abbrev)]
}
