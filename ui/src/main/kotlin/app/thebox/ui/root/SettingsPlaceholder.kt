package app.thebox.ui.root

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens

const val SETTINGS_TAG = "settings_placeholder"

/**
 * Mode SETTINGS in M3 (docs/design/m3-guide.md §3.4.3, §8 Q10): the version, "Settings arrive with the next update"
 * and how to get back. Opaque: it covers the hole (viewport HIDDEN, audio continues). Back is handled by the state
 * machine through the root key sink.
 */
@Composable
fun SettingsPlaceholder(versionName: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .testTag(SETTINGS_TAG)
            .background(DesignTokens.Colors.Canvas)
            .padding(start = 128.dp, top = 120.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Line(stringResource(R.string.settings_title), GuideUi.sans(48, FontWeight.SemiBold))
        Line(stringResource(R.string.settings_version, versionName), GuideUi.mono(18, color = GuideUi.Fg60))
        Line(stringResource(R.string.settings_coming), GuideUi.sans(24, color = GuideUi.Fg70))
        Line(stringResource(R.string.settings_back), GuideUi.sans(20, color = GuideUi.Fg55))
    }
}
