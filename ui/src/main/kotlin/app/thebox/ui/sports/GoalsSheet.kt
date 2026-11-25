package app.thebox.ui.sports

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.GameDetail

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.4; WP-5) The Goals sheet over the right column: goals in game order, then
 * (final) Game recap, Condensed game and the catch-up "Full game from the start" rows. A goal row's queue is that goal and
 * every later goal with a clip; the recap or condensed game is a queue of one. It takes focus on open (live: the latest
 * goal; final: the first) or on [focusVideoId] (the clip just played, U-S14). Back calls [onClose].
 *
 * [clipInfo] holds the lookups the page already made (durations, thumbnails), by video id. [onMissingClip]: OK on a goal
 * without a clip id (the page shows the §6.4 toast).
 *
 * Commit 0 stub: draws nothing. The signature is frozen (WP-4 composes it).
 */
@Composable
fun GoalsSheet(
    game: GameSnapshot,
    detail: GameDetail,
    clipInfo: Map<Long, ClipInfo>,
    catchUp: List<ChannelCandidate>,
    focusVideoId: Long?,
    onPlay: (queue: List<ClipRef>, index: Int) -> Unit,
    onPlayArchive: (ChannelCandidate) -> Unit,
    onMissingClip: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
