package app.thebox.ui.guide.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.FooterHint

const val FOOTER_TAG = "footer_hints"

/**
 * Footer key hints at (64, bottom 40) (docs/design/m3-guide.md §3.18.3; prototype footer): 17 px at .55, key caps mono
 * 14 with a .22 border, gap 36. The list is built by the state machine from the keys the device has reported.
 */
@Composable
fun FooterHints(footer: List<FooterHint>, modifier: Modifier = Modifier) {
    if (footer.isEmpty()) return
    Row(modifier.testTag(FOOTER_TAG), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
        footer.forEach { hint ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                KeyCap(hint.keys)
                Line(hint.label, GuideUi.sans(17, color = GuideUi.Fg55))
            }
        }
    }
}
