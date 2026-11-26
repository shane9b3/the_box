package app.thebox.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.thebox.core.settings.SettingsMachine

/**
 * (Settings panel commit 0, docs/design/m6-player-settings-panel.md §5) The in-player settings panel: the one settings
 * machine's state drawn in one column over FULL, in the picker box (1376, 48) 496 × 880 design px. `AppRoot` draws it in
 * the key sink, after `FullScreenHost`, while `shown == FULL && GuideUiState.settingsPanel && settingsMachine != null`
 * (§5.13): above the player overlays, below the reminder card, the PIN pad and the toast. It collects [machine]'s `ui`
 * itself and draws the panel box (header, page title, compact rows, description band, context menu), then the centred
 * card overlays (`ConfirmCard`, `NumberPad`, `TextEditorOverlay` wired as in `SettingsScreen`, `CaptureOverlay`).
 * [lowEnd]: the 150 ms show/hide fade is snapped. Never an ancestor of `VideoLayer`; no blur, no `graphicsLayer` (§6).
 *
 * Commit 0: signature only, draws nothing (the panel never opens yet). A5 writes the body and `object SettingsPanelUi`.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun SettingsPanel(machine: SettingsMachine, lowEnd: Boolean, modifier: Modifier = Modifier) = Unit
