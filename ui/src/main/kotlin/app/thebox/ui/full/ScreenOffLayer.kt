package app.thebox.ui.full

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag

const val SCREEN_OFF_TAG = "player_screen_off"

/**
 * Screen off (docs/design/m4-player-ux.md §3.4.5): an opaque black full-window layer. The root maps the SCREEN_OFF
 * layer to `HoleMode.COVERED` (viewport HIDDEN: video track off, audio and the connection continue); any key wakes
 * through the machine, and the root's scrim then waits for the next rendered first frame (M3 H-S10).
 */
@Composable
fun ScreenOffLayer(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag(SCREEN_OFF_TAG).background(Color.Black))
}
