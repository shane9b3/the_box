package app.thebox.core.sports.hub

import app.thebox.core.sports.GameMedia
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.SportsConstants

/**
 * (NFL hub, docs/design/m6-nfl-hub.md §20) The one rule for an NFL game's clips and its highlight. ESPN lists the game's
 * "Final Game Highlight" on the **scoreboard** (`competitions[0].highlights[]`) and usually, but not always, in the game
 * **summary** (`videos[]`): for GB @ LAR (2026-11-26) the summary listed six single plays and never the highlight. So:
 *
 * - [clips]: the shown game's one clip list is the summary's selection ∪ the board's highlight, one entry per video id
 *   (the summary's entry wins), every highlight first, then the single plays in the summary's order, at most
 *   `HUB_NFL_MAX_CLIPS`. The repository publishes this list as `GameDetail.clips` and puts the board's entry into the
 *   clip catalog; the UI applies it again over its own (possibly newer) board snapshot, which changes nothing when the
 *   two agree.
 * - [offered]: the highlight offered now, the first [ClipKind.RECAP] of that list for which [ClipOffer.offered] holds.
 *   Every surface that shows or plays the highlight (the panel's slot 1, its picture and caption, the card's chip, the
 *   Scoring sheet's "Game highlights" row, WATCH_RECAP, the clip session's offer check) and the repository's final-game
 *   wait ("re-read every 5 min until the highlight appears") ask this one function, so they can never disagree.
 *
 * Pure; no wall clock (callers pass `now + skew`).
 */
object NflHighlight {
    /** The board's highlight: its full entry's clip; (hand-built snapshots without an entry) the id with its expiry. */
    fun boardClip(media: GameMedia?): GameClip? {
        media ?: return null
        media.highlight?.clip?.takeIf { it.kind == ClipKind.RECAP }?.let { return it }
        val id = media.recapId ?: return null
        // (P3 note 3) A board id without an expiry is trusted as offered.
        return GameClip(id, ClipKind.RECAP, "", null, null, null, media.recapExpiresMs ?: Long.MAX_VALUE, null)
    }

    /** [detailClips] ∪ [board], one per id (the detail's entry wins), highlights first, at most `HUB_NFL_MAX_CLIPS`. */
    fun clips(detailClips: List<GameClip>, board: GameClip?): List<GameClip> {
        if (board == null || detailClips.any { it.videoId == board.videoId }) return detailClips
        val recaps = detailClips.filter { it.kind == ClipKind.RECAP }
        val rest = detailClips.filter { it.kind != ClipKind.RECAP }
        return (recaps + board + rest).take(SportsConstants.HUB_NFL_MAX_CLIPS)
    }

    /** The game's clip list: [detail]'s clips ∪ the board's highlight of [game]. */
    fun clips(game: GameSnapshot, detail: GameDetail?): List<GameClip> = clips(detail?.clips.orEmpty(), boardClip(game.media))

    /** The first highlight of [clips] offered at [atMs] (`now + skew`), or null. */
    fun offered(clips: List<GameClip>, atMs: Long): GameClip? =
        clips.firstOrNull { it.kind == ClipKind.RECAP && ClipOffer.offered(it.embargoMs, it.expiresMs, atMs) }

    /** The highlight of [game] offered at [atMs]: [offered] over [clips]. */
    fun offered(game: GameSnapshot, detail: GameDetail?, atMs: Long): GameClip? = offered(clips(game, detail), atMs)
}
