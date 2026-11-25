package app.thebox.ui.sports

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.5; WP-5) The clip layer `SportsScreen` composes (full screen,
 * transparent, over the root surface) while [session] is not idle: the transport bar, "Up next", the mid-clip error card,
 * and every key (OK / Play / Pause, Left / Right, Rewind / Fast-forward, Channel ±, Menu, Back → [ClipSession.back]).
 * It calls [onBackFreeChanged]`(false)` for its whole life, so Back never reaches `onSportsClosed()`. No
 * `graphicsLayer`, clip or alpha on it.
 *
 * Commit 0 stub: draws nothing. The signature is frozen (WP-4 composes it).
 */
@Composable
fun ClipPlayerLayer(
    session: ClipSession,
    onBackFreeChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
