package app.thebox

import android.os.Bundle
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
import androidx.tv.material3.Text
import app.thebox.ui.theme.BoxTextStyles
import app.thebox.ui.theme.BoxTheme
import app.thebox.ui.theme.DesignTokens
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
