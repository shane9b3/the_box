package app.thebox.core.sports.hub

import app.thebox.core.sports.GameMedia
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** docs/design/m6-nfl-hub.md §20: the shown game's one clip list (summary ∪ board highlight) and its offered highlight. */
class NflHighlightTest {
    private val t0 = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun clip(id: Long, kind: ClipKind, published: Long = t0, embargo: Long? = null, expires: Long = t0 + 2 * day, title: String = "c$id") =
        GameClip(id, kind, title, 30_000L, published, embargo, expires, "https://img.invalid/$id.jpg")

    private val board = clip(9, ClipKind.RECAP, title = "board")
    private val plays = listOf(clip(1, ClipKind.PLAY, t0 - 3), clip(2, ClipKind.PLAY, t0 - 2))

    private fun game(media: GameMedia?) = SportsFixtures.nflGame(id = "401873079", phase = GamePhase.FINAL).copy(media = media)
    private fun detail(g: app.thebox.core.sports.GameSnapshot, clips: List<GameClip>) = GameDetail(
        g.id, GamePhase.FINAL, null, null, null, null, emptyList(), null, emptyList(), emptyList(), emptyList(), emptyList(), null, null,
        null, null, emptyList(), clips = clips,
    )
    private fun media(c: GameClip) = GameMedia(c.videoId, null, 0, c.expiresMs, VideoEntry(c, "https://clips.invalid/${c.videoId}/playlist.m3u8"))

    @Test fun theBoardHighlightJoinsTheSummarysPlaysFirst() {
        assertEquals(listOf(board) + plays, NflHighlight.clips(plays, board))
        assertSame(plays, NflHighlight.clips(plays, null), "no board highlight: the summary's list as is")
    }

    @Test fun oneEntryPerIdAndTheSummarysWins() {
        val summaryCopy = board.copy(title = "summary", expiresMs = t0 - 1)
        val list = listOf(summaryCopy) + plays
        assertSame(list, NflHighlight.clips(list, board))
        assertNull(NflHighlight.offered(NflHighlight.clips(list, board), t0), "the summary lists it expired: not offered")
    }

    @Test fun aSecondHighlightGoesAfterTheSummarysAndTheCapHolds() {
        val other = clip(5, ClipKind.RECAP)
        assertEquals(listOf(other, board) + plays, NflHighlight.clips(listOf(other) + plays, board))
        val many = (1..SportsConstants.HUB_NFL_MAX_CLIPS).map { clip(100L + it, ClipKind.PLAY, t0 + it) }
        val merged = NflHighlight.clips(many, board)
        assertEquals(SportsConstants.HUB_NFL_MAX_CLIPS, merged.size)
        assertEquals(board, merged.first())
        assertEquals(many.dropLast(1), merged.drop(1), "the newest play gives way, never the highlight")
    }

    @Test fun offeredIsTheFirstHighlightThatPassesClipOffer() {
        val embargoed = board.copy(embargoMs = t0 + 1_000L)
        assertNull(NflHighlight.offered(listOf(embargoed) + plays, t0))
        assertEquals(embargoed, NflHighlight.offered(listOf(embargoed) + plays, t0 + 1_000L))
        assertNull(NflHighlight.offered(plays, t0), "a single play is never the highlight")
        val expired = board.copy(expiresMs = t0 + SportsConstants.HUB_CLIP_EXPIRY_MARGIN_MS)
        assertNull(NflHighlight.offered(listOf(expired), t0))
    }

    @Test fun theGameFormUsesTheBoardsEntry() {
        val g = game(media(board))
        val d = detail(g, plays)
        assertEquals(listOf(board) + plays, NflHighlight.clips(g, d))
        assertEquals(board, NflHighlight.offered(g, d, t0))
        assertEquals(board, NflHighlight.offered(g, null, t0), "before the summary arrives")
        assertNull(NflHighlight.offered(game(null), d, t0))
    }

    /** A board id without an entry (hand-built snapshots): offered by its expiry, or always without one (P3 note 3). */
    @Test fun aBareBoardIdIsStillAHighlight() {
        val bare = NflHighlight.boardClip(GameMedia(7L, null, 0, t0 + day))!!
        assertEquals(7L to ClipKind.RECAP, bare.videoId to bare.kind)
        assertEquals(t0 + day, bare.expiresMs)
        assertEquals(Long.MAX_VALUE, NflHighlight.boardClip(GameMedia(7L, null, 0))!!.expiresMs)
        assertNull(NflHighlight.boardClip(GameMedia(null, null, 0)))
    }
}
