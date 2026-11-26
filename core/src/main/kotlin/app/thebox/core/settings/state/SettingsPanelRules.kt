// Contract (Settings panel commit 0/0b): docs/design/m6-player-settings-panel.md §2.6, §4.1, §4.4, "Commit 0 rulings".
// Signatures, constants and bodies are frozen and owned by the lead (`SettingsPanelRulesTest`); stage B's B2 edits it.
package app.thebox.core.settings.state

import app.thebox.core.input.BoxAction
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.Pane
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsUiState
import app.thebox.core.settings.tree.PlaybackRows

/**
 * The in-player settings panel's pure rules (docs/design/m6-player-settings-panel.md §2.6, §4.4): which routes the panel
 * hands to full-screen Settings, which keys expand it, and the band's timing line. Pure and main-safe; read by the guide
 * machine (after `settings.act` returns, never from inside it, S10), `AppRoot`'s expand collector, the settings effects
 * and `SettingsPanel`.
 */
object SettingsPanelRules {
    /**
     * True when [route] needs the whole canvas and the panel hands it to full-screen Settings at the same path (§2.6
     * table): another screen draws it over the canvas (PhoneSetup, ChannelEditor), the onboarding / import flow
     * (FirstRun, AddSource, ImportProgress, GroupsReview, MatchingReport), it replaces the library (RestorePreview), the
     * network-folder walk (FindComputers, ComputerShares, ComputerSignIn, LocationSignIn, NetworkFolder, LocationTest), or
     * Licences. Everything else (Root, every Section, Playlist, PlaylistSource, NetworkTest, …) stays in the panel.
     * An exhaustive `when` with no `else`: a new route does not compile until it is classified here.
     */
    fun expands(route: SettingsRoute): Boolean = when (route) {
        // Panel: rows, lists, info lines; their editors and cards are drawn centred over the video.
        SettingsRoute.Root, is SettingsRoute.Section,
        is SettingsRoute.Playlist, is SettingsRoute.PlaylistSource, is SettingsRoute.NetworkTest,
        is SettingsRoute.Groups, is SettingsRoute.HiddenChannels, is SettingsRoute.HideRules,
        SettingsRoute.FavouriteLists, is SettingsRoute.FavouriteList,
        SettingsRoute.EpgSources, SettingsRoute.SyncStatus, is SettingsRoute.FixGuide,
        is SettingsRoute.Channel,
        SettingsRoute.ButtonMapping, is SettingsRoute.ActionBindings,
        SettingsRoute.Diagnostics, SettingsRoute.Backup,
        SettingsRoute.PauseRewind, is SettingsRoute.SaveLocation, is SettingsRoute.LocationDetail -> false
        // (b) onboarding and import flow
        SettingsRoute.FirstRun, is SettingsRoute.AddSource,
        is SettingsRoute.ImportProgress, is SettingsRoute.GroupsReview, is SettingsRoute.MatchingReport -> true
        // (a) drawn by another screen over the whole canvas
        is SettingsRoute.PhoneSetup, is SettingsRoute.ChannelEditor -> true
        // (c) replaces the library
        is SettingsRoute.RestorePreview -> true
        // (d) the network-folder walk, and Licences (long text) (N2)
        SettingsRoute.FindComputers, is SettingsRoute.ComputerShares, is SettingsRoute.ComputerSignIn,
        is SettingsRoute.LocationSignIn, is SettingsRoute.NetworkFolder, is SettingsRoute.LocationTest,
        SettingsRoute.Licences -> true
    }

    /**
     * (S11) True when [action], pressed in the panel, expands it to full-screen Settings: OPEN_SETTINGS (the remote's
     * Settings key) anywhere, CONTEXT_MENU (☰, also a long OK) only on the root list (`Pane.SECTIONS` at
     * `SettingsRoute.Root`), never on a row. The guide asks it before `settings.act`.
     *
     * (Commit 0b ruling) The Menu key and a long OK are one action, CONTEXT_MENU (`DefaultKeyMap`), and this rule cannot
     * tell them apart: on the root list a long OK expands too. That list has no row menus, so nothing is lost; on any
     * page (rows) neither expands, which is what S11 asks.
     */
    fun expandKey(ui: SettingsUiState, action: BoxAction): Boolean = when (action) {
        BoxAction.OPEN_SETTINGS -> true
        BoxAction.CONTEXT_MENU -> ui.route == SettingsRoute.Root && ui.pane == Pane.SECTIONS
        else -> false
    }

