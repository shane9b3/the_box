package app.thebox.ui.sports

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.hub.ChannelCandidate

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §5.3, §5.5, §5.6; WP-5) The Channels sheet: one 96 px row per candidate
 * (logo, name, the §5.3 reason, the playlist line when candidates come from more than one playlist, "Also: FHD, 4K"),
 * ending with the regional note. [archiveOnly]: the final NFL / CHL "Watch from start" rows (OK → [onPlayArchive]).
 * Otherwise OK → [onTune] when [tunable] (live, or starting within `HUB_IMMINENT_MIN`), else [onNotYet] (the page's
 * "The game starts at …" toast). Focus on the first row; Back calls [onClose].
 *
 * Commit 0 stub: draws nothing. The signature is frozen (WP-4 composes it).
 */
@Composable
fun ChannelSheet(
    game: GameSnapshot,
    candidates: List<ChannelCandidate>,
    tunable: Boolean,
    archiveOnly: Boolean,
    clock24: Boolean,
    onTune: (ChannelCandidate) -> Unit,
    onPlayArchive: (ChannelCandidate) -> Unit,
    onNotYet: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
