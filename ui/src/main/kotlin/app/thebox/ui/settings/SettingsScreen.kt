package app.thebox.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.thebox.ui.root.SettingsPlaceholder

/**
 * Mode SETTINGS (docs/design/m5-setup-settings.md §3.1, §3.2). **Commit-0 stub (WP-A replaces the body):** draws M3's
 * [SettingsPlaceholder] (opaque, covers the hole; tag `settings_placeholder`), so every existing test and screen stays
 * as it was. WP-A deletes `SettingsPlaceholder.kt` when this screen lands.
 */
@Composable
fun SettingsScreen(versionName: String, modifier: Modifier = Modifier) {
    SettingsPlaceholder(versionName, modifier)
}
