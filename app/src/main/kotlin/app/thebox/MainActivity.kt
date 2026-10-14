package app.thebox

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import app.thebox.player.api.PlaybackController
import app.thebox.player.engine.WindowDisplayModeBinder
import app.thebox.ui.theme.BoxTextStyles
import app.thebox.ui.theme.BoxTheme
import app.thebox.ui.theme.DesignTokens
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var controller: PlaybackController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // M2 §3.10.6 / §3.9.2: screen-on while playing, API < 31 AFR mode on this window.
        lifecycleScope.launch { controller.keepScreenOn.collect { if (it) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
        WindowDisplayModeBinder(controller).bind(this)
        setContent {
            BoxTheme { PlaceholderScreen() }
        }
    }
}

/** M0 placeholder. Sizes are design px (BoxDesignScale). */
@Composable
private fun PlaceholderScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DesignTokens.Colors.Canvas),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("The Box", style = BoxTextStyles.Title, color = DesignTokens.Colors.Fg)
        Text("M0 skeleton", style = BoxTextStyles.CellTime, color = DesignTokens.Colors.AccentText)
    }
}

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun PlaceholderScreenPreview() {
    BoxTheme { PlaceholderScreen() }
}