    /**
     * (S8) The band's timing line for [row] in the panel (§4.4), from `row.effect` (EffectTiming LIVE → AT_ONCE,
     * REPREPARE → REPREPARE, NEXT_TUNE → CHANGE_CHANNEL, NEXT_LAUNCH → NEXT_START, RESTART → RESTART_APP, NEXT_REFRESH
     * → NEXT_UPDATE, NEXT_EPG_IMPORT → NEXT_GUIDE_UPDATE, NONE → NONE). Stage A; B2 refines it in stage B.
     */
    fun timing(row: SettingsRow): PanelTiming = when (row.effect) {
        EffectTiming.LIVE -> PanelTiming.AT_ONCE
        EffectTiming.REPREPARE -> PanelTiming.REPREPARE
        EffectTiming.NEXT_TUNE -> PanelTiming.CHANGE_CHANNEL
        EffectTiming.NEXT_LAUNCH -> PanelTiming.NEXT_START
        EffectTiming.RESTART -> PanelTiming.RESTART_APP
        EffectTiming.NEXT_REFRESH -> PanelTiming.NEXT_UPDATE
        EffectTiming.NEXT_EPG_IMPORT -> PanelTiming.NEXT_GUIDE_UPDATE
        EffectTiming.NONE -> PanelTiming.NONE
    }

    /**
     * (Commit 0 ruling) The stage-A text key of [timing]'s line; null for NONE and AT_ONCE (no line). Stage B (B2) changes
     * REPREPARE and the connection rows' CHANGE_CHANNEL here. `SettingsPanel` formats it with `SettingsTexts`.
     */
    fun timingText(timing: PanelTiming): SettingsText? = when (timing) {
        PanelTiming.NONE, PanelTiming.AT_ONCE -> null
        PanelTiming.REPREPARE -> SettingsText.PANEL_TIMING_REPREPARE
        PanelTiming.CHANGE_CHANNEL -> SettingsText.PANEL_TIMING_CHANGE_CHANNEL
        PanelTiming.NEXT_START -> SettingsText.PANEL_TIMING_NEXT_START
        PanelTiming.NEXT_UPDATE -> SettingsText.PANEL_TIMING_NEXT_UPDATE
        PanelTiming.NEXT_GUIDE_UPDATE -> SettingsText.PANEL_TIMING_NEXT_GUIDE_UPDATE
        PanelTiming.RESTART_APP -> SettingsText.PANEL_TIMING_RESTART_APP
    }

    /**
     * (Commit 0 ruling, §4.1) True for the rows whose band adds [SettingsText.PANEL_QUICK_BAR_HINT] after the timing
     * line: `playback.aspect`, `playback.audio_language`, `playback.subtitles` (a per-channel choice on the quick bar wins
     * over them). Matched by id or sub-row prefix (`key == id || key.startsWith("$id.")`, as S4).
     */
    fun quickBarHint(row: SettingsRow): Boolean = QUICK_BAR_ROWS.any { row.id.key == it.key || row.id.key.startsWith(it.key + ".") }

    /** The §4.1 rows whose per-channel value lives on the quick bar. */
    val QUICK_BAR_ROWS: List<RowId> = listOf(PlaybackRows.ASPECT, PlaybackRows.AUDIO_LANGUAGE, PlaybackRows.SUBTITLES)

    /** (§4.2) A REPREPARE change in the panel is applied this long after the last change (`scheduleApply` debounce). */
    const val APPLY_DEBOUNCE_MS = 400L

    /** (Q5) A panel closed less than this long ago reopens at its last page; otherwise at the root list. */
    const val PANEL_REOPEN_MS = 600_000L
}

/**
 * (§4.4, S8) What the band's timing line says for a row. NONE and AT_ONCE draw no line. Stage-A texts: REPREPARE "Re-prepares
 * the stream when you change it", CHANGE_CHANNEL "Applies when you change channel", NEXT_START "Applies the next time The
 * Box starts", RESTART_APP "Applies after a restart (Restart now)", NEXT_UPDATE / NEXT_GUIDE_UPDATE "Applies at the next
 * playlist / guide update" ([SettingsPanelRules.timingText]). Append-only.
 */
enum class PanelTiming { NONE, AT_ONCE, REPREPARE, CHANGE_CHANNEL, NEXT_START, NEXT_UPDATE, NEXT_GUIDE_UPDATE, RESTART_APP }
