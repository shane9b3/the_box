package app.thebox.ui.sports

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.hub.BoxScore

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.4, U-N7; WP-5) The Box score sheet (final NHL): Away / Home tab chips,
 * skaters (forwards then defence) and goalies. [box] is null while loading; a `Fetch.Failed` / `NotFound` shows the §4.5
 * text with a Retry row ([onRetry]). Focus on the Away tab; Back calls [onClose].
 *
 * Commit 0 stub: draws nothing. The signature is frozen (WP-4 composes it).
 */
@Composable
fun BoxScoreSheet(
    game: GameSnapshot,
    box: Fetch<BoxScore>?,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
