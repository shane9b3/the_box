package app.thebox.ui.sports

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.hub.GameDetail

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.4; WP-5) The Matchup sheet (pre-game NHL): "Leaders · last 5 games"
 * (Points, Goals, Assists) and "Goalies · this season" from [detail]. Focus on the first row; Back calls [onClose].
 *
 * Commit 0 stub: draws nothing. The signature is frozen (WP-4 composes it).
 */
@Composable
fun MatchupSheet(
    game: GameSnapshot,
    detail: GameDetail,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
